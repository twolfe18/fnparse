package edu.jhu.hlt.fnparse.rl.params;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.CommitIndex;
import edu.jhu.hlt.fnparse.rl.PruneAdjoints;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;
import edu.jhu.prim.vector.IntDoubleVector;
import edu.jhu.util.Alphabet;

/**
 * You implement features, and this does the rest to let you implement Params.
 * Supports both Alphabets and feature-hashing.
 *
 * @author travis
 */
public abstract class FeatureParams implements Serializable {
  private static final long serialVersionUID = 4206437082045635260L;

  private transient Logger log = Logger.getLogger(getClass());
  Logger getLog() {
    if (log == null)
      log = Logger.getLogger(getClass());
    return log;
  }

  public static int DEFAULT_DIMENSION = 1024;

  public static int UPDATE_L2_EVERY = 128;

//  public boolean averageFeatures = false;  // only applies upon construction

//  protected AveragedWeights theta;
  protected LazyL2UpdateVector theta;
  protected double l2Penalty;

  protected Alphabet<String> featureIndices;
  protected int numBuckets;

  public boolean showWeightsEvenWithoutAlphabet = true;

  /** For AlphabetBased implementation */
  public FeatureParams(double l2Penalty) {
    this.featureIndices = new Alphabet<>();
    this.featureIndices.startGrowth(); // stops growing when doneTraining is called
    this.numBuckets = -1;
//    this.theta = new AveragedWeights(DEFAULT_DIMENSION, averageFeatures);
    this.theta = new LazyL2UpdateVector(new IntDoubleDenseVector(DEFAULT_DIMENSION), UPDATE_L2_EVERY);
    this.l2Penalty = l2Penalty;
  }

  /** For HashBased implementation */
  public FeatureParams(double l2Penalty, int numBuckets) {
    this.featureIndices = null;
    this.numBuckets = numBuckets;
//    this.theta = new AveragedWeights(numBuckets, averageFeatures);
    this.theta = new LazyL2UpdateVector(new IntDoubleDenseVector(numBuckets), UPDATE_L2_EVERY);
    this.l2Penalty = l2Penalty;
  }

  /** Override one of the getFeatures methods */
  public IntDoubleUnsortedVector getFeatures(FNTagging frames, Action a) {
    throw new RuntimeException("you should have either overriden this "
        + "method or called the other one");
  }

  /** Override one of the getFeatures methods */
  //public IntDoubleUnsortedVector getFeatures(State s, SpanIndex<Action> ai, Action a) {
  public IntDoubleUnsortedVector getFeatures(State s, CommitIndex ai, Action a) {
    throw new RuntimeException("you should have either overriden this "
        + "method or called the other one");
  }

  /** Override one of the getFeatures methods */
  public IntDoubleUnsortedVector getFeatures(FNTagging frames, PruneAdjoints pruneAction, String... providenceInfo) {
    throw new RuntimeException("you should have either overriden this "
        + "method or called the other one");
  }

  /** Adds the weights from other to this */
  public void addWeights(Params other, boolean checkAlphabetEquality) {
    FeatureParams fp = (FeatureParams) other;
    theta.weights.add(fp.theta.weights);
    if (checkAlphabetEquality) {
      if (featureIndices != null) {
        int n1 = featureIndices.size();
        int n2 = fp.featureIndices.size();
        if (n1 != n2) throw new IllegalArgumentException();
        for (int i = 0; i < n1; i++)
          if (!featureIndices.lookupObject(i).equals(fp.featureIndices.lookupObject(i)))
            throw new IllegalArgumentException();
      } else if (numBuckets != fp.numBuckets) {
        throw new IllegalArgumentException();
      }
    }
  }

  public void scaleWeights(double scale) {
    theta.weights.scale(scale);
  }

  public void serialize(DataOutputStream out) throws IOException {
//    out.writeBoolean(averageFeatures);
//    theta.serialize(out);
//    out.writeDouble(l2Penalty);
//    if (featureIndices != null) {
//      out.writeBoolean(true);
//      int n = featureIndices.size();
//      out.writeInt(n);
//      for (int i = 0; i < n; i++)
//        out.writeUTF(featureIndices.lookupObject(i));
//    } else {
//      out.writeBoolean(false);
//    }
//    out.writeInt(numBuckets);
    Log.warn("re-implement me!");
  }

  public void deserialize(DataInputStream in) throws IOException {
//    averageFeatures = in.readBoolean();
//    theta.deserialize(in);
//    l2Penalty = in.readDouble();
//    boolean alphabetBased = in.readBoolean();
//    if (alphabetBased) {
//      int n = in.readInt();
//      featureIndices = new Alphabet<>();
//      for (int i = 0; i < n; i++)
//        featureIndices.lookupIndex(in.readUTF(), true);
//    }
//    numBuckets = in.readInt();
    throw new RuntimeException("re-implement me!");
  }

