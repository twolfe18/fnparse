package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.StateSequence;
import edu.jhu.hlt.fnparse.rl.TransitionFunction;
import edu.jhu.hlt.fnparse.rl.TransitionFunction.ActionDrivenTransitionFunction;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateful;
import edu.jhu.hlt.fnparse.util.Beam;
import edu.jhu.hlt.fnparse.util.Beam.Beam1;
import edu.jhu.hlt.fnparse.util.Beam.BeamN;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.fnparse.util.FNDiff;
import edu.jhu.hlt.fnparse.util.MultiTimer;
import edu.jhu.hlt.fnparse.util.Timer;

/**
 * Lets think about how this model compares to the previous one I have, which
 * roughly parameterizes the same set of variables (but just not sequences over
 * how to label them).
 * I could just use the previous model for p(s|t,k) and then just learn a decoder.
 * What would this look like?
 * Features would all look like "reranking" features, consider a lot of state
 * like what roles have been assigned where.
 * 
 * Sort all of the (t,k,s) by probability, indexed by rank as i
 * The set of items chosen so far and the parse so far is represented as state s
 * Each item therefore gets features f(i,s) which add to the original log-prob p(i)
 * Algorithm:
 * 1) sort list by s(i) = p(i) + theta * f(i,s)
 * 2) if s(0) < 0, the parse is done.
 * 2) else take top item and add it to the parse, s += i, goto 1
 * 
 * This is a nice proving-ground for my idea. We already have a model which is
 * decent (has some idea about what a good (t,k,s) is). We should be able to
 * improve it. Probably with just the decoder, but if not, we can start throwing
 * in more realistic scoring features (e.g. embedding stuff) and anneal p(i)
 * towards the uniform distribution.
 *
 * NOTE: ^^^I totally didn't do this^^^
 */
public class Reranker {
  public static final Logger LOG = Logger.getLogger(Reranker.class);
  public static boolean LOG_UPDATE = false;
  public static boolean LOG_FORWARD_SEARCH = false;

  public static final double COST_FN = 1d;

  // Higher score is better, this puts highest scores at the end of the list
  public static final Comparator<Item> byScore = new Comparator<Item>() {
    @Override
    public int compare(Item o1, Item o2) {
      if (o1.getScore() < o2.getScore())
        return -1;
      if (o1.getScore() > o2.getScore())
        return 1;
      return 0;
    }
  };

  private Params.Stateful thetaStateful;
  private Params.Stateless thetaStateless;

  private Random rand;
  private ActionType[] actionTypes;
  private int beamWidth;
  public boolean useItemsForPruning = false; // Otherwise use them as features
  // via Params, in e.g. PriorScoreParams

  private MultiTimer timer = new MultiTimer();

  public Reranker(Params.Stateful thetaStateful, Params.Stateless thetaStateless, int beamWidth, Random rand) {
    this.thetaStateful = thetaStateful;
    this.thetaStateless = thetaStateless;
    this.beamWidth = beamWidth;
    this.actionTypes = new ActionType[] {
        ActionType.COMMIT,
        //ActionType.COMMIT_AND_PRUNE,
    };
    this.rand = rand;
  }

  public String toString() {
    return "<Reranker beam=" + beamWidth + " " + thetaStateful + " " + thetaStateless + ">";
  }

  public Params.Stateless getStatelessParams() {
    return thetaStateless;
  }

  public Params.Stateful getStatefulParams() {
    return thetaStateful;
  }

  public void setStatelessParams(Params.Stateless theta) {
    this.thetaStateless = theta;
  }

  public void setStatefulParams(Params.Stateful theta) {
    this.thetaStateful = theta;
  }

  public int getBeamWidth() {
    return beamWidth;
  }

  public void setBeamWidth(int w) {
    if (w < 1) throw new IllegalArgumentException();
    beamWidth = w;
  }

  public static ItemProvider getItemProvider() {
    return getItemProvider(100, true);
  }

