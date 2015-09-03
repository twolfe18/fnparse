package edu.jhu.hlt.fnparse.rl.params;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.CommitIndex;
import edu.jhu.hlt.fnparse.rl.PruneAdjoints;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.net.NetworkParameterAveraging;

/**
 * Parameterizes a score function on (state,action) pairs
 */
public interface Params extends Serializable {
  public static final Logger LOG = Logger.getLogger(Params.class);

  /**
   * This is called after training is complete. This is a useful hook for
   * switching from regular perceptron weights to averaged ones.
   */
  public void doneTraining();

  public void showWeights();

  public void serialize(DataOutputStream out) throws IOException;
  public void deserialize(DataInputStream in) throws IOException;

  // For parameter averaging.
  // Params should only ever be instantiated at the same type as the
  // implementing class. As in class X implements Params can expect the first
  // argument to be of type X.
  public void addWeights(Params other, boolean checkAlphabetEquality);
  public void scaleWeights(double scale);

  // score is in an extending class
  // because its signature differs between Stateful and Stateless,
  // its not listed here.

  /**
   * For serialization and network parameter averaging.
   */
  public static class Glue implements Params {
    private static final long serialVersionUID = 5523323641331682349L;
    private Params.Stateful stateful;
    private Params.Stateless stateless;
    private Params.PruneThreshold tau;
    public Glue(
        Params.Stateful stateful,
        Params.Stateless stateless,
        Params.PruneThreshold tau) {
      if (stateful == null)
        throw new IllegalArgumentException();
      if (stateless == null)
        throw new IllegalArgumentException();
      if (tau == null)
        throw new IllegalArgumentException();
      this.stateful = stateful;
      this.stateless = stateless;
      this.tau = tau;
    }
    public Params.Stateful getStateful() {
      return stateful;
    }
    public Params.Stateless getStateless() {
      return stateless;
    }
    public Params.PruneThreshold getTau() {
      return tau;
    }
    @Override
    public String toString() {
      return "(Glue stateful=" + stateful + " stateless=" + stateless + " tau=" + tau + ")";
    }
    @Override
    public void doneTraining() {
      stateful.doneTraining();
      stateless.doneTraining();
      tau.doneTraining();
    }
    @Override
    public void showWeights() {
      stateful.showWeights();
      stateless.showWeights();
      tau.showWeights();
    }
    @Override
    public void serialize(DataOutputStream out) throws IOException {
      stateful.serialize(out);
      stateless.serialize(out);
      tau.serialize(out);
    }
    @Override
    public void deserialize(DataInputStream in) throws IOException {
      stateful.deserialize(in);
      stateless.deserialize(in);
      tau.deserialize(in);
    }
    @Override
    public void addWeights(Params other, boolean checkAlphabetEquality) {
      Log.info("other.class=" + other.getClass().getName());
      Glue g = (Glue) other;
      stateful.addWeights(g.stateful, checkAlphabetEquality);
      stateless.addWeights(g.stateless, checkAlphabetEquality);
      tau.addWeights(g.tau, checkAlphabetEquality);
    }
    @Override
    public void scaleWeights(double scale) {
      stateful.scaleWeights(scale);
      stateless.scaleWeights(scale);
      tau.scaleWeights(scale);
    }
  }

  /**
   * Parameters that specify tau in ActionType.PRUNE.
   */
  public static interface PruneThreshold extends Params {
    // TODO add a stateful version of this?
    public Adjoints score(FNTagging frames, PruneAdjoints pruneAction, String... providenceInfo);

    public static class Const implements PruneThreshold {
      private static final long serialVersionUID = 1L;
      public static final Const ZERO = new Const(0);
      public static final Const ONE = new Const(1);
      public double intercept;
      public Const(double intercept) {
        this.intercept = intercept;
      }
      @Override
      public String toString() {
        return String.format("(Constant %.1f)", intercept);
      }
      @Override
      public Adjoints score(FNTagging frames, PruneAdjoints pruneAction, String... providenceInfo) {
        return new Adjoints.Explicit(intercept, pruneAction, "tauConst");
      }
      @Override
      public void showWeights() {
        LOG.info("[Const showWeights] intercept=" + intercept);
      }
      @Override
      public void doneTraining() {
        LOG.info("[Const doneTraining] no-op");
      }
      @Override
      public void serialize(DataOutputStream out) throws IOException {
        out.writeDouble(intercept);
      }
      @Override
      public void deserialize(DataInputStream in) throws IOException {
        intercept = in.readDouble();
      }
      @Override
      public void addWeights(Params other, boolean checkAlphabetEquality) {
        Const c = (Const) other;
        assert intercept == c.intercept;
      }
      @Override
      public void scaleWeights(double scale) {
        // no-op
      }
    }

