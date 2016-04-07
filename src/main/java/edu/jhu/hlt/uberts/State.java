package edu.jhu.hlt.uberts;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
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
  public static final int MAX_ARGS = 12;    // how many args allowed for a Relation
  public static final int HEAD_ARG_POS = MAX_ARGS - 1;

  /** Another way to index into edges: lookup by (relation,argument) */
  public static class Arg {
    public final Relation rel;
    public final HypNode arg;
    public final int argPos;
    public final int hc;
    public Arg(int argPos, Relation rel, HypNode arg) {
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
      if (other instanceof Arg) {
        Arg a = (Arg) other;
        return argPos == a.argPos && rel == a.rel && arg == a.arg;
      }
      return false;
    }
  }

  private Map<HypNode, LL<HypEdge>[]> primaryView;
  private Map<Arg, LL<HypEdge>> fineView;

  public State() {
    this.primaryView = new HashMap<>();
    this.fineView = new HashMap<>();
  }

  public void clear() {
    primaryView.clear();
    fineView.clear();
  }
  public void clearNonSchema() {
    // Find the Schema edges
    List<HypEdge.Schema> se = new ArrayList<>();
    for (LL<HypEdge> le : fineView.values()) {
      for (LL<HypEdge> cur = le; cur != null; cur = cur.next) {
        HypEdge e = cur.item;
        if (e instanceof HypEdge.Schema)
          se.add((HypEdge.Schema) e);
      }
    }
    // Remove everything
    clear();
    // Add back the schema edges
    for (HypEdge.Schema e : se)
      add(e);
  }

  public int getNumNodes() {
    return primaryView.size();
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
    Set<HypEdge> es = new HashSet<>();
    List<HypEdge> el = new ArrayList<>();
    for (LL<HypEdge>[] ll : primaryView.values()) {
      for (int i = 0; i < ll.length; i++) {
        LL<HypEdge> l = ll[i];
        for (LL<HypEdge> cur = l; cur != null; cur = cur.next) {
          HypEdge e = cur.item;
          if (skip != null && skip.contains(e.getRelation()))
            continue;
          if (es.add(e))
            el.add(e);
        }
      }
    }
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
    add(HEAD_ARG_POS, e.getHead(), e);
    int n = e.getNumTails();
    for (int i = 0; i < n; i++)
      add(i, e.getTail(i), e);

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
    es[argPos] = new LL<>(e, es[argPos]);

    Arg key2 = new Arg(argPos, e.getRelation(), n);
    LL<HypEdge> es2 = fineView.get(key2);
    fineView.put(key2, new LL<>(e, es2));
  }

  /** May return null */
  public LL<HypEdge> match(int argPos, Relation rel, HypNode arg) {
    return fineView.get(new Arg(argPos, rel, arg));
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