  public static ItemProvider getItemProvider(int n, boolean addParses) {
    File cache = new File("data/rl/items." + n + ".train");
    FrameInstanceProvider fip = FileFrameInstanceProvider.dipanjantrainFIP;
    ItemProvider items;
    if (cache.isFile()) {
      LOG.info("[getItemProvider] getting cached items from " + cache.getPath());
      items = new ItemProvider.Caching(cache, fip);
    } else {
      LOG.info("[getItemProvider] parsing to get items...");
      List<FNParse> parses = DataUtil.iter2list(fip.getParsedSentences());
      parses = parses.subList(0, n);
      ItemProvider.Caching c = new ItemProvider.Caching(new ItemProvider.Slow(parses));
      LOG.info("[getItemProvider] saving cached items to " + cache.getPath());
      try { c.save(cache); }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
      items = c;
    }

    // Add parses
    if (addParses) {
      LOG.info("[getItemProvider] adding parses to " + items.size() + " sentences");
      ConcreteStanfordWrapper parser = ConcreteStanfordWrapper.getSingleton(true);
      for (int i = 0; i < items.size(); i++) {
        Sentence s = items.label(i).getSentence();
        if (s.getBasicDeps() == null)
          s.setBasicDeps(parser.getBasicDParse(s));
        if (s.getStanfordParse() == null)
          s.setStanfordParse(parser.getCParse(s));
      }
    }

    LOG.info("[getItemProvider] done");
    return items;
  }

  public State randomDecodingState(FNTagging frames, Random rand) {
    TransitionFunction transF =
        new ActionDrivenTransitionFunction(actionTypes);
    State init = State.initialState(frames);
    StateSequence frontier = new StateSequence(null, null, init, null);
    int TK = init.numFrameRoleInstances();
    if (TK == 0)
      throw new IllegalArgumentException("only works when there are frameInstances");
    int tkStop = rand.nextInt(TK);
    for (int i = 0; i < tkStop; i++) {
      Action a = DataUtil.reservoirSampleOne(transF.nextStates(frontier), rand);
      Adjoints adj = new Adjoints.Explicit(0d, a, "randomDecodingState");
      State n = frontier.getCur().apply(a, true);
      frontier = new StateSequence(frontier, null, n, adj);
    }
    return frontier.getCur();
  }

  // Single predict
  public FNParse predict(State initialState) {
    LOG.info("[predict] starting TK=" + initialState.numFrameRoleInstances());

    // Caching Adjoints isn't as clear as I thought it would be
    // Assume: TK=300, #spans=1000, and Adjoints.size=10000*8b
    //   (10000 is for leftTimesRight, which can be skipped if score is given the
    //   info that the returned Adjoints will never have backwards called on them)
    // This is 24 GB per decode, which is not reasonable.

    // 10000 longs per Adjoints in the current case is for my debugging experiment
    // where left=[1] and right=[sparse features < 10k]
    // but in general, you will need this if you are learning theta and neither
    // left nor right are sparse.
    // 10k = 200 * 50, which is kind of tiny for embeddings

    // pretrain => no Adjoint caching => no problem
    // train => Adjoint caching + you actually need adjoints => PROBLEM
    // predict => Adjoint caching + only need score rather than adjoints => no problem
    //            (this assumes I fixe score to include a onlyForwards flag)

    // Right now I can cache because I just realilzed that leftTimesRight can
    // be sparse if right is sparse.
    // When I switch to full dense embeddings, I will have to revisit this.

    boolean cacheAdjoints = true;
    Params.Stateful model = getFullParams(cacheAdjoints);
    boolean solveMax = true;
    ForwardSearch fs = fullSearch(initialState, BFunc.NONE, solveMax, model);
    fs.run();
    StateSequence ss = fs.getPath();
    FNParse yhat = ss.getCur().decode();
    assert yhat != null;
    LOG.info("[predict] done");
    return yhat;
  }
  public FNParse predict(FNTagging frames) {
    assert !useItemsForPruning : "need the items then!";
    if (frames.numFrameInstances() == 0)
      return new FNParse(frames.getSentence(), Collections.emptyList());
    return predict(State.initialState(frames));
  }
  public FNParse predict(FNTagging frames, List<Item> items) {
    assert useItemsForPruning : "probably should use the other one";
    if (frames.numFrameInstances() == 0)
      return new FNParse(frames.getSentence(), Collections.emptyList());
    return predict(State.initialState(frames, items));
  }

