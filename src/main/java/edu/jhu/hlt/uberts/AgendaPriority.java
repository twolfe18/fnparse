package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.tuple.Pair;

public interface AgendaPriority extends BiFunction<HypEdge, Adjoints, Double> {
  public static int DEBUG = 1;

  public double priority(HypEdge edge, Adjoints score);

  @Override
  default public Double apply(HypEdge e, Adjoints s) {
    return priority(e, s);
  }

  public static AgendaPriority parse(String description) {
    if (DEBUG > 0)
      Log.info("parsing agenda priority " + description);
    String[] toks = description.split("\\+");
    WeightedSum w = new WeightedSum();
    for (int i = 0; i < toks.length; i++) {
      String[] wp = toks[i].trim().split("\\*");
      assert wp.length == 2;
      double weight = Double.parseDouble(wp[0].trim());
      AgendaPriority p = byName(wp[1].trim());
      w.add(p, weight);
    }
    return w;
  }

  public static AgendaPriority byName(String name) {
    switch (name.toLowerCase()) {
    case "leftright":
    case "left2right":
    case "left-right":
    case "l2r":
      return new LeftRight();
    case "easyfirst":
    case "easy-first":
    case "bestfirst":
    case "best-first":
      return new EasyFirst();
    case "dfs":
      return new Dfs();
    case "bfs":
      return new Bfs();
    default:
      throw new IllegalArgumentException("don't know about: " + name);
    }
  }

  public static class LeftRight implements AgendaPriority {
    public double slope = 1/5d;   // smaller slopes care less about left-right
    @Override
    public double priority(HypEdge edge, Adjoints score) {
      return slope * -last(edge);
    }
    static int last(HypEdge edge) {
      switch (edge.getRelation().getName()) {
      case "event1":
      case "predicate2":
      case "srl3":
        return Span.inverseShortString((String) edge.getTail(0).getValue()).end;
      case "srl2":        // (t,s)
        return Math.max(
            Span.inverseShortString((String) edge.getTail(0).getValue()).end,
            Span.inverseShortString((String) edge.getTail(1).getValue()).end);
      case "argument4":   // (t,f,s,k)
        return Math.max(
            Span.inverseShortString((String) edge.getTail(0).getValue()).end,
            Span.inverseShortString((String) edge.getTail(2).getValue()).end);
      default:
        throw new RuntimeException("don't know about: " + edge);
      }
    }
  }

  public static class EasyFirst implements AgendaPriority {
    // helps avoid saturation, requires knowing typical score range
    public double scale = 5;
    @Override
    public double priority(HypEdge edge, Adjoints score) {
      return Math.tanh(score.forwards() / scale);
    }
  }

  public static class Dfs implements AgendaPriority {
    @Override
    public double priority(HypEdge edge, Adjoints score) {
      switch (edge.getRelation().getName()) {
      case "event1":
        return -2;
      case "predicate2":
        return -1;
      case "srl2":
        return 0;
      case "srl3":
        return +1;
      case "argument4":
        return +2;
      default:
        throw new RuntimeException("don't know about: " + edge);
      }
    }
  }

  public static class Bfs implements AgendaPriority {
    @Override
    public double priority(HypEdge edge, Adjoints score) {
      switch (edge.getRelation().getName()) {
      case "event1":
        return +2;
      case "predicate2":
        return +1;
      case "srl2":
        return 0;
      case "srl3":
        return -1;
      case "argument4":
        return -2;
      default:
        throw new RuntimeException("don't know about: " + edge);
      }
    }
  }

  public static class WeightedSum implements AgendaPriority {
    private List<Pair<AgendaPriority, Double>> priorities;
    public WeightedSum() {
      priorities = new ArrayList<>();
    }
    public void add(AgendaPriority p, double w) {
      this.priorities.add(new Pair<>(p, w));
    }
    public double priority(HypEdge edge, Adjoints score) {
      double p = 0;
      for (Pair<AgendaPriority, Double> pp : priorities)
        p += pp.get2() * pp.get1().priority(edge, score);
      return p;
    }
  }
}
