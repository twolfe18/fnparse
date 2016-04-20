package edu.jhu.hlt.uberts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.factor.GlobalFactor;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;

/**
 * A trie which stores graph fragments.
 *
 * @author travis
 */
public class TNode {
  public static boolean DEBUG = false;

  // Singleton, both NodeType and Relation are null
  public static final TKey GOTO_PARENT = new TKey();

  /**
   * A trace which includes all necessary information to execute rule when
   * a fragment is matched. You can think of this as a path which originates
   * with a new {@link HypEdge} and matches some traversal stored in the trie.
   */
  public static class GraphTraversalTrace {
    private List<HNode> boundVals;      // like stack but with random access
    private Deque<HNode> stack;         // shrinks when you gotoParent
    private Set<HNode> visited;         // never shrinks

    public GraphTraversalTrace() {
      this.boundVals = new ArrayList<>();
      this.stack = new ArrayDeque<>();
      this.visited = new HashSet<>();
    }

    public HypNode getBoundNode(int i) {
      assert i < boundVals.size();
      return boundVals.get(i).getLeft();
    }
    public HypEdge getBoundEdge(int i) {
      assert i < boundVals.size();
      return boundVals.get(i).getRight();
    }

    @Override
    public String toString(){
      return "(GTT bound=" + boundVals + ")";
    }
  }

  /**
   * A node in a graph fragment which must match.
   *
   * You can think of this as a tagged union of either:
   * - relation match, equality defined == on {@link Relation#equals(Object)}
   * - node type match, equality defined using == on {@link NodeType}
   * - node type and value match, equality using == on {@link NodeType} and equals on nodeValue
   *
   * CLARIFICATION (on argPos):
   * This class should be generally called an edge, with some specifics related
   * to when you can cross it or not. It is really a set of edges in the
   * HypEdge-HypNode bipartite graph[1]. ONLY ONE of Relation|NodeType will be
   * non-null in these instances, but that is an implementation detail w.r.t. to
   * TNode. Every TNode SHOULD have either a Relation|NodeType (currently TKey)
   * and a set of edges leaving it, where every edge in that collection shares
   * a source, the Relation|NodeType belonging to this TNode.
   * ON argPos:
   * It always refers to the Relation! Don't try to make sense of (NodeType,argPos),
   * it doesn't make sense. Using the edge analogy, there is always one Relation
   * in the edge (its an edge in a bipartite graph), and the argPos relates to
   * that. If you have a Relation TNode, then argPos means you must follow that
   * argPos to arrive at the TKey's NodeType. If you have a NodeType TNode, you
   * the TKey neighbors will mean "and that NodeType is argX of this Relation
   * which you're now at".
   */
  public static class TKey {
    public static final int RELATION = 0;
    public static final int NODE_TYPE = 1;
    public static final int NODE_VALUE = 2;
    private NodeType nodeType;
    private Object nodeValue;
    private Relation relationType;

    /**
     * Argument position of the relevant Relation.
     * See class description for clarification.
     */
    private final int argPos;

    private int mode;
    private int hc;

    public TKey(int argPos, HypNode n) {  // Sugar
      this(argPos, n.getNodeType(), n.getValue());
    }
    public TKey(int argPos, NodeType nodeType, Object nodeValue) {
      if (argPos < 0)
        throw new IllegalArgumentException("argPos=" + argPos);
      assert nodeType != null && nodeValue != null;
      this.argPos = argPos;
      this.nodeType = nodeType;
      this.nodeValue = nodeValue;
      this.relationType = null;
      this.mode = NODE_VALUE;
      this.hc = 3 * Hash.mix(nodeType.hashCode(), nodeValue.hashCode(), argPos) + 0;
    }

    public TKey(int argPos, NodeType nodeType) {
      if (argPos < 0)
        throw new IllegalArgumentException("argPos=" + argPos);
      assert nodeType != null;
      this.argPos = argPos;
      this.nodeType = nodeType;
      this.nodeValue = null;
      this.relationType = null;
      this.mode = NODE_TYPE;
      this.hc = 3 * Hash.mix(nodeType.hashCode(), argPos) + 1;
    }

    public TKey(int argPos, Relation relation) {
      if (argPos < 0)
        throw new IllegalArgumentException("argPos=" + argPos);
      assert relation != null;
      this.argPos = argPos;
      this.nodeType = null;
      this.nodeValue = null;
      this.relationType = relation;
      this.mode = RELATION;
      this.hc = 3 * Hash.mix(relation.hashCode(), argPos) + 2;
    }

    private TKey() {    // only for GOTO_PARENT
      this.hc = Integer.MAX_VALUE;
      this.argPos = -2;
      this.mode = -1;
    }

    public int getMode() {
      return mode;
    }

