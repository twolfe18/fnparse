package edu.jhu.hlt.fnparse.rl.params;

import java.util.Collection;
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
import edu.jhu.util.Alphabet;

/**
 * Wraps my feature template language and implements Params.Stateless.
 *
 * @author travis
 */
public class OldFeatureParams implements Params.Stateless {
  public static boolean SHOW_ON_UPDATE = true;
  public static boolean SHOW_FEATURES = false;
  public static boolean AVERAGE_FEATURES = true;  // only applies upon construction

  private String featureTemplateString;
  private TemplatedFeatures features;
  private HeadFinder headFinder;
  private AveragedWeights theta;
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
    theta = new AveragedWeights(1024, AVERAGE_FEATURES);
    setFeatures(featureTemplateString);
  }

  /** For HashBased implementation */
  public OldFeatureParams(String featureTemplateString, int numBuckets) {
    this();
    featureIndices = null;
    this.numBuckets = numBuckets;
    theta = new AveragedWeights(1024, AVERAGE_FEATURES);
    setFeatures(featureTemplateString);
  }

  private OldFeatureParams() {
    learningRate = 1d;
    headFinder = new SemaforicHeadFinder();
  }

  public boolean isAlphabetBased() {
    assert (numBuckets <= 0) != (featureIndices == null);
    return numBuckets <= 0;
  }

  public OldFeatureParams sizeHint(int size) {
    assert isAlphabetBased() : "size must match numBuckets, don't use this method";
    if (size > theta.dimension())
      theta.grow(size);
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
  public <T extends HasUpdate> void update(Collection<T> batch) {
    double[] update = new double[theta.dimension()];
    double scale = learningRate / batch.size();
    for (T up : batch)
      up.getUpdate(update, scale);
    theta.add(update);
    theta.incrementCount();
    printedSinceUpdate = false;
  }

  public void showFeatures(String msg) {
    int k = 12; // how many of the most extreme features to show
    List<FeatureWeight> w = ModelViewer.getSortedWeights(theta.getWeights(), featureIndices);
    ModelViewer.showBiggestWeights(w, k, msg, LOG);
    if (theta.hasAverage()) {
      w = ModelViewer.getSortedWeights(theta.getAveragedWeights(), featureIndices);
      ModelViewer.showBiggestWeights(w, k, msg + " avg", LOG);
    }
  }

  @Override
  public Adjoints score(FNTagging f, Action a) {
    if (SHOW_ON_UPDATE && !printedSinceUpdate && isAlphabetBased()) {
      showFeatures("[update]");
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
    checkSize();

    // TODO consider if we should ever use the average here
    //return new Adjoints.SparseFeatures(fv, theta, a);
    return new Adjoints.SparseFeatures(fv, theta.getWeights(), a);
  }

  private void checkSize() {
    if (isAlphabetBased()) {
      int n = featureIndices.size();
      if (n > theta.dimension()) {
        int ns = (int) (1.6d * n + 0.5d);
        theta.grow(ns);
      }
    } else {
      if (theta.dimension() < numBuckets)
        theta.grow(numBuckets);
    }
  }

  @Override
  public void doneTraining() {
    LOG.info("[doneTraining] setting theta to averaged value");
    if (theta.hasAverage()) {
      if (SHOW_ON_UPDATE)
        showFeatures("[doneTraining] averaged:");
      theta.setAveragedWeights();
    }
  }
}
