package edu.jhu.hlt.uberts;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

  private Map<HypNode, LL<HypEdge>[]> primaryView;
  private Map<ArgVal, LL<HypEdge>> fineView;
  private Map<Relation, LL<HypEdge>> relView;
  private List<HypEdge> edges;
  private MultiTimer timer;

  public State() {
    this.primaryView = new HashMap<>();
    this.fineView = new HashMap<>();
    this.relView = new HashMap<>();
    this.edges = new ArrayList<>();
    this.timer = new MultiTimer();
    this.timer.put("clearNonSchema", new Timer("clearNonSchema", 30, true));
  }

  public void clear() {
    primaryView.clear();
    fineView.clear();
    relView.clear();
    edges.size();
  }
  public void clearNonSchema() {
    if (DEBUG) {
      Log.info("edges=" + edges.size() + " nodes=" + fineView.size() + " args=" + primaryView.size());
    }
    timer.start("clearNonSchema");
    List<HypEdge> temp = edges;
    primaryView.clear();
    fineView.clear();
    relView.clear();
    edges = new ArrayList<>();
    for (HypEdge e : temp) {
      if (e instanceof HypEdge.WithProps &&
          ((HypEdge.WithProps)e).hasProperty(HypEdge.IS_SCHEMA)) {
        add(e);
      }
    }
    timer.stop("clearNonSchema");
  }

  public int getNumNodes() {
    return primaryView.size();
  }
  public int getNumEdges() {
    return edges.size();
  }

  public void dbgShowEdges() {
    System.out.println("State with " + primaryView.size() + " nodes:");
    try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(System.out))) {
      writeEdges(w);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    System.out.println();
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
    List<HypEdge> el = new ArrayList<>(edges);
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
    edges.add(e);
    add(HEAD_ARG_POS, e.getHead(), e);
    int n = e.getNumTails();
    for (int i = 0; i < n; i++)
      add(i, e.getTail(i), e);

    Relation key = e.getRelation();
    relView.put(key, new LL<>(e, relView.get(key)));

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
    LL<HypEdge>[] es = primaryView.get(n);
    if (es == null) {
      es = new LL[MAX_ARGS];
      primaryView.put(n, es);
    }
    // Unique values?
    if (CHECK_UNIQ) {
      for (LL<HypEdge> cur = es[argPos]; cur != null; cur = cur.next)
        assert !cur.item.equals(e);
    }
    es[argPos] = new LL<>(e, es[argPos]);

    ArgVal key2 = new ArgVal(argPos, e.getRelation(), n);
    LL<HypEdge> es2 = fineView.get(key2);
    // Unique values?
    if (CHECK_UNIQ) {
      for (LL<HypEdge> cur = es2; cur != null; cur = cur.next)
        assert !cur.item.equals(e);
    }
    fineView.put(key2, new LL<>(e, es2));

    if (DEBUG) {
      int p = primaryView.size();
      int f = fineView.size();
      int z = CHECK_UNIQ ? 50000 : 500000;
      if ((p+f) % z == 0 && es2 == null)
        Log.info("nodes=" + f + " args=" + p + " curFact=" + e);
    }
  }

  /** May return null */
  public LL<HypEdge> match(int argPos, Relation rel, HypNode arg) {
    return fineView.get(new ArgVal(argPos, rel, arg));
  }

  /** May return null */
  public HypEdge match1(int argPos, Relation rel, HypNode arg) {
    LL<HypEdge> es = match(argPos, rel, arg);
    assert es != null && es.next == null;
    return es.item;
  }

  public List<HypEdge> neighbors(int argPos, HypNode n) {
    List<HypEdge> el = new ArrayList<>();
    LL<HypEdge>[] allPos = primaryView.get(n);
    for (int i = 0; i < allPos.length; i++)
      for (LL<HypEdge> cur = allPos[i]; cur != null; cur = cur.next)
        el.add(cur.item);
    return el;
  }

  public LL<HypEdge> match2(Relation rel) {
    assert rel != null;
    return relView.get(rel);
  }

  /**
   * Returns neighbors along with the argument index that hold between a HypNode
   * and a HypEdge (it may be that this:HypNode,neighbors:[HypEdge] or that
   * this:HypEdge,neighbors:[HypNode], but you can always talk about what position
   * some HypNode is w.r.t. a HypEdge (i.e. an edge)).
   */
  public List<StateEdge> neighbors2(HNode node) {
    List<StateEdge> a = new ArrayList<>();
    if (node.isLeft()) {
      HypNode n = node.getLeft();
//      if (n.getNodeType().getName().equals("tokenIndex"))
//        Log.info("check this");
      LL<HypEdge>[] allArgs = primaryView.get(n);
      for (int i = 0; i < allArgs.length; i++)
        for (LL<HypEdge> cur = allArgs[i]; cur != null; cur = cur.next)
          a.add(new StateEdge(n, cur.item, i, true));
    } else {
      HypEdge e = node.getRight();
      a.add(new StateEdge(e.getHead(), e, HEAD_ARG_POS, false));
      for (int i = 0; i < e.getNumTails(); i++)
        a.add(new StateEdge(e.getTail(i), e, i, false));
    }
    return a;
  }

  /**
   * @deprecated Loses argPos information!
   */
  public List<HNode> neighbors(HNode node) {
    List<HNode> a = new ArrayList<>();
    if (node.isLeft()) {
      HypNode n = node.getLeft();
      LL<HypEdge>[] allArgs = primaryView.get(n);
      for (int i = 0; i < allArgs.length; i++)
        for (LL<HypEdge> cur = allArgs[i]; cur != null; cur = cur.next)
          a.add(new HNode(cur.item));
    } else {
      HypEdge e = node.getRight();
      for (HypNode n : e.getNeighbors())
        a.add(new HNode(n));
    }
    return a;
  }
}