package edu.jhu.hlt.uberts.factor;

import java.util.Random;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.Uberts;

public interface LocalFactor {
  public static boolean DEBUG = false;

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

  public static class Oracle implements LocalFactor {
    @Override
    public Adjoints score(HypEdge yhat, Uberts x) {
      boolean y = x.getLabel(yhat);
      // This needs to be >1 so that it over-rides Agenda.RescoreMode.LOSS_AUGMENTED
      if (y)
        return new Adjoints.Constant(+2);
      else
        return new Adjoints.Constant(-2);
    }
    @Override
    public String toString() {
      return "(OracleLocalFactor)";
    }
  }

  public static class NoisyOracle implements LocalFactor {
    private double pFlip;
    private Random rand;
    public NoisyOracle(double pFlip, Random rand) {
      this.pFlip = pFlip;
      this.rand = rand;
    }
    @Override
    public Adjoints score(HypEdge f, Uberts x) {
      boolean flip = rand.nextDouble() < pFlip;
      boolean y = x.getLabel(f);
      if (y ^ flip)
        return Adjoints.Constant.ONE;
      else
        return Adjoints.Constant.NEGATIVE_ONE;
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