  // Batch predict
  public <T extends FNTagging> List<FNParse> predict(List<T> frames) {
    assert !useItemsForPruning : "need the items then!";
    List<FNParse> r = new ArrayList<>();
    for (T t : frames)
      r.add(predict(t));
    return r;
  }
  public List<FNParse> predict(ItemProvider ip) {
    assert useItemsForPruning : "probably should use the other one";
    List<FNParse> r = new ArrayList<>();
    int n = ip.size();
    for (int i = 0; i < n; i++) {
      FNTagging frames = DataUtil.convertParseToTagging(ip.label(i));
      r.add(predict(frames, ip.items(i)));
    }
    return r;
  }

  /** Shows some info about the given state */
  private void logStateInfo(String desc, State init) {
    LOG.info("");
    LOG.info(desc + " working on " + init.getFrames().getId());
    int n = init.getSentence().size();
    LOG.info(desc + " T=" + init.numFrameInstance()
        + " TK=" + init.numFrameRoleInstances()
        + " O(n^2)=" + (n*(n-1)/2));
    LOG.info(desc + " #committed=" + init.numCommitted()
        + " #unCommitted=" + init.numUncommitted());
    StringBuilder sb = new StringBuilder("action types:");
    for (ActionType at : actionTypes) sb.append(" " + at.getName());
    LOG.info(desc + " " + sb.toString());
    LOG.info(desc + " " + init.show());
  }

  /** Shows some info about the Action (wrapped in a StateSequence for ancillary info) */
  private void logAction(String desc, int iteration, double score, StateSequence ss, FNParse y, boolean showDeltaLoss) {
    StringBuilder sb = new StringBuilder(desc);
    if (iteration >= 0)
      sb.append(" iter=" + iteration);
    sb.append(" id=" + ss.getCur().getFrames().getId());
    sb.append(" " + ss.getAction());
    sb.append(" score=" + score);
    sb.append(" actionScore=" + ss.getAdjoints().forwards());
    if (showDeltaLoss) {
      Action a = ss.getAction();
      ActionType at = a.getActionType();
      //State s = ss.getCur();          // State after applying a
      State s = ss.neighbor().getCur(); // State before applying a
      double dl = at.deltaLoss(s, a, y);
      sb.append(" deltaLoss=" + dl);
      //sb.append(" totalLoss=" + ss.getLoss(y));
    }
    sb.append(" totalScore=" + ss.getScore());
    LOG.info(sb.toString());
  }

  /** Shows the action sequence of a StateSequence given */
  private void logSolution(String desc, StateSequence frontier) {
    String actions = frontier.showActions();
    int k = 250;  // max characters to display of solution action sequence
    if (actions.length() > k)
      actions = actions.substring(0, k) + "...";
    LOG.info(desc + " solution: " + actions);
  }

  /**
   * Returns parameters which cache the Stateless features, but not the Stateful
   * ones (obviously...).
   *
   * Right now this only caches for a single FNTagging, and it will likely have
   * to stay this way, because otherwise it would need to cache for an entire
   * data set, which is likely too much.
   */
  private Params.Stateful getFullParams(boolean caching) {
    Params.Stateless stateless = caching
        ? new Params.Stateless.Caching(thetaStateless)
        : thetaStateless;
    return new Params.SumMixed(thetaStateful, stateless);
  }

  /**
   * BFunc = "bias function" for use with
   * {@link edu.jhu.hlt.fnparse.rl.rerank.Reranker.ForwardSearch}
   */
  public static interface BFunc {
    public double score(State s, Action a);

    /** Returns a score of 0 always */
    public static class None implements BFunc {
      @Override
      public double score(State s, Action a) {
        return 0d;
      }
      @Override
      public String toString() {
        return "NONE";
      }
    }
    public static BFunc NONE = new None();

