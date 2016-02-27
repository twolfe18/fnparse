package edu.jhu.hlt.uberts;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.factor.GlobalFactor;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;

public class TNode {

  // Singleton, both NodeType and Relation are null
  public static final TKey GOTO_PARENT = new TKey();

  public static class GraphTraversalTrace {
    private Map<TKey, HNode> bindings;  // never shrinks
    private Deque<HNode> stack;         // shrinks when you gotoParent
    private Set<HNode> visited;         // never shrinks
    public GraphTraversalTrace() {
        this.bindings = new HashMap<>();
        this.stack = new ArrayDeque<>();
        this.visited = new HashSet<>();
    }
    public HypNode getValueFor(NodeType nt) {
      TKey k = new TKey(nt);
      HNode hn = bindings.get(k);
      return hn.getLeft();
    }
  }

  static class TKey {
    // One of these is non-null/false
    private NodeType nt;
    private Relation rel;
    public TKey(NodeType nt) {
      this.nt = nt;
      this.rel = null;
    }
    public TKey(Relation rel) {
      this.nt = null;
      this.rel = rel;
    }
    private TKey() {};  // only for GOTO_PARENT
    @Override
    public int hashCode() {
      int hc = 7;
      if (nt != null)
        hc = nt.hashCode() + hc * 31;
      if (rel != null)
        hc = rel.hashCode() + hc * 31;
      return hc;
    }
    @Override
    public boolean equals(Object other) {
      if (other instanceof TKey) {
        TKey tk = (TKey) other;
        return nt == tk.nt && rel == tk.rel;
      }
      return false;
    }
    @Override
    public String toString() {
      return "(TKey nt=" + nt + " rel=" + rel + ")";
    }
  }

  static class TVal {
    // Both may be null
    TransitionGenerator tg;
    GlobalFactor gf;
    Uberts u;
  }

  private TKey key;
  private TVal value;
  private Map<TKey, TNode> children;

  public TNode(TKey key, TVal value) {
    this.key = key;
    this.value = value;
  }

  public TKey getKey() {
    return key;
  }

  public TVal getValue() {
    return value;
  }

  public boolean isLeaf() {
    return children == null || children.isEmpty();
  }

  public TNode getChild(TKey key) {
    if (children == null)
      return null;
    return children.get(key);
  }

  public TNode putChild(TKey key, TNode value) {
    if (children == null)
      children = new HashMap<>();
    return children.put(key, value);
  }

  public TNode lookup(TKey[] location, boolean addIfNotPresent) {
    TNode cur = this;
    for (int i = 0; i < location.length; i++) {
      TNode prev = cur;
      cur = cur.getChild(location[i]);
      if (cur == null) {
        if (addIfNotPresent) {
          cur = new TNode(location[i], new TVal());
          prev.putChild(location[i], cur);
        } else {
          return null;
        }
      }
    }
    return cur;
  }

  public static void match(Uberts u, HypEdge newEdge, TNode trie) {
    Log.info("matching after " + newEdge + " was popped");
//    HNode cur = new HNode(newEdge);
    HNode cur = new HNode(newEdge.getHead());
    GraphTraversalTrace gtt = new GraphTraversalTrace();
    match(u, cur, gtt, trie);
  }

  private static void match(Uberts u, HNode cur, GraphTraversalTrace traversal, TNode trie) {
    State state = u.getState();
    if (trie.value != null)
      emit(u, traversal, trie.value);

    if (trie.isLeaf())
      return;

    TNode childTrie = trie.getChild(GOTO_PARENT);
    if (childTrie != null) {
      HNode r = traversal.stack.pop();
      match(u, r, traversal, childTrie);
      traversal.stack.push(r);
    }

    for (HNode n : state.neighbors(cur)) {
      if (traversal.visited.contains(n))
        continue;

      TKey key;
      if (n.isLeft())
        key = new TKey(n.getLeft().getNodeType());
      else
        key = new TKey(n.getRight().getRelation());

      // This is the intersection of:
      // 1) unvisited neighbors (DFS style)
      // 2) allowed DFS strings in the trie
      childTrie = trie.getChild(key);
      if (childTrie != null) {
        traversal.stack.push(n);
        traversal.visited.add(n);
        traversal.bindings.put(key, n);
        match(u, n, traversal, childTrie);
        traversal.stack.pop();
        traversal.visited.remove(n);
        traversal.bindings.remove(key);
      }
    }
  }

  /**
   * This is the only time you can read all of the values of out {@link GraphTraversalTrace},
   * since they will be removed later.
   */
  private static void emit(Uberts u, GraphTraversalTrace traversal, TVal tval) {
//    Log.info("matching graph fragment!");
    if (tval.tg != null) {
      for (HypEdge e : tval.tg.generate(traversal)) {
//        Log.info("generated " + e);
        tval.u.addEdgeToAgenda(e);
      }
    }
    if (tval.gf != null) {
      tval.gf.rescore(u.getAgenda(), traversal);
    }
  }
}
