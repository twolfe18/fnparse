package edu.jhu.hlt.uberts;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.hash.Hash;

/**
 * A hyper-graph representing joint NLP predictions.
 *
 * @see HypEdge, HypNode
 *
 * @author travis
 */
public class State {

  public static boolean DEBUG = false;
  public static boolean CHECK_UNIQ = false;
  public static final int MAX_ARGS = 12;    // how many args allowed for a Relation
  public static final int HEAD_ARG_POS = MAX_ARGS - 1;

  /** Another way to index into edges: lookup by (relation,argument) */
  private static class ArgVal {
    public final Relation rel;
    public final HypNode arg;
    public final int argPos;
    public final int hc;
    public ArgVal(int argPos, Relation rel, HypNode arg) {
      if (rel == null)
        throw new IllegalArgumentException();
      if (arg == null)
        throw new IllegalArgumentException();
      this.argPos = argPos;
      this.rel = rel;
      this.arg = arg;
      this.hc = Hash.mix(argPos, rel.hashCode(), arg.hashCode());
    }
    @Override
    public int hashCode() { return hc; }
    @Override
    public boolean equals(Object other) {
      if (other instanceof ArgVal) {
        ArgVal a = (ArgVal) other;
        return argPos == a.argPos && rel == a.rel && arg == a.arg;
      }
      return false;
    }
  }

  public static class Split extends State {
    private State regularEdges;
    private State schemaEdges;

    public Split() {
      this.regularEdges = new State();
      this.schemaEdges = new State();
    }

    @Override
    public void clear() {
      regularEdges.clear();
      schemaEdges.clear();
    }
    @Override
    public void clearNonSchema() {
      regularEdges.clear();
    }
    @Override
    public int getNumNodes() {
      throw new RuntimeException("some nodes will overlap");
    }
    @Override
    public int getNumEdges() {
      return regularEdges.getNumEdges() + schemaEdges.getNumEdges();
    }
    /**
     * Writes out {@link HypEdge}s in the same format as {@link
     * Uberts#readRelData(java.io.BufferedReader)}, only using "yhat" for the
     * first column. Does not write out relation definitions.
     */
    public void writeEdges(BufferedWriter w) throws IOException {
      regularEdges.writeEdges(w);
      schemaEdges.writeEdges(w);
    }
    public void writeEdges(BufferedWriter w, Set<Relation> skip) throws IOException {
      regularEdges.writeEdges(w, skip);
      schemaEdges.writeEdges(w, skip);
    }
    public void add(HypEdge e) {
      if (e instanceof HypEdge.WithProps
          && ((HypEdge.WithProps) e).hasProperty(HypEdge.IS_SCHEMA)) {
        schemaEdges.add(e);
      } else {
        regularEdges.add(e);
      }
    }
    /** May return null */
    @Override
    public LL<HypEdge> match(int argPos, Relation rel, HypNode arg) {
      LL<HypEdge> r = regularEdges.match(argPos, rel, arg);
      LL<HypEdge> s = schemaEdges.match(argPos, rel, arg);
      if (s == null)
        return r;
      // I'm assuming s is longer than r
      while (r != null) {
        s = new LL<>(r.item, s);
        r = r.next;
      }
      return s;
    }

