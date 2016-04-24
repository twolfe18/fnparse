package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.pruning.FNParseSpanPruning;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.CommitIndex;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.StateSequence;
import edu.jhu.hlt.fnparse.rl.TransitionFunction;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateful;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer.OracleMode;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.tutils.Beam;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.util.Alphabet;

/**
 * See paper.
 *
 * @author travis
 */
public class Reranker implements Serializable {
  private static final long serialVersionUID = 1L;

  public static final Logger LOG = Logger.getLogger(Reranker.class);

  public static boolean BUG_FIX = true;

  // If true, show the oracle and most violated paths for getFullUpdate
  public static boolean LOG_UPDATE = false;

  // If true, show a bunch of details about forward search (verbose and slow)
  public static boolean LOG_FORWARD_SEARCH = false;

  // NOTE: Do not change this without considerable consideration. This is related
  // to how margins are computed, and I think a lot will break if this isn't 1.
  public static double COST_FN = 1d;

  // If true, in the mostVoilated search, you should *not* use loss augmented
  // inference, but rather just decode.
  public static boolean PERCEPTRON = false;

  // I'm an idiot... how did this work?
  public static boolean GRADIENT_BUGFIX = true;

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
  private Params.PruneThreshold tauParams;

  @SuppressWarnings("unused")
  private Random rand;
  private int trainBeamWidth;
  private int testBeamWidth;

  public DeterministicRolePruning.Mode argPruningMode;

  private transient MultiTimer timer;

  public boolean logArgPruningStats;
  public boolean logPredict;

  // Module for caching/precomputing features.
  public CachedFeatures cachedFeatures;

  // In PB, during decoding we train R-ARG-X/C-ARG-X to look like just plain
  // ARG-X mentions, and when we run the transition system we may end up with
  // multiple ARG-X mentions. This is responsible for finding the base ARG-X
  // mention and clasifying the others in ref/cont roles, and building the final
  // FrameInstances/FNParse.
  // For FN, this can be null/not used.
//  public ContRefRoleClassifier crClassify;

  public Reranker(
      Params.Stateful thetaStateful,
      Params.Stateless thetaStateless,
      Params.PruneThreshold tauParams,
      DeterministicRolePruning.Mode argPruningMode,
      CachedFeatures cachedFeatures,
      int trainBeamWidth,
      int testBeamWidth,
      Random rand) {
    Log.info("argPruningMode=" + argPruningMode
        + " trainBeam=" + trainBeamWidth
        + " testBeam=" + testBeamWidth);
    this.thetaStateful = thetaStateful;
    this.thetaStateless = thetaStateless;
    this.argPruningMode = argPruningMode;
    this.cachedFeatures = cachedFeatures;
    this.trainBeamWidth = trainBeamWidth;
    this.testBeamWidth = testBeamWidth;
    this.rand = rand;
    this.tauParams = tauParams;

    ExperimentProperties config = ExperimentProperties.getInstance();
    logArgPruningStats = config.getBoolean("Reranker.logArgPruningStats", false);
    logPredict = config.getBoolean("Reranker.logPredict", false);
  }

  public String toString() {
    return "(Reranker beam=" + trainBeamWidth + "/" + testBeamWidth
        + " stateful=" + thetaStateful
        + " stateless=" + thetaStateless
        + " tau=" + tauParams
        + " argPruningMode=" + argPruningMode
        + ")";
  }

  public Params.Stateless getStatelessParams() {
    return thetaStateless;
  }

  public Params.Stateful getStatefulParams() {
    return thetaStateful;
  }

  public Params.PruneThreshold getPruningParams() {
    return tauParams;
  }

  public void setStatelessParams(Params.Stateless theta) {
    this.thetaStateless = theta;
  }

  public void setStatefulParams(Params.Stateful theta) {
    this.thetaStateful = theta;
  }

  public void setPruningParams(Params.PruneThreshold tauParams) {
    this.tauParams = tauParams;
  }

  public boolean hasStatefulFeatures() {
    return thetaStateful != Params.Stateful.NONE;
  }

