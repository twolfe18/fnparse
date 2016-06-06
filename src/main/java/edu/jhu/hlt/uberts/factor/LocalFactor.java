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
      if (l == Adjoints.Constant.ZERO)
        return r;
      if (r == Adjoints.Constant.ZERO)
        return l;
      return Adjoints.sum(l, r);
    }
    @Override
    public String toString() {
      return "(Sum " + left + " " + right + ")";
    }
  }

  public static class Constant implements LocalFactor {
    private Adjoints constant;
    public Constant(double v) {
      this.constant = new Adjoints.Constant(v);
    }
    @Override
    public Adjoints score(HypEdge y, Uberts x) {
      return constant;
    }
    public String toString() {
      return String.format("(Constant %.1f)", constant.forwards());
    }
  }

  public static class Zero extends Constant {
    public Zero() {
      super(0);
    }
  }
}