    public static class Sum implements PruneThreshold {
      private static final long serialVersionUID = 1L;
      private final PruneThreshold left, right;
      public Sum(PruneThreshold left, PruneThreshold right) {
        if (left == null || right == null)
          throw new IllegalArgumentException();
        this.left = left;
        this.right = right;
      }
      @Override
      public Adjoints score(FNTagging frames, PruneAdjoints pruneAction, String... providenceInfo) {
        Adjoints l = left.score(frames, pruneAction, providenceInfo);
        Adjoints r = right.score(frames, pruneAction, providenceInfo);
        return new SumAdj(l, r);
      }
      @Override
      public void showWeights() {
        left.showWeights();
        right.showWeights();
      }
      @Override
      public void doneTraining() {
        left.doneTraining();
        right.doneTraining();
      }
      @Override
      public void serialize(DataOutputStream out) throws IOException {
        left.serialize(out);
        right.serialize(out);
      }
      @Override
      public void deserialize(DataInputStream in) throws IOException {
        left.deserialize(in);
        right.deserialize(in);
      }
      @Override
      public void addWeights(Params other, boolean checkAlphabetEquality) {
        Sum s = (Sum) other;
        left.addWeights(s.left, checkAlphabetEquality);
        right.addWeights(s.right, checkAlphabetEquality);
      }
      @Override
      public void scaleWeights(double scale) {
        left.scaleWeights(scale);
        right.scaleWeights(scale);
      }
    }

    // TODO move this over to use FrameRolePacking and bit-shifting
    /** Implementation */
    public static class Impl extends FeatureParams implements PruneThreshold {
      private static final long serialVersionUID = 1L;
      public Impl(double l2Penalty) {
        super(l2Penalty);
      }
      public Impl(double l2Penalty, int hashBuckets) {
        super(l2Penalty, hashBuckets);
      }
      @Override
      public FeatureVector getFeatures(FNTagging frames, PruneAdjoints pruneAction, String... providenceInfo) {
        Frame f = null;
        if (pruneAction.t >= 0)
          f = frames.getFrameInstance(pruneAction.t).getFrame();
        int k = pruneAction.k;
        FeatureVector fv = new FeatureVector();
        bb(fv, f, k, null);
        for (String pi : providenceInfo)
          bb(fv, f, k, pi);
        return fv;
      }
      private void bb(FeatureVector fv, Frame f, int k, String provInfo) {
        double interceptW = 0.2d;
        double nonInterceptW = 0.1d;
        if (provInfo == null)
          b(fv, interceptW, "intercept");
        else
          b(fv, interceptW, "intercept-" + provInfo);
        if (f != null) {
          if (provInfo == null)
            b(fv, nonInterceptW,  "frame=" + f.getName());
          else
            b(fv, nonInterceptW, "frame=" + f.getName(), provInfo);
          if (k >= 0) {
            if (provInfo == null)
              b(fv, nonInterceptW, "role=" + f.getRole(k));
            else
              b(fv, nonInterceptW, "role=" + f.getRole(k), provInfo);
            if (provInfo == null)
              b(fv, nonInterceptW, "frameRole=" + f.getName() + "." + f.getRole(k));
            else
              b(fv, nonInterceptW, "frameRole=" + f.getName() + "." + f.getRole(k), provInfo);
          }
        } else {
          if (provInfo == null)
            b(fv, nonInterceptW, "allTargets");
          else
            b(fv, nonInterceptW, "allTargets", provInfo);
        }
      }
    }
  }

  /**
   * Features that need to look at the state of the parser (uncacheable)
   */
  public static interface Stateful extends Params {

    /**
     * @param s is the state of the decoding.
     * @param ai is an index of all of the Actions that have been taken so far.
     * @param a is the action to be scored.
     */
    //public Adjoints score(State s, SpanIndex<Action> ai, Action a);
    public Adjoints score(State s, CommitIndex ai, Action a);

