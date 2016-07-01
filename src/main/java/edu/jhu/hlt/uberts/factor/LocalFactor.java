package edu.jhu.hlt.uberts.factor;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.Uberts;

public interface LocalFactor {
  public static boolean DEBUG = true;

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
      Adjoints a;
      if (l == Adjoints.Constant.ZERO) {
        a = r;
      } else if (r == Adjoints.Constant.ZERO) {
        a = l;
      } else {
        a = Adjoints.sum(l, r);
      }
      if (DEBUG)
        a = new Adjoints.Named("" + y, a);
      return a;
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
      if (DEBUG)
        return new Adjoints.NamedConstant("" + y, constant.forwards());
      return constant;
    }
    public String toString() {
      return String.format("(Constant %.1f)", constant.forwards());
    }
  }

  public static Constant ZERO = new Constant(0);
  public static Constant ONE = new Constant(1);
}