    @Override
    public int hashCode() {
      return hc;
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof TKey) {
        TKey tk = (TKey) other;
        if (mode != tk.mode || argPos != tk.argPos)
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
      return "(TKey nt=" + nodeType + " nv=" + nodeValue + " rel=" + relationType + " argPos=" + argPos + ")";
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
  private Map<TKey, TNode> children;

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
    if (DEBUG) {
      System.out.println();
      System.out.println("START MATCH: after " + newEdge
          + " was added, numTrieNodes=" + trie.getNumNodes()
          + " numStateNodes=" + u.getState().getNumNodes());
    }
    HNode cur = new HNode(newEdge.getHead());
//    HNode cur = new HNode(newEdge);
    GraphTraversalTrace gtt = new GraphTraversalTrace();
    match(u, cur, gtt, trie);
    if (DEBUG) {
      System.out.println("END MATCH: after " + newEdge
          + " was added, numTrieNodes=" + trie.getNumNodes()
          + " numStateNodes=" + u.getState().getNumNodes());
      System.out.println();
    }
  }

  private String dbgChildKeys() {
    if (children == null)
      return "null";
    return children.keySet().toString();
  }
  private static void match(Uberts u, HNode cur, GraphTraversalTrace traversal, TNode trie) {

    if (DEBUG) {
      System.out.println("TRACE cur=" + cur);
      System.out.println("childKeys: " + trie.dbgChildKeys());
    }

    State state = u.getState();
    if (trie.value != null)
      emit(u, traversal, trie.value);

    if (trie.isLeaf()) {
      if (DEBUG)
        System.out.println("isLeaf, returning");
      return;
    }

    TNode childTrie;
    if ((childTrie = trie.getChild(GOTO_PARENT)) != null) {
      HNode r = traversal.stack.pop();
      if (DEBUG)
        System.out.println("TRACE: GOTO_PARENT, r=" + r);
      match(u, traversal.stack.peek(), traversal, childTrie);
      traversal.stack.push(r);
    }

    // Intersect nodes that follow cur with trie.children.keys()
    if (cur == null) {
      // This means that we are free to start anywhere on the graph,
      // intersection degenerates to just trie.children.keys()
      for (TKey poss : trie.children.keySet()) {
        switch (poss.mode) {
        case TKey.RELATION:
          for (LL<HypEdge> ll = state.match2(poss.relationType); ll != null; ll = ll.next)
            tryMatch(poss, u, new HNode(ll.item), traversal, trie);
          break;
        case TKey.NODE_TYPE:
          throw new RuntimeException("implement me!");
        case TKey.NODE_VALUE:
          HypNode maybeExists = u.lookupNode(poss.nodeType, poss.nodeValue, false);
          if (maybeExists != null)
            tryMatch(poss, u, new HNode(maybeExists), traversal, trie);
          break;
        default:
          throw new RuntimeException();
        }
      }
    } else {
      int edges = 0;
      for (StateEdge p : state.neighbors2(cur)) {
        if (DEBUG)
          System.out.println("trying edge: " + p);
        /*
         * I'm getting, e.g. StateEdge = (HEAD_ARG_POS, pos2)
         * And my trie doesn't contain any argPos=HEAD_ARG_POS entries,
         *
         * h = pos(4,VBZ)
         * h --argPos=HEAD--> pos:Relation --argPos=0--> 4:tokenIndex
         */
        edges++;
        HNode n = p.getTarget();
        assert n != null : "p=" + p;
        if (traversal.visited.contains(n)) {
          if (DEBUG)
            System.out.println("skipping visited node: " + n);
          continue;
        }
        TKey key;
        if (n.isNode()) {
          HypNode node = n.getNode();
          // Match both value and type
          key = new TKey(p.argPos, node.getNodeType(), node.getValue());
          tryMatch(key, u, n, traversal, trie);
          // Match just type
          key = new TKey(p.argPos, node.getNodeType());
          tryMatch(key, u, n, traversal, trie);
        } else {
          HypEdge edge = n.getEdge();
          key = new TKey(p.argPos, edge.getRelation());
          tryMatch(key, u, n, traversal, trie);
        }
      }
      if (DEBUG)
        System.out.println("checked " + edges + " edges adjacent to " + cur);
    }
  }

  /**
   * @param keyConstructedFromState should not have a name set!
   */
  private static void tryMatch(TKey keyConstructedFromState, Uberts u, HNode n, GraphTraversalTrace traversal, TNode trie) {
    if (DEBUG) {
      System.out.println("keyFromState=" + keyConstructedFromState);
    }
    // This is the intersection of:
    // 1) unvisited neighbors (DFS style)
    // 2) allowed DFS strings in the trie
    TNode childTrie = trie.getChild(keyConstructedFromState);
    if (childTrie != null) {
      traversal.stack.push(n);
      traversal.visited.add(n);
      traversal.boundVals.add(n);
      match(u, n, traversal, childTrie);
      traversal.stack.pop();
      traversal.visited.remove(n);
      traversal.boundVals.remove(traversal.boundVals.size() - 1);
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
//    Log.info(traversal.boundVals);
    // NOTE: Both of these operations don't mutate the State, only Agenda
    if (tval.tg != null) {
      for (Pair<HypEdge, Adjoints> p : tval.tg.generate(traversal))
        tval.u.addEdgeToAgenda(p);
    }
    // Good to put this second so that any new actions generated above get rescored immediately
    if (tval.gf != null) {
      tval.gf.rescore(u.getAgenda(), traversal);
    }
  }
}