    public static final Stateful NONE = new Stateful() {
      private static final long serialVersionUID = 1L;
      @Override public Adjoints score(State s, CommitIndex ai, final Action a) {
        return new Adjoints() {
          @Override public String toString() { return "0"; }
          @Override public double forwards() { return 0d; }
          @Override public Action getAction() {
            return a;
          }
          @Override
          public void backwards(double dScore_dForwards) {
            // no-op
          }
        };
      }
      @Override public String toString() { return "0"; }
      @Override
      public void showWeights() {
        LOG.info("[Stateful.NONE showWeights] none");
      }
      @Override
      public void doneTraining() {
        LOG.info("[Stateful.NONE doneTraining] no-op");
      }
      @Override
      public void serialize(DataOutputStream out) throws IOException {
        LOG.info("[Stateful.NONE serialize] no-op");
      }
      @Override
      public void deserialize(DataInputStream in) throws IOException {
        LOG.info("[Stateful.NONE deserialize] no-op");
      }
      @Override
      public void addWeights(Params other, boolean checkAlphabetEquality) {
        assert other.getClass().equals(this.getClass())
          : "this.class=" + this.getClass().getName()
          + " other.class=" + other.getClass().getName()
          + "this=Stateful.NONE other=" + other.toString();
        LOG.info("[Stateful.NONE addWeights] no-op");
      }
      @Override
      public void scaleWeights(double scale) {
        LOG.info("[Stateful.NONE scaleWeights] no-op");
      }
    };

    public static class Lift implements Stateful {
      private final Stateless stateless;
      public Lift(Stateless theta) {
        stateless = theta;
      }
      private static final long serialVersionUID = 1L;
      @Override
      public String toString() {
        return "(Lifted " + stateless + ")";
      }
      @Override
      public Adjoints score(State s, CommitIndex ai, Action a) {
        return stateless.score(s.getFrames(), a);
      }
      @Override
      public void doneTraining() {
        stateless.doneTraining();
      }
      @Override
      public void showWeights() {
        stateless.showWeights();
      }
      @Override
      public void serialize(DataOutputStream out) throws IOException {
        stateless.serialize(out);
      }
      @Override
      public void deserialize(DataInputStream in) throws IOException {
        stateless.deserialize(in);
      }
      @Override
      public void addWeights(Params other, boolean checkAlphabetEquality) {
        Lift d = (Lift) other;
        stateless.addWeights(d.stateless, checkAlphabetEquality);
      }
      @Override
      public void scaleWeights(double scale) {
        stateless.scaleWeights(scale);
      }
    }
  }

  /**
   * Features that don't know about inference (cacheable)
   */
  public static interface Stateless extends Params {
    public Adjoints score(FNTagging f, Action a);

    public static final Stateless NONE = new Stateless() {
      private static final long serialVersionUID = 1L;
      @Override public Adjoints score(FNTagging frames, final Action a) {
        return new Adjoints() {
          @Override public String toString() { return "0"; }
          @Override
          public double forwards() {
            return 0d;
          }
          @Override
          public Action getAction() {
            return a;
          }
          @Override
          public void backwards(double dScore_dForwards) {
            // no-op
          }
        };
      }
      @Override public String toString() { return "0"; }
      @Override
      public void showWeights() {
        LOG.info("[Stateless.NONE showWeights] none");
      }
      @Override
      public void doneTraining() {
        LOG.info("[Stateless.NONE doneTraining] no-op");
      }
      @Override
      public void serialize(DataOutputStream out) throws IOException {
        LOG.info("[Stateless.NONE serialize] no-op");
      }
      @Override
      public void deserialize(DataInputStream in) throws IOException {
        LOG.info("[Stateless.NONE deserialize] no-op");
      }
      @Override
      public void addWeights(Params other, boolean checkAlphabetEquality) {
        assert getClass().equals(other.getClass());
        LOG.info("[Stateless.NONE addWeights] no-op");
      }
      @Override
      public void scaleWeights(double scale) {
        LOG.info("[Stateless.NONE scaleWeights] no-op");
      }
    };

