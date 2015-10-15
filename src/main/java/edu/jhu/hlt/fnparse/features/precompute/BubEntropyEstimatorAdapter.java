package edu.jhu.hlt.fnparse.features.precompute;

import java.io.File;
import java.util.Arrays;

import edu.jhu.hlt.tutils.ExperimentProperties;
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
  private File bubFuncPath;

  public BubEntropyEstimatorAdapter(File bubFuncPath) {
    MatlabProxyFactoryOptions.Builder builder = new MatlabProxyFactoryOptions.Builder();
    MatlabProxyFactory factory = new MatlabProxyFactory(builder.build());
    try {
      proxy = factory.getProxy();
      proxy.eval("addpath('" + bubFuncPath.getPath() + "')");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    this.bubFuncPath = bubFuncPath;
  }

  public BubEntropyEstimatorAdapter(MatlabProxy proxy, File bubFuncPath) {
    this.proxy = proxy;
    this.bubFuncPath = bubFuncPath;
    try {
      proxy.eval("addpath('" + bubFuncPath.getPath() + "')");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns the path to the matlab code implementing entropy calculation */
  public File getBubCode() {
    return bubFuncPath;
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
      proxy.eval("[a,MM]=BUBfunc(N,m,k_max,display_flag)");
      proxy.eval("BUB_est=sum(a(n+1))");
      Object I = proxy.getVariable("BUB_est");
      return (double) I;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void close() {
    proxy.disconnect();
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    File bubCode = config.getExistingFile("bubCode");
    BubEntropyEstimatorAdapter bub = new BubEntropyEstimatorAdapter(bubCode);
    long[] counts = new long[] {1, 2, 3, 4};
    long dim = 4;
    double I = bub.entropy(counts, dim);
    System.out.println("n=" + Arrays.toString(counts) + " m=" + dim + " I=" + I);
    bub.close();
  }
}
