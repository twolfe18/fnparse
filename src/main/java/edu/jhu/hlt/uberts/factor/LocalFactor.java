package edu.jhu.hlt.uberts.factor;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.Uberts;

public interface LocalFactor {

  public Adjoints score(HypEdge y, Uberts x);


  public static class Sum implements LocalFactor {
    private LocalFactor left, right;

    public Sum(LocalFactor left, LocalFactor right) {
      this.left = left;
      this.right = right;
    }

    @Override
    public Adjoints score(HypEdge y, Uberts x) {
      Adjoints l = left.score(y, x);
      Adjoints r = right.score(y, x);
      return Adjoints.sum(l, r);
    }
  }


  public static class Zero implements LocalFactor {
    @Override
    public Adjoints score(HypEdge y, Uberts x) {
      return Adjoints.Constant.ZERO;
    }
  }
}
