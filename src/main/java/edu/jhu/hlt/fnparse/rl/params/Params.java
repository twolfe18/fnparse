package edu.jhu.hlt.fnparse.rl.params;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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

  // score is in an extending class
  // because its signature differs between Stateful and Stateless,
  // its not listed here.

  /**
   * Parameters that specify tau in ActionType.PRUNE.
   */
  public static interface PruneThreshold extends Params {
    // TODO add a stateful version of this?
    public Adjoints score(FNTagging frames, PruneAdjoints pruneAction, String... providenceInfo);

    public static class Const implements PruneThreshold {
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
    }
    public static class Sum implements PruneThreshold {
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
    }

    // TODO move this over to use FrameRolePacking and bit-shifting
    /** Implementation */
    public static class Impl extends FeatureParams implements PruneThreshold {
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
    };

    public static Stateful lift(final Stateless theta) {
      return new Stateful() {
        @Override
        public String toString() {
          return "(Lifted " + theta + ")";
        }
        @Override
        public Adjoints score(State s, CommitIndex ai, Action a) {
          return theta.score(s.getFrames(), a);
        }
        @Override
        public void doneTraining() {
          theta.doneTraining();
        }
        @Override
        public void showWeights() {
          theta.showWeights();
        }
        @Override
        public void serialize(DataOutputStream out) throws IOException {
          theta.serialize(out);
        }
        @Override
        public void deserialize(DataInputStream in) throws IOException {
          theta.deserialize(in);
        }
      };
    }
  }

  /**
   * Features that don't know about inference (cacheable)
   */
  public static interface Stateless extends Params {
    public Adjoints score(FNTagging f, Action a);

    public static final Stateless NONE = new Stateless() {
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
    };

    /**
     * A cache for Stateless params/features.
     *
     * It's a shame, but I can't seem to make this work for both types of Params
     * using Java's generics, but its not that big of a deal because you should
     * really only be caching Stateless Params anyway.
     */
    public static class Caching implements Stateless {
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
  }

  /** Params is closed under addition */
  public static class SumStateless implements Stateless {
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
  }

  /** Params is closed under addition */
  public static class SumStateful implements Stateful {
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
  }

  public static class RandScore implements Stateless, Stateful {
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
  }

}
