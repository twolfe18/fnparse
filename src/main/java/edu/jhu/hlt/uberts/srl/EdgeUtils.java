package edu.jhu.hlt.uberts.srl;

import java.util.Comparator;

import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.uberts.HypEdge;

public class EdgeUtils {

  public static Span target(HypEdge e) {
    String r = e.getRelation().getName();
    switch (r) {
    case "predicate2":
      return Span.inverseShortString((String) e.getTail(0).getValue());
    case "argument4":
      return Span.inverseShortString((String) e.getTail(0).getValue());
    default:
      if (r.startsWith("xue-palmer") && e.getRelation().getNumArgs() == 2)
        return Span.inverseShortString((String) e.getTail(1).getValue());
      throw new RuntimeException("implement me");
    }
  }

  public static String frame(HypEdge e) {
    switch (e.getRelation().getName()) {
    case "predicate2":
    case "srl3":
    case "argument4":
      return e.getTail(1).getValue().toString();
    case "role2":
      assert e.getNumTails() == 2;
      return (String) e.getTail(0).getValue();
    default:
      return null;
    }
  }

  public static String role(HypEdge e) {
    switch (e.getRelation().getName()) {
    case "argument4":
      // argument4(t,f,s,k)
      assert e.getNumTails() == 4;
      return (String) e.getTail(3).getValue();
    case "srl3":
      // srl2(t,s)
      assert e.getNumTails() == 3;
      return (String) e.getTail(2).getValue();
    case "role2":
      assert e.getNumTails() == 2;
      return (String) e.getTail(1).getValue();
    default:
      return null;
    }
  }

  public static Span arg(HypEdge e) {
    String r = e.getRelation().getName();
    switch (r) {
    case "argument4":
      // argument4(t,f,s,k)
      assert e.getNumTails() == 4;
      return Span.inverseShortString((String) e.getTail(2).getValue());
    case "srl2":
      // srl2(t,s)
      assert e.getNumTails() == 2;
      return Span.inverseShortString((String) e.getTail(1).getValue());
    default:
      if (r.startsWith("xue-palmer") && e.getRelation().getNumArgs() == 2)
        return Span.inverseShortString((String) e.getTail(1).getValue());
      return null;
    }
  }

  public static final Comparator<HypEdge> BY_TF = new Comparator<HypEdge>() {
    @Override
    public int compare(HypEdge o1, HypEdge o2) {
      Span t1 = EdgeUtils.target(o1);
      Span t2 = EdgeUtils.target(o2);
      int i = Span.BY_END_LR_THEN_WIDTH_THIN.compare(t1, t2);
      if (i != 0)
        return i;
      String f1 = EdgeUtils.frame(o1);
      String f2 = EdgeUtils.frame(o2);
      return f1.compareTo(f2);
    }
  };

  public static final Comparator<HypEdge> BY_S = new Comparator<HypEdge>() {
    @Override
    public int compare(HypEdge o1, HypEdge o2) {
      Span s1 = EdgeUtils.arg(o1);
      Span s2 = EdgeUtils.arg(o2);
      return Span.BY_END_LR_THEN_WIDTH_THIN.compare(s1, s2);
    }
  };

  public static final Comparator<HypEdge> BY_K_NAME = new Comparator<HypEdge>() {
    @Override
    public int compare(HypEdge o1, HypEdge o2) {
      String k1 = EdgeUtils.role(o1);
      String k2 = EdgeUtils.role(o2);
      return k1.compareTo(k2);
    }
  };
}
