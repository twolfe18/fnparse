package edu.jhu.hlt.fnparse.rl.params;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.rl.Action;

/**
 * Lifts TemplatedFeatures into Stateless.Params. Does so by only looking at the
 * span provided by an action.
 * 
 * Doesn't care what type of Action you give it, so make sure to add something
 * like ActionTypeParams if you want the model to be able tell apart two actions
 * that have the same span.
 *
 * @author travis
 */
public class TemplatedFeatureParams
    extends FeatureParams implements Params.Stateless {

  // if true, call featurizeDebug, which shows all the features that were just
  // computed on every call (very slow -- debug only).
  private boolean showFeatures = false;

  private TemplatedFeatures features;
  private String featureTemplateString;
  private HeadFinder headFinder;

  /** Use alphabet */
  public TemplatedFeatureParams(String featureTemplateString, double l2Penalty) {
    super(l2Penalty);
    headFinder = new SemaforicHeadFinder();
    setFeatures(featureTemplateString);
  }

  /** Use feature hashing */
  public TemplatedFeatureParams(String featureTemplateString, double l2Penalty, int numBuckets) {
    super(l2Penalty, numBuckets);
    headFinder = new SemaforicHeadFinder();
    setFeatures(featureTemplateString);
  }

  @Override
  public FeatureVector getFeatures(FNTagging f, Action a) {
    // Capture the context for the TemplatedFeatures
    FrameInstance fi = f.getFrameInstance(a.t);
    TemplateContext context = new TemplateContext();
    context.setSentence(f.getSentence());
    context.setFrame(fi.getFrame());
    context.setTarget(fi.getTarget());
    context.setTargetHead(fi.getTarget().end - 1);
    context.setHead2(fi.getTarget().end - 1);
    context.setRole(a.k);
    context.setArg(a.getSpanSafe());
    if (a.hasSpan()) {
      int h1 = headFinder.head(a.getSpan(), f.getSentence());
      context.setHead1(h1);
      context.setArgHead(h1);
    }
    context.setSpan1(a.getSpanSafe());
    context.setSpan2(fi.getTarget());

    // Compute the features
    FeatureVector fv = new FeatureVector();
    if (showFeatures)
      features.featurizeDebug(fv, context, "[OldFeatureParams]");
    else
      features.featurize(fv, context);

    return fv;
  }

  public void setFeatures(String desc) {
    featureTemplateString = desc;
    if (isAlphabetBased()) {
      features = new TemplatedFeatures.AlphabetBased(
          getClass().getName(), desc, featureIndices);
    } else {
      features = new TemplatedFeatures.HashBased(
          getClass().getName(), desc, numBuckets);
    }
  }

  public String getFeatureDescription() {
    return featureTemplateString;
  }
}