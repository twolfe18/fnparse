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
 * This class stores a set of true facts and uses that set to determine the
 * cost of facts/actions. In previous work I went deep into how to compute the
 * hamming loss for pruning action, and it has reared its ugly head here too.
 * Facts with NIL arguments are secretly pruning actions.
 *   F(a0,...,a_{i-1},NIL,...,a_n) => !F(a0,...,a_{i=1},x,...,a_n) \forall x
 * This is implicit. Explicitly, the NIL fact is just stored in the State.
 * The implication is only enforced by an AtMost1/AtLeast1 factor.
 * NIL is an agent of AtMost1/AtLeast1, it is not used otherwise.
 * This class participates in this conspiracy by computing the COST assuming
 * there is an AtMost1/AtLeast1 constraint, event if it doesn't see it (only the NIL).
 *
 * We do not compute "downstream cost". For example, if our grammar has
 * R1(x) => R2(x,y) and R2(x,y) => R3(x,y,z), we estimate the COST of R2(x,NIL)
 * by looking at the number of (back-propped) gold R2 facts which are missed
 * by this, and don't include any possible R3 facts which are now not reachable.
 *
 * @author travis
 */
public class Labels {

  // NO means costly/lossy, YES means cost=0
  public static enum Lossless {
    NO,
    YES,
//    YES_NULL_SPAN,    // prediction=true and prediction is a nullSpan argument4
    NO_NIL,
    YES_NIL,
  }

  static final String NULL_SPAN = "0-0";
  static final Object NIL = "NIL";

  public static int getNilArgPos(HypEdge e) {
    int nilArgPos = -1;
    int n = e.getNumTails();
    for (int i = 0; i < n; i++) {
//      if (e.getTail(i).getValue() == NIL) {
      // TODO use NIL everywhere, maybe need first class Rule support
      if (NULL_SPAN.equals(e.getTail(i).getValue())) {
        assert nilArgPos < 0 : "multiple NIL args? " + e;
        nilArgPos = i;
      }
    }
    return nilArgPos;
  }

  /**
   * Presupposing this is a NIL valued fact, returns true if this rules out at
   * least one other gold fact (assuming an AtMost1/Exactly1 factor is in play).
   *
   * This is implemented by finding some fact f which has the same relation as e
   * AND matches all of the bindings other than the NIL argument. If this fact
   * f can be found, then we return true (this NIL fact forces a FN).
   */
  public boolean nilFN(HypEdge e, int nilArgPos) {
    // Check all god facts which match every binding but NIL,
    // if we find one then that is a proof of a FN.
    Set<HashableHypEdge> related = edges2.get(e.getRelation());
    if (related != null)
      for (HashableHypEdge f : related)
        if (bindingsMatch(f.getEdge(), e, nilArgPos))
          return true;
    return false;
  }

  /**
   * Presupposing f and e are facts of the same type/relation, return true if
   * they have same arguments in every position except nilArgPos.
   */
  public static boolean bindingsMatch(HypEdge f, HypEdge e, int nilArgPos) {
    assert f.getRelation() == e.getRelation();
    int n = f.getNumTails();
    for (int i = 0; i < n; i++) {
      if (i == nilArgPos)
        continue;
      Object v1 = f.getTail(i).getValue();
      Object v2 = e.getTail(i).getValue();
      if (!v1.equals(v2))
        return false;
    }
    return true;
  }

//  /**
//   * TODO Remove, this is SRL-specific.
//   *
//   * Returns true if given an argument4(t,f,0-0,k) fact and there is no fact
//   * argument4(t,*,s,k) such that s != 0-0 in the set of positive labels.
//   */
//  public boolean nullSpanAllowable(HypEdge arg4) {
//    if (!"argument4".equals(arg4.getRelation().getName()))
//      return false; // Not applicable
//
//    // check that s == 0-0
//    int tIdx = 0;
//    int sIdx = 2;
//    int kIdx = 3;
//    HypNode s = arg4.getTail(sIdx);
//    if (!NULL_SPAN.equals(s.getValue()))
//      return false; // Not applicable
//
//    HypNode t = arg4.getTail(tIdx);
//    HypNode k = arg4.getTail(kIdx);
//    Relation arg4Rel = u.getEdgeType("argument4");
//
//    Set<HashableHypEdge> posArg4Facts = edges2.get(arg4Rel);
//    if (posArg4Facts != null) {
//      for (HashableHypEdge a4 : posArg4Facts) {
//        HypNode tt = a4.getEdge().getTail(tIdx);
//        HypNode kk = a4.getEdge().getTail(kIdx);
//        if (t.equals(tt) && k.equals(kk)) {
//          // We have found an argument4(t,f,s,k) with a non-null-span value
//          assert !NULL_SPAN.equals(kk.getValue());
//          return false;
//        }
//      }
//    }
//
////    System.out.println("gold null span: " + arg4);
//    return true;
//  }

  private Uberts u;
  private Set<HashableHypEdge> edges;
  private Counts<String> relationCounts;

  // Stratify by relation
  private Map<Relation, Set<HashableHypEdge>> edges2;

  public Labels(Uberts u) {
    this.u = u;
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

  public boolean getLabel(HypEdge e) {
    return getLabel(new HashableHypEdge(e));
  }

  /**
   * Returns true if this is a "gold edge". This will be true if the given fact
   * is a NIL-value-containing (aka pruning) fact AND there is no conflicting
   * non-NIL-value-containing gold edge.
   */
  public boolean getLabel(HashableHypEdge e) {
    Lossless y = lossless(e);
//    if (y == Lossless.NO_NIL)
//      System.currentTimeMillis();
    return y == Lossless.YES || y == Lossless.YES_NIL;
  }

  public Lossless lossless(HashableHypEdge e) {
    int nilArgPos = getNilArgPos(e.getEdge());
    if (nilArgPos < 0)
      return edges.contains(e) ? Lossless.YES : Lossless.NO;
    return nilFN(e.getEdge(), nilArgPos) ? Lossless.NO_NIL : Lossless.YES_NIL;
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

    // You only need to keep track of non-NIL facts.
    // Gold facts are always non-NIL and we don't want to evaluate against
    // NIL facts, whose semantics has more to do with pruning than predictions.
    int tp = 0, fp = 0;

//    // split predictions by nullSpan vs realSpan, gold does not contain any nullSpan edges
//    int tpNullSpan = 0, fpNullSpan = 0;

    Counts<Relation> tpByRel, fpByRel;
//    Counts<Relation> tpByRelNS; // FPs for nullSpan are not counted against

    public Perf() {
      tpByRel = new Counts<>();
      fpByRel = new Counts<>();
//      tpByRelNS = new Counts<>();
    }

    public void add(HypEdge e) {
      HashableHypEdge he = new HashableHypEdge(e);
      if (e.getRelation().getName().startsWith("srl")
          || e.getRelation().getName().startsWith("argument")) {
        System.currentTimeMillis();
      }
      assert seen.add(he);
      Lossless y = lossless(he);
      switch (y) {
      case NO:
        fp++;
        fpByRel.increment(e.getRelation());
        break;
      case YES:
        tp++;
        tpByRel.increment(e.getRelation());
        break;
      default:
        // We can ignore NIL-facts (pruning actions)
        break;
      }
//      if (contains(he)) {
//        if (seen.add(he)) {
//          tp++;
//          tpByRel.increment(e.getRelation());
//        }
//        return true;
//      } else {
//        if (seen.add(he)) {
//          fp++;
//          fpByRel.increment(e.getRelation());
//        }
//        return false;
//      }
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
