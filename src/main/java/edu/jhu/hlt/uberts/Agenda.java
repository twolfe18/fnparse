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
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.State.ArgVal;
import edu.jhu.prim.tuple.Pair;

public class Agenda {
  public static boolean DEBUG = false;

  // Measured about 1.5x slow-down (on pure add-remove benchmark) when this is enabled.
  public static final boolean FINE_VIEW = true;

  public static class AgendaItem implements Comparable<AgendaItem> {
    public final HypEdge edge;
    public final Adjoints score;
    public final double priority;
    private HashableHypEdge hashableEdge;

    public AgendaItem(HypEdge edge, Adjoints score, double priority) {
      assert Double.isFinite(priority) : "priority=" + priority;
      assert !Double.isNaN(priority) : "priority=" + priority;
      this.edge = edge;
      this.score = Adjoints.cacheIfNeeded(score);
      this.priority = priority;
    }

    public HashableHypEdge getHashableEdge() {
      if (hashableEdge == null)
        hashableEdge = new HashableHypEdge(edge);
      return hashableEdge;
    }

    @Override
    public int compareTo(AgendaItem o) {
      if (priority < o.priority) return -1;
      if (priority > o.priority) return +1;
      return 0;
    }

    @Override
    public String toString() {
      return String.format("(AI %s p=%+.2f s=%s)", edge, priority, StringUtils.trunc(score, 200));
    }

    public LabledAgendaItem withLabel(boolean y) {
      return new LabledAgendaItem(edge, score, priority, y);
    }
  }

  public static class LabledAgendaItem extends AgendaItem {
    public final boolean label;
    public LabledAgendaItem(HypEdge edge, Adjoints score, double priority, boolean label) {
      super(edge, score, priority);
      this.label = label;
    }
    @Override
    public String toString() {
      return String.format("(AI %s y=%s p=%+.2f s=%s)", edge, label, priority, StringUtils.trunc(score, 200));
    }
  }

  // ei == "edge index" in the heap
  // e == "edge"
  private AgendaItem[] heap;            // ei2e
  private int top;                      // aka size
  private Map<HypNode, BitSet> n2ei;    // node adjacency matrix, may contain old nodes as keys
  private Map<HypEdge, Integer> e2i;    // location of edges in heap
  // e2i does not merge different HypEdges which are equivalent
  private Set<HashableHypEdge> uniq;    // supports contains(e)

  // Other indices
  private Map<State.ArgVal, LinkedHashSet<HypEdge>> fineView;

  // Priority function
  private BiFunction<HypEdge, Adjoints, Double> priority;

  public Agenda(BiFunction<HypEdge, Adjoints, Double> priority) {
    this.top = 0;
    int initSize = 16;
    this.heap = new AgendaItem[initSize];
    this.n2ei = new HashMap<>();
    this.e2i = new HashMap<>();
    this.fineView = new HashMap<>();
    this.priority = priority;
    this.uniq = new HashSet<>();
  }

  public Agenda duplicate() {
    Agenda c = new Agenda(priority);
    c.heap = Arrays.copyOf(heap, heap.length);
    c.top = top;
    c.n2ei.putAll(n2ei);
    c.e2i.putAll(e2i);
    c.uniq.addAll(uniq);
    for (Entry<ArgVal, LinkedHashSet<HypEdge>> x : fineView.entrySet())
      c.fineView.put(x.getKey(), new LinkedHashSet<>(x.getValue()));
    return c;
  }

  public void clear() {
    this.top = 0;
    Arrays.fill(heap, null);
    this.n2ei = new HashMap<>();
    this.e2i = new HashMap<>();
    this.fineView = new HashMap<>();
    this.uniq.clear();
  }

  public List<AgendaItem> getContentsInNoParticularOrder() {
    List<AgendaItem> l = new ArrayList<>();
    for (int i = 0; i < top; i++)
      if (heap[i] != null)
        l.add(heap[i]);
    assert l.size() == this.size();
    return l;
  }