    /** May return null */
    @Override
    public HypEdge match1(int argPos, Relation rel, HypNode arg) {
      LL<HypEdge> es = regularEdges.match(argPos, rel, arg);
      if (es != null) {
        assert es.next == null;
        return es.item;
      }
      es = schemaEdges.match(argPos, rel, arg);
      assert es != null && es.next == null;
      return es.item;
    }
    @Override
    public List<HypEdge> neighbors(int argPos, HypNode n) {
      List<HypEdge> el = new ArrayList<>();
      el.addAll(regularEdges.neighbors(argPos, n));
      el.addAll(schemaEdges.neighbors(argPos, n));
      return el;
    }
    @Override
    public LL<HypEdge> match2(Relation rel) {
      assert rel != null;
      LL<HypEdge> r = regularEdges.match2(rel);
      LL<HypEdge> s = schemaEdges.match2(rel);
      if (s == null)
        return r;
      // I'm assuming s is longer than r
      while (r != null) {
        s = new LL<>(r.item, s);
        r = r.next;
      }
      return s;
    }
    /**
     * Returns neighbors along with the argument index that hold between a HypNode
     * and a HypEdge (it may be that this:HypNode,neighbors:[HypEdge] or that
     * this:HypEdge,neighbors:[HypNode], but you can always talk about what position
     * some HypNode is w.r.t. a HypEdge (i.e. an edge)).
     */
    @Override
    public List<StateEdge> neighbors2(HNode node) {
      List<StateEdge> l = new ArrayList<>();

      if (node.isEdge()) {
        // For HypEdge -> HypNode edges, we can generate these by looking at
        // just the HypEdge (arguments are known). Does not need to look at any
        // edge indices/views.
        HypEdge e = node.getRight();
        l.add(new StateEdge(e.getHead(), e, HEAD_ARG_POS, false));
        for (int i = 0; i < e.getNumTails(); i++)
          l.add(new StateEdge(e.getTail(i), e, i, false));
        return l;
      }

//      System.out.println("neighbors2 regularEdges:");
      List<StateEdge> l1 = regularEdges.neighbors2(node);
      l.addAll(l1);

//      System.out.println("neighbors2 schemaEdges:");
      List<StateEdge> l2 = schemaEdges.neighbors2(node);
      l.addAll(l2);

      // DEBUGGING
      Set<StateEdge> uniq = new HashSet<>();
      for (StateEdge se : l)
        assert uniq.add(se) : "duplicate edge: " + se;

      return l;
    }
  }

  private Map<HypNode, LL<HypEdge>[]> primaryViewReg;
  private Map<ArgVal, LL<HypEdge>> fineViewReg;
  private Map<Relation, LL<HypEdge>> relViewReg;
  private List<HypEdge> edgesReg;
  private MultiTimer timer;

  public State() {
    this.primaryViewReg = new HashMap<>();
    this.fineViewReg = new HashMap<>();
    this.relViewReg = new HashMap<>();
    this.edgesReg = new ArrayList<>();
    this.timer = new MultiTimer();
    this.timer.put("clearNonSchema", new Timer("clearNonSchema", 30, true));
  }

  public void clear() {
    primaryViewReg.clear();
    fineViewReg.clear();
    relViewReg.clear();
    edgesReg.size();
  }

  public void clearNonSchema() {
    if (DEBUG) {
      Log.info("edges=" + edgesReg.size() + " nodes=" + fineViewReg.size() + " args=" + primaryViewReg.size());
    }
    timer.start("clearNonSchema");
    List<HypEdge> temp = edgesReg;
    primaryViewReg.clear();
    fineViewReg.clear();
    relViewReg.clear();
    edgesReg = new ArrayList<>();
    for (HypEdge e : temp) {
      if (e instanceof HypEdge.WithProps &&
          ((HypEdge.WithProps)e).hasProperty(HypEdge.IS_SCHEMA)) {
        add(e);
      }
    }
    timer.stop("clearNonSchema");
  }

  public int getNumNodes() {
    return primaryViewReg.size();
  }
  public int getNumEdges() {
    return edgesReg.size();
  }

  /**
   * Writes out {@link HypEdge}s in the same format as {@link
   * Uberts#readRelData(java.io.BufferedReader)}, only using "yhat" for the
   * first column. Does not write out relation definitions.
   */
  public void writeEdges(BufferedWriter w) throws IOException {
    writeEdges(w, Collections.emptySet());
  }
  public void writeEdges(BufferedWriter w, Set<Relation> skip) throws IOException {
    List<HypEdge> el = new ArrayList<>(edgesReg);
    try {
      Collections.sort(el, HypEdge.BY_RELATION_THEN_TAIL);
    } catch (ClassCastException cce) {
      Log.warn("couldn't sort HypEdges because: " + cce.getMessage());
    }
    for (HypEdge e : el) {
      w.write(e.getRelFileString("yhat"));
      w.newLine();
    }
  }

  public void add(HypEdge e) {
    edgesReg.add(e);
    add(HEAD_ARG_POS, e.getHead(), e);
    int n = e.getNumTails();
    for (int i = 0; i < n; i++)
      add(i, e.getTail(i), e);

    Relation key = e.getRelation();
    relViewReg.put(key, new LL<>(e, relViewReg.get(key)));

    if (DEBUG) {
      Log.info("just added to State: " + e);
      Log.info("Adjacent(HEAD=" + HEAD_ARG_POS + "," + e.getHead() + ")\t" + neighbors(HEAD_ARG_POS, e.getHead()));
      for (int i = 0; i < e.getNumTails(); i++) {
        HypNode x = e.getTail(i);
        Log.info("tail[" + i + "]=" + x + "  adjacent=" + neighbors(i, x));
      }
      System.out.println();
    }
  }