  public void setBeamWidth(int w, boolean train) {
    LOG.info("[setBeamWidth] setting train=" + train + " beamWidth=" + w);
    if (w < 1) throw new IllegalArgumentException();
    if (train)
      trainBeamWidth = w;
    else
      testBeamWidth = w;
  }

  public void showWeights() {
    LOG.info("[showWeights] stateful:");
    thetaStateful.showWeights();
    LOG.info("[showWeights] stateless:");
    thetaStateless.showWeights();
    LOG.info("[showWeights] tau:");
    tauParams.showWeights();
  }

  public void serializeParams(File f) throws IOException {
    LOG.info("[serializeParams] writing to " + f.getPath());
    if (f.isFile())
      LOG.warn("about to overwrite " + f.getPath());
    try (OutputStream os = new FileOutputStream(f)) {
      if (f.getName().toLowerCase().endsWith(".gz"))
        serializeParams(new DataOutputStream(new GZIPOutputStream(os)));
      else
        serializeParams(new DataOutputStream(os));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void deserializeParams(File f) throws IOException {
    LOG.info("[deserializeParams] reading from " + f.getPath());
    if (!f.isFile())
      throw new IllegalArgumentException("not a file: " + f.getPath());
    try (InputStream is = new FileInputStream(f)) {
      if (f.getName().toLowerCase().endsWith(".gz"))
        deserializeParams(new DataInputStream(new GZIPInputStream(is)));
      else
        deserializeParams(new DataInputStream(is));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void serializeParams(DataOutputStream dos) throws IOException {
    long start = System.currentTimeMillis();
    LOG.info("[serialize] saving model params");
    thetaStateful.serialize(dos);
    thetaStateless.serialize(dos);
    tauParams.serialize(dos);
    LOG.info("[serialize] done saving model params, took "
        + (System.currentTimeMillis() - start)/1000d + " seconds");
  }

  public void deserializeParams(DataInputStream in) throws IOException {
    long start = System.currentTimeMillis();
    LOG.info("[deserialize] loading model params");
    thetaStateful.deserialize(in);
    thetaStateless.deserialize(in);
    tauParams.deserialize(in);
    LOG.info("[deserialize] done loading model params, took "
        + (System.currentTimeMillis() - start)/1000d + " seconds");
  }

  /**
   * @param gold may be null. If not, add gold spans if they're not already
   * included in the prune mask.
   */
  public State getInitialStateWithPruning(FNTagging frames, FNParse gold) {
    double priorScore = 0;
    List<Item> items = new ArrayList<>();
    DeterministicRolePruning drp =
        new DeterministicRolePruning(argPruningMode, null, null);
    drp.cachedFeatures = cachedFeatures;
    FNParseSpanPruning mask = drp.setupInference(
        Arrays.asList(frames), null).decodeAll().get(0);
    int T = mask.numFrameInstances();
    for (int t = 0; t < T; t++) {
      int K = mask.getFrame(t).numRoles();
      for (Span arg : mask.getPossibleArgs(t))
        for (int k = 0; k < K; k++)
          items.add(new Item(t, k, arg, priorScore));
      // Always include the target as a possibility
      for (int k = 0; k < K; k++)
        items.add(new Item(t, k, mask.getTarget(t), priorScore));
      // Add gold args if gold is provided
      if (gold != null) {
        FrameInstance goldFI = gold.getFrameInstance(t);
        assert mask.getFrame(t) == goldFI.getFrame();
        assert mask.getTarget(t) == goldFI.getTarget();
        for (int k = 0; k < K; k++) {
          Span goldArg = goldFI.getArgument(k);
          if (goldArg != Span.nullSpan)
            items.add(new Item(t, k, goldArg, priorScore));
        }
      }
    }
    if (logArgPruningStats) {
      LOG.info("[getInitialStateWithPruning] cut size down from "
          + mask.numPossibleArgsNaive() + " to "
          + mask.numPossibleArgs());
    }
    return State.initialState(frames, items);
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
      throw new RuntimeException("re-implement me");
//      LOG.info("[getItemProvider] parsing to get items...");
//      List<FNParse> parses = DataUtil.iter2list(fip.getParsedSentences());
//      parses = parses.subList(0, n);
//      ItemProvider.Caching c = new ItemProvider.Caching(new ItemProvider.Slow(parses));
//      LOG.info("[getItemProvider] saving cached items to " + cache.getPath());
//      try { c.save(cache); }
//      catch (Exception e) {
//        throw new RuntimeException(e);
//      }
//      items = c;
    }

    // Add parses
    if (addParses) {
      LOG.info("[getItemProvider] adding parses to " + items.size() + " sentences");
      ConcreteStanfordWrapper parser = ConcreteStanfordWrapper.getSingleton(true);
      for (int i = 0; i < items.size(); i++) {
        Sentence s = items.label(i).getSentence();
//        if (s.getBasicDeps() == null)
//          s.setBasicDeps(parser.getBasicDParse(s));
//        if (s.getStanfordParse() == null)
//          s.setStanfordParse(parser.getCParse(s));
        if (s.getBasicDeps(false) == null || s.getStanfordParse(false) == null) {
          Alphabet<String> edgeAlph = null; // only needed for collapsed(-cc)
          parser.addAllParses(s, edgeAlph, true);
        }
      }
    }

    LOG.info("[getItemProvider] done");
    return items;
  }

  public State randomDecodingState(FNTagging frames, Random rand) {
    TransitionFunction transF = new TransitionFunction.Tricky(
        Params.Stateful.NONE, Params.PruneThreshold.Const.ONE);
    State init = State.initialState(frames);
    StateSequence frontier = new StateSequence(null, null, init, null);
    int TK = init.numFrameRoleInstances();
    if (TK == 0)
      throw new IllegalArgumentException("only works when there are frameInstances");
    int tkStop = rand.nextInt(TK);
    for (int i = 0; i < tkStop; i++) {
      Action a = ReservoirSample.sampleOne(transF.nextStates(frontier), rand);
      Adjoints adj = new Adjoints.Explicit(0d, a, "randomDecodingState");
      State n = frontier.getCur().apply(a, true);
      frontier = new StateSequence(frontier, null, n, adj);
    }
    return frontier.getCur();
  }

  // Single predict
  public FNParse predict(State initialState) {
    if (logPredict)
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
    //            (this assumes I fix score to include a onlyForwards flag)

    // Right now I can cache because I just realized that leftTimesRight can
    // be sparse if right is sparse.
    // When I switch to full dense embeddings, I will have to revisit this.

    boolean cacheAdjoints = true;
    Params.Stateful model = getFullParams(cacheAdjoints);
    boolean solveMax = true;
    boolean decode = true;
    ForwardSearch fs = fullSearch(
        initialState, BFunc.NONE, solveMax, decode, testBeamWidth, model);
    fs.run();
    StateSequence ss = fs.getPath();
    FNParse yhat = ss.getCur().decode();  // crClassify.decode(ss.getCur());
    assert yhat != null;    // split maxbeam.pop and beam.pop? path vs decode?
    if (logPredict)
      LOG.info("[predict] done");
    return yhat;
  }

  // Batch predict
  public List<FNParse> predict(List<State> initialStates) {
    List<FNParse> r = new ArrayList<>();
    for (State is : initialStates)
      r.add(predict(is));
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
//    LOG.info(desc + " #committed=" + init.numCommitted()
//        + " #unCommitted=" + init.numUncommitted());
    StringBuilder sb = new StringBuilder("action types:");
    LOG.info(desc + " " + sb.toString());
    LOG.info(desc + " " + init.show());
  }

  /** Shows some info about the Action (wrapped in a StateSequence for ancillary info) */
  //private void logAction(String desc, int iteration, double score, StateSequence ss, FNParse y, boolean showDeltaLoss) {
  private void logAction(String desc, int iteration, Adjoints action, FNParse y, boolean showDeltaLoss) {
    StringBuilder sb = new StringBuilder("[logAction] ");
    sb.append(desc);
    if (iteration >= 0)
      sb.append(" iter=" + iteration);
    sb.append(" id=" + (y == null ? "NULL" : y.getId()));
    sb.append(" " + action);
    //sb.append(" score=" + score);
    //sb.append(" actionScore=" + ss.getAdjoints().forwards());
//    if (showDeltaLoss) {
//      Action a = ss.getAction();
//      ActionType at = a.getActionType();
//      //State s = ss.getCur();          // State after applying a
//      State s = ss.neighbor().getCur(); // State before applying a
//      double dl = at.deltaLoss(s, a, y);
//      sb.append(" deltaLoss=" + dl);
//      //sb.append(" totalLoss=" + ss.getLoss(y));
//    }
//    sb.append(" totalScore=" + ss.getScore());
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
   *
   * Should tauParams be included here?
   * => NO, not really. Those features are called upon Action/Adjoint construction
   *    in TransitionFunction.Tricky. It would be nice to cache those calls, but
   *    it does not take the same form as this.
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
    /**
     * Give the score of taking action a in state s, with the additional resource
     * that ai is an index on all of the actions take so far to get to s.
     */
    //public double score(State s, SpanIndex<Action> ai, Action a);
    public double score(State s, CommitIndex ai, Action a);

    /** Returns a score of 0 always */
    public static class None implements BFunc {
      @Override
      //public double score(State s, SpanIndex<Action> ai, Action a) {
      public double score(State s, CommitIndex ai, Action a) {
        return 0d;
      }
      @Override
      public String toString() {
        return "NONE";
      }
    }
    public static BFunc NONE = new None();

    public static class Sum implements BFunc {
      private BFunc left, right;
      public Sum(BFunc left, BFunc right) {
        this.left = left;
        this.right = right;
      }
      @Override
      public String toString() {
        return left.toString() + " + " + right.toString();
      }
      @Override
      public double score(State s, CommitIndex ai, Action a) {
        double l = left.score(s, ai, a);
        assert !Double.isNaN(l);
        if (Double.isInfinite(l))
          return l;
        double r = right.score(s, ai, a);
        assert !Double.isNaN(r);
        return l + r;
      }
    }

    /** Lifts a Params.Stateful to a BFunc */
    public static class StatefulAdapter implements BFunc {
      public Stateful params;
      public StatefulAdapter(Stateful params) {
        this.params = params;
      }
      @Override
      public String toString() {
        return params.toString();
      }
      @Override
      //public double score(State s, SpanIndex<Action> ai, Action a) {
      public double score(State s, CommitIndex ai, Action a) {
        Adjoints adj = params.score(s, ai, a);
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
      //public double score(State s, SpanIndex<Action> ai, Action a) {
      public double score(State s, CommitIndex ai, Action a) {
        // Old COMMIT implementation:
//        FrameInstance fi = gold.getFrameInstance(a.t);
//        Span arg = fi.getArgument(a.k);
//        if (arg != a.getSpanSafe())
//          return solveMax ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
//        return 0d;
        ActionType at = a.getActionType();
        double dl = at.deltaLoss(s, a, gold);
        assert dl >= 0;
        if (dl > 0d) {
          // Incurred some loss: not on the oracle decode, skip in ForwardSearch
          return solveMax ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        } else {
          return 0d;
        }
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
      //public double score(State s, SpanIndex<Action> ai, Action a) {
      public double score(State s, CommitIndex ai, Action a) {
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
      //public double score(State s, SpanIndex<Action> ai, Action a) {
      public double score(State s, CommitIndex ai, Action a) {
        double deltaLoss = super.score(s, ai, a);
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

  public ForwardSearch fullSearch(State initialState, BFunc biasFunction, boolean solveMax, boolean decode, int beamSize, Params.Stateful model) {
    return this.new ForwardSearch(initialState, model, biasFunction, solveMax, decode, null, true, beamSize);
  }

  /** @deprecated doesn't work for some reason */
  public ForwardSearch initialActionsSearch(State initialState, BFunc biasFunction, boolean solveMax, Params.Stateful model) {
    boolean decode = false;
    int beamSize = 1;
    return this.new ForwardSearch(initialState, model, biasFunction, solveMax, decode, new ArrayList<>(), false, beamSize);
  }

  /**
   * This is now an update.
   * @deprecated pretraining works terribly.
   */
  public static class ScoredAction2 implements Update {
    public final Adjoints adjoints;
    public final double y;  // either -1 or +1
    public final double wx;
    public final double cost;

    public ScoredAction2(Adjoints a, double deltaLoss) {
      if (deltaLoss < 0)
        throw new RuntimeException("deltaLoss=" + deltaLoss);
      this.adjoints = a;
      this.wx = a.forwards();

      this.y = ((deltaLoss == 0) ^ (wx > 0)) ? +1 : -1;

      // HO SHIT I'M DUMB
      // y=1,yhat=0 for COMMIT has cost 5
      // y=1,yhat=0 for PRUNE has cost 1
      boolean commit = a.getAction().getActionType() == ActionType.COMMIT;
      assert commit || a.getAction().getActionType() == ActionType.PRUNE;
      this.cost = commit
          ? (y == +1 ? Reranker.COST_FN : 1)
          : (y == +1 ? 1 : Reranker.COST_FN);

      //LOG.info("y=" + y + " cost=" + cost + " wx=" + wx + " deltaLoss=" + deltaLoss + " adjoints=" + adjoints.getAction().getActionType().getName());
    }

    @Override
    public double apply(double learningRate) {
      assert learningRate > 0;
      double v = violation();
      if (v > 0) {
        double step = learningRate * y * cost;
        //if (adjoints.getAction().getActionType() == ActionType.PRUNE)
        //LOG.info("[apply] y=" + y + " cost=" + cost + " wx=" + wx + " step=" + step + " adjoints=" + adjoints.getAction().getActionType().getName());
        adjoints.backwards(step);
      }
      return v;
    }

    @Override
    public double violation() {
      return Math.max(0d, 1d - y * wx) * cost;
    }
  }

  public static final Timer FS_TIMER = new Timer("ForwardSearch")
      .ignoreFirstTime(true).setPrintInterval(20);

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
    private boolean hasRun;
    private int beamSize;

    // If true, take the actions out of initialState and build ScoredActions
    // for each for pretrain.
    private boolean fullSearch;

    // If true, then require that path is a path to a final (decodable) state.
    private boolean decode;

    private StateSequence path;                 // to be used if fullSearch (decode, oracle, mostViolated)
    private List<ScoredAction2> initialActions; // to be used if !fullSearch (statelessTrain)

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
    public ForwardSearch(
        State initialState,
        Params.Stateful model,
        BFunc biasFunction,
        boolean solvingMax,
        boolean decode,
        List<ScoredAction2> initialActions,
        boolean fullSearch,
        int beamSize) {
//      assert initialState.numCommitted() == 0;
      this.beamSize = beamSize;
      this.initialState = initialState;
      this.model = model;
      this.biasFunction = biasFunction;
      this.solvingMax = solvingMax;
      this.decode = decode;
      this.initialActions = initialActions;
      this.path = null;
      this.fullSearch = fullSearch;
      this.hasRun = false;

      if (initialState.getFrames() instanceof FNParse)
        gold = (FNParse) initialState.getFrames();
    }

    @Override
    public void run() {
      assert !hasRun;

      FS_TIMER.start();
      String desc = "[forwardSearch "
          + (fullSearch ? "full" : "init")
          + (solvingMax ? " MAX" : " MIN")
          + " bFunc=" + biasFunction.toString() + "]";
      boolean verbose = true;
      if (verbose && LOG_FORWARD_SEARCH)
        LOG.info(desc + " starting...");

      TransitionFunction transF =
          new TransitionFunction.Tricky(model, tauParams);
      StateSequence frontier = new StateSequence(null, null, initialState, null);
      final boolean useActionIndex = hasStatefulFeatures();
      if (useActionIndex)
        frontier.initActionIndexFromScratch();

      // This is the beam used to explore.
      // The score of the elements on this beam are the model score + bias:
      //   \sum_{i=1}^n s(z_i) + bias(z_i)
      // If oracle is used, bias will not affect score except to take a subset.
      // If decode is used, bias = 0.
      // If mostViolated is used, bias = deltaLoss, and this is the beam of
      // states being explored, where we only push items that are children of
      // things that were previously on the beam.
      Beam<StateSequence> beam = Beam.getMostEfficientImpl(beamSize);
      beam.push(frontier, 0d);

      // This beam keeps a running max over all n of:
      //   \sum_{i=1}^n s(z_i) + bias(z_i)
      // Every time an item is popped off of the other beam, it is put onto this
      // beam and its children are considered for adding to the other beam.
      Beam<StateSequence> maxBeam = Beam.getMostEfficientImpl(1);

      for (int iter = 0; beam.size() > 0; iter++) {

        // Pop the highest scoring z off of the beam and use it to find a next
        // z' (which may be a maxViolator, oracle, or just a decode).
        // We are going to score Actions leaving that StateSequence.getCur (s).
        Beam.Item<StateSequence> frontierItem = beam.popItem();
        frontier = frontierItem.getItem();

        if (!BUG_FIX && !decode) maxBeam.push(frontierItem);

        State s = frontier.getCur();
        //SpanIndex<Action> ai = frontier.getActionIndex();
        CommitIndex ai = frontier.getActionIndex();
        if (verbose && LOG_FORWARD_SEARCH)
          logStateInfo(desc + " @ iter=" + iter, s);

        int added = 0, actionsTried = 0, beamAdds = 0;
        Iterable<Action> nextStates = transF.nextStates(frontier);
        for (Action a : nextStates) {

          actionsTried++;
          double bias = biasFunction.score(s, ai, a);
          assert !Double.isNaN(bias);
          if (Double.isInfinite(bias)) {
            if (verbose && LOG_FORWARD_SEARCH)
              LOG.info("skipping due to bFunc=" + biasFunction);
            if (solvingMax) assert bias < 0;
            else assert bias > 0;
            transF.observeAdjoints(null);
            continue;
          }

          // model score
          Adjoints adj;
          if (a.mode == ActionType.PRUNE.getIndex()) {
            adj = (Adjoints) a;
          } else {
            assert a.mode == ActionType.COMMIT.getIndex();
            adj = model.score(s, ai, a);
          }
          transF.observeAdjoints(adj);
          double modelScore = adj.forwards();
          double score = bias + modelScore;
          if (!solvingMax)
            score = -score;

          if (verbose && LOG_FORWARD_SEARCH)
            logAction(desc, iter, adj, gold, false);

          // Save the initial actions and their scores
          if (initialActions != null && iter == 0)
            initialActions.add(new ScoredAction2(adj, bias));

          // Add to the beam
          if (fullSearch) {
            added++;
            StateSequence ss = new StateSequence(frontier, null, null, adj);
            boolean onBeam = beam.push(ss, frontierItem.getScore() + score);
            if (onBeam) beamAdds++;
            if (useActionIndex && onBeam) {
              // Important to delay this until you know this will add to the
              // beam because this is much slower than the rest of the
              // StateSequence constructor.
              ss.initActionIndexFromPrev();
            }
            if (verbose && onBeam && LOG_FORWARD_SEARCH && gold != null)
              logAction(desc + " beamAdd", iter, adj, gold, false);
          }
        }     // end loop over next actions

        if (BUG_FIX) {
          boolean finalState = actionsTried == 0;
          if ((decode && finalState) || !decode)
            maxBeam.push(frontierItem);
        }

        if (LOG_FORWARD_SEARCH) {
          LOG.info(desc + " scored " + added + "/" + actionsTried
              + " actions and " + beamAdds + " were put on the beam");
        }
        if (!fullSearch) {
          this.hasRun = true;
          FS_TIMER.stop();
          return;
        }
      }       // end loop while beam.size > 0

      // When we've done exploring items on the beam, take the biggest violator
      // we found on maxBeam.
      assert fullSearch;
      if (BUG_FIX) {
        this.path = maxBeam.pop();
      } else {
        if (decode) {
          this.path = frontier;
        } else {
          this.path = maxBeam.pop();
        }
      }
      this.hasRun = true;
      logSolution(desc, this.path);
      FS_TIMER.stop();
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
  public Update getFullUpdate(State init, FNParse y, OracleMode oracleMode, Random rand, Timer oracleTimer, Timer mvTimer) {
    if (y.numFrameInstances() == 0) {
      assert init.numFrameInstance() == 0;
      return Update.NONE;
    }

    // Find the oracle parse
    if (oracleTimer != null) oracleTimer.start();
    Params.Stateful cachingModelParams = getFullParams(true);
    boolean oracleSolveMax = oracleMode == OracleMode.MAX
        || oracleMode == OracleMode.RAND_MAX;
    boolean oracleDecode = true;
    BFunc bfunc = new BFunc.Oracle(y, oracleSolveMax);
    if (oracleMode == OracleMode.RAND_MAX || oracleMode == OracleMode.RAND_MIN)
      bfunc = new BFunc.Sum(bfunc, new BFunc.StatefulAdapter(new Params.RandScore(rand, 1d)));
    ForwardSearch oracleSearch = fullSearch(
        init, bfunc, oracleSolveMax, oracleDecode, trainBeamWidth, cachingModelParams);
    if (LOG_FORWARD_SEARCH)
      oracleSearch.gold = y;
    oracleSearch.run();
    if (oracleTimer != null) oracleTimer.stop();

    // Find the most violated parse
    if (mvTimer != null) mvTimer.start();
    cachingModelParams = getFullParams(true); // release old cache, not worth it
    boolean mvSolveMax = true;
    boolean mvDecode = false;
    BFunc mvBFunc = PERCEPTRON ? BFunc.NONE : new BFunc.MostViolated(y);
    ForwardSearch mvSearch =
        fullSearch(init, mvBFunc, mvSolveMax, mvDecode, trainBeamWidth, cachingModelParams);
    if (LOG_FORWARD_SEARCH)
      mvSearch.gold = y;
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
    Params.Stateful model = new Params.Stateful.Lift(thetaStateless);
    BFunc deltaLoss = new BFunc.MostViolated(y);
//    double negSubsampleRate = 0.9d;
//    BFunc deltaLoss = new BFunc.MostViolatedWithSubsampling(y, negSubsampleRate, rand);
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

    /**
     * @return the error before applying this update
     * (should not depend on learningRate)
     */
    public double violation();

    public static Update NONE = new Update() {
      @Override public double apply(double learningRate) {
        return 0d;
      }
      @Override public double violation() {
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
        for (T up : updates) {
          u += up.apply(s);
//          if (Double.isInfinite(u) || Double.isNaN(u)) {
//            up.apply(s);
//            throw new RuntimeException();
//          }
        }
        return u / updates.size();
      }
      @Override
      public double violation() {
        assert updates.size() > 0;
        double u = 0d;
        for (T up : updates)
          u += up.violation();
        return u / updates.size();
      }
    }
  }

  /**
   * Sub-gradient step on loss of:
   *   max_{z,z',y'} s(x,z,y) - (s(x,z',y') + loss(y,y'))
   *
   * Remember that z are sets of y, so that equation is a bit of a pun for:
   *   max_{z,z':z={y}} s(x,z) - (s(x,z') + max_{y' \in z'} loss(y,y'))
   *   
   * BAHHH, trying to make this asymmetric loss work.
   * In a regular SVM you might have
   *  min ||w||^2_2 + C * max_{y'} (s(y') + loss(y,y') - s(y))
   * There is no reason why you can't push that C inside the max and make it a
   * function of y':
   *  min ||w||^2_2 + max_{y'} C(y') * (s(y') + loss(y,y') - s(y))
   * Now we need to ensure that we can solve that max in a step wise fashion. We
   * know that s and loss decompose over i, but does C?
   * 
   *
   * loss need not be \in {0,1}, it can be any arbitrary non-negative value.
   * We will use an asymmetric Hamming loss.
   *
   * Wait a sec, what's the reason that I can't have a multiplicative loss term
   * on the outside?
   * I think it has something to do with the fact that we are doing greedy/beam
   * search and we want to say something about our correctness in finding the
   * maxViolated (z,z',y').
   * What do we want to say about our ability to do that?
   * The reach-ability argument: that if we can reach it under normal decoding
   * we can reach it under the most violation search.
   * If we add a loss value in {0,1} AND THEN scale by a factor >= 1, then it is
   * still the case that if we're on a beam, anything that looked good before
   * that operation would have looked better afterwards.
   *
   * BUT I still need to write down the objective!
   * Can we admit general loss functions or just hamming?
   * Its easy to push this through if you say that the margin is the loss, but
   * its not clear if it works when you want "the margin to be 1, and the cost
   * to be scaled by the class label"...
   *
   * That loss term on the outside makes it seem like we can't necessarily find
   * the most violated (z,z',y') efficiently, but this is a bit misleading.
   * Think of that loss term as C in the regular SVM formulation. For example in
   * the binary case, you can use different C_{y=1} and C_{y=-1} to re-weight
   * a lob-sided training set.
   * AH, just like if we were doing the max only once, I think the loss bit should
   * be recorded for each particular z_i'.
   * This is saying that we want a margin of 1, and more strongly that we want
   * it to hold for the entire trajectory, but that when considering those actions
   * on their own, they should
   * 1) have a local margin?
   * 2) take an update which is comesurate with the local deltaLoss?
   * ....
   * I think both of these seem desirable, but not directly implied by the SSVM math.
   *
   * where (y,z) = oracle
   *       (y',z') = mostViolated
   *       max(0, s(y,z) - [s(y',z') + loss(y,y')]) = loss
   *
   * @return the hinge loss
   */
  public class FullUpdate implements Update {
    public final FNParse gold;
    private StateSequence oracle;
    private StateSequence mostViolated;
    private double hinge;

    public FullUpdate(FNParse gold, StateSequence oracle, StateSequence mostViolated) {
      this.gold = gold;
      this.oracle = oracle;
      this.mostViolated = mostViolated;

      assert oracle.getLoss(gold) == 0;
      assert mostViolated.getLoss(gold) >= 0;

      FNParse yHat = mostViolated.getCur().decode();  // crClassify.decode(mostViolated.getCur());
      assert yHat != null : "mostViolated returned non-terminal state?";
      SentenceEval se = new SentenceEval(gold, yHat);
      double loss = se.argOnlyFP() + COST_FN * se.argOnlyFN();

      // This is the loss before we put it though a max(0,-)
      this.hinge = oracle.getScore() - (mostViolated.getScore() + loss);
      if (Double.isNaN(hinge) || Double.isInfinite(hinge)) {
        LOG.info("oralce.score=" + oracle.getScore());
        LOG.info("mostViolated.score=" + mostViolated.getScore());
        LOG.info("loss=" + loss);
        assert false;
      }

      if (LOG_UPDATE) {
        LOG.info("[FullUpdate init] hinge=" + hinge
            + " oracle.length=" + oracle.length() + " oracle.score=" + oracle.getScore()
            + " mv.length=" + mostViolated.length() + " mv.score=" + mostViolated.getScore()
            + " mv.loss=" + mostViolated.getLoss(gold));
      }
    }

    public boolean isViolated() {
      return hinge < 0d;
    }

    public double violation() {
      if (hinge < 0d) {
        //return -hinge / (oracle.length() + mostViolated.length());
        //return -hinge;
        return -hinge / oracle.length();
      } else {
        return 0d;
      }
    }

    @Override
    public double apply(double learningRate) {
      getTimer().start("Update.getUpdate");

      if (!isViolated()) {
        getTimer().stop("Update.getUpdate");
        return 0d;
      }

      int skipped;
      double v = violation();

      skipped = 0;
      final double upOracle = GRADIENT_BUGFIX
          ? learningRate
          : learningRate * v;
      for (StateSequence cur = oracle; cur != null; cur = cur.getPrev()) {
        Adjoints a = cur.getAdjoints();
        if (a != null) {
          if (LOG_UPDATE)
            LOG.info("[FullUpdate apply] upOracle=" + upOracle + " action=" + a.getAction());
          a.backwards(upOracle);
        } else {
          skipped++;
        }
      }
      assert skipped <= 1;

      skipped = 0;
      final double upMV = GRADIENT_BUGFIX
          ? -learningRate
          : -learningRate * v;
      for (StateSequence cur = mostViolated; cur != null; cur = cur.getPrev()) {
        Adjoints a = cur.getAdjoints();
        if (a != null) {
          if (LOG_UPDATE)
            LOG.info("[FullUpdate apply] upMV=" + upMV + " action=" + a.getAction());
          a.backwards(upMV);
        } else {
          skipped++;
        }
      }
      assert skipped <= 1;

//      showWeights();

      getTimer().stop("Update.getUpdate");
      return violation();
    }
  }

  private MultiTimer getTimer() {
    if (timer == null)
      timer = new MultiTimer();
    return timer;
  }
}
