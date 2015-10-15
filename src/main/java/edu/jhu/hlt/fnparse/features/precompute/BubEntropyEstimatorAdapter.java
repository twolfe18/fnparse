package edu.jhu.hlt.fnparse.features.precompute;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.prim.vector.IntIntDenseVector;
import matlabcontrol.MatlabProxy;
import matlabcontrol.MatlabProxyFactory;
import matlabcontrol.MatlabProxyFactoryOptions;

/**
 * Calls some matlab code to compute entropy and mutual information.
 *
 * @see http://www.stat.columbia.edu/~liam/research/info_est.html
 * Paninski, L. (2003). Estimation of entropy and mutual information.
 * Neural Computation 15: 1191-1254.
 *
 * @author travis
 */
public class BubEntropyEstimatorAdapter {

  private MatlabProxy proxy;
  public int kMax = 20;   // they claim 11-15 is good enough, may lower if too slow

  public BubEntropyEstimatorAdapter(File bubFuncParentDir) {
    if (!bubFuncParentDir.isDirectory() || !new File(bubFuncParentDir, "BUBfunc.m").isFile())
      throw new IllegalArgumentException("provided file is not a directory or does not contain BUBfunc.m: " + bubFuncParentDir.getPath());
    MatlabProxyFactoryOptions.Builder builder = new MatlabProxyFactoryOptions.Builder();
    MatlabProxyFactory factory = new MatlabProxyFactory(builder.build());
    try {
      proxy = factory.getProxy();
      proxy.eval("addpath('" + bubFuncParentDir.getPath() + "')");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Make sure you've executed a command to add the BUB code to the path, see other constructor */
  public BubEntropyEstimatorAdapter(MatlabProxy proxy) {
    this.proxy = proxy;
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
    try {
      long N = 0;
      for (long c : counts) N += c;
      proxy.setVariable("N", N);
      proxy.setVariable("n", counts);
      proxy.setVariable("m", dimension);
      proxy.setVariable("k_max", kMax);
      proxy.setVariable("display_flag", 0);
      proxy.eval("[a,MM]=BUBfunc(N,m,k_max,display_flag)");
      proxy.eval("BUB_est=sum(a(n+1))");
      Object I = proxy.getVariable("BUB_est");
      return ((double[]) I)[0];
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void close() {
    proxy.disconnect();
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    File bubParent = config.getExistingDir("bubFuncParentDir");
    BubEntropyEstimatorAdapter bub = new BubEntropyEstimatorAdapter(bubParent);
    long[] counts = new long[] {1, 2, 3, 4};
    long dim = 4;
    double I = bub.entropy(counts, dim);
    System.out.println("n=" + Arrays.toString(counts) + " m=" + dim + " I=" + I);
    bub.close();
  }
}
