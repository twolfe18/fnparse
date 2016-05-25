package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;

/**
 * Holds a set of labels. Has some extra functionality like the inner Perf class
 * which can report {@link FPR} by {@link Relation}.
 *
 * @author travis
 */
public class Labels {

  private Set<HashableHypEdge> edges;
  private Counts<String> relationCounts;

  // Stratify by relation
  private Map<Relation, Set<HashableHypEdge>> edges2;

  public Labels() {
    edges = new HashSet<>();
    relationCounts = new Counts<>();
    edges2 = new HashMap<>();
  }

  public void add(HypEdge e) {
    add(new HashableHypEdge(e));
  }
  public void add(HashableHypEdge e) {
    boolean added = edges.add(e);
    assert added;
    relationCounts.increment(e.getEdge().getRelation().getName());

    Relation key = e.getEdge().getRelation();
    Set<HashableHypEdge> s = edges2.get(key);
    if (s == null) {
      s = new HashSet<>();
      edges2.put(key, s);
    }
    s.add(e);
  }

  public boolean contains(HypEdge e) {
    return contains(new HashableHypEdge(e));
  }
  public boolean contains(HashableHypEdge e) {
    return edges.contains(e);
  }

  public void clear() {
    edges.clear();
    relationCounts.clear();
  }

  public Counts<String> getRelCounts() {
    return relationCounts;
  }

  public int getRelCount(String relName) {
    return relationCounts.getCount(relName);
  }

  /**
   * All the relation names which have gold/label facts.
   */
  public List<String> getLabeledRelationNames() {
    List<String> r = new ArrayList<>();
    for (Relation rel : edges2.keySet())
      r.add(rel.getName());
    Collections.sort(r);
    return r;
  }
  /**
   * All the relation names which have gold/label facts.
   */
  public List<Relation> getLabeledRelation() {
    List<Relation> r = new ArrayList<>(edges2.keySet());
    Collections.sort(r, Relation.BY_NAME);
    return r;
  }

  public static <T> Map<T, FPR> combinePerfByRel(Map<T, FPR> a, Map<T, FPR> b) {
    Map<T, FPR> c = new HashMap<>();
    // c += a
    for (Entry<T, FPR> x : a.entrySet()) {
      FPR aa = x.getValue();
      FPR cc = c.get(x.getKey());
      if (cc == null) {
        cc = new FPR();
        c.put(x.getKey(), cc);
      }
      cc.accum(aa);
    }
    // c += b
    for (Entry<T, FPR> x : b.entrySet()) {
      FPR bb = x.getValue();
      FPR cc = c.get(x.getKey());
      if (cc == null) {
        cc = new FPR();
        c.put(x.getKey(), cc);
      }
      cc.accum(bb);
    }
    return c;
  }

  public static List<String> showPerfByRel(Map<String, FPR> m) {
    List<String> keys = new ArrayList<>(m.keySet());
    Collections.sort(keys);
    List<String> lines = new ArrayList<>();
    for (String rel : keys) {
      FPR p = m.get(rel);
//      if (p.tpPlusFpPlusFn() == 0)
//        continue;
      String l = "";
      l += String.format("tp(%s)=%d\tfp(%s)=%d\tfn(%s)=%d",
          rel, (int) p.getTP(), rel, (int) p.getFP(), rel, (int) p.getFN());
      l += "\t";
      l += String.format("R(%s)=%.1f\tP(%s)=%.1f\tF(%s)=%.1f",
          rel, p.recall()*100d, rel, p.precision()*100d, rel, p.f1()*100d);
      lines.add(l);
    }
    return lines;
  }

  /** You add predicted edges to this and it tracks precision, recall, F1 */
  public class Perf {
    Set<HashableHypEdge> seen = new HashSet<>();
    int tp = 0, fp = 0;
    Counts<Relation> tpByRel, fpByRel;

    public Perf() {
      tpByRel = new Counts<>();
      fpByRel = new Counts<>();
    }

    /** returns true if this is a gold edge */
    public boolean add(HypEdge e) {
      // Ignore duplicates: P and R are measures on sets
      HashableHypEdge he = new HashableHypEdge(e);
      if (contains(he)) {
        if (seen.add(he)) {
          tp++;
          tpByRel.increment(e.getRelation());
        }
        return true;
      } else {
        if (seen.add(he)) {
          fp++;
          fpByRel.increment(e.getRelation());
        }
        return false;
      }
    }

    public Map<String, FPR> perfByRel() {
      if (edges.size() == 0)
        return Collections.emptyMap();
      Map<String, FPR> m = new HashMap<>();
      for (Relation rel : edges2.keySet()) {
        int tp = tpByRel.getCount(rel);
        int fp = fpByRel.getCount(rel);
        int fn = edges2.get(rel).size() - tp;
        FPR perf = new FPR();
        perf.accum(tp, fp, fn);
        Object old = m.put(rel.getName(), perf);
        assert old == null;
      }
      return m;
    }

    public Map<Relation, FPR> perfByRel2() {
      if (edges.size() == 0)
        return Collections.emptyMap();
      Map<Relation, FPR> m = new HashMap<>();
      for (Relation rel : edges2.keySet()) {
        int tp = tpByRel.getCount(rel);
        int fp = fpByRel.getCount(rel);
        int fn = edges2.get(rel).size() - tp;
        FPR perf = new FPR();
        perf.accum(tp, fp, fn);
        Object old = m.put(rel, perf);
        assert old == null;
      }
      return m;
    }

    public double precision() {
      if (tp + fp == 0)
        return 1;
      return ((double) tp) / (tp + fp);
    }

    public Map<String, Double> precisionByRel() {
      if (edges.size() == 0)
        return Collections.emptyMap();
      Map<String, Double> m = new HashMap<>();
      for (Relation rel : edges2.keySet()) {
        int tp = tpByRel.getCount(rel);
        int fp = fpByRel.getCount(rel);
        double recall = ((double) tp) / (tp + fp);
        Object old = m.put(rel.getName(), recall);
        assert old == null;
      }
      return m;
    }

    public double recall() {
      if (edges.size() == 0)
        return 1;
      return ((double) tp) / edges.size();
    }

    public Map<String, Double> recallByRel() {
      if (edges.size() == 0)
        return Collections.emptyMap();
      Map<String, Double> m = new HashMap<>();
      for (Relation rel : edges2.keySet()) {
        int tp = tpByRel.getCount(rel);
        double recall = ((double) tp) / edges2.get(rel).size();
        Object old = m.put(rel.getName(), recall);
        assert old == null;
      }
      return m;
    }

    public List<HypEdge> getFalseNegatives(Relation rel) {
      List<HypEdge> fn = new ArrayList<>();
      Set<HashableHypEdge> s = edges2.get(rel);
      for (HashableHypEdge he : s) {
        if (!seen.contains(he))
          fn.add(he.getEdge());
      }
      return fn;
    }

    public List<HypEdge> getFalseNegatives() {
      List<HypEdge> fn = new ArrayList<>();
      List<Relation> rels = new ArrayList<>(edges2.keySet());
      Collections.sort(rels, Relation.BY_NAME);
      for (Relation rel : rels)
        fn.addAll(getFalseNegatives(rel));
      return fn;
    }
  }
}