    /**
     * A cache for Stateless params/features.
     *
     * It's a shame, but I can't seem to make this work for both types of Params
     * using Java's generics, but its not that big of a deal because you should
     * really only be caching Stateless Params anyway.
     */
    public static class Caching implements Stateless {
      private static final long serialVersionUID = 1L;
      private int cache = 2;  // 0=none, 1=hashmap, 2=array
      private Stateless wrapping;
      private Map<Action, Adjoints> cache1;
      private Adjoints[][][][] cache2; // [t][k][start][end]
      private Object tag;
      public Caching(Stateless wrapping) {
        this.wrapping = wrapping;
        this.cache1 = new HashMap<>();
      }
      @Override
      public String toString() {
        return "(Cache " + wrapping + ")";
      }
      public Stateless getWrapped() {
        return wrapping;
      }
      public void flush() {
        cache1.clear();
        cache2 = null;
        tag = null;
      }
     private void initCache2(FNTagging f) {
        int T = f.numFrameInstances();
        int N = f.getSentence().size();
        cache2 = new Adjoints[T][][][];
        for (int t = 0; t < T; t++) {
          int K = f.getFrameInstance(t).getFrame().numRoles();
          cache2[t] = new Adjoints[K][N][N+1];
        }
      }
      @Override
      public Adjoints score(FNTagging f, Action a) {

        if (cache == 0)
          return wrapping.score(f, a);

        // Check that this is caching the right thing.
        if (tag == null) {
          assert f != null;
          tag = f;
          initCache2(f);
        } else if (tag != f) {
          throw new RuntimeException("forget to flush?");
        }
        // Get or compute the adjoints
        Adjoints adj;
        if (cache == 1)
          adj = cache1.get(a);
        else
          adj = cache2[a.t][a.k][a.start][a.end];
        if (adj == null)
          adj = cacheMiss(wrapping, f, a);
        return adj;
      }
      /** This only exists to let VisualVM know the difference between cache hits and misses */
      private Adjoints cacheMiss(Stateless wrapping, FNTagging f, Action a) {
        Adjoints adj = wrapping.score(f, a);
        if (cache == 1)
          cache1.put(a, adj);
        else
          cache2[a.t][a.k][a.start][a.end] = adj;
        return adj;
      }
      /*
      public static Set<Object> seenTags = new HashSet<>();
      public void estimateCollisions() {
        int c1 = 0, c2 = 0, n = 0;
        BitSet seen1 = new BitSet();
        BitSet seen2 = new BitSet();
        for (Action a : cache.keySet()) {
          n++;

          int hc1 = a.hc1();
          if (seen1.get(hc1))
            c1++;
          seen1.set(hc1);

          int hc2 = a.hc2();
          if (seen2.get(hc2))
            c2++;
          seen2.set(hc2);
        }
        LOG.info("[estimateCollisions] c1=" + c1 + " c2=" + c2 + " n=" + n);
      }
      */
      @Override
      public void doneTraining() {
        wrapping.doneTraining();
      }
      @Override
      public void showWeights() {
        wrapping.showWeights();
      }
      @Override
      public void serialize(DataOutputStream out) throws IOException {
        wrapping.serialize(out);
      }
      @Override
      public void deserialize(DataInputStream in) throws IOException {
        flush();
        wrapping.deserialize(in);
      }
      @Override
      public void addWeights(Params other, boolean checkAlphabetEquality) {
        Caching c = (Caching) other;
        wrapping.addWeights(c.wrapping, checkAlphabetEquality);
      }
      @Override
      public void scaleWeights(double scale) {
        wrapping.scaleWeights(scale);
      }
    }
  }

  // TODO move into Adjoints as static inner class
  static class SumAdj implements Adjoints {
    private final Adjoints left, right;
    public SumAdj(Adjoints stateful, Adjoints stateless) {
      assert stateful.getAction() == stateless.getAction()
          || stateful.getAction().equals(stateless.getAction());
      this.left = stateful;
      this.right = stateless;
    }
    @Override
    public String toString() {
      return left + " + " + right;
    }
    @Override
    public double forwards() {
      return left.forwards() + right.forwards();
    }
    @Override
    public Action getAction() {
      assert left.getAction() == right.getAction()
          || left.getAction().equals(right.getAction());
      return left.getAction();
    }
    @Override
    public void backwards(double dScore_dForwards) {
      left.backwards(dScore_dForwards);
      right.backwards(dScore_dForwards);
    }
  }

