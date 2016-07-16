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
import edu.jhu.hlt.uberts.auto.Rule;
import edu.jhu.hlt.uberts.factor.GlobalFactor;
import edu.jhu.hlt.uberts.rules.Env.Trie3;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;
import edu.stanford.nlp.util.StringUtils;

/**
 * A trie which stores graph fragments.
 *
 * @deprecated See {@link Trie3}
 *
 * @author travis
 */
public class TNode {
  public static boolean DEBUG = false;
  public static boolean COARSE_DEBUG = false;

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
      assert i < boundVals.size() : "i=" + i + " boundVals: " + boundVals;
      return boundVals.get(i).getLeft();
    }
    public HypEdge getBoundEdge(int i) {
      assert i < boundVals.size() : "i=" + i + " boundVals: " + boundVals;
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
  public static class TVal {
    // Each may be null
    public TransitionGenerator tg;
    public GlobalFactor gf;
    public Uberts u;
    public Rule r;
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
    if (DEBUG || COARSE_DEBUG) {
      System.out.println();
      System.out.println("START MATCH: after " + newEdge
          + " was added, numTrieNodes=" + trie.getNumNodes());
    }
    HNode cur = new HNode(newEdge.getHead());
//    HNode cur = new HNode(newEdge);
    GraphTraversalTrace gtt = new GraphTraversalTrace();
    match(u, cur, gtt, trie, new HashSet<>());
    if (DEBUG || COARSE_DEBUG) {
      System.out.println("END MATCH: after " + newEdge
          + " was added, numTrieNodes=" + trie.getNumNodes());
      System.out.println();
    }
  }

  private String dbgChildKeys() {
    if (children == null)
      return "null";
    return children.keySet().toString();
  }

  /**
   * @param u
   * @param cur is the last HNode that we came from, and thus must enforce
   * equality with by traversing out of.
   * @param traversal
   * @param trie
   * @param crossed is the set of (directed) edges in the state hypergraph which
   * have already been crossed. You should never need to cross an edge twice. An
   * edge can be thought of as a binding of a functor's argument; observing this
   * twice is not necessary since its value is known after being seen once and
   * cannot change.
   */
  private static void match(Uberts u, HNode cur, GraphTraversalTrace traversal, TNode trie, Set<StateEdge> crossed) {

    if (DEBUG || COARSE_DEBUG) {
      System.out.println("TRACE cur=" + cur);
      if (DEBUG)
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
      if (DEBUG || COARSE_DEBUG)
        System.out.println("TRACE: GOTO_PARENT, r=" + r);
      match(u, traversal.stack.peek(), traversal, childTrie, crossed);
      traversal.stack.push(r);
    }

    if (cur == null) {
      // This happens when we have a rule with disconnected functors
      // (no common arguments), e.g. R1(a,b) & R2(c,d) => R3(a,b,c,d).
      // Cur means the last node we left and must enforce equality with,
      // which will be null after we match R1 and are about to match R2.
      // This means that we are free to start anywhere on the graph,
      // check all possible continuations in trie.children.keys()
      int n = 0;
      for (TKey poss : trie.children.keySet()) {
        n++;
        switch (poss.mode) {
        case TKey.RELATION:
          for (LL<HypEdge> ll = state.match2(poss.relationType); ll != null; ll = ll.next)
            tryMatch(poss, u, new HNode(ll.item), traversal, trie, crossed);
          break;
        case TKey.NODE_TYPE:
          throw new RuntimeException("implement me!");
        case TKey.NODE_VALUE:
          boolean isSchema = false;
          if (true)
            throw new RuntimeException("should this be isSchema? ask caller to provide this?");
          HypNode maybeExists = u.lookupNode(poss.nodeType, poss.nodeValue, false, isSchema);
          if (maybeExists != null)
            tryMatch(poss, u, new HNode(maybeExists), traversal, trie, crossed);
          break;
        default:
          throw new RuntimeException();
        }
      }
      if (DEBUG)
        System.out.println("tried " + n + " keys in the trie");

    } else {
      int edges = 0;
      List<StateEdge> sEdges = state.neighbors2(cur);
      if (COARSE_DEBUG)
        System.out.println("considering edges:\n\t" + StringUtils.join(sEdges, "\n\t"));
      for (StateEdge p : sEdges) {
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

        // Check that we're not going in loops
//        if (traversal.visited.contains(n)) {
//          // This is wrong: suppose n:HNode is a Relation/HypEdge node.
//          // You could visit it multiple times to reach various arguments.
//          if (DEBUG)
//            System.out.println("skipping visited node: " + n);
//          continue;
//        }
        boolean remove = false;
        if (!crossed.add(p)) {
          // You can never cross an edge in the state graph more than once.
          // An edge represents a binding of an argument to a functor.
          // It can only be bound once. Any subsequent times could only
          // verify that the value is still the same. But, the compilation
          // process to produce TKey paths does this statically.
          if (DEBUG)
            System.out.println("skipping edge since we've crossed it once already: " + p);
          continue;
        } else {
          if (DEBUG)
            System.out.println("crossing edge for the first and only time: " + p);
          remove = true;
        }

        TKey key;
        if (n.isNode()) {
          HypNode node = n.getNode();
          // Match both value and type
          key = new TKey(p.argPos, node.getNodeType(), node.getValue());
          tryMatch(key, u, n, traversal, trie, crossed);
          // Match just type
          key = new TKey(p.argPos, node.getNodeType());
          tryMatch(key, u, n, traversal, trie, crossed);
        } else {
          HypEdge edge = n.getEdge();
          key = new TKey(p.argPos, edge.getRelation());
          tryMatch(key, u, n, traversal, trie, crossed);
        }

        if (remove)
          crossed.remove(p);
      }
      if (DEBUG)
        System.out.println("checked " + edges + " edges adjacent to " + cur);
    }
    if (DEBUG || COARSE_DEBUG)
      System.out.println("TRACE returning from cur=" + cur);
  }

  /**
   * @param keyConstructedFromState should not have a name set!
   */
  private static void tryMatch(TKey keyConstructedFromState, Uberts u, HNode n, GraphTraversalTrace traversal, TNode trie, Set<StateEdge> crossed) {
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
      if (n.isEdge())
        traversal.boundVals.add(n);
      match(u, n, traversal, childTrie, crossed);
      traversal.stack.pop();
      traversal.visited.remove(n);
      if (n.isEdge())
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
      if (COARSE_DEBUG)
        System.out.println("state match: " + tval.r);
      for (Pair<HypEdge, Adjoints> p : tval.tg.generate(traversal))
        tval.u.addEdgeToAgenda(p);
    }
    // Good to put this second so that any new actions generated above get rescored immediately
    if (tval.gf != null) {
      throw new RuntimeException("we dropped support for GraphTraversalTrace");
//      tval.gf.rescore4(u, traversal);
    }
  }
}
