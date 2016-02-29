package edu.jhu.hlt.uberts;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.uberts.factor.GlobalFactor;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;

public class TNode {

  // Singleton, both NodeType and Relation are null
  public static final TKey GOTO_PARENT = new TKey();

  /**
   * A trace which includes all necessary information to execute rule when
   * a fragment is matched. You can think of this as a path which originates
   * with a new {@link HypEdge} and matches some traversal stored in the trie.
   */
  public static class GraphTraversalTrace {
    // TODO bindings has the wrong type: there could be more than one TKey(posTag)
    // in a graph fragment, how to tell them apart? Probably need a name for each variable.
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

  /**
   * A node in a graph fragment which must match.
   *
   * You can think of this as a tagged union of either:
   * - relation match, equality defined using {@link Relation#equals(Object)}
   * - node type match, equality defined using == on {@link NodeType}
   * - node type and value match, equality using == on {@link NodeType} and equals on nodeValue
   */
  static class TKey {
    static final int RELATION = 0;
    static final int NODE_TYPE = 1;
    static final int NODE_VALUE = 2;
    private NodeType nodeType;
    private Object nodeValue;
    private Relation relationType;
    private int mode;
    private int hc;

    public TKey(NodeType nodeType, Object nodeValue) {
      this.nodeType = nodeType;
      this.nodeValue = nodeValue;
      this.relationType = null;
      this.mode = NODE_VALUE;
      this.hc = 3 * Hash.mix(nodeType.hashCode(), nodeValue.hashCode()) + 0;
    }

    public TKey(NodeType nodeType) {
      this.nodeType = nodeType;
      this.nodeValue = null;
      this.relationType = null;
      this.mode = NODE_TYPE;
      this.hc = 3 * nodeType.hashCode() + 1;
    }

    public TKey(Relation relation) {
      this.nodeType = null;
      this.nodeValue = null;
      this.relationType = relation;
      this.mode = RELATION;
      this.hc = 3 * relation.hashCode() + 2;
    }

    private TKey() {    // only for GOTO_PARENT
      this.hc = Integer.MAX_VALUE;
      this.mode = -1;
    }

    @Override
    public int hashCode() {
      return hc;
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof TKey) {
        TKey tk = (TKey) other;
        if (mode != tk.mode)
          return false;
        switch (mode) {
        case RELATION:
          return relationType == tk.relationType;
        case NODE_TYPE:
          return nodeType == tk.nodeType;
        case NODE_VALUE:
          return nodeType == tk.nodeType && nodeValue.equals(tk.nodeValue);
        default:
          throw new RuntimeException("unknown mode: " + mode);
        }
      }
      return false;
    }

    @Override
    public String toString() {
      if (this == GOTO_PARENT)
        return "(TKey GOTO_PARENT)";
      return "(TKey nt=" + nodeType + " nv=" + nodeValue + " rel=" + relationType + ")";
    }
  }

  /**
   * Represents what to do once you match a graph fragment.
   */
  static class TVal {
    // Each may be null
    TransitionGenerator tg;
    GlobalFactor gf;
    Uberts u;
  }

  private TKey key;
  private TVal value;
  private Map<TKey, TNode> children;  // Note: hashcode IGNORES nodeValue

  public TNode(TKey key, TVal value) {
    this.key = key;
    this.value = value;
  }

  public int getNumNodes() {
    int nn = 1;
    if (children != null) {
      for (TNode tn : children.values())
        nn += tn.getNumNodes();
    }
    return nn;
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
    System.out.println("START MATCH: after " + newEdge + " was popped, numNodes=" + trie.getNumNodes());
//    HNode cur = new HNode(newEdge);
    HNode cur = new HNode(newEdge.getHead());
    GraphTraversalTrace gtt = new GraphTraversalTrace();
    match(u, cur, gtt, trie);
    System.out.println("END MATCH: after " + newEdge + " was popped");
  }

  private String dbgChildKeys() {
    if (children == null)
      return "null";
    return children.keySet().toString();
  }
  private static void match(Uberts u, HNode cur, GraphTraversalTrace traversal, TNode trie) {

    System.out.println("TRACE cur=" + cur);
    System.out.println("childKeys: " + trie.dbgChildKeys());
//    System.out.println();

    State state = u.getState();
    if (trie.value != null)
      emit(u, traversal, trie.value);

    if (trie.isLeaf()) {
      System.out.println("isLeaf, returning");
      return;
    }

    TNode childTrie = trie.getChild(GOTO_PARENT);
    if (childTrie != null) {
      HNode r = traversal.stack.pop();
      System.out.println("TRACE: GOTO_PARENT, r=" + r);
      match(u, traversal.stack.peek(), traversal, childTrie);
      traversal.stack.push(r);
    }

    for (HNode n : state.neighbors(cur)) {
      if (traversal.visited.contains(n)) {
        System.out.println("skipping visited node: " + n);
        continue;
      }
      System.out.println("trying to go to " + n);
      TKey key;
      if (n.isLeft()) {
        HypNode node = n.getLeft();
        // Match both value and type
        key = new TKey(node.getNodeType(), node.getValue());
        tryMatch(key, u, n, traversal, trie);
        // Match just type
        key = new TKey(node.getNodeType());
        tryMatch(key, u, n, traversal, trie);
      } else {
        HypEdge edge = n.getRight();
        key = new TKey(edge.getRelation());
        tryMatch(key, u, n, traversal, trie);
      }
    }
  }
  private static void tryMatch(TKey key, Uberts u, HNode n, GraphTraversalTrace traversal, TNode trie) {
      // This is the intersection of:
      // 1) unvisited neighbors (DFS style)
      // 2) allowed DFS strings in the trie
      TNode childTrie = trie.getChild(key);
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

  /**
   * This is the only time you can read all of the values of out {@link GraphTraversalTrace},
   * since they will be removed later.
   */
  private static void emit(Uberts u, GraphTraversalTrace traversal, TVal tval) {
//    System.out.println("EMIT");
//    System.out.println("\tbindings:" + traversal.bindings);
//    System.out.println("\tstack:" + traversal.stack);
//    System.out.println();
    // NOTE: Both of these operations don't mutate the State, only Agenda
    if (tval.tg != null) {
      for (HypEdge e : tval.tg.generate(traversal)) {
        tval.u.addEdgeToAgenda(e);
      }
    }
    // Good to put this second so that any new actions generated above get rescored immediately
    if (tval.gf != null) {
      tval.gf.rescore(u.getAgenda(), traversal);
    }
  }
}
