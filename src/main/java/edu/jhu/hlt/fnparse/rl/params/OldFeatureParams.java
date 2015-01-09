package edu.jhu.hlt.fnparse.rl.params;

import java.util.Arrays;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.util.Alphabet;

/**
 * Wraps my feature template language and implements Params.Stateless.
 *
 * @author travis
 */
public class OldFeatureParams implements Params.Stateless {

  private String featureTemplateString;
  private TemplatedFeatures features;
  private HeadFinder headFinder;
  private Alphabet<String> featureIndices;
  private double[] theta;
  private double learningRate;

  public OldFeatureParams(String featureTemplateString) {
    featureIndices = new Alphabet<>();
    theta = new double[1024];
    learningRate = 0.01;
    headFinder = new SemaforicHeadFinder();
    setFeatures(featureTemplateString);
  }

  public void setFeatures(String desc) {
    featureTemplateString = desc;
    features = new TemplatedFeatures(getClass().getName(), desc, featureIndices);
  }

  public String getFeatureDescription() {
    return featureTemplateString;
  }

  public int getNumParams() {
    return featureIndices.size();
  }

  @Override
  public void update(Adjoints a, double reward) {
    ((Adjoints.SparseFeatures) a).update(reward, learningRate);
  }

  @Override
  public Adjoints score(FNTagging f, Action a) {
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
    features.featurize(fv, context);

    // Make sure that theta is big enough
    checkSize();

    return new Adjoints.SparseFeatures(fv, theta, a);
  }

  private void checkSize() {
    int n = featureIndices.size();
    if (n > theta.length) {
      int m = (int) (1.6d * n + 0.5d);
      theta = Arrays.copyOf(theta, m);
    }
  }
}
