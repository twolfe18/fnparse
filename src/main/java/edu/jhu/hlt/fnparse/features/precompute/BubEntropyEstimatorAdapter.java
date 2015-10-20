package edu.jhu.hlt.fnparse.features.precompute;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;

import dk.ange.octave.OctaveEngine;
import dk.ange.octave.OctaveEngineFactory;
import dk.ange.octave.type.Octave;
import dk.ange.octave.type.OctaveDouble;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
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

//  private MatlabProxy proxy;
  private OctaveEngine octave;
  public int kMax = 20;   // they claim 11-15 is good enough, may lower if too slow
  public boolean debug = false;

  public BubEntropyEstimatorAdapter(File bubFuncParentDir) {
    if (!bubFuncParentDir.isDirectory() || !new File(bubFuncParentDir, "BUBfunc.m").isFile())
      throw new IllegalArgumentException("provided file is not a directory or does not contain BUBfunc.m: " + bubFuncParentDir.getPath());
    octave = new OctaveEngineFactory().getScriptEngine();
    octave.eval("addpath('" + bubFuncParentDir.getPath() + "');");
  }

  public <T> double entropy(Counts<T> counts, long dimension) {
    List<Integer> cnt = new ArrayList<>();
    for (Entry<T, Integer> x : counts.entrySet())
      cnt.add(x.getValue());
    Collections.sort(cnt);
    Collections.reverse(cnt);
    long[] c = new long[cnt.size()];
    for (int i = 0; i < c.length; i++)
      c[i] = cnt.get(i);
    return entropy(c, dimension);
  }

  public double entropy(IntIntDenseVector counts) {
    long dimension = counts.getNumImplicitEntries();
    List<Integer> c = new ArrayList<>();
    for (int i = 0; i < dimension; i++)
      if (counts.get(i) > 0)
        c.add(counts.get(i));
    Collections.sort(c);
    Collections.reverse(c);
    long[] cnt = new long[c.size()];
    for (int i = 0; i < cnt.length; i++)
      cnt[i] = c.get(i);
    return entropy(cnt, dimension);
  }

  /**
   * @param counts is a histogram of just counts, no feature indices
   *  (note: this function is invariant in permutation of this argument)
   * @param dimension of the space from which the histogram is drawn.
   * @return the BUB entropy estimate.
   */
  public double entropy(long[] counts, long dimension) {
    long N = 0;
    for (long c : counts) N += c;
    if (debug)
      Log.info("N=" + N + " n.length=" + counts.length + " m=" + dimension + " k_max=" + kMax);
    try {
      // Convert counts to double[] since javaoctave doesn't appear to support longs/ints
      // TODO Implement an IntMatrixReader (current impl uses doubles only) in javaoctave
      OctaveDouble n = new OctaveDouble(counts.length, 1);
      for (int i = 0; i < counts.length; i++)
        n.set(counts[i], i+1, 1);
      octave.put("n", n);

      octave.put("N", Octave.scalar(N));
      octave.put("m", Octave.scalar(dimension));
      octave.put("k_max", Octave.scalar(kMax));
      octave.put("display_flag", Octave.scalar(0));

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
      return r.get(1);
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
      long[] counts = new long[] {1, 2, 3, 4};
      long dim = 4;
      double I = bub.entropy(counts, dim);
      System.out.println("n=" + Arrays.toString(counts) + " m=" + dim + " I=" + I);
    }
  }
}
