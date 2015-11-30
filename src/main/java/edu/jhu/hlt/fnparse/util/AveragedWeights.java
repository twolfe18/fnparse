package edu.jhu.hlt.fnparse.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.map.IntDoubleEntry;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;

/**
 * A dense vector of doubles that you can add to. Allows efficient averaging,
 * as used in the average perceptron. Call incrementCount() every time you add
 * a vector (which may not be every time add is called if you use the
 * add(int,double) version).
 *
 * Currently has no support for overflow detection, not sure how numerically
 * stable this is.
 *
 * @author travis
 */
public class AveragedWeights implements Serializable {
  private static final long serialVersionUID = 1L;

  // Useful for debugging (e.g. network parameter averaging is only compatible
  // with feature hashing, which means this class should never grow).
  public static boolean GROWING_ALLOWED = true;

  private double[] theta;
  private double[] thetaSum;
  private int c;    // how many iterates of theta have been added into thetaSum

  public AveragedWeights(int dimension) {
    this(dimension, true);
  }

  public AveragedWeights(int dimension, boolean average) {
    c = 1;
    theta = new double[dimension];
    if (average)
      thetaSum = new double[dimension];
    else
      thetaSum = null;
  }

  @Override
  public String toString() {
    return toString(30);
  }

  public String toString(int k) {
    StringBuilder sb = new StringBuilder();
    sb.append("(AveragedWeights\n");
    for (int i = 0; i < Math.min(k, theta.length); i++)
      sb.append(String.format("\t%+1.2f", theta[i]));
    if (k > theta.length)
      sb.append("... and " + (theta.length - k) + " more");
    if (thetaSum != null) {
      sb.append('\n');
      for (int i = 0; i < Math.min(k, thetaSum.length); i++)
        sb.append(String.format("\t%+1.2f", thetaSum[i]));
      if (k > thetaSum.length)
        sb.append("... and " + (thetaSum.length - k) + " more");
    }
    sb.append(')');
    return sb.toString();
  }

  public void serialize(DataOutputStream out) throws IOException {
    out.writeInt(theta.length);
    for (double d : theta)
      out.writeDouble(d);
    if (thetaSum == null) {
      out.writeBoolean(false);
    } else {
      assert theta.length == thetaSum.length;
      out.writeBoolean(true);
      for (double d : thetaSum)
        out.writeDouble(d);
    }
    out.writeInt(c);
  }

  public void deserialize(DataInputStream in) throws IOException {
    int n = in.readInt();
    theta = new double[n];
    for (int i = 0; i < n; i++)
      theta[i] = in.readDouble();
    if (in.readBoolean()) {
      thetaSum = new double[n];
      for (int i = 0; i < n; i++)
        thetaSum[i] = in.readDouble();
    } else {
      thetaSum = null;
    }
    c = in.readInt();
  }

  public AveragedWeights(AveragedWeights weights, boolean average) {
    c = weights.c;
    theta = Arrays.copyOf(weights.theta, weights.theta.length);
    if (average) {
      assert weights.thetaSum != null;
      thetaSum = Arrays.copyOf(weights.thetaSum, weights.thetaSum.length);
    }
  }

  public boolean hasAverage() {
    return thetaSum != null;
  }

  public int dimension() {
    assert thetaSum == null || thetaSum.length == theta.length;
    return theta.length;
  }

  public void grow(int dimension) {
    if (dimension <= theta.length)
      throw new IllegalArgumentException();
    Log.info("[grow] dimension " + theta.length + " => " + dimension);
    if (!GROWING_ALLOWED)
      throw new RuntimeException("growing is not allowed! use feature hashing?");
    theta = Arrays.copyOf(theta, dimension);
    if (thetaSum != null)
      thetaSum = Arrays.copyOf(thetaSum, dimension);
  }

  public void add(AveragedWeights w) {
    for (int i = 0; i < theta.length; i++)
      theta[i] += w.theta[i];
    assert (thetaSum == null) == (w.thetaSum == null);
    if (thetaSum != null) {
      for (int i = 0; i < thetaSum.length; i++)
        thetaSum[i] += w.thetaSum[i];
      c += w.c;
    }
  }

  public void add(double[] a) {
    if (a.length != theta.length)
      throw new IllegalArgumentException();
    for (int i = 0; i < a.length; i++)
      theta[i] += a[i];
    if (thetaSum != null) {
      for (int i = 0; i < a.length; i++)
        thetaSum[i] += c * a[i];
    }
  }

  public void add(IntDoubleUnsortedVector a) {
    Iterator<IntDoubleEntry> itr = a.iterator();
    while (itr.hasNext()) {
      IntDoubleEntry ide = itr.next();
      add(ide.index(), ide.get());
    }
  }

  public void add(int index, double value) {
    theta[index] += value;
    if (thetaSum != null)
      thetaSum[index] += c * value;
  }

  /** Scales both the value and the history/sum */
  public void scale(double scale) {
    for (int i = 0; i < theta.length; i++)
      theta[i] *= scale;
    if (thetaSum != null)
      for (int i = 0; i < thetaSum.length; i++)
        thetaSum[i] *= scale;
  }

  public void incrementCount() {
    c++;
  }

  public double[] getWeights() {
    return theta;
  }

  public double[] getAveragedWeights() {
    if (thetaSum == null)
      throw new IllegalStateException("not in average mode");
    double[] avg = Arrays.copyOf(thetaSum, thetaSum.length);
    for (int i = 0; i < thetaSum.length; i++)
      avg[i] /= c;
    return avg;
  }

  public void setAveragedWeights() {
    theta = getAveragedWeights();
  }
}