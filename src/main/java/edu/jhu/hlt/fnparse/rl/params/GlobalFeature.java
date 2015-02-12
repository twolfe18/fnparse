package edu.jhu.hlt.fnparse.rl.params;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.SpanIndex;
import edu.jhu.hlt.fnparse.rl.SpanIndex.IndexItem;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.util.FeatureUtils;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.util.Alphabet;

/**
 * More of a namespace than an interface, used for things that need to
 * efficiently known about previous actions take. See ActionSpanIndex for how
 * the indices are kept.
 *
 * @author travis
 */
public interface GlobalFeature extends Params.Stateful {



  public static class Cheating extends FeatureParams implements Params.Stateful {
    private CheatingParams cheat;
    /**
     * Uses cheat to determine when this feature should fire.
     */
    public Cheating(CheatingParams cheat, double l2Penalty) {
      super(l2Penalty);   // Use Alphabet
      this.cheat = cheat;
    }
    @Override
    public FeatureVector getFeatures(State state, SpanIndex<Action> ai, Action a2) {
      FeatureVector fv = new FeatureVector();
      FNTagging frames = state.getFrames();
      b(fv, "intercept");
      if (cheat.isGold(frames, a2)) {
        b(fv, "currentActionIsGold");
        for (IndexItem<Action> ii = ai.allActions(); ii != null; ii = ii.prevNonEmptyItem) {
          Action a1 = ii.payload;
          if (cheat.isGold(frames, a1))
            b(fv, "pairOfActionsAreBothGold");
        }
      }
      return fv;
    }