    /** Lifts a Params.Stateful to a BFunc */
    public static class StatefulAdapter implements BFunc {
      public Stateful params;
      public StatefulAdapter(Stateful params) {
        this.params = params;
      }
      @Override
      public double score(State s, Action a) {
        Adjoints adj = params.score(s, a);
        return adj.forwards();
      }
    }

    /**
     * A BFunc which represents a constraint (i.e. only returns values of 0 or
     * -infinity) that given actions must lead to the given gold parse.
     */
    public static class Oracle implements BFunc {
      private final FNParse gold;
      private final boolean solveMax;
      public Oracle(FNParse gold, boolean solveMax) {
        this.gold = gold;
        this.solveMax = solveMax;
      }
      @Override
      public double score(State s, Action a) {
        FrameInstance fi = gold.getFrameInstance(a.t);
        Span arg = fi.getArgument(a.k);
        if (arg != a.getSpanSafe())
          return solveMax ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        return 0d;
      }
      @Override
      public String toString() {
        return "ORACLE";
      }
      public FNParse getLabel() {
        return gold;
      }
    }

    /**
     * A BFunc which rewards increasing the hamming loss (aka deltaLoss).
     */
    public static class MostViolated implements BFunc {
      private final FNParse gold;
      public MostViolated(FNParse gold) {
        this.gold = gold;
      }
      @Override
      public double score(State s, Action a) {
        ActionType at = a.getActionType();
        double dl = at.deltaLoss(s, a, gold);
        assert dl >= 0;
        return dl;
      }
      @Override
      public String toString() {
        return "MOST_VIOLATED";
      }
    }

    /**
     * Same as MostViolated, but you can use this when building stateless
     * updates to skip computing model scores on most negative actions
     * (most actions are negative and you don't need to see all of them to learn
     * that they're bad -- a class imbalance problem).
     */
    public static class MostViolatedWithSubsampling extends MostViolated {
      private final double negSubsampleRate;
      private final Random rand;
      public MostViolatedWithSubsampling(FNParse gold, double negSubsampleRate, Random rand) {
        super(gold);
        this.negSubsampleRate = negSubsampleRate;
        this.rand = rand;
      }
      @Override
      public double score(State s, Action a) {
        double deltaLoss = super.score(s, a);
        if (deltaLoss > 0) {
          // Negative action, skip it with some probability
          if (rand.nextDouble() < negSubsampleRate)
            return Double.NEGATIVE_INFINITY;
          else
            return deltaLoss;
        } else {
          // Gold (or consistent-with-gold) action, keep no matter what
          return deltaLoss;
        }
      }
      @Override
      public String toString() {
        return String.format("MOST_VIOLATED_SUBSAMPLE_%.2g", negSubsampleRate);
      }
    }
  }

  public ForwardSearch fullSearch(State initialState, BFunc biasFunction, boolean solveMax, Params.Stateful model) {
    return this.new ForwardSearch(initialState, biasFunction, solveMax, model, null, true);
  }

  public ForwardSearch initialActionsSearch(State initialState, BFunc biasFunction, boolean solveMax, Params.Stateful model) {
    return this.new ForwardSearch(initialState, biasFunction, solveMax, model, new ArrayList<>(), false);
  }

  /**
   * This is now an update.
   */
  public static class ScoredAction2 implements Update {
    public final Adjoints adjoints;
    public final double y;
    public final double wx;
    public final double hinge;
    public ScoredAction2(Adjoints a, double deltaLoss) {
      if (deltaLoss != 0d && deltaLoss != 1d)
        throw new IllegalArgumentException("deltaLoss should be 0 or 1");
      this.adjoints = a;
      this.y = (deltaLoss - 0.5) * -2;  // {0,1} => {1,-1}
      this.wx = adjoints.forwards();
      hinge = Math.max(0d, 1d - y * wx);

      if (hinge > 10)
        LOG.warn("wat? badHinge=" + hinge);
    }
    @Override
    public double apply(double learningRate) {
      //LOG.info("hinge=" + hinge + " wx=" + wx + " y=" + y);
      if (hinge > 0)
        adjoints.backwards(learningRate * y * hinge);
      return hinge;
    }
  }