  public boolean contains(HashableHypEdge e) {
//    return e2i.containsKey(e);
    return uniq.contains(e);
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
      HypEdge e = heap[i].edge;
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
      HypEdge e = heap[i].edge;
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
        HypEdge e = heap[i].edge;
        assert e != null;
        el.add(e);
      }
    }
    return el;
  }

  /**
   * ONLY works if you have a {@link HypEdge} which you got from this agenda,
   * does not use {@link HashableHypEdge}.
   */
  public Adjoints getScore(HypEdge e) {
    int i = e2i.get(e);
    assert heap[i].edge == e;
    return heap[i].score;
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
    return heap[parent].compareTo(heap[i]) >= 0;
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
    if (t == heap.length)
      grow();
    addEdgeToFineView(edge);
    heap[t] = new AgendaItem(edge, score, priority.apply(edge, score));
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
    return heap.length;
  }

  public void dbgShowScores() {
    Log.info("Agenda has " + top + " items:");
    for (int i = 0; i < top; i++)
      System.out.println(heap[i]);
  }

  public Adjoints peekScore() {
    assert top > 0;
    return heap[0].score;
  }

  public HypEdge peek() {
    assert top > 0;
    return heap[0].edge;
  }

  public Pair<HypEdge, Adjoints> peekBoth() {
    return new Pair<>(heap[0].edge, heap[0].score);
  }

  public HypEdge pop() {
    assert top > 0;
    AgendaItem ai = heap[0];
    moveAndFree(top-1, 0);
    top--;
    if (top > 0)
      siftDown(0);
    return ai.edge;
  }

  public Pair<HypEdge, Adjoints> popBoth() {
    assert top > 0;
    AgendaItem ai = heap[0];
    moveAndFree(top-1, 0);
    top--;
    if (top > 0)
      siftDown(0);
    return new Pair<>(ai.edge, ai.score);
  }

  public AgendaItem popBoth2() {
    assert top > 0;
    AgendaItem ai = heap[0];
    moveAndFree(top-1, 0);
    top--;
    if (top > 0)
      siftDown(0);
    return ai;
  }

  public void siftDown(int i) {
    if (i >= top)
      return;
    double sc = heap[i].priority;
    int lc = (i << 1) + 1;
    int rc = lc + 1;
    double lcScore = lc < top ? heap[lc].priority : sc;
    double rcScore = rc < top ? heap[rc].priority : sc;
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
      if (heap[parent].compareTo(heap[i]) > 0)
        break;
      swap(i, parent);
      i = parent;
    }
  }

  private void swap(int i, int j) {
//    Log.info("i=" + i + " j=" + j);
    assert i != j;
    AgendaItem ii = heap[i];
    AgendaItem jj = heap[j];
    n2eiSet(i, ii.edge, false);
    n2eiSet(j, jj.edge, false);
    n2eiSet(j, ii.edge, true);
    n2eiSet(i, jj.edge, true);
    e2i.put(ii.edge, j);
    e2i.put(jj.edge, i);
    heap[i] = jj;
    heap[j] = ii;
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
    uniq.remove(heap[to].getHashableEdge());
    removeEdgeFromFineView(heap[to].edge);
    n2eiSet(to, heap[to].edge, false);
    n2eiSet(to, heap[from].edge, true);
    n2eiSet(from, heap[from].edge, false);
    e2i.put(heap[from].edge, to);
    e2i.remove(heap[to].edge);
    heap[to] = heap[from];
    heap[from] = null;
  }

  private void free(int i) {
    uniq.remove(heap[i].getHashableEdge());
    removeEdgeFromFineView(heap[i].edge);
    n2eiSet(i, heap[i].edge, false);
    e2i.remove(heap[i].edge);
    heap[i] = null;
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
    int newSize = (int) (heap.length * 1.6 + 2);
    heap = Arrays.copyOf(heap, newSize);
  }
}