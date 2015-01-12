package edu.jhu.hlt.fnparse.rl.params;

import java.util.Arrays;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.util.ModelViewer;
import edu.jhu.hlt.fnparse.util.ModelViewer.FeatureWeight;
import edu.jhu.hlt.fnparse.util.Projections;
import edu.jhu.util.Alphabet;

/**
 * Wraps my feature template language and implements Params.Stateless.
 *
 * @author travis
 */
public class OldFeatureParams implements Params.Stateless {
  public static boolean SHOW_ON_UPDATE = true;
  public static boolean SHOW_FEATURES = false;

  private String featureTemplateString;
  private TemplatedFeatures features;
  private HeadFinder headFinder;
  private double[] theta;
  private double l2Radius;
  private double learningRate;

  private Alphabet<String> featureIndices;
  private int numBuckets;

  private boolean printedSinceUpdate = false;

  /** For AlphabetBased implementation */
  public OldFeatureParams(String featureTemplateString) {
    this();
    featureIndices = new Alphabet<>();
    featureIndices.startGrowth();       // always growing
    numBuckets = -1;
    theta = new double[1024];
    setFeatures(featureTemplateString);
  }

  /** For HashBased implementation */
  public OldFeatureParams(String featureTemplateString, int numBuckets) {
    this();
    featureIndices = null;
    this.numBuckets = numBuckets;
    theta = new double[numBuckets];
    setFeatures(featureTemplateString);
  }

  private OldFeatureParams() {
    l2Radius = -1d;  // project theta to be in L2 ball of this radius, <=0 means don't project
    learningRate = 1d;
    headFinder = new SemaforicHeadFinder();
  }

  public boolean isAlphabetBased() {
    assert (numBuckets <= 0) != (featureIndices == null);
    return numBuckets <= 0;
  }

  public OldFeatureParams sizeHint(int size) {
    assert isAlphabetBased() : "size must match numBuckets, don't use this method";
    if (size > theta.length)
      theta = Arrays.copyOf(theta, size);
    return this;
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

  public int getNumParams() {
    return featureIndices.size();
  }

  @Override
  public void update(Adjoints a, double reward) {
    assert theta != null && theta.length > 0;
    assert !isAlphabetBased() || featureIndices.size() > 0;
    ((Adjoints.SparseFeatures) a).update(reward, learningRate);
    if (l2Radius > 0d)
      Projections.l2Ball(theta, l2Radius);
    printedSinceUpdate = false;
  }

  @Override
  public Adjoints score(FNTagging f, Action a) {
    if (SHOW_ON_UPDATE && !printedSinceUpdate && isAlphabetBased()) {
      List<FeatureWeight> w = ModelViewer.getSortedWeights(theta, featureIndices);
      ModelViewer.showBiggestWeights(w, 15, "[update]", LOG);
      printedSinceUpdate = true;
    }

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
    if (SHOW_FEATURES)
      features.featurizeDebug(fv, context, "[OldFeatureParams]");
    else
      features.featurize(fv, context);

    // Make sure that theta is big enough
    if (isAlphabetBased())
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
