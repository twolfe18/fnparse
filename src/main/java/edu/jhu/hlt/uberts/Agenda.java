package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.tuple.Pair;

public class Agenda {
  private HypEdge[] heap1;      // ei2e
  private Adjoints[] heap2;
  private int top;
  private Map<HypNode, BitSet> n2ei;
  private Map<HypEdge, Integer> e2i; 

  // TODO Can play around with more indices which may make things like AtMost1
  // faster (can also look up by relation), but may make swap and everything else
  // slower.

  /*
   * Indices I actually need:
   * 1) heap over HypEdge
   * 2) probably: HypNode -> LL<HypEdge>
   * 3) maybe: Relation -> LL<HypEdge>
   *
   * swap doesn't affect 2 or 3.
   * remove does affect 1.
   *
   * If I store my indices as HypNode -> LL<int>, then I implicitly have an
   * HypNode -> LL<HypEdge> via the heap
   * and I can use these locations to do removes.
   */

  public Agenda() {
    this.top = 0;
    int initSize = 16;
    this.heap1 = new HypEdge[initSize];
    this.heap2 = new Adjoints[initSize];
    this.n2ei = new HashMap<>();
    this.e2i = new HashMap<>();
  }

  public List<HypEdge> adjacent(HypNode n) {
    List<HypEdge> e = new ArrayList<>();
    BitSet bs = n2ei.get(n);
    if (bs == null) {
//      for (HypNode nn : n2ei.keySet())
//        Log.warn(nn);
//      throw new RuntimeException("could not lookup any nodes attached to " + n);
      return Collections.emptyList();
    }
    for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1))
      e.add(heap1[i]);
    return e;
  }

  public void remove(HypEdge e) {
    int i = e2i.get(e);
    removeAt(i);
  }

  public void removeAt(int i) {
    int t = top--;
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
    int parent = (i - 1) >>> 1;
    return heap2[parent].forwards() >= heap2[i].forwards();
  }

  public void add(HypEdge edge, Adjoints score) {
    int t = top++;
    if (t == heap1.length)
      grow();
    heap1[t] = edge;
    heap2[t] = score;
    e2i.put(edge, t);
    n2eiSet(t, edge, true);
    siftUp(t);
  }

  public int size() {
    return top;
  }
  public int capacity() {
    return heap1.length;
  }

  public void dbgShowScores() {
    for (int i = 0; i < top; i++) {
      System.out.printf("%d\t%.4f\t%s\n", i, heap2[i].forwards(), heap1[i]);
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
//    Log.info("i=" + i);
    double sc = heap2[i].forwards();
    int lc = (i << 1) + 1;
    int rc = lc + 1;
    double lcScore = lc < top ? heap2[lc].forwards() : sc;
    double rcScore = rc < top ? heap2[rc].forwards() : sc;
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
    while (i > 0) {
      int parent = (i - 1) >>> 1;
      if (heap2[parent].forwards() >= heap2[i].forwards())
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
    heap2[i] = aj;
    heap2[j] = ai;
  }

  /**
   * Moves item at from (first arg) to to (second arg).
   * to items are over-written and from items are assigned to null.
   */
  private void moveAndFree(int from, int to) {
    if (from == to)
      return;
    n2eiSet(from, heap1[from], false);
    n2eiSet(to, heap1[to], false);
    n2eiSet(to, heap1[from], true);
    e2i.put(heap1[from], to);
    e2i.remove(heap1[to]);
    heap1[to] = heap1[from];
    heap1[from] = null;
    heap2[to] = heap2[from];
    heap2[from] = null;
  }

  private void n2eiSet(int i, HypEdge e, boolean b) {
    if (e == null)
      throw new IllegalArgumentException();
    n2eiGetOrInit(e.getHead()).set(i, b);
    int n = e.getNumTails();
    for (int j = 0; j < n; j++)
      n2eiGetOrInit(e.getTail(j)).set(i, b);
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