  /**
   * TODO what this class lacks is the ability to tell when Stateless params are
   * being used, and the search decomposes to parallel process over (t,k) rather
   * than sequential (using the beam).
   *
   * For !fullSearch, this class needs the ability to sub-sample negatives,
   * which means skipping actions when deltaLoss > 0.
   * => do this in bFunc
   */
  public class ForwardSearch implements Runnable {
    private final State initialState;
    private final BFunc biasFunction;
    private final Params.Stateful model;
    private final boolean solvingMax;
    private boolean fullSearch;
    private boolean hasRun;

    private StateSequence path;                 // to be used if fullSearch (oracle, mostViolated)
    private List<ScoredAction2> initialActions;  // to be used if !fullSearch (statelessTrain)

    public FNParse gold = null;  // purely for debugging

    /**
     * @param biasFunction is evaluated before the model score and can short-circuit
     * the model score if -infinity is returned.
     * @param solvingMax says whether we should be solving an argmax or argmin
     * in this search.
     * 
     * Note: if solvingMax=true, biasFunction is allowed to return -infinity
     * to short-circuiting the model score function, but not +infinity. For
     * solvingMax=false, the reverse is true, +infinity is allowed, not -infinity.
     */
    ForwardSearch(State initialState, BFunc biasFunction, boolean solvingMax, Params.Stateful model, List<ScoredAction2> initialActions, boolean fullSearch) {
      assert initialState.numCommitted() == 0;
      this.initialState = initialState;
      this.biasFunction = biasFunction;
      this.solvingMax = solvingMax;
      this.model = model;
      this.initialActions = initialActions;
      this.path = null;
      this.fullSearch = fullSearch;
      this.hasRun = false;
    }

    @Override
    public void run() {
      assert !hasRun;

      String desc = "[forwardSearch " + (fullSearch ? "full" : "init")
          + " bFunc=" + biasFunction.toString() + "]";
      boolean verbose = true;
      if (verbose && LOG_FORWARD_SEARCH)
        LOG.info(desc + " starting...");

      TransitionFunction transF =
          new ActionDrivenTransitionFunction(actionTypes);
      StateSequence frontier = new StateSequence(null, null, initialState, null);
      Beam<StateSequence> beam = beamWidth == 1
          ? new Beam1<>() : new BeamN<>(beamWidth);
      beam.push(frontier, 0d);

      for (int iter = 0; beam.size() > 0; iter++) {

        int added = 0, actionsTried = 0;
        frontier = beam.pop();
        State s = frontier.getCur();
        if (verbose && LOG_FORWARD_SEARCH)
          logStateInfo(desc + " @ iter=" + iter, s);

        for (Action a : transF.nextStates(frontier)) {
          if (verbose && LOG_FORWARD_SEARCH)
            LOG.info("trying out " + a);
//          if (verbose && fullSearch && ORACLE_DEBUG && biasFunction instanceof BFunc.Oracle) {
//            FNParse y = ((BFunc.Oracle) biasFunction).getLabel();
//            int T = y.numFrameInstances();
//            for (int t = 0; t < T; t++) {
//              FrameInstance fi = y.getFrameInstance(t);
//              int K = fi.numArguments();
//              for (int k = 0; k < K; k++) {
//                Span goldArg = fi.getArgument(k);
//                boolean p = s.possible(t, k, goldArg);
//                Span c = s.committed(t, k);
//                LOG.info("checking t=" + t + " k=" + k + " p=" + p + " c=" + c);
//                // Either this must be possible or committed to
//                assert p || c == goldArg;
//              }
//            }
//            LOG.info("yup");
//          }

          actionsTried++;
          double bias = biasFunction.score(s, a);
          if (Double.isInfinite(bias)) {
            if (verbose && LOG_FORWARD_SEARCH)
              LOG.info("skipping due to bFunc");
            if (solvingMax) assert bias < 0;
            else assert bias > 0;
            continue;
          }

          // model score
          Adjoints adj = model.score(s, a);
          double modelScore = adj.forwards();
          double score = bias + modelScore;
          if (!solvingMax)
            score = -score;

          if (verbose && LOG_FORWARD_SEARCH) {
            LOG.info("score=" + score + " bias=" + bias
                + " modelScore=" + modelScore + " adjoints=" + adj);
          }
          
          // TODO speed up stateless decoding
          // (certainly works for only COMMIT, other action types... ???)
          // if (!fullSearch && only stateless params) {
          //   if (score > 0) applyThis action;
          //   break;
          // }
          

          // Save the initial actions and their scores
          if (initialActions != null && iter == 0)
            initialActions.add(new ScoredAction2(adj, bias));

          // Add to the beam
          if (fullSearch) {
            added++;
            StateSequence ss = new StateSequence(frontier, null, null, adj);
            boolean onBeam = beam.push(ss, score);
            if (verbose && onBeam && LOG_FORWARD_SEARCH && gold != null)
              logAction(desc, iter, score, ss, gold, false);
          }
        }

        if (LOG_FORWARD_SEARCH)
          LOG.info(desc + " added=" + added + " of=" + actionsTried);

        if (!fullSearch || added == 0) {
          if (LOG_FORWARD_SEARCH)
            LOG.info(desc + " done on iteration " + iter);
          if (fullSearch) {
            logSolution(desc, frontier);
            this.path = frontier;
//            if (ORACLE_DEBUG) {
//              State finalState = frontier.getCur();
//              int nu = finalState.numUncommitted();
//              int nc = finalState.numCommitted();
//              int TK = finalState.numFrameRoleInstances();
//              assert nu == 0;
//              assert nc == TK;
//            }
          }
          this.hasRun = true;
          return;
        }

      }
      throw new RuntimeException("how did you run out of items on the beam?");
    }