  @SuppressWarnings("unchecked")
  private void add(int argPos, HypNode n, HypEdge e) {
    LL<HypEdge>[] es = primaryViewReg.get(n);
    if (es == null) {
      es = new LL[MAX_ARGS];
      primaryViewReg.put(n, es);
    }
    // Unique values?
    if (CHECK_UNIQ) {
      for (LL<HypEdge> cur = es[argPos]; cur != null; cur = cur.next)
        assert !cur.item.equals(e);
    }
    es[argPos] = new LL<>(e, es[argPos]);

    ArgVal key2 = new ArgVal(argPos, e.getRelation(), n);
    LL<HypEdge> es2 = fineViewReg.get(key2);
    // Unique values?
    if (CHECK_UNIQ) {
      for (LL<HypEdge> cur = es2; cur != null; cur = cur.next)
        assert !cur.item.equals(e);
    }
    fineViewReg.put(key2, new LL<>(e, es2));

    if (DEBUG) {
      int p = primaryViewReg.size();
      int f = fineViewReg.size();
      int z = CHECK_UNIQ ? 50000 : 500000;
      if ((p+f) % z == 0 && es2 == null)
        Log.info("nodes=" + f + " args=" + p + " curFact=" + e);
    }
  }

  /** May return null */
  public LL<HypEdge> match(int argPos, Relation rel, HypNode arg) {
    return fineViewReg.get(new ArgVal(argPos, rel, arg));
  }

  /** May return null */
  public HypEdge match1(int argPos, Relation rel, HypNode arg) {
    LL<HypEdge> es = match(argPos, rel, arg);
    assert es != null : "[match1] but there are no matches";
    assert es.next == null : "[match1] but there is more than one";
    return es.item;
  }

  public List<HypEdge> neighbors(int argPos, HypNode n) {
    List<HypEdge> el = new ArrayList<>();
    LL<HypEdge>[] allPos = primaryViewReg.get(n);
    for (int i = 0; i < allPos.length; i++)
      for (LL<HypEdge> cur = allPos[i]; cur != null; cur = cur.next)
        el.add(cur.item);
    return el;
  }

  public LL<HypEdge> match2(Relation rel) {
    assert rel != null;
    return relViewReg.get(rel);
  }

  /**
   * Returns neighbors along with the argument index that hold between a HypNode
   * and a HypEdge (it may be that this:HypNode,neighbors:[HypEdge] or that
   * this:HypEdge,neighbors:[HypNode], but you can always talk about what position
   * some HypNode is w.r.t. a HypEdge (i.e. an edge)).
   */
  public List<StateEdge> neighbors2(HNode node) {
    List<StateEdge> a = new ArrayList<>();
    if (node.isNode()) {
      HypNode n = node.getNode();
      LL<HypEdge>[] allArgs = primaryViewReg.get(n);
      for (int i = 0; allArgs != null && i < allArgs.length; i++)
        for (LL<HypEdge> cur = allArgs[i]; cur != null; cur = cur.next)
          a.add(new StateEdge(n, cur.item, i, true));
    } else {
      // For HypEdge -> HypNode edges, we can generate these by looking at
      // just the HypEdge (arguments are known). Does not need to look at any
      // edge indices/views.
      HypEdge e = node.getRight();
      a.add(new StateEdge(e.getHead(), e, HEAD_ARG_POS, false));
      for (int i = 0; i < e.getNumTails(); i++)
        a.add(new StateEdge(e.getTail(i), e, i, false));
    }

    // DEBUGGING:
    Set<StateEdge> uniq = new HashSet<>();
//    Set<String> uniq2 = new HashSet<>();
    for (StateEdge se : a) {
//      System.out.println("neighbors2 checking: " + se);
      assert uniq.add(se);
//      assert uniq2.add(se.toString()) : se.toString();
    }
//    System.out.println("neighbors2 uniq.size=: " + uniq.size());
//    System.out.println("neighbors2 uniq2.size=: " + uniq2.size());

    return a;
  }
}