    public void setParamsByHand() {
      theta.add(featureIndex("intercept"), -2.5d);
      theta.add(featureIndex("currentActionIsGold"), 1d);
      theta.add(featureIndex("pairOfActionsAreBothGold"), 1d);
    }
  }


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
    public RoleCooccurenceFeatureStateful(double l2Penalty) {
      super(l2Penalty, BUCKETS); // use Hashing
    }
    @Override
    public FeatureVector getFeatures(State state, SpanIndex<Action> ai, Action a2) {
      // must be >0 because 0 is the implicit backoff label
      final int SAME_TARGET = 1;
      final int BEFORE = 2;
      final int AFTER = 3;
      final int WAT = 4;

      // An int that encodes the (frame,role) of the action being considered.
      int da2;
      if (a2.hasSpan()) {
        da2 = frPacking.index(state.getFrame(a2.t), a2.k);
      } else {
        da2 = frPacking.index(state.getFrame(a2.t));
      }
      da2 = da2 << REL_BITS; // move over to make room for rel type

      int previousActions = 0;
      FeatureVector fv = new FeatureVector();
      for (IndexItem<Action> i = ai.allActions(); i != null; i = i.prevNonEmptyItem) {
        previousActions++;
        Action a = i.payload;
        assert a.getActionType() == ActionType.COMMIT;
        FrameInstance fi = state.getFrameInstance(a.t);
        Frame f = fi.getFrame();

        // An int that encodes the (frame,role) of a previous action.
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
            //fv.add(mod(da1 ^ da2, BUCKETS), 1d);
          } else if (s1.after(s2)) {
            fv.add(mod(da1 ^ da2 ^ AFTER, BUCKETS), 1d);
            //fv.add(mod(da1 ^ da2, BUCKETS), 1d);
          } else {
            fv.add(mod(da1 ^ da2 ^ WAT, BUCKETS), 1d);
          }
        }
      }

      // Feature for committing to this (frame,role) given that there have
      // been a certain number of actions previously committed to.
      // This can be used to push certain frame-roles to the beginning or end
      // of decoding.
      fv.add(mod((-(previousActions/4)) ^ da2 ^ WAT, BUCKETS), 1d);

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

    // How many you want to bucket your collisions up to.
    // This is not the dimension of the feature vector because we have to
    // variants of this bucketing, one for all collisions and another for when
    // there is a collision for the same frame-target.
    public static final int BUCKETS = 6;

    public static final Alphabet<String> FEATURE_NAMES = new Supplier<Alphabet<String>>() {
      @Override
      public Alphabet<String> get() {
        Alphabet<String> a = new Alphabet<>();
        for (String prefix : Arrays.asList("anyT", "tMatch")) {
          for (int i = 0; i < BUCKETS; i++) {
            String f = prefix + "_overlap=" + i;
            if (i == BUCKETS-1)
              f += "+";
            a.lookupIndex(f, true);
          }
        }
        return a;
      }
    }.get();

    @Override
    public Alphabet<String> getAlphabetForShowingWeights() {
      return FEATURE_NAMES;
    }

    public ArgOverlapFeature(double l2Penalty) {
      super(l2Penalty, BUCKETS * 2);   // use Hashing
    }
    // q = query span
    // X = {x} = set of spans already committed to
    // find x s.t. q.s < x.s < q.e < x.e
    @Override
    public FeatureVector getFeatures(State s, SpanIndex<Action> ai, Action a) {
      if (a.hasSpan()) {
        FeatureVector fv = new FeatureVector();
        List<Action> overlappingActions = new ArrayList<>();
        ai.crosses(a.start, a.end, overlappingActions);

        // All overlapping
        int ovlp = overlappingActions.size();
        if (ovlp >= BUCKETS)
          ovlp = BUCKETS - 1;
        fv.add(ovlp, 1d);

        // Overlapping for the same frame-target
        int ovlpT = 0;
        for (Action oa : overlappingActions)
          if (oa.t == a.t)
            ovlpT++;
        if (ovlpT >= BUCKETS)
          ovlpT = BUCKETS - 1;
        fv.add(BUCKETS + ovlpT, 1d);

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

    // This will be used to support feature names for showWeights
    public static final Alphabet<String> FEATURE_NAMES = new Supplier<Alphabet<String>>() {
      @Override
      public Alphabet<String> get() {
        Alphabet<String> a = new Alphabet<>();
        a.lookupIndex("MSTART1", true);
        a.lookupIndex("MSTART1_TMATCH", true);
        a.lookupIndex("MSTART2P", true);
        a.lookupIndex("MSTART2P_TMATCH", true);

        a.lookupIndex("MEND1", true);
        a.lookupIndex("MEND1_TMATCH", true);
        a.lookupIndex("MEND2P", true);
        a.lookupIndex("MEND2P_TMATCH", true);

        a.lookupIndex("MSTART_MEND", true);
        a.lookupIndex("MLEFT", true);
        a.lookupIndex("MRIGHT", true);
        a.lookupIndex("MLEFT_MRIGHT", true);
        return a;
      }
    }.get();

    @Override
    public Alphabet<String> getAlphabetForShowingWeights() {
      return FEATURE_NAMES;
    }

    public SpanBoundaryFeature(double l2Penalty) {
      super(l2Penalty, 12);
    }

    @Override
    public FeatureVector getFeatures(State state, SpanIndex<Action> ai, Action a) {
      if (!a.hasSpan()) return FeatureUtils.emptyFeatures;
      Span s = a.getSpan();

      IndexItem<Action> mStart = ai.startsAt(s.start);
      IndexItem<Action> mEnd = ai.endsAt(s.end - 1);
      IndexItem<Action> mLeft = s.start > 0 ? ai.endsAt(s.start - 1) : null;
      IndexItem<Action> mRight = s.end < ai.getSentenceSize() ? ai.startsAt(s.end) : null;

      FeatureVector fv = new FeatureVector();

      // TODO conjoin these features with frame and/or role?

      if (mStart != null) {
        if (mStart.size == 1) {
          fv.add(MSTART1, 1d);
          if (mStart.payload.t == a.t)
            fv.add(MSTART1_TMATCH, 1d);
        } else if (mStart.size > 1) {
          fv.add(MSTART2P, 1d);
        }
      }

      if (mEnd != null) {
        if (mEnd.size == 1) {
          fv.add(MEND1, 1d);
          if (mEnd.payload.t == a.t)
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