    /**
     * @return the path found using model+bfunc scores. The Adjoints in the
     * returned StateSequence come from model, not including bfunc.
     */
    public StateSequence getPath() {
      assert hasRun;
      return path;
    }

    /**
     * @return all actions out of initialState with their score from bfunc
     */
    public List<ScoredAction2> getStatelessTrainUpdates() {
      assert hasRun;
      assert initialActions.size() > 0;
      return initialActions;
    }
  }

  /**
   * Runs a forward search to find both the oracle and mostViolated parses
   * and returns an Update.
   * @param oracleTimer may be null
   * @param mvTimer may be null
   */
  public Update getFullUpdate(State init, FNParse y, Random rand, Timer oracleTimer, Timer mvTimer) {
    if (y.numFrameInstances() == 0) {
      assert init.numFrameInstance() == 0;
      return Update.NONE;
    }

    // Find the oracle parse
    if (oracleTimer != null) oracleTimer.start();
    Params.Stateful cachingModelParams = getFullParams(true);
    boolean oracleSolveMax = false;
    ForwardSearch oracleSearch = fullSearch(
        init, new BFunc.Oracle(y, oracleSolveMax), oracleSolveMax, cachingModelParams);
    oracleSearch.run();
    if (oracleTimer != null) oracleTimer.stop();

    // Find the most violated parse
    if (mvTimer != null) mvTimer.start();
    cachingModelParams = getFullParams(true); // release old cache, not worth it
    boolean mvSolveMax = true;
    ForwardSearch mvSearch =
        fullSearch(init, new BFunc.MostViolated(y), mvSolveMax, cachingModelParams);
    mvSearch.run();
    if (mvTimer != null) mvTimer.stop();

    return new FullUpdate(y, oracleSearch.getPath(), mvSearch.getPath());
  }

  /**
   * Does no search and returns a batch of ScoredActions.
   */
  public Update getStatelessUpdate(State init, FNParse y) {
    if (y.numFrameInstances() == 0) {
      assert init.numFrameInstance() == 0;
      return Update.NONE;
    }
    Params.Stateful model = Params.Stateful.lift(thetaStateless);
    //BFunc deltaLoss = new BFunc.MostViolated(y);
    double negSubsampleRate = 0.75d;
    BFunc deltaLoss = new BFunc.MostViolatedWithSubsampling(y, negSubsampleRate, rand);
    boolean solveMax = true;
    ForwardSearch initialActions =
        initialActionsSearch(init, deltaLoss, solveMax, model);
    initialActions.run();
    return new Update.Batch<>(initialActions.getStatelessTrainUpdates());
  }

