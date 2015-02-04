package edu.jhu.hlt.fnparse.rl.params;

import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.util.AveragedWeights;
import edu.jhu.hlt.fnparse.util.ModelViewer;
import edu.jhu.hlt.fnparse.util.ModelViewer.FeatureWeight;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleVector;
import edu.jhu.util.Alphabet;

/**
 * Wraps my feature template language and implements Params.Stateless.
 *
 * @author travis
 */
public abstract class FeatureParams<Context> {
  public final Logger log = Logger.getLogger(getClass());

  public boolean averageFeatures = false;  // only applies upon construction

  protected AveragedWeights theta;
  protected double l2Penalty;
  protected double learningRate;

  protected Alphabet<String> featureIndices;
  protected int numBuckets;

  /** For AlphabetBased implementation */
  public FeatureParams(double l2Penalty) {
    this.featureIndices = new Alphabet<>();
    this.featureIndices.startGrowth(); // stops growing when doneTraining is called
    this.numBuckets = -1;
    this.theta = new AveragedWeights(1024, averageFeatures);
    this.l2Penalty = l2Penalty;
    this.learningRate = 1;
  }

  /** For HashBased implementation */
  public FeatureParams(double l2Penalty, int numBuckets) {
    this.featureIndices = null;
    this.numBuckets = numBuckets;
    this.theta = new AveragedWeights(1024, averageFeatures);
    this.l2Penalty = l2Penalty;
    this.learningRate = 1;
  }

  public abstract FeatureVector getFeatures(Context c, Action a);

  public boolean isAlphabetBased() {
    assert (numBuckets <= 0) != (featureIndices == null);
    return numBuckets <= 0;
  }

  public void b(FeatureVector fv, String... pieces) {
    b(fv, 1d, pieces);
  }

  public void b(FeatureVector fv, double w, String... pieces) {
    StringBuilder sb = new StringBuilder(pieces[0]);
    for (int i = 1; i < pieces.length; i++)
      sb.append("-" + pieces[i]);
    int idx = featureIndex(sb.toString());
    if (idx >= 0)
      fv.add(idx, w);
  }

  public int featureIndex(String featureName) {
    if (isAlphabetBased()) {
      return featureIndices.lookupIndex(featureName);
    } else {
      int h = featureName.hashCode();
      if (h < 0) h = ~h;
      return h % numBuckets;
    }
  }

  /** Only works if you're using Alphabet mode */
  public int getNumParams() {
    return featureIndices.size();
  }

  /** Only works if you're using feature hashing mode */
  public int getNumHashingBuckets() {
    assert numBuckets > 0;
    return numBuckets;
  }

  public FeatureParams<Context> sizeHint(int size) {
    assert isAlphabetBased() : "size must match numBuckets, don't use this method";
    if (size > theta.dimension())
      theta.grow(size);
    return this;
  }

  public Adjoints score(Context f, Action a) {
    FeatureVector fv = getFeatures(f, a);

    // Make sure that theta is big enough
    checkSize();

    IntDoubleVector weights = new IntDoubleDenseVector(theta.getWeights());
    Adjoints.Vector adj = new Adjoints.Vector(a, weights, fv, l2Penalty, learningRate);
    return adj;
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

  /** This will override Params.showWeights for extending classes */
  public void showWeights() {
    if (featureIndices == null) {
      log.info("[showFeatures] can't show features because we're using feature hashing");
      return;
    }
    String msg = getClass().getName();
    int k = 15; // how many of the most extreme features to show
    List<FeatureWeight> w = ModelViewer.getSortedWeights(theta.getWeights(), featureIndices);
    ModelViewer.showBiggestWeights(w, k, msg, log);
  }

  /**
   * This will override Params.doneTraining for extending classes.
   * Stop the alphabet from growing.
   */
  public void doneTraining() {
    if (featureIndices != null) {
      log.info("[doneTraining] stopping alphabet growth");
      featureIndices.stopGrowth();
    }
    showWeights();
  }
}
