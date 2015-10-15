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
  private File bubFuncParentDir;  // has to be parent bc matlab can't have files on the path...

  public BubEntropyEstimatorAdapter(File bubFuncParentDir) {
    MatlabProxyFactoryOptions.Builder builder = new MatlabProxyFactoryOptions.Builder();
    MatlabProxyFactory factory = new MatlabProxyFactory(builder.build());
    try {
      proxy = factory.getProxy();
      proxy.eval("addpath('" + bubFuncParentDir.getPath() + "')");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    this.bubFuncParentDir = bubFuncParentDir;
  }

  public BubEntropyEstimatorAdapter(MatlabProxy proxy, File bubFuncParentDir) {
    this.proxy = proxy;
    this.bubFuncParentDir = bubFuncParentDir;
    try {
      proxy.eval("addpath('" + bubFuncParentDir.getPath() + "')");
      proxy.eval("addpath('/home/hltcoe/twolfe/temp')");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns the path to the matlab code implementing entropy calculation */
  public File getBubCode() {
    return bubFuncParentDir;
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
      proxy.setVariable("k_max", 30);
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
