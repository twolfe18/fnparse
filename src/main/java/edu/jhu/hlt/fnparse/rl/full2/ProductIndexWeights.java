package edu.jhu.hlt.fnparse.rl.full2;

import java.util.List;

import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.scoring.Adjoints;

public interface ProductIndexWeights {
  public Adjoints score(List<ProductIndex> features, boolean convertToIntArray);
  public Adjoints score(LL<ProductIndex> features);
  public int dimension();
}
