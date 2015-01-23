package edu.jhu.hlt.fnparse.rl.params;

import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.State;

/**
 * Parameterizes a score function on (state,action) pairs
 */
public interface Params {
  public static final Logger LOG = Logger.getLogger(Params.class);

  // This is no longer needed because the Adjoints have an apply method
  //public void update(Update update);

  /**
   * This is called after training is complete. This is a useful hook for
   * switching from regular perceptron weights to averaged ones.
   */
  public void doneTraining();

  // score is in an extending class
  // because its signature differs between Stateful and Stateless,
  // its not listed here.


  /**
   * Features that need to look at the state of the parser (uncacheable)
   */
  public static interface Stateful extends Params {
    public Adjoints score(State s, Action a);

    public static final Stateful NONE = new Stateful() {
      @Override public Adjoints score(State s, final Action a) {
        return new Adjoints() {
          @Override public double forwards() { return 0d; }
          @Override public Action getAction() {
            return a;
          }
          @Override
          public void backwards(double dErr_dForwards) {
            // no-op
          }
        };
      }
      @Override
      public void doneTraining() {
        // no-op
      }
    };

    public static Stateful lift(final Stateless theta) {
      return new Stateful() {
        @Override
        public Adjoints score(State s, Action a) {
          return theta.score(s.getFrames(), a);
        }
        @Override
        public void doneTraining() {
          theta.doneTraining();
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
          @Override
          public double forwards() {
            return 0d;
          }
          @Override
          public Action getAction() {
            return a;
          }
          @Override
          public void backwards(double dErr_dForwards) {
            // no-op
          }
        };
      }
      @Override
      public void doneTraining() {
        // no-op
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
    }
  }

  static class SumAdj implements Adjoints {
    private final Adjoints left, right;
    public SumAdj(Adjoints stateful, Adjoints stateless) {
      assert stateful.getAction() == stateless.getAction()
          || stateful.getAction().equals(stateless.getAction());
      this.left = stateful;
      this.right = stateless;
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
    public void backwards(double dErr_dForwards) {
      left.backwards(dErr_dForwards);
      right.backwards(dErr_dForwards);
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
    public Adjoints score(State s, Action a) {
      FNTagging f = s.getFrames();
      return new SumAdj(stateful.score(s, a), stateless.score(f, a));
    }
   @Override
    public void doneTraining() {
      stateful.doneTraining();
      stateless.doneTraining();
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
    public Adjoints score(FNTagging f, Action a) {
      return new SumAdj(left.score(f, a), right.score(f, a));
    }
    @Override
    public void doneTraining() {
      left.doneTraining();
      right.doneTraining();
    }
  }

  // TODO SumStateful?
}
