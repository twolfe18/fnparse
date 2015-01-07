package edu.jhu.hlt.fnparse.rl.params;

import java.util.HashMap;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.State;

/**
 * Parameterizes a score function on (state,action) pairs
 */
public interface Params {

  public void update(Adjoints a, double reward);


  /**
   * Features that need to look at the state of the parser (uncacheable)
   */
  public static interface Stateful extends Params {
    public Adjoints score(State s, Action a);

    public static final Stateful NONE = new Stateful() {
      @Override public void update(Adjoints a, double reward) {}
      @Override public Adjoints score(State s, final Action a) {
        return new Adjoints() {
          @Override public double getScore() { return 0d; }
          @Override public Action getAction() {
            return a;
          }
        };
      }
    };

    public static Stateful lift(final Stateless theta) {
      return new Stateful() {
        @Override public void update(Adjoints a, double reward) {
          theta.update(a, reward);
        }
        @Override public Adjoints score(State s, Action a) {
          return theta.score(s.getFrames(), a);
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
      @Override public void update(Adjoints a, double reward) {}
      @Override public Adjoints score(FNTagging frames, final Action a) {
        return new Adjoints() {
          @Override public double getScore() { return 0d; }
          @Override public Action getAction() {
            return a;
          }
        };
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
      private Stateless wrapping;
      private Map<Action, Adjoints> cache;
      private Object tag;
      public Caching(Stateless wrapping) {
        this.wrapping = wrapping;
        this.cache = new HashMap<>();
      }
      public Stateless getWrapped() {
        return wrapping;
      }
      public int size() {
        return cache.size();
      }
      public void flush() {
        cache.clear();
        tag = null;
      }
      @Override
      public void update(Adjoints a, double reward) {
        wrapping.update(a, reward);
      }
      @Override
      public Adjoints score(FNTagging f, Action a) {
        // Check that this is caching the right thing.
        if (tag == null)
          tag = f;
        else if (tag != f)
          throw new RuntimeException("forget to flush?");
        // Get or compute the adjoints
        Adjoints adj = cache.get(a);
        if (adj == null) {
          adj = wrapping.score(f, a);
          cache.put(a, adj);
        }
        return adj;
      }
    }
  }

  /** Params is closed under addition */
  public static class SumMixed implements Stateful {
    private static class SumAdj implements Adjoints {
      private final Adjoints stateful, stateless;
      public SumAdj(Adjoints stateful, Adjoints stateless) {
        assert stateful.getAction() == stateless.getAction()
            || stateful.getAction().equals(stateless.getAction());
        this.stateful = stateful;
        this.stateless = stateless;
      }
      @Override
      public double getScore() {
        return stateful.getScore() + stateless.getScore();
      }
      @Override
      public Action getAction() {
        assert stateful.getAction() == stateless.getAction()
            || stateful.getAction().equals(stateless.getAction());
        return stateful.getAction();
      }
    }
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
    public void update(Adjoints a, double reward) {
      SumAdj sa = (SumAdj) a;
      stateful.update(sa.stateful, reward);
      stateless.update(sa.stateless, reward);
    }
  }

  // TODO SumStateful and SumStateless?
}