  public boolean isAlphabetBased() {
    assert (numBuckets <= 0) != (featureIndices == null);
    return numBuckets <= 0;
  }

  public void b(IntDoubleUnsortedVector fv, String... pieces) {
    b(fv, 1d, pieces);
  }

  public void b(IntDoubleUnsortedVector fv, double w, String... pieces) {
    StringBuilder sb = new StringBuilder(pieces[0]);
    for (int i = 1; i < pieces.length; i++)
      sb.append("-" + pieces[i]);
    int idx = featureIndex(sb.toString());
    if (idx >= 0)
      fv.add(idx, w);
  }

  public String featureName(int index) {
    Alphabet<String> featureIndices = getAlphabetForShowingWeights();
    if (featureIndices == null)
      return index + "?";
    return featureIndices.lookupObject(index);
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
//    if (size > theta.dimension())
//      theta.grow(size);
    Log.warn("no longer supported");
    return this;
  }

  public Adjoints score(FNTagging frames, Action a) {
    IntDoubleUnsortedVector fv = getFeatures(frames, a);

    // Make sure that theta is big enough
    checkSize();

    Adjoints.Vector adj = new Adjoints.Vector(this, a, theta, fv, l2Penalty);
    return adj;
  }

  //public Adjoints score(State s, SpanIndex<Action> ai, Action a) {
  public Adjoints score(State s, CommitIndex ai, Action a) {
    IntDoubleUnsortedVector fv = getFeatures(s, ai, a);

    // Make sure that theta is big enough
    checkSize();

    Adjoints.Vector adj = new Adjoints.Vector(this, a, theta, fv, l2Penalty);
    return adj;
  }

  public Adjoints score(FNTagging frames, PruneAdjoints pruneAction, String... providenceInfo) {
    IntDoubleUnsortedVector fv = getFeatures(frames, pruneAction, providenceInfo);

    // Make sure that theta is big enough
    checkSize();

    Adjoints.Vector adj = new Adjoints.Vector(this, pruneAction, theta, fv, l2Penalty);
    return adj;
  }

  public String describeFeatures(IntDoubleVector features) {
    StringBuilder sb = new StringBuilder();
    features.apply(new FnIntDoubleToDouble() {
      @Override
      public double call(int arg0, double arg1) {
        String k = featureName(arg0);
        sb.append(String.format(" %s:%.2f", k, arg1));
        return arg1;
      }
    });
    return sb.toString();
  }

  public void logFeatures(IntDoubleVector features, State s, Action a) {
    String fs = features.getNumImplicitEntries() == 0
        ? "EMPTY" : describeFeatures(features);
    getLog().info("[logFeatures] " + a + " " + fs);
  }

  protected void checkSize() {
    // IntDoubleDenseVector grows in its own
//    if (isAlphabetBased()) {
//      int n = featureIndices.size();
//      if (n > theta.dimension()) {
//        int ns = (int) (1.6d * n + 0.5d);
//        theta.grow(ns);
//      }
//    } else {
//      if (theta.dimension() < numBuckets)
//        theta.grow(numBuckets);
//    }
  }

  /** This will override Params.showWeights for extending classes */
  public void showWeights() {
//    Alphabet<String> featureIndices = getAlphabetForShowingWeights();
//    if (featureIndices == null) {
//      String meta = "[showFeatures " + getClass() + "]";
//      getLog().info(meta + " can't show features "
//          + "because we're using feature hashing with no custom "
//          + "getAlphabetForShowingWeights.");
//      if (showWeightsEvenWithoutAlphabet) {
//        StringBuilder sb = new StringBuilder();
//        sb.append(meta + " showing weights without an alphabet:\n");
//        sb.append(StringUtils.trim(theta.weights.toString(), 400));
//        getLog().info(sb.toString());
//      }
//      return;
//    }
//    String msg = getClass().getName();
//    int k = 15; // how many of the most extreme features to show
//    List<FeatureWeight> w = ModelViewer.getSortedWeights(theta.getWeights(), featureIndices);
//    ModelViewer.showBiggestWeights(w, k, msg, getLog());
    Log.warn("re-implement me!");
  }

  /**
   * You can override this method for implementations that use hashing. Hashing
   * means that you don't have to do a HashMap lookup during inference, but
   * you probably still want to be able to look at the weights. In some cases
   * you can manually define this alphabet (or a subset of it) so that you can
   * know the weight names for debugging even though you never use them during
   * inference.
   */
  public Alphabet<String> getAlphabetForShowingWeights() {
    if (!isAlphabetBased())
      return null;
    assert featureIndices != null;
    return featureIndices;
  }

  /**
   * This will override Params.doneTraining for extending classes.
   * Stop the alphabet from growing.
   */
  public void doneTraining() {
    if (featureIndices != null) {
      getLog().info("[doneTraining] stopping alphabet growth");
      featureIndices.stopGrowth();
    }
    showWeights();
  }
}
