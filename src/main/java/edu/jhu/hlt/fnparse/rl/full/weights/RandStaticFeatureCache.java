package edu.jhu.hlt.fnparse.rl.full.weights;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.tutils.Span;

public class RandStaticFeatureCache extends AbstractStaticFeatureCache {
  private Random rand = new Random(9001);
  private int D = 9001;
  @Override
  public List<ProductIndex> featT(Span t) {
    return Arrays.asList(new ProductIndex(rand.nextInt(D), D));
  }
  @Override
  public List<ProductIndex> featTF(Span t, Frame f) {
    return Arrays.asList(new ProductIndex(rand.nextInt(D), D));
  }
  @Override
  public List<ProductIndex> featTS(Span t, Span s) {
    return Arrays.asList(new ProductIndex(rand.nextInt(D), D));
  }
  @Override
  public List<ProductIndex> featFK(Frame f, int k, int q) {
    return Arrays.asList(new ProductIndex(rand.nextInt(D), D));
  }
  @Override
  public List<ProductIndex> featFKS(Frame f, int k, int q, Span s) {
    return Arrays.asList(new ProductIndex(rand.nextInt(D), D));
  }
  @Override
  public List<ProductIndex> featTFKS(Span t, Frame f, int k, int q, Span s) {
    return Arrays.asList(new ProductIndex(rand.nextInt(D), D));
  }
}