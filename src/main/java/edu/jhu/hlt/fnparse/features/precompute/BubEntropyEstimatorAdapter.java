package edu.jhu.hlt.fnparse.features.precompute;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import dk.ange.octave.OctaveEngine;
import dk.ange.octave.OctaveEngineFactory;
import dk.ange.octave.type.OctaveDouble;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.map.LongIntEntry;
import edu.jhu.prim.map.LongIntHashMap;
import edu.jhu.prim.vector.IntIntDenseVector;

/**
 * Calls some matlab/octave code to compute entropy and mutual information.
 *
 * @see http://www.stat.columbia.edu/~liam/research/info_est.html
 * Paninski, L. (2003). Estimation of entropy and mutual information.
 * Neural Computation 15: 1191-1254.
 *
 * @author travis
 */
public class BubEntropyEstimatorAdapter implements AutoCloseable {

  // Turn off logging used by javaoctave
  static {
    System.setProperty("org.apache.commons.logging.Log",
                       "org.apache.commons.logging.impl.NoOpLog");
  }

  private OctaveEngine octave;
  public int kMax = 20;   // they claim 11-15 is good enough, may lower if too slow
  public boolean debug = false;
  public boolean allowMleFallback = true;

  public BubEntropyEstimatorAdapter(File bubFuncParentDir) {
    if (!bubFuncParentDir.isDirectory() || !new File(bubFuncParentDir, "BUBfunc.m").isFile())
      throw new IllegalArgumentException("provided file is not a directory or does not contain BUBfunc.m: " + bubFuncParentDir.getPath());
    OctaveEngineFactory factory = new OctaveEngineFactory();
    octave = factory.getScriptEngine();
    octave.eval("addpath('" + bubFuncParentDir.getPath() + "');");
  }

  public <T> double entropy(Counts<T> counts, int dimension) {
    List<Integer> cnt = new ArrayList<>();
    for (Entry<T, Integer> x : counts.entrySet())
      cnt.add(x.getValue());
    Collections.sort(cnt);
    Collections.reverse(cnt);
    int[] c = new int[cnt.size()];
    for (int i = 0; i < c.length; i++)
      c[i] = cnt.get(i);
    return entropy(c, dimension);
  }

  public double entropy(IntIntDenseVector counts) {
    int dimension = counts.getNumImplicitEntries();
    List<Integer> c = new ArrayList<>();
    for (int i = 0; i < dimension; i++)
      if (counts.get(i) > 0)
        c.add(counts.get(i));
    Collections.sort(c);
    Collections.reverse(c);
    int[] cnt = new int[c.size()];
    for (int i = 0; i < cnt.length; i++)
      cnt[i] = c.get(i);
    return entropy(cnt, dimension);
  }

  /** preferred, fast */
  public double entropyUsingDimensionNNZ(LongIntHashMap counts) {
    int dimension = counts.size();
    return entropy(counts, dimension);
  }

  /** dispreferred, slow */
  public double entropyUsingDimensionFromIndices(LongIntHashMap counts) {
    int dimension = counts.size();
    Iterator<LongIntEntry> itr = counts.iterator();
    while (itr.hasNext()) {
      LongIntEntry e = itr.next();
      long feat = e.index();
      if (feat >= Integer.MAX_VALUE) {
        throw new RuntimeException("index too large: " + feat
            + ". Did you pack the indices properly? If there are more than 2B "
            + "indices, you can't fit this feature anyway...");
      }
      if (feat >= dimension)
        dimension = ((int) feat) + 1;
    }
    assert dimension > 0 : "overflow";
    return entropy(counts, dimension);
  }

  public double entropy(LongIntHashMap counts, int dimension) {
    List<Integer> c = new ArrayList<>();
    Iterator<LongIntEntry> itr = counts.iterator();
    while (itr.hasNext()) {
      LongIntEntry e = itr.next();
      int count = e.get();
      c.add(count);
    }
    Collections.sort(c);
    Collections.reverse(c);
    int[] cnt = new int[c.size()];
    for (int i = 0; i < cnt.length; i++)
      cnt[i] = c.get(i);
    return entropy(cnt, dimension);
  }

  public static double mleEntropyEstimate(int[] counts) {
    long z = 0;
    for (long c : counts) {
      assert c > 0;
      z += c;
      assert z > 0;
    }
    double h = 0;
    for (int i = 0; i < counts.length; i++) {
      long c = counts[i];
      if (c > 0 && c < z) {
        double p = ((double) c) / z;
        h += p * -Math.log(p);
      }
    }
    if (h < -0.1)
      Log.warn("problem in MLE entropy estimate: h=" + h);
    return h;
  }

  /**
   * @param counts is a histogram of just counts, no feature indices
   *  (note: this function is invariant in permutation of this argument)
   * @param dimension of the space from which the histogram is drawn.
   * @return the BUB entropy estimate.
   */
  public double entropy(int[] counts, int dimension) {
    if (dimension < counts.length)
      throw new IllegalArgumentException();

    long N = 0;
    for (long c : counts) N += c;
    if (debug)
      Log.info("N=" + N + " n.length=" + counts.length + " m=" + dimension + " k_max=" + kMax);

    if (N >= 4 * dimension && N >= 300 && allowMleFallback) {
      if (debug)
        Log.info("falling back on MLE for N/m is large case");
      return mleEntropyEstimate(counts);
    }

    try {
      // Convert counts to double[] since javaoctave doesn't appear to support longs/ints
      // TODO Implement an IntMatrixReader (current impl uses doubles only) in javaoctave
      OctaveDouble n = new OctaveDouble(dimension, 1);
      for (int i = 0; i < dimension; i++) {
        if (i < counts.length)
          n.set(counts[i], i+1, 1);
        else
          n.set(0, i+1, 1);
      }
      octave.put("n", n);

      octave.eval("N=" + N
          + "; m=" + dimension
          + "; k_max=" + kMax
          + "; display_flag=0"
          + ";");

      if (debug)
        Log.info("about to call1");
      octave.eval("[a,MM]=BUBfunc(N,m,k_max,display_flag);");
      if (debug)
        Log.info("about to call2");
      octave.eval("BUB_est=sum(a(n+1));");
      if (debug)
        Log.info("about to get result");
      OctaveDouble r = octave.get(OctaveDouble.class, "BUB_est");
      if (debug)
        Log.info("result=" + r.get(1));
      double h = r.get(1);

      if (h < -0.1)
        Log.warn("problem in BUB entropy estimate: h=" + h);

      return h;
    } catch (Exception e) {
      Log.warn("m=" + dimension + " n.length=" + counts.length + " N=" + N);
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() {
    octave.close();
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    File bubParent = config.getExistingDir("bubFuncParentDir");
    try (BubEntropyEstimatorAdapter bub = new BubEntropyEstimatorAdapter(bubParent)) {
      int[] counts = new int[] {1, 2, 3, 4};
      int dim = 4;
      double I = bub.entropy(counts, dim);
      System.out.println("n=" + Arrays.toString(counts) + " m=" + dim + " I=" + I);
    }
  }
}