  /** Params is closed under addition */
  public static class SumMixed implements Stateful {
    private static final long serialVersionUID = 1L;
    private final Stateful stateful;
    private final Stateless stateless;
    public SumMixed(Stateful stateful, Stateless stateless) {
      this.stateful = stateful;
      this.stateless = stateless;
    }
    @Override
    public String toString() {
      return stateful + " + " + stateless;
    }
    @Override
    //public Adjoints score(State s, SpanIndex<Action> ai, Action a) {
    public Adjoints score(State s, CommitIndex ai, Action a) {
      FNTagging f = s.getFrames();
      return new SumAdj(stateful.score(s, ai, a), stateless.score(f, a));
    }
    @Override
    public void doneTraining() {
      stateful.doneTraining();
      stateless.doneTraining();
    }
    @Override
    public void showWeights() {
      stateful.showWeights();
      stateless.showWeights();
    }
    @Override
    public void serialize(DataOutputStream out) throws IOException {
      stateful.serialize(out);
      stateless.serialize(out);
    }
    @Override
    public void deserialize(DataInputStream in) throws IOException {
      stateful.deserialize(in);
      stateless.deserialize(in);
    }
    @Override
    public void addWeights(Params other, boolean checkAlphabetEquality) {
      SumMixed sm = (SumMixed) other;
      stateful.addWeights(sm.stateful, checkAlphabetEquality);
      stateless.addWeights(sm.stateless, checkAlphabetEquality);
    }
    @Override
    public void scaleWeights(double scale) {
      stateful.scaleWeights(scale);
      stateless.scaleWeights(scale);
    }
  }

  /** Params is closed under addition */
  public static class SumStateless implements Stateless {
    private static final long serialVersionUID = 1L;
    private final Stateless left, right;
    public SumStateless(Stateless left, Stateless right) {
      this.left = left;
      this.right = right;
    }
    @Override
    public String toString() {
      return left + " + " + right;
    }
    @Override
    public Adjoints score(FNTagging f, Action a) {
      return new SumAdj(left.score(f, a), right.score(f, a));
    }
    @Override
    public void doneTraining() {
      left.doneTraining();
      right.doneTraining();
    }
    @Override
    public void showWeights() {
      left.showWeights();
      right.showWeights();
    }
    @Override
    public void serialize(DataOutputStream out) throws IOException {
      left.serialize(out);
      right.serialize(out);
    }
    @Override
    public void deserialize(DataInputStream in) throws IOException {
      left.deserialize(in);
      right.deserialize(in);
    }
    @Override
    public void addWeights(Params other, boolean checkAlphabetEquality) {
      SumStateless ss = (SumStateless) other;
      left.addWeights(ss.left, checkAlphabetEquality);
      right.addWeights(ss.right, checkAlphabetEquality);
    }
    @Override
    public void scaleWeights(double scale) {
      left.scaleWeights(scale);
      right.scaleWeights(scale);
    }
  }

  /** Params is closed under addition */
  public static class SumStateful implements Stateful {
    private static final long serialVersionUID = 1L;
    private final Stateful left, right;
    public SumStateful(Stateful left, Stateful right) {
      this.left = left;
      this.right = right;
    }
    @Override
    public String toString() {
      return left + " + " + right;
    }
    @Override
    //public Adjoints score(State s, SpanIndex<Action> ai, Action a) {
    public Adjoints score(State s, CommitIndex ai, Action a) {
      return new SumAdj(left.score(s, ai, a), right.score(s, ai, a));
    }
    @Override
    public void doneTraining() {
      left.doneTraining();
      right.doneTraining();
    }
    @Override
    public void showWeights() {
      left.showWeights();
      right.showWeights();
    }
    @Override
    public void serialize(DataOutputStream out) throws IOException {
      left.serialize(out);
      right.serialize(out);
    }
    @Override
    public void deserialize(DataInputStream in) throws IOException {
      left.deserialize(in);
      right.deserialize(in);
    }
    @Override
    public void addWeights(Params other, boolean checkAlphabetEquality) {
      SumStateful ss = (SumStateful) other;
      left.addWeights(ss.left, checkAlphabetEquality);
      right.addWeights(ss.right, checkAlphabetEquality);
    }
    @Override
    public void scaleWeights(double scale) {
      left.scaleWeights(scale);
      right.scaleWeights(scale);
    }
  }

