package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiFunction;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.State.ArgVal;
import edu.jhu.prim.tuple.Pair;

public class Agenda {
  public static boolean DEBUG = false;

  // Measured about 1.5x slow-down (on pure add-remove benchmark) when this is enabled.
  public static final boolean FINE_VIEW = true;

  // ei == "edge index" in the heap
  // e == "edge"
  private HypEdge[] heap1;              // ei2e
  private Adjoints[] heap2;
  private int top;                      // aka size
  private Map<HypNode, BitSet> n2ei;    // node adjacency matrix, may contain old nodes as keys
  private Map<HypEdge, Integer> e2i;    // location of edges in heap

  // Other indices
  private Map<State.ArgVal, LinkedHashSet<HypEdge>> fineView;

  // Priority function
  private BiFunction<HypEdge, Adjoints, Double> priority;

  public Agenda(BiFunction<HypEdge, Adjoints, Double> priority) {
    this.top = 0;
    int initSize = 16;
    this.heap1 = new HypEdge[initSize];
    this.heap2 = new Adjoints[initSize];
    this.n2ei = new HashMap<>();
    this.e2i = new HashMap<>();
    this.fineView = new HashMap<>();
    this.priority = priority;
  }

  public Agenda duplicate() {
    Agenda c = new Agenda(priority);
    c.heap1 = Arrays.copyOf(heap1, heap1.length);
    c.heap2 = Arrays.copyOf(heap2, heap2.length);
    c.top = top;
    c.n2ei.putAll(n2ei);
    c.e2i.putAll(e2i);
    for (Entry<ArgVal, LinkedHashSet<HypEdge>> x : fineView.entrySet())
      c.fineView.put(x.getKey(), new LinkedHashSet<>(x.getValue()));
    return c;
  }

  public void clear() {
    this.top = 0;
    int initSize = 16;
    this.heap1 = new HypEdge[initSize];
    this.heap2 = new Adjoints[initSize];
    this.n2ei = new HashMap<>();
    this.e2i = new HashMap<>();
    this.fineView = new HashMap<>();
  }

  public List<HypEdge> getContentsInNoParticularOrder() {
    List<HypEdge> l = new ArrayList<>();
    for (int i = 0; i < top; i++)
      if (heap1[i] != null)
        l.add(heap1[i]);
    assert l.size() == this.size();
    return l;
  }

  /**
   * Removes node->[edge index] entries in n2ei where the node is orphaned (no
   * edges are adjacent because they have been removed).
   */
  public void cleanN2Ei() {
    List<HypNode> l = new ArrayList<>();
    for (Map.Entry<HypNode, BitSet> x : n2ei.entrySet()) {
      if (x.getValue().cardinality() == 0)
        l.add(x.getKey());
    }
    for (HypNode n : l)
      n2ei.remove(n);
  }
  /** Don't use this, its slow */
  public Set<HypNode> dbgNodeSet1() {
    cleanN2Ei();
    return n2ei.keySet();
  }
  public Set<HypNode> dbgNodeSet2() {
    Set<HypNode> s = new HashSet<>();
    for (int i = 0; i < top; i++) {
      HypEdge e = heap1[i];
      for (HypNode n : e.getNeighbors())
        s.add(n);
    }
    return s;
  }

  /**
   * Return all edges on the agenda which have type rel and have argPos matching
   * equal to arg.
   */
  public Iterable<HypEdge> match(int argPos, Relation rel, HypNode arg) {
    Iterable<HypEdge> r = fineView.get(new State.ArgVal(argPos, rel, arg));
//    return r == null ? Collections.emptyList() : r;
    List<HypEdge> eager = new ArrayList<>();
    if (r != null)
      for (HypEdge e : r)
        eager.add(e);
    return eager;
  }

