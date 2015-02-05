package edu.jhu.hlt.fnparse.rl.params;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionIndex;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.ActionIndex.IndexItem;
import edu.jhu.hlt.fnparse.util.FeatureUtils;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;

/**
 * GlobalFeatures are expected to keep their own indexes up to date.
 * 
 * There is a problem: GlobalFeatures (or Params.Stateful if they're merged)
 * cannot keep their own index, they must be able to read off their answers from
 * State, because we are reasoning about many States on the beam at any given
 * time.
 * 
 * New design: GlobalFeatures will inject an observer into every new state...
 * no that wont work either, States are forked with State.apply
 * 
 * First thing to do is to avoid the problem of indexing by simply ignoring it
 * and seeing if things are too slow.
 *
 * @author travis
 */
public interface GlobalFeature extends Params.Stateful {

  /**
   * Called every time an action is committed to.
   */
  public void observe(State s, Action a);

  public void clear();

  public FeatureVector featurize(State s, Action a);


  /**
   * Fires when roles co-occur.
   * Captures "stopping features", e.g. "commit to (t,k) = nullSpan even though
   * (t',k') = nonNullSpan".
   */
  public static class RoleCooccurenceFeatureStateful
      extends FeatureParams implements Params.Stateful {
    public static FrameRolePacking frPacking = new FrameRolePacking();
    public static final int BUCKETS = 1<<20;
    public static final int DA_BITS = 14;
    public static final int REL_BITS = 3;
    public RoleCooccurenceFeatureStateful(double l2Penalty, double learningRate) {
      super(l2Penalty, BUCKETS); // use Hashing
      this.learningRate = learningRate;
    }
    @Override
    public FeatureVector getFeatures(State state, ActionIndex ai, Action a2) {
      // must be >0 because 0 is the implicit backoff label
      final int SAME_TARGET = 1;
      final int BEFORE = 2;
      final int AFTER = 3;
      final int WAT = 4;

      int da2;
      if (a2.hasSpan()) {
        da2 = frPacking.index(state.getFrame(a2.t), a2.k);
      } else {
        da2 = frPacking.index(state.getFrame(a2.t));
      }
      da2 = da2 << REL_BITS; // move over to make room for rel type

      FeatureVector fv = new FeatureVector();
      for (IndexItem i = ai.allActions(); i != null; i = i.prevNonEmptyItem) {
        Action a = i.action;
        FrameInstance fi = state.getFrameInstance(a.t);
        Frame f = fi.getFrame();
        int da1 = frPacking.index(f, a.k);
        da1 = da1 << (REL_BITS + DA_BITS);
        if (a.t == a2.t) {
          fv.add(mod(da1 ^ da2 ^ SAME_TARGET, BUCKETS), 1d);
          fv.add(mod(da1 ^ da2, BUCKETS), 1d);
        } else {
          // This characterizes the linear relationship between the targets
          // of the previously committed item and the current one.
          // TODO characterize the arg position as well.
          Span s1 = state.getFrameInstance(a.t).getTarget();
          Span s2 = state.getFrameInstance(a2.t).getTarget();
          if (s1.before(s2)) {
            fv.add(mod(da1 ^ da2 ^ BEFORE, BUCKETS), 1d);
            fv.add(mod(da1 ^ da2, BUCKETS), 1d);
          } else if (s1.after(s2)) {
            fv.add(mod(da1 ^ da2 ^ AFTER, BUCKETS), 1d);
            fv.add(mod(da1 ^ da2, BUCKETS), 1d);
          } else {
            fv.add(mod(da1 ^ da2 ^ WAT, BUCKETS), 1d);
          }
        }
      }
      return fv;
    }

    /** Returns a non-negative version of i % b */
    public static int mod(int i, int b) {
      int y = i % b;
      return y < 0 ? ~y : y;
    }

    @Override
    public void doneTraining() {
      LOG.info("[doneTraining] not freezing alphabet");
    }
  }


  /**
   * Fires when an action commits to a span that overlaps with an already
   * committed to span.
   */
  public static class ArgOverlapFeature
      extends FeatureParams implements Params.Stateful {
    public static final int BUCKETS = 6;
    public ArgOverlapFeature(double l2Penalty, double learningRate) {
      super(l2Penalty, BUCKETS);   // use Hashing
      this.learningRate = learningRate;
    }
    // q = query span
    // X = {x} = set of spans already committed to
    // find x s.t. q.s < x.s < q.e < x.e
    @Override
    public FeatureVector getFeatures(State s, ActionIndex ai, Action a) {
      if (a.hasSpan()) {
        FeatureVector fv = new FeatureVector();
        List<Action> overlappingActions = new ArrayList<>();
        ai.crosses(a.start, a.end, overlappingActions);
        int ovlp = overlappingActions.size();
        if (ovlp >= BUCKETS)
          ovlp = BUCKETS - 1;
        fv.add(ovlp, 1d);
        return fv;
      } else {
        return FeatureUtils.emptyFeatures;
      }
    }
  }

  /**
   * Fires when an action commits to a span that shares a left or right boundary
   * with a span that has already been committed to.
   */
  public static class SpanBoundaryFeature
      extends FeatureParams implements Params.Stateful {

    public static final int MSTART1 = 0;
    public static final int MSTART1_TMATCH = 1;
    public static final int MSTART2P = 2;
    public static final int MSTART2P_TMATCH = 3;  // not used

    public static final int MEND1 = 4;
    public static final int MEND1_TMATCH = 5;
    public static final int MEND2P = 6;
    public static final int MEND2P_TMATCH = 7;    // not used

    public static final int MSTART_MEND = 8;
    public static final int MLEFT = 9;
    public static final int MRIGHT = 10;
    public static final int MLEFT_MRIGHT = 11;

    public SpanBoundaryFeature(double l2Penalty, double learningRate) {
      super(l2Penalty, 12);
      this.learningRate = learningRate;
    }

    @Override
    public FeatureVector getFeatures(State state, ActionIndex ai, Action a) {
      if (!a.hasSpan()) return FeatureUtils.emptyFeatures;
      Span s = a.getSpan();

      IndexItem mStart = ai.startsAt(s.start);
      IndexItem mEnd = ai.endsAt(s.end - 1);
      IndexItem mLeft = s.start > 0 ? ai.endsAt(s.start - 1) : null;
      IndexItem mRight = s.end < ai.size() ? ai.startsAt(s.end) : null;

      FeatureVector fv = new FeatureVector();

      // TODO conjoin these features with frame and/or role?

      if (mStart != null) {
        if (mStart.size == 1) {
          fv.add(MSTART1, 1d);
          if (mStart.action.t == a.t)
            fv.add(MSTART1_TMATCH, 1d);
        } else if (mStart.size > 1) {
          fv.add(MSTART2P, 1d);
        }
      }

      if (mEnd != null) {
        if (mEnd.size == 1) {
          fv.add(MEND1, 1d);
          if (mEnd.action.t == a.t)
            fv.add(MEND1_TMATCH, 1d);
        } else if (mEnd.size > 1) {
          fv.add(MEND2P, 1d);
        }
      }

      if (mStart != null && mStart.size > 0 && mEnd != null && mEnd.size > 0)
        fv.add(MSTART_MEND, 1d);

      if (mLeft != null && mLeft.size > 0)
        fv.add(MLEFT, 1d);

      if (mRight != null && mRight.size > 0)
        fv.add(MRIGHT, 1d);

      if (mLeft != null && mLeft.size > 0 && mRight != null && mRight.size > 0)
        fv.add(MLEFT_MRIGHT, 1d);

      return fv;
    }
  }

}
