package edu.jhu.hlt.fnparse.rl.params;

import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.PruneAdjoints;
import edu.jhu.hlt.fnparse.rl.SpanIndex;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.util.AveragedWeights;
import edu.jhu.hlt.fnparse.util.ModelViewer;
import edu.jhu.hlt.fnparse.util.ModelViewer.FeatureWeight;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleVector;
import edu.jhu.util.Alphabet;

/**
 * You implement features, and this does the rest to let you implement Params.
 * Supports both Alphabets and feature-hashing.
 *
 * @author travis
 */
public abstract class FeatureParams {
  public final Logger log = Logger.getLogger(getClass());

  public boolean averageFeatures = false;  // only applies upon construction

  protected AveragedWeights theta;
  protected double l2Penalty;

  protected Alphabet<String> featureIndices;
  protected int numBuckets;

  /** For AlphabetBased implementation */
  public FeatureParams(double l2Penalty) {
    this.featureIndices = new Alphabet<>();
    this.featureIndices.startGrowth(); // stops growing when doneTraining is called
    this.numBuckets = -1;
    this.theta = new AveragedWeights(1024, averageFeatures);
    this.l2Penalty = l2Penalty;
  }

  /** For HashBased implementation */
  public FeatureParams(double l2Penalty, int numBuckets) {
    this.featureIndices = null;
    this.numBuckets = numBuckets;
    this.theta = new AveragedWeights(1024, averageFeatures);
    this.l2Penalty = l2Penalty;
  }

  /** Override one of the getFeatures methods */
  public FeatureVector getFeatures(FNTagging frames, Action a) {
    throw new RuntimeException("you should have either overriden this "
        + "method or called the other one");
  }

  /** Override one of the getFeatures methods */
  public FeatureVector getFeatures(State s, SpanIndex<Action> ai, Action a) {
    throw new RuntimeException("you should have either overriden this "
        + "method or called the other one");
  }

  /** Override one of the getFeatures methods */
  public FeatureVector getFeatures(FNTagging frames, PruneAdjoints pruneAction, String... providenceInfo) {
    throw new RuntimeException("you should have either overriden this "
        + "method or called the other one");
  }

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

  public FeatureParams sizeHint(int size) {
    assert isAlphabetBased() : "size must match numBuckets, don't use this method";
    if (size > theta.dimension())
      theta.grow(size);
    return this;
  }

  public Adjoints score(FNTagging frames, Action a) {
    FeatureVector fv = getFeatures(frames, a);

    // Make sure that theta is big enough
    checkSize();

    IntDoubleVector weights = new IntDoubleDenseVector(theta.getWeights());
    Adjoints.Vector adj = new Adjoints.Vector(a, weights, fv, l2Penalty);
    return adj;
  }

  public Adjoints score(State s, SpanIndex<Action> ai, Action a) {
    FeatureVector fv = getFeatures(s, ai, a);

    // Make sure that theta is big enough
    checkSize();

    IntDoubleVector weights = new IntDoubleDenseVector(theta.getWeights());
    Adjoints.Vector adj = new Adjoints.Vector(a, weights, fv, l2Penalty);
    return adj;
  }

  public Adjoints score(FNTagging frames, PruneAdjoints pruneAction, String... providenceInfo) {
    FeatureVector fv = getFeatures(frames, pruneAction, providenceInfo);

    // Make sure that theta is big enough
    checkSize();

    IntDoubleVector weights = new IntDoubleDenseVector(theta.getWeights());
    Adjoints.Vector adj = new Adjoints.Vector(pruneAction, weights, fv, l2Penalty);
    return adj;
  }

  protected void checkSize() {
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
