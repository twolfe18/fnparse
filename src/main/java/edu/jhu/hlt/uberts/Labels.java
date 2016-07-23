package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;

/**
 * Holds a set of labels. Has some extra functionality like the inner Perf class
 * which can report {@link FPR} by {@link Relation}.
 *
 * A fact is either a "nil fact" or not. Nil facts are used to give a score for
 * something that is immaterial, like p(some set of facts are false). This class
 * keeps track of nil and non-nil facts separately, and you have to specify
 * whether facts are nil or not.
 *
 * This class doesn't care about mutual exclusion between nil facts and non-nil
 * facts. They are just two different types of facts for these purporses.
 *
 * The non-static inner class {@link Perf} can measure {@link FPR} of predicted
 * facts added to to it against the gold facts contained in {@link Labels}.
 * By default this measurement ignores nil facts.
 *
 * @author travis
 */
public class Labels {

  /**
   * This is a temporary measure until HypEdges are either endowed with a boolean
   * for whether they are nil facts or until I add a nilFact:boolean argument
   * to every method which takes a HypEdge.
   */
  public static boolean isNilFact(HypEdge e) {
    if ("argument4".equals(e.getRelation().getName())) {
      String s = (String) e.getTail(2).getValue();
      return Span.nullSpan.shortString().equals(s);
    }
    return false;
  }

  // These two sets don't intersect.
  private Set<HashableHypEdge> edges;     // gold facts
  private Set<HashableHypEdge> edgesNil;  // gold facts which are "nil facts", e.g. argument4(t,f,0-0,k)

  // Stratify by relation
  private Map<Relation, Set<HashableHypEdge>> edges2;
  private Map<Relation, Set<HashableHypEdge>> edgesNil2;

  public Labels(Uberts u) {
    edges = new HashSet<>();
    edgesNil = new HashSet<>();
    edges2 = new HashMap<>();
    edgesNil2 = new HashMap<>();
  }

  public void add(HypEdge e) {
    add(new HashableHypEdge(e));
  }
  public void add(HashableHypEdge e) {
    Set<HashableHypEdge> es;
    Map<Relation, Set<HashableHypEdge>> em;
    if (isNilFact(e.getEdge())) {
      es = edgesNil;
      em = edgesNil2;
    } else {
      es = edges;
      em = edges2;
    }

    boolean added = es.add(e);
    assert added;

    Relation key = e.getEdge().getRelation();
    Set<HashableHypEdge> s = em.get(key);
    if (s == null) {
      s = new HashSet<>();
      em.put(key, s);
    }
    s.add(e);
  }

  public boolean getLabel(HypEdge e) {
    return getLabel(new HashableHypEdge(e));
  }

  /**
   * Returns true if this is a "gold edge". This will be true if the given fact
   * is a NIL-value-containing (aka pruning) fact AND there is no conflicting
   * non-NIL-value-containing gold edge.
   */
  public boolean getLabel(HashableHypEdge e) {
    if (isNilFact(e.getEdge()))
      return edgesNil.contains(e);
    return edges.contains(e);
  }

  public void clear() {
    edges.clear();
    edgesNil.clear();
    edges2.clear();
    edgesNil2.clear();
  }

  public List<HypEdge> getGoldEdges(boolean includeNilFacts) {
    List<HypEdge> all = new ArrayList<>();
    for (HashableHypEdge hhe : edges)
      all.add(hhe.getEdge());
    if (includeNilFacts) {
      for (HashableHypEdge hhe : edgesNil)
        all.add(hhe.getEdge());
    }
    Collections.sort(all, HypEdge.BY_RELATION_THEN_TAIL);
    return all;
  }

  public Collection<HashableHypEdge> getGoldEdges(Relation r, boolean includeNilFacts) {
    Collection<HashableHypEdge> c = new ArrayList<>();
    c.addAll(edges2.getOrDefault(r, Collections.emptySet()));
    if (includeNilFacts)
      c.addAll(edgesNil2.getOrDefault(r, Collections.emptySet()));
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

  /**
   * You add predicted edges to this and it tracks precision, recall, F1.
   *
   * Any nil facts added to this are ignored, and {@link FPR} numbers are computed
   * with respect to non-nil facts.
   */
  public class Perf {
    Set<HashableHypEdge> seen;
    public List<HypEdge> tpInstances;
    int tp, fp;
    Counts<Relation> tpByRel, fpByRel;

    public Perf() {
      tp = fp = 0;
      seen = new HashSet<>();
      tpInstances = new ArrayList<>();
      tpByRel = new Counts<>();
      fpByRel = new Counts<>();
    }

    public void add(HypEdge e) {
      if (isNilFact(e))
        return;
      // Set semantics: adding an edge twice doesn't change the performance.
      HashableHypEdge he = new HashableHypEdge(e);
      if (seen.add(he)) {
        tpInstances.add(e);
        if (edges.contains(he)) {
          tp++;
          tpByRel.increment(e.getRelation());
        } else {
          fp++;
          fpByRel.increment(e.getRelation());
        }
      }
    }

    public Map<String, FPR> perfByRel() {
      if (edges.size() == 0)
        return Collections.emptyMap();
      Map<String, FPR> m = new HashMap<>();
      for (Relation rel : edges2.keySet()) {

        // Measure w.r.t. non-null-span prediction and gold edges
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
//      Map<Relation, FPR> m = new HashMap<>();
//      for (Relation rel : edges2.keySet()) {
//        int tp = tpByRel.getCount(rel);
//        int fp = fpByRel.getCount(rel);
//        int fn = edges2.get(rel).size() - tp;
//        FPR perf = new FPR();
//        perf.accum(tp, fp, fn);
//        Object old = m.put(rel, perf);
//        assert old == null;
//      }
//      return m;
      throw new RuntimeException("update this");
    }

    public double precision() {
      if (tp + fp == 0)
        return 1;
      double p = ((double) tp) / (tp + fp);
      assert p >= 0 && p <= 1;
      return p;
    }

    public Map<String, Double> precisionByRel() {
      if (edges.size() == 0)
        return Collections.emptyMap();
//      Map<String, Double> m = new HashMap<>();
//      for (Relation rel : edges2.keySet()) {
//        int tp = tpByRel.getCount(rel);
//        int fp = fpByRel.getCount(rel);
//        double recall = ((double) tp) / (tp + fp);
//        Object old = m.put(rel.getName(), recall);
//        assert old == null;
//      }
//      return m;
      throw new RuntimeException("update this");
    }

    public double recall() {
      if (edges.size() == 0)
        return 1;
      double r = ((double) tp) / edges.size();
      assert r >= 0 && r <= 1;
      return r;
    }

    public Map<String, Double> recallByRel() {
      if (edges.size() == 0)
        return Collections.emptyMap();
//      Map<String, Double> m = new HashMap<>();
//      for (Relation rel : edges2.keySet()) {
//        int tp = tpByRel.getCount(rel);
//        double recall = ((double) tp) / edges2.get(rel).size();
//        Object old = m.put(rel.getName(), recall);
//        assert old == null;
//      }
//      return m;
      throw new RuntimeException("update this");
    }

    public List<HypEdge> getFalseNegatives(Relation rel) {
      List<HypEdge> fn = new ArrayList<>();
      Set<HashableHypEdge> s = edges2.get(rel);
      if (s != null) {
        for (HashableHypEdge he : s) {
          if (!seen.contains(he))
            fn.add(he.getEdge());
        }
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
