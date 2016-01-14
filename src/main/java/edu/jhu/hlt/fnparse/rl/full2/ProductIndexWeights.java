package edu.jhu.hlt.fnparse.rl.full2;

import java.util.List;

import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.tutils.scoring.Adjoints;

public interface ProductIndexWeights {
  public Adjoints score(List<ProductIndex> features);
  public int dimension();
}
