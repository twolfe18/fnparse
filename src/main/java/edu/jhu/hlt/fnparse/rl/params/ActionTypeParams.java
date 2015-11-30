package edu.jhu.hlt.fnparse.rl.params;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;

/**
 * Says what the action type is.
 * @author travis
 */
public class ActionTypeParams extends FeatureParams implements Params.Stateless {
  private static final long serialVersionUID = 8390112911973762104L;
  public static int BUCKETS = 1<<18;

  private boolean includeFrame;
  private boolean includeFrameRole;
  private boolean includeRole;

  public ActionTypeParams(double l2Penalty) {
    //super(l2Penalty); // use Alphabet
    super(l2Penalty, BUCKETS);
    includeFrame = true;
    includeFrameRole = true;
    includeRole = true;
  }

  @Override
  public IntDoubleUnsortedVector getFeatures(FNTagging frames, Action a) {
    IntDoubleUnsortedVector fv = new IntDoubleUnsortedVector();
    String s = "a.hasSpan=" + a.hasSpan();
    String t = "at=" + a.getActionType().getName();
    b(fv, s, t);
    if (includeFrame) {
      Frame f = frames.getFrameInstance(a.t).getFrame();
      b(fv, s, t, f.getName());
    }
    if (includeFrameRole) {
      Frame f = frames.getFrameInstance(a.t).getFrame();
      b(fv, s, t, f.getName() + "." + f.getRole(a.k));
    }
    if (includeRole) {
      Frame f = frames.getFrameInstance(a.t).getFrame();
      b(fv, s, t, f.getRole(a.k));
    }
    return fv;
  }
}
