package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
  private Map<String, Set<HashableHypEdge>> edges2;

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

    String key = e.getEdge().getRelation().getName();
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
    r.addAll(edges2.keySet());
    Collections.sort(r);
    return r;
  }

  /** You add predicted edges to this and it tracks precision, recall, F1 */
  public class Perf {
    Set<HashableHypEdge> seen = new HashSet<>();
    int tp = 0, fp = 0;
    Counts<String> tpByRel, fpByRel;

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
          tpByRel.increment(e.getRelation().getName());
        }
        return true;
      } else {
        if (seen.add(he)) {
          fp++;
          fpByRel.increment(e.getRelation().getName());
        }
        return false;
      }
    }

    public Map<String, FPR> perfByRel() {
      if (edges.size() == 0)
        return Collections.emptyMap();
      Map<String, FPR> m = new HashMap<>();
      for (String relName : edges2.keySet()) {
        int tp = tpByRel.getCount(relName);
        int fp = fpByRel.getCount(relName);
        int fn = edges2.get(relName).size() - tp;
        FPR perf = new FPR();
        perf.accum(tp, fp, fn);
        Object old = m.put(relName, perf);
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
      for (String relName : edges2.keySet()) {
        int tp = tpByRel.getCount(relName);
        int fp = fpByRel.getCount(relName);
        double recall = ((double) tp) / (tp + fp);
        Object old = m.put(relName, recall);
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
      for (String relName : edges2.keySet()) {
        int tp = tpByRel.getCount(relName);
        double recall = ((double) tp) / edges2.get(relName).size();
        Object old = m.put(relName, recall);
        assert old == null;
      }
      return m;
    }

    public List<HypEdge> getFalseNegatives(String relName) {
      List<HypEdge> fn = new ArrayList<>();
      Set<HashableHypEdge> s = edges2.get(relName);
      for (HashableHypEdge he : s) {
        if (!seen.contains(he))
          fn.add(he.getEdge());
      }
      return fn;
    }

    public List<HypEdge> getFalseNegatives() {
      List<HypEdge> fn = new ArrayList<>();
      List<String> rels = new ArrayList<>(edges2.keySet());
      Collections.sort(rels);
      for (String relName : rels)
        fn.addAll(getFalseNegatives(relName));
      return fn;
    }
  }
}