  public List<HypEdge> adjacent(HypNode n) {
    BitSet bs = n2ei.get(n);
    if (bs == null)
      return Collections.emptyList();
    List<HypEdge> el = new ArrayList<>();
    for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
      assert i < top;
      HypEdge e = heap1[i];
      assert e != null;
      el.add(e);
    }
    return el;
  }

  /**
   * Returns a list of {@link HypEdge}s which are adjacent to both nodes.
   */
  public List<HypEdge> adjacent(HypNode n1, HypNode n2) {
    BitSet bs1 = n2ei.get(n1);
    if (bs1 == null)
      return Collections.emptyList();
    BitSet bs2 = n2ei.get(n2);
    if (bs2 == null)
      return Collections.emptyList();
    List<HypEdge> el = new ArrayList<>();
    for (int i = bs1.nextSetBit(0); i >= 0; i = bs1.nextSetBit(i + 1)) {
      assert i < top;
      if (bs2.get(i)) {
        HypEdge e = heap1[i];
        assert e != null;
        el.add(e);
      }
    }
    return el;
  }

  public Adjoints getScore(HypEdge e) {
    int i = e2i.get(e);
    assert heap1[i] == e;
    assert heap2[i] != null;
    return heap2[i];
  }

  public void remove(HypEdge e) {
//    if (DEBUG) {
//      Log.info("before remove " + e);
//      dbgShowScores();
//      System.out.println();
//    }

    int i = e2i.get(e);
    removeAt(i);

//    if (DEBUG) {
//      Log.info("after remove " + e);
//      dbgShowScores();
//      System.out.println();
//    }
  }

  private void removeAt(int i) {
    assert i < top;
    int t = --top;
    moveAndFree(t, i);
    if (parentInvariantSatisfied(i))
      siftDown(i);
    else
      siftUp(i);
  }

  public boolean parentInvariantSatisfied() {
    for (int i = 0; i < top; i++)
      if (!parentInvariantSatisfied(i))
        return false;
    return true;
  }
  public boolean parentInvariantSatisfied(int i) {
    if (i == 0)
      return true;
    if (i == top)
      return true;
    int parent = (i - 1) >>> 1;
//    return heap2[parent].forwards() >= heap2[i].forwards();
    return priority.apply(heap1[parent], heap2[parent])
        >= priority.apply(heap1[i], heap2[i]);
  }

  public void add(HypEdge edge, Adjoints score) {
    if (DEBUG) {
      Log.info("adding " + edge + " with score " + score);
    }
    if (edge == null)
      throw new IllegalArgumentException();
    if (score == null)
      throw new IllegalArgumentException();
    int t = top++;
    if (t == heap1.length)
      grow();
    addEdgeToFineView(edge);
    heap1[t] = edge;
    heap2[t] = score;
    e2i.put(edge, t);
    n2eiSet(t, edge, true);
    siftUp(t);

//    if (DEBUG) {
//      Log.info("just added " + edge + " with score " + score);
//      for (int i = 0; i < edge.getNumTails(); i++) {
//        HypNode n = edge.getTail(i);
//        System.out.println("Adjacent" + n + "\t" + adjacent(n));
//      }
//      System.out.println();
//    }
  }

  public int size() {
    return top;
  }
  public int capacity() {
    return heap1.length;
  }

  public void dbgShowScores() {
    Log.info("Agenda has " + top + " items:");
    for (int i = 0; i < top; i++) {
//      System.out.printf("%d\t%+.4f\t%s\n", i, heap2[i].forwards(), heap1[i]);
      System.out.printf("%d\t%+.4f\t%s\n", i, priority.apply(heap1[i], heap2[i]), heap1[i]);
    }
  }

  public Adjoints peekScore() {
    assert top > 0;
    return heap2[0];
  }

  public HypEdge peek() {
    assert top > 0;
    return heap1[0];
  }

  public Pair<HypEdge, Adjoints> peekBoth() {
    return new Pair<>(heap1[0], heap2[0]);
  }

  public HypEdge pop() {
    assert top > 0;
    HypEdge e = heap1[0];
    moveAndFree(top-1, 0);
    top--;
    if (top > 0)
      siftDown(0);
    return e;
  }

  public Pair<HypEdge, Adjoints> popBoth() {
    assert top > 0;
    HypEdge e = heap1[0];
    Adjoints a = heap2[0];
    moveAndFree(top-1, 0);
    top--;
    if (top > 0)
      siftDown(0);
    return new Pair<>(e, a);
  }

  public void siftDown(int i) {
    if (i >= top)
      return;
//    double sc = heap2[i].forwards();
    double sc = priority.apply(heap1[i], heap2[i]);
    int lc = (i << 1) + 1;
    int rc = lc + 1;
//    double lcScore = lc < top ? heap2[lc].forwards() : sc;
//    double rcScore = rc < top ? heap2[rc].forwards() : sc;
    double lcScore = lc < top ? priority.apply(heap1[lc], heap2[lc]) : sc;
    double rcScore = rc < top ? priority.apply(heap1[rc], heap2[rc]) : sc;
    if (sc >= lcScore && sc >= rcScore)
      return;
    if (lcScore > rcScore) {
      swap(i, lc);
      siftDown(lc);
    } else {
      swap(i, rc);
      siftDown(rc);
    }
  }

  public void siftUp(int i) {
    assert i < top && i >= 0;
    while (i > 0) {
      int parent = (i - 1) >>> 1;
//      if (heap2[parent].forwards() >= heap2[i].forwards())
      if (priority.apply(heap1[parent], heap2[parent]) >= priority.apply(heap1[i], heap2[i]))
        break;
      swap(i, parent);
      i = parent;
    }
  }

  private void swap(int i, int j) {
//    Log.info("i=" + i + " j=" + j);
    assert i != j;
    HypEdge ei = heap1[i];
    HypEdge ej = heap1[j];
    n2eiSet(i, ei, false);
    n2eiSet(j, ej, false);
    n2eiSet(j, ei, true);
    n2eiSet(i, ej, true);
    e2i.put(ei, j);
    e2i.put(ej, i);
    heap1[i] = ej;
    heap1[j] = ei;
    Adjoints ai = heap2[i];
    Adjoints aj = heap2[j];
    assert ai != null;
    assert aj != null;
    heap2[i] = aj;
    heap2[j] = ai;
  }

  /**
   * Moves item at from (first arg) to to (second arg).
   * to items are over-written and from items are assigned to null.
   */
  private void moveAndFree(int from, int to) {
//    if (DEBUG)
//      Log.info("from=" + from + " to=" + to);
    if (from == to) {
      free(from);
      return;
    }
    removeEdgeFromFineView(heap1[to]);
    n2eiSet(to, heap1[to], false);
    n2eiSet(to, heap1[from], true);
    n2eiSet(from, heap1[from], false);
    e2i.put(heap1[from], to);
    e2i.remove(heap1[to]);
    heap1[to] = heap1[from];
    heap1[from] = null;
    heap2[to] = heap2[from];
    heap2[from] = null;
  }

  private void free(int i) {
    removeEdgeFromFineView(heap1[i]);
    n2eiSet(i, heap1[i], false);
    e2i.remove(heap1[i]);
    heap1[i] = null;
    heap2[i] = null;
  }

  private void addEdgeToFineView(HypEdge e) {
    if (!FINE_VIEW) return;
    boolean r;
    Relation rel = e.getRelation();
    int n = rel.getNumArgs();
    for (int j = 0; j < n; j++) {
      HypNode arg = e.getTail(j);
      State.ArgVal a = new State.ArgVal(j, rel, arg);
      LinkedHashSet<HypEdge> s = fineView.get(a);
      if (s == null) {
        s = new LinkedHashSet<>();
        fineView.put(a, s);
      }
      r = s.add(e);
      assert r;
    }
    State.ArgVal a = new State.ArgVal(State.HEAD_ARG_POS, rel, e.getHead());
    LinkedHashSet<HypEdge> s = fineView.get(a);
    if (s == null) {
      s = new LinkedHashSet<>();
      fineView.put(a, s);
    }
    r = s.add(e);
    assert r;
  }

  private void removeEdgeFromFineView(HypEdge e) {
    if (!FINE_VIEW) return;
    boolean r;
    Relation rel = e.getRelation();
    int n = rel.getNumArgs();
    for (int j = 0; j < n; j++) {
      HypNode arg = e.getTail(j);
      State.ArgVal a = new State.ArgVal(j, rel, arg);
      r = fineView.get(a).remove(e);
      assert r;
    }
    State.ArgVal a = new State.ArgVal(State.HEAD_ARG_POS, rel, e.getHead());
    r = fineView.get(a).remove(e);
    assert r;
  }

  /**
   * Looks up the edge-index adjacency list (BitSet) for each node neighboring e,
   * and sets the i^{th} index (an edge index) to b.
   */
  private void n2eiSet(int i, HypEdge e, boolean b) {
    if (e == null)
      throw new IllegalArgumentException();
//    if (DEBUG)
//      Log.info("setting " + e.getHead() + " (heapIndex, " + i + ") to " + b);
    n2eiGetOrInit(e.getHead()).set(i, b);
    int n = e.getNumTails();
    for (int j = 0; j < n; j++) {
//      if (DEBUG)
//        Log.info("setting " + e.getTail(j) + " (heapIndex, " + i + ") to " + b);
      n2eiGetOrInit(e.getTail(j)).set(i, b);
    }
  }
  private BitSet n2eiGetOrInit(HypNode n) {
    BitSet bs = n2ei.get(n);
    if (bs == null) {
      bs = new BitSet();
      n2ei.put(n, bs);
    }
    return bs;
  }

  private void grow() {
    int newSize = (int) (heap1.length * 1.6 + 2);
    heap1 = Arrays.copyOf(heap1, newSize);
    heap2 = Arrays.copyOf(heap2, newSize);
  }
}