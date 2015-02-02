package edu.jhu.hlt.fnparse.rl.params;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.rl.Action;

/**
 * Says what the action type is.
 * @author travis
 */
public class ActionTypeParams extends FeatureParams<FNTagging> implements Params.Stateless {
  private boolean includeFrame;
  private boolean includeFrameRole;
  private boolean includeRole;

  public ActionTypeParams(double l2Penalty) {
    super(l2Penalty); // use Alphabet
    includeFrame = true;
    includeFrameRole = true;
    includeRole = true;
  }

  @Override
  public FeatureVector getFeatures(FNTagging frames, Action a) {
    FeatureVector fv = new FeatureVector();
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