  public static class RandScore implements Stateless, Stateful {
    private static final long serialVersionUID = 1L;
    private java.util.Random rand;
    private double variance;
    public RandScore(Random rand, double variance) {
      this.rand = rand;
      this.variance = variance;
    }
    @Override
    public String toString() {
      return String.format("(Rand %.1f)", variance);
    }
    @Override
    //public Adjoints score(State s, SpanIndex<Action> ai, Action a) {
    public Adjoints score(State s, CommitIndex ai, Action a) {
      double r = (rand.nextDouble() - 0.5) * 2 * variance;
      return new Adjoints.Explicit(r, a, "rand");
    }
    @Override
    public Adjoints score(FNTagging f, Action a) {
      double r = (rand.nextDouble() - 0.5) * 2 * variance;
      return new Adjoints.Explicit(r, a, "rand");
    }
    @Override
    public void showWeights() {
      LOG.info("[RandScore showWeights] variance=" + variance);
    }
    @Override
    public void doneTraining() {
      LOG.info("[RandScore doneTraining] no-op");
    }
    @Override
    public void serialize(DataOutputStream out) throws IOException {
      out.writeDouble(variance);
    }
    @Override
    public void deserialize(DataInputStream in) throws IOException {
      variance = in.readDouble();
    }
    @Override
    public void addWeights(Params other, boolean checkAlphabetEquality) {
      // variance is not *weights*
      // only purpose of having add/scale weights is for parameter averaging,
      // which doesn't apply to variance.
      RandScore rs = (RandScore) other;
      assert variance == rs.variance;
    }
    @Override
    public void scaleWeights(double scale) {
      // variance is not *weights*
      // only purpose of having add/scale weights is for parameter averaging,
      // which doesn't apply to variance.
    }
  }

  /**
   * Assuming Params are serializable, this class lifts them up to support
   * parameter averaging over the network. See {@link NetworkParameterAveraging.AvgParams}
   */
  public static class NetworkAvg implements NetworkParameterAveraging.AvgParams, Serializable {
    private static final long serialVersionUID = -4791579161317201098L;

    private Params sum;
    private boolean checkAlph;
    private int adds;
    public int showParamsEveryKMessage = 3;
    public boolean debug = false;

    /**
     * @param zero is an instantiated set of weights set to 0 (this class
     * doesn't know how to create new Params).
     * @param checkAlphabetEquality is a safety option but may cause slowness.
     */
    public NetworkAvg(Params zero, boolean checkAlphabetEquality) {
      sum = zero;
      adds = 0;
      checkAlph = checkAlphabetEquality;
      Log.info("zero.class=" + zero.getClass().getName()
          + " zero.toString=" + zero
          + " checkAlphabetEquality=" + checkAlphabetEquality);
    }

    @Override
    public void receiveMessage(String message) {
      Log.info("[NetworkAvg receiveMessage] " + message);
      if (adds % showParamsEveryKMessage == 0) {
        Log.info("[NetworkAvg receiveMessage] adds=" + adds + " showing average:");
        Params avg = getAverage();
        avg.showWeights();
      }
    }

    @Override
    public String toString() {
      return "(NetworkAvg checkAlph=" + checkAlph + " adds=" + adds + " debug=" + debug + " sum=" + sum + ")";
    }

    @Override
    public void set(InputStream data) {
      try {
        ObjectInputStream ois = new ObjectInputStream(data);
        Params d = (Params) ois.readObject();
        Log.info("other.class=" + d.getClass().getName() + " sum=" + sum + " other=" + d);
        sum = d;
        adds = 1;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void get(OutputStream data) {
      try {
        Log.info("sum.class=" + sum.getClass().getName() + " sum=" + sum);
        ObjectOutputStream oos = new ObjectOutputStream(data);
        oos.writeObject(sum);
        oos.flush();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    public Params get() {
      return cloneViaSerialization(this.sum);
    }

    @Override
    public void add(InputStream other) {  // matches get
      try {
        ObjectInputStream ois = new ObjectInputStream(other);
        Params d = (Params) ois.readObject();
        Log.info("other.class=" + d.getClass().getName());
        sum.addWeights(d, checkAlph);
        adds++;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void getAverage(OutputStream data) {
      try {
        Log.info("adds=" + adds);
        Params avg = cloneViaSerialization(sum);
        avg.scaleWeights(1d / adds);
        ObjectOutputStream oos = new ObjectOutputStream(data);
        oos.writeObject(avg);
        oos.flush();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    public Params getAverage() {
      if (adds < 1)
        throw new IllegalStateException();
      Params avg = cloneViaSerialization(this.sum);
      avg.scaleWeights(1d / adds);
      return avg;
    }

    public int getNumAdds() {
      return adds;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Serializable> T cloneViaSerialization(T input) {
      try {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(input);
        byte[] bytes = baos.toByteArray();
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        ObjectInputStream ois = new ObjectInputStream(bais);
        return ((T) ois.readObject());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

}
