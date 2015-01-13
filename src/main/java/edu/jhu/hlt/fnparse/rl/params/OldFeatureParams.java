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

  private String featureTemplateString;
  private TemplatedFeatures features;
  private HeadFinder headFinder;
  private AveragedWeights theta2;
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
    theta2 = new AveragedWeights(1024, false);
    setFeatures(featureTemplateString);
  }

  /** For HashBased implementation */
  public OldFeatureParams(String featureTemplateString, int numBuckets) {
    this();
    featureIndices = null;
    this.numBuckets = numBuckets;
    theta2 = new AveragedWeights(1024, false);
    setFeatures(featureTemplateString);
  }

  private OldFeatureParams() {
    learningRate = 1d;
    headFinder = new SemaforicHeadFinder();
  }

  // I have two options in applying the update
  // actually, almost certainly doesn't matter because it will not be a hot spot
  // 1) accumulated update across StateSequence, then apply it (and average update) at the same time
  // 2) apply the update and average update for every Action in the StateSequence (amortized twice as much work)
  // it might start to matter when I use dense embedding features...
  // should prefer 1 if possible.

  // The problem with accumulating an update into a double[] is that
  // Params is the receiver of update
  // HA
  // in the real implementation Adjoints is the receiver of update
  // Adjoints maintains a pointer to theta to carry out the update
  
  // Reranker.Update.apply
  // StateSequence.udpateAllAdjoints
  // Params.update
  // Adjoints.update
  
  // Reranker.Update.apply (will have to loop over StateSequence)
  // Adjoints.update (to accumulate into a double[])
  // AveragedWeights.apply (to do averaging)
  
  // SHIT, there is a problem with accumulating updates into a double[]
  // this puts them into a global namespace
  // I wanted to keep it so that each implementation of Params is in full control
  // of its weights and namespace.
  // This would mean that I should have some kind of notion of a update transaction in Params.

  // Reranker.Update.apply
  // Params.updateAll(StateSequence oracle, mv, double loss) => then Params knows about structure of update?
  // Adjoints.update (since params is calling, don't need Adjoints to store pointer to Params.weights)
  
  // maybe this design isn't too terrible, because I could have Params.update
  // store the constraints and re-use them?
  
  // I DEFINITELY like the idea of Params receiving updates
  // probably more than I dislike the idea of Params knowing the structure of an update
  
  
  // Params (recursive DS) x Adjoints x batch
  
  
  // All of this headache is because I want efficient updates?
  // I could just leave everything as-is and take a SNAPSHOT of params from time to time
  // would need to get a signal from above on when to do this, 
  
  
  // how about in Params:
  // public void update(Iterable<Reranker.Update> batch, double learningRate);
  // the implementation would:
  // 1) cover the update transaction needed for averaging parameters
  // 2) require Reranker.Update to offer up an update
  
  // in Reranker.Update:
  // public void getUpdate(double[] addTo);
  
  // this forces Adjoints to have an update method
  // public void getUpdate(double[] addTo);


  public boolean isAlphabetBased() {
    assert (numBuckets <= 0) != (featureIndices == null);
    return numBuckets <= 0;
  }

  public OldFeatureParams sizeHint(int size) {
    assert isAlphabetBased() : "size must match numBuckets, don't use this method";
    if (size > theta2.dimension())
      theta2.grow(size);
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
    double[] update = new double[theta2.dimension()];
    double scale = learningRate / batch.size();
    for (T up : batch)
      up.getUpdate(update, scale);
    theta2.add(update);
    theta2.incrementCount();
  }

  @Override
  public Adjoints score(FNTagging f, Action a) {
    if (SHOW_ON_UPDATE && !printedSinceUpdate && isAlphabetBased()) {
      List<FeatureWeight> w = ModelViewer.getSortedWeights(theta2.getWeights(), featureIndices);
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

    // TODO consider if we should ever use the average here
    //return new Adjoints.SparseFeatures(fv, theta, a);
    return new Adjoints.SparseFeatures(fv, theta2.getWeights(), a);
  }

  private void checkSize() {
    int n = featureIndices.size();
    if (n > theta2.dimension())
      theta2.grow(n);
  }
}
