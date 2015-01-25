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
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.fnparse.util.MultiTimer;

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
    Params.Stateful model = getFullParams(true);
    ForwardSearch fs = fullSearch(initialState, BFunc.NONE, model);
    fs.run();
    StateSequence ss = fs.getPath();
    FNParse yhat = ss.getCur().decode();
    assert yhat != null;
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
      private FNParse gold;
      public Oracle(FNParse gold) {
        this.gold = gold;
      }
      @Override
      public double score(State s, Action a) {
        FrameInstance fi = gold.getFrameInstance(a.t);
        Span arg = fi.getArgument(a.k);
        if (arg != a.getSpanSafe())
          return Double.NEGATIVE_INFINITY;
        return 0d;
      }
    }

    /**
     * A BFunc which rewards increasing the hamming loss (aka deltaLoss).
     */
    public static class MostViolated implements BFunc {
      private FNParse gold;
      public MostViolated(FNParse gold) {
        this.gold = gold;
      }
      @Override
      public double score(State s, Action a) {
        ActionType at = a.getActionType();
        return at.deltaLoss(s, a, gold);
      }
    }
  }

  private ForwardSearch fullSearch(State initialState, BFunc biasFunction, Params.Stateful model) {
    // TODO expose option to record initialActions as well?
    return this.new ForwardSearch(initialState, biasFunction, model, null, true);
  }

  private ForwardSearch initialActionsSearch(State initialState, BFunc biasFunction, Params.Stateful model) {
    return this.new ForwardSearch(initialState, biasFunction, model, new ArrayList<>(), false);
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

  public class ForwardSearch implements Runnable {
    private final State initialState;
    private final BFunc biasFunction;
    private final Params.Stateful model;
    private boolean fullSearch;
    private boolean hasRun;

    private StateSequence path;                 // to be used if fullSearch (oracle, mostViolated)
    private List<ScoredAction2> initialActions;  // to be used if !fullSearch (statelessTrain)

    public FNParse gold = null;  // purely for debugging

    /**
     * @param biasFunction is evaluated before the model score and can short-circuit
     * the model score if -infinity is returned.
     */
    ForwardSearch(State initialState, BFunc biasFunction, Params.Stateful model, List<ScoredAction2> initialActions, boolean fullSearch) {
      this.initialState = initialState;
      this.biasFunction = biasFunction;
      this.model = model;
      this.initialActions = initialActions;
      this.path = null;
      this.fullSearch = fullSearch;
      this.hasRun = false;
    }

    @Override
    public void run() {
      assert !hasRun;

      String desc = "[forwardSearch " + (fullSearch ? "full" : "init") + "]";
      boolean verbose = false;

      TransitionFunction transF =
          new ActionDrivenTransitionFunction(actionTypes);
      StateSequence frontier = new StateSequence(null, null, initialState, null);
      Beam<StateSequence> beam = new Beam<>(beamWidth);
      beam.push(frontier, 0d);

      for (int iter = 0; beam.getSize() > 0; iter++) {

        frontier = beam.pop();
        if (verbose && LOG_FORWARD_SEARCH)
          logStateInfo(desc, frontier.getCur());
        int added = 0;

        // As skipProb -> 1, the behavior of this method starts to look like
        // "choose a random span for every (t,k)" as the negative evidence.
        //      int reservoirSize = (int) (keepFactor * init.getSentence().size()) + 1;
        //      LOG.info(desc + " keepFactor=" + keepFactor + " reservoirSize=" + reservoirSize);
        //      List<StateSequence> consider = DataUtil.reservoirSample(
        //          transF.nextStates(frontier), reservoirSize, rand);

        State s = frontier.getCur();
        //for (StateSequence ss : transF.nextStates(frontier)) {
        for (Action a : transF.nextStates(frontier)) {

          double bias = biasFunction.score(s, a);
          if (Double.isInfinite(bias)) {
            assert bias < 0;
            continue;
          }

          // model score
          Adjoints adj = model.score(s, a);
          double modelScore = adj.forwards();
          double score = bias + modelScore;

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
          LOG.info(desc + " added=" + added);

        if (!fullSearch || added == 0) {
          if (LOG_FORWARD_SEARCH)
            LOG.info(desc + " done on iteration " + iter);
          if (fullSearch) {
            logSolution(desc, frontier);
            this.path = frontier;
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
   */
  public Update getFullUpdate(State init, FNParse y) {
    if (y.numFrameInstances() == 0) {
      assert init.numFrameInstance() == 0;
      return Update.NONE;
    }

    // Will cache adjoints across oracle and mostViolated
    Params.Stateful cachingModelParams = getFullParams(true);

    // Find the oracle parse
    ForwardSearch oracleSearch =
        fullSearch(init, new BFunc.Oracle(y), cachingModelParams);
    oracleSearch.run();

    // Find the most violated parse
    ForwardSearch mvSearch =
        fullSearch(init, new BFunc.MostViolated(y), cachingModelParams);
    mvSearch.run();

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
    Params.Stateful model = getFullParams(false);
    BFunc deltaLoss = new BFunc.MostViolated(y);
    ForwardSearch initialActions =
        initialActionsSearch(init, deltaLoss, model);
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
      if (hinge < 0d)
        return -hinge;
      else
        return 0d;
    }

//    public void getUpdate(double[] addTo, double scale) {
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
//          a.getUpdate(addTo, upOracle);
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
//          a.getUpdate(addTo, upMV);
        else
          skipped++;
      }
      assert skipped <= 1;

      timer.stop("Update.getUpdate");
      return violation();
    }
  }
}