  public static interface Update {
    /**
     * @return the error before applying this update
     * (should not depend on learningRate)
     */
    public double apply(double learningRate);

    public static Update NONE = new Update() {
      @Override public double apply(double learningRate) {
        return 0d;
      }
    };

    public static class Batch<T extends Update> implements Update {
      private List<T> updates;
      public Batch() {
        updates = new ArrayList<>();
      }
      public Batch(List<T> updates) {
        this.updates = updates;
      }
      public void add(T update) {
        updates.add(update);
      }
      public int size() {
        return updates.size();
      }
      @Override
      public double apply(double learningRate) {
        assert updates.size() > 0;
        double s = learningRate / updates.size();
        double u = 0d;
        for (T up : updates)
          u += up.apply(s);
        return u / updates.size();
      }
    }
  }

  /**
   * Sub-gradient step on loss of:
   * max(0, s(y,z) - [s(y',z') + loss(y,y')])
   * 
   * where (y,z) = oracle
   *       (y',z') = mostViolated
   *       max(0, s(y,z) - [s(y',z') + loss(y,y')]) = loss
   *
   * @return the hinge loss
   */
  public class FullUpdate implements Update {
    public final FNParse gold;
    public final StateSequence oracle;
    public final StateSequence mostViolated;
    public final double hinge;

    public FullUpdate(FNParse gold, StateSequence oracle, StateSequence mostViolated) {
      this.gold = gold;
      this.oracle = oracle;
      this.mostViolated = mostViolated;

      // The oracle is supposed to find the highest/lowest scoring derivation
      // of the gold parse.
      State os = oracle.getCur();
      if (os == null) {
        logSolution("[oracle]", oracle);
        LOG.debug("null1");
      } else {
        FNParse osd = os.decode();
        if (osd == null) {
          logSolution("[oracle]", oracle);
          logStateInfo("[oracle]", os);
          LOG.debug("null2");
        } else {
          if (!osd.equals(gold)) {
            LOG.info(FNDiff.diffArgs(osd, gold, true));
            logSolution("[oracle]", oracle);
            logStateInfo("[oracle]", os);
            LOG.info("oracle isn't gold?");
          } else {
            // good!
          }
        }
      }

      FNParse yHat = mostViolated.getCur().decode();
      assert yHat != null : "mostViolated returned non-terminal state?";
      SentenceEval se = new SentenceEval(gold, yHat);
      double loss = se.argOnlyFP() + se.argOnlyFN();
      this.hinge = oracle.getScore() - (mostViolated.getScore() + loss);
      assert !Double.isNaN(hinge);
      assert Double.isFinite(hinge);
    }

    public boolean isViolated() {
      return hinge < 0d;
    }

    public double violation() {
      if (hinge < 0d) {
        double z = oracle.length() + mostViolated.length();
        return -hinge / z;
      } else {
        return 0d;
      }
    }

    @Override
    public double apply(double learningRate) {
      timer.start("Update.getUpdate");

      if (!isViolated()) {
        timer.stop("Update.getUpdate");
        return 0d;
      }

      int skipped;

      skipped = 0;
      final double upOracle = learningRate * -hinge / oracle.length();
      for (StateSequence cur = oracle; cur != null; cur = cur.neighbor()) {
        Adjoints a = cur.getAdjoints();
        if (a != null)
          a.backwards(upOracle);
        else
          skipped++;
      }
      assert skipped <= 1;

      skipped = 0;
      final double upMV = learningRate * hinge / mostViolated.length();
      for (StateSequence cur = mostViolated; cur != null; cur = cur.neighbor()) {
        Adjoints a = cur.getAdjoints();
        if (a != null)
          a.backwards(upMV);
        else
          skipped++;
      }
      assert skipped <= 1;

      timer.stop("Update.getUpdate");
      return violation();
    }
  }
}
