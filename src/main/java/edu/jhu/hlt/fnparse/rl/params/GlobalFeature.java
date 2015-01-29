package edu.jhu.hlt.fnparse.rl.params;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.util.FeatureUtils;

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
      extends FeatureParams<State> implements Params.Stateful {
    public RoleCooccurenceFeatureStateful(double l2Penalty, double learningRate) {
      super(l2Penalty); // use Alphabet
      this.learningRate = learningRate;
    }
    @Override
    public FeatureVector getFeatures(State state, Action a2) {
      String da2 = state.getFrame(a2.t).getName()
          + "." + state.getFrame(a2.t).getRole(a2.k);
      if (!a2.hasSpan())
        da2 += ".nullSpan";
      FeatureVector fv = new FeatureVector();
      //double smooth = 3d;
      double w = 1d;  //smooth / (state.numCommitted() + smooth);
      final int T = state.numFrameInstance();
      for (int t = 0; t < T; t++) {
        FrameInstance fi = state.getFrameInstance(t);
        Frame f = fi.getFrame();
        int K = f.numRoles();
        for (int k = 0; k < K; k++) {
          Span arg1 = fi.getArgument(k);
          if (arg1 == Span.nullSpan) continue;
          String da1 = f.getName() + "." + f.getRole(k);
          if (t == a2.t) {
            b(fv, w, da1, da2, "sameTarget");
          } else {
            // This characterizes the linear relationship between the targets
            // of the previously committed item and the current one.
            // TODO characterize the arg position as well.
            Span s1 = state.getFrameInstance(t).getTarget();
            Span s2 = state.getFrameInstance(a2.t).getTarget();
            if (s1.before(s2))
              b(fv, w, da1, da2, "before");
            else if (s1.after(s2))
              b(fv, w, da1, da2, "after");
            else
              b(fv, w, da1, da2, "wat");
          }
        }
      }
      return fv;
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
      extends FeatureParams<State> implements Params.Stateful {
    public ArgOverlapFeature(double l2Penalty, double learningRate) {
      super(l2Penalty);   // use Alphabet
      this.learningRate = learningRate;
    }
    // start with a balanced tree, leaves are token indices
    // internal nodes represent a contiguous span of all the leaves/tokens it dominates
    // represent a span as a union of logarithmically-many nodes
    // if you want to know if a span has any other spans that overlap with it...
    // this is too hard... start with dumbest implementation possible
    // q = query span
    // X = {x} = set of spans already committed to
    // find x s.t. q.s < x.s < q.e < x.e
    @Override
    public FeatureVector getFeatures(State s, Action a) {
      if (!a.hasSpan())
        return FeatureUtils.emptyFeatures;
      int ovlp = 0;
      Span s1 = a.getSpan();
      for (Span s2 : s.getCommittedSpans(new ArrayList<>())) {
        if (s1.overlaps(s2) && s1.start != s2.start && s1.end != s2.end)
          ovlp++;
      }
      if (ovlp == 0)
        return FeatureUtils.emptyFeatures;
      FeatureVector fv = new FeatureVector();
      int k = 5;
      if (ovlp < k)
        b(fv, "overlap=" + ovlp);
      else
        b(fv, "overlap=" + k + "+");
      return fv;
    }
  }

  /**
   * Fires when an action commits to a span that shares a left or right boundary
   * with a span that has already been committed to.
   */
  public static class SpanBoundaryFeature
      extends FeatureParams<State> implements Params.Stateful {
    public SpanBoundaryFeature(double l2Penalty, double learningRate) {
      super(l2Penalty);
      this.learningRate = learningRate;
    }
    private static void indexSpans(State state, Span s,
        List<Action> mStart, List<Action> mEnd,
        List<Action> mLeft, List<Action> mRight) {
      final int T = state.numFrameInstance();
      for (int t = 0; t < T; t++) {
        FrameInstance fi = state.getFrameInstance(t);
        Frame f = fi.getFrame();
        int K = f.numRoles();
        for (int k = 0; k < K; k++) {
          Span arg = fi.getArgument(k);
          if (arg == Span.nullSpan) continue;
          // TODO get Action type from state somehow?
          Action a = new Action(t, k, ActionType.COMMIT.getIndex(), arg);
          if (arg.end == s.start)
            mLeft.add(a);
          if (arg.start == s.start)
            mStart.add(a);
          if (arg.end == s.end)
            mEnd.add(a);
          if (arg.start == s.end)
            mRight.add(a);
        }
      }
    }
    @Override
    public FeatureVector getFeatures(State state, Action a) {
      if (!a.hasSpan()) return FeatureUtils.emptyFeatures;
      Span s = a.getSpan();
      List<Action> mStart = new ArrayList<>();
      List<Action> mEnd = new ArrayList<>();
      List<Action> mLeft = new ArrayList<>();
      List<Action> mRight = new ArrayList<>();
      indexSpans(state, s, mStart, mEnd, mLeft, mRight);

      FeatureVector fv = new FeatureVector();

      // TODO conjoin these features with frame and/or role?

      if (mStart.size() == 1) {
        b(fv, "mStart1");
        if (mStart.get(0).t == a.t)
          b(fv, "mStart1", "tMatch");
      } else if (mStart.size() > 1) {
        b(fv, "mStart2+");
      }

      if (mEnd.size() == 1) {
        b(fv, "mEnd1");
        if (mEnd.get(0).t == a.t)
          b(fv, "mEnd1", "tMatch");
      } else if (mEnd.size() > 1) {
        b(fv, "mEnd2+");
      }

      if (mStart.size() > 0 && mEnd.size() > 0)
        b(fv, "mStart+mEnd");

      if (mLeft.size() > 0)
        b(fv, "mLeft");

      if (mRight.size() > 0)
        b(fv, "mRight");

      if (mLeft.size() > 0 && mRight.size() > 0)
        b(fv, "mLeft+mRight");

      return fv;
    }
  }

}
