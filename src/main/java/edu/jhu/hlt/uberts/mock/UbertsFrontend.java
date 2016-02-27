package edu.jhu.hlt.uberts.mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.tutils.Either;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.mock.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.mock.TNode.TKey;
import edu.jhu.prim.tuple.Pair;

public class UbertsFrontend {

  /* Hyper graph **************************************************************/
  public static class HypNode implements NodeSpecifier {
    private NodeType nt;
    private Object value;
    // e.g. (tokenIndex, 5)
    public HypNode(NodeType type, Object value) {
      this.nt = type;
      this.value = value;
    }
    public NodeType getNodeType() {
      return nt;
    }
    public Object getValue() {
      return value;
    }
    @Override
    public String toString() {
      return "(" + nt + " " + value + ")";
    }
    @Override
    public boolean matches(HypNode n) {
      return this == n;
    }
    @Override
    public boolean matches(HypEdge e) {
      return false;
    }
  }
  public static class HypEdge implements NodeSpecifier {
    private Relation relation;    // e.g. 'srl2'
    private HypNode head;
    private HypNode[] tail;
    public HypEdge(Relation edgeType, HypNode head, HypNode[] tail) {
      this.relation = edgeType;
      this.head = head;
      this.tail = tail;
    }
    public Relation getRelation() {
      return relation;
    }
    public HypNode getHead() {
      return head;
    }
    public int getNumTails() {
      return tail.length;
    }
    public HypNode getTail(int i) {
      return tail[i];
    }
    public Iterable<HypNode> getNeighbors() {
      List<HypNode> n = new ArrayList<>();
      n.add(head);
      for (HypNode t : tail)
        n.add(t);
      return n;
    }
    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("(Edge [");
      for (int i = 0; i < tail.length; i++) {
        if (i > 0) sb.append(", ");
        sb.append(tail[i].toString());
      }
      sb.append("] -");
      sb.append(relation.getName());
      sb.append("-> ");
      sb.append(head.toString());
      sb.append(')');
      return sb.toString();
    }
    @Override
    public boolean matches(HypNode n) {
      return false;
    }
    @Override
    public boolean matches(HypEdge e) {
      return this == e;
    }
  }

  /**
   * This is the node type in the graph (i.e. not the hyper-graph).
   * Mostly sugar.
   */
  public static class HNode extends Either<HypNode, HypEdge> implements NodeSpecifier {
    private int hc;
    public HNode(HypNode l) {
      super(l, null);
      hc = super.hashCode();
    }
    public HNode(HypEdge r) {
      super(null, r);
      hc = super.hashCode();
    }
    @Override
    public int hashCode() {
      return hc;
    }
    @Override
    public boolean matches(HypNode n) {
      if (isLeft())
        return getLeft() == n;
      return false;
    }
    @Override
    public boolean matches(HypEdge e) {
      if (isRight())
        return getRight() == e;
      return false;
    }
  }

  /* Graph ********************************************************************/
  /**
   * Not quite a node in the graph, but something that could match a node.
   * May capture a value.
   *
   * For example, if I had a LHS of "pos(i,*)", this requires two NodeSpeicifiers,
   * the first is a capturing tokenIndex:NodeType and the second is a non-capturing
   * posTag:NodeType. Each NodeSpecifier must be at least as strong as its relation's argument type.
   *
   * Note: I have separated the notion of capturing from this class and is now
   * handled by instances of {@link TrieTraversal.DfsTraversalAction}.
   */
  public interface NodeSpecifier {
    public boolean matches(HypNode n);
    public boolean matches(HypEdge e);
    default public boolean matches(HNode n) {
      if (n.isLeft())
        return matches(n.getLeft());
      return matches(n.getRight());
    }
  }
  public static class NodeTypeNodeSpecifier implements NodeSpecifier {
    private NodeType nt;
    public NodeTypeNodeSpecifier(NodeType nt) {
      this.nt = nt;
    }
    @Override
    public boolean matches(HypNode n) {
      return n.getNodeType() == nt;
    }
    @Override
    public boolean matches(HypEdge e) {
      return false;
    }
  }
  public static class RelationNodeSpecifier implements NodeSpecifier {
    private Relation rel;
    public RelationNodeSpecifier(Relation rel) {
      this.rel = rel;
    }
    @Override
    public boolean matches(HypNode n) {
      return false;
    }
    @Override
    public boolean matches(HypEdge e) {
      return e.getRelation() == rel;
    }
  }


  /* State ********************************************************************/
  public interface UGraph<N> {
    // TODO use LL? Iterator? Iterable?
    public List<N> neighbors(N node);
  }
  public static class State implements UGraph<HNode> {
    private Map<HypNode, LL<HypEdge>> adjacencyView1;
    public State() {
      this.adjacencyView1 = new HashMap<>();
    }
    public void add(HypEdge e) {
      add(e.getHead(), e);
      int n = e.getNumTails();
      for (int i = 0; i < n; i++)
        add(e.getTail(i), e);
    }
    private void add(HypNode n, HypEdge e) {
      LL<HypEdge> es = adjacencyView1.get(n);
      adjacencyView1.put(n, new LL<>(e, es));
    }
    public LL<HypEdge> getEdges(HypNode n) {
      return adjacencyView1.get(n);
    }
    @Override
    public List<HNode> neighbors(HNode node) {
      List<HNode> a = new ArrayList<>();
      if (node.isLeft()) {
        HypNode n = node.getLeft();
        for (LL<HypEdge> cur = adjacencyView1.get(n); cur != null; cur = cur.next)
          a.add(new HNode(cur.item));
      } else {
        HypEdge e = node.getRight();
        for (HypNode n : e.getNeighbors())
          a.add(new HNode(n));
      }
      return a;
    }
  }
  /* Transition Grammar *******************************************************/
  public static interface TransitionGenerator {
    Iterable<HypEdge> generate(GraphTraversalTrace lhsValues);
  }

  /* Global Factors ***********************************************************/
  public static interface GlobalFactor {
    /** Do whatever you want to the edges in the agenda */
    public void rescore(Agenda a, GraphTraversalTrace match);
  }
  /** Only works for 2 arg relations now */
  public static class AtMost1 implements GlobalFactor {
    // Right now this is pos(i,T) => !pos(i,S) s.t. S \not\eq T
    private Relation rel2;
//    private NodeType freeVar;   // range over which AtMost1 applies
    private NodeType boundVar;  // should be able to find this value in matches
    public AtMost1(Relation twoArgRelation, NodeType range) {
      if (twoArgRelation.getNumArgs() != 2)
        throw new IllegalArgumentException();
      this.rel2 = twoArgRelation;
      this.boundVar = range;
    }
    public void rescore(Agenda a, GraphTraversalTrace match) {
      HypNode observedValue = match.getValueFor(boundVar);
      Log.info("removing all nodes adjacent to " + observedValue + " matching " + rel2 + " from agenda");
      for (HypEdge e : a.adjacent(observedValue)) {
        if (e.getRelation() == rel2) {
          Log.info("actually removing: " + e);
          a.remove(e);
        }
      }
    }
  }

  private State state;
  private Agenda agenda;
  private Map<String, Relation> relations;
  private TNode trie;
  private Random rand;

  // Alphabet of HypNodes which appear in either state or agenda.
  private Map<Pair<NodeType, Object>, HypNode> nodes;

  public UbertsFrontend(Random rand) {
    this.rand = rand;
    this.relations = new HashMap<>();
    this.agenda = new Agenda();
    this.nodes = new HashMap<>();
    this.state = new State();
    this.trie = new TNode(null, null);
  }
  
  public State getState() {
    return state;
  }
  public Agenda getAgenda() {
    return agenda;
  }

  /**
   * Use this rather than calling the HypNode constructor so that nodes are
   * gauranteed to be unique.
   */
  public HypNode lookupNode(NodeType nt, Object value) {
    Pair<NodeType, Object> key = new Pair<>(nt, value);
    HypNode v = nodes.get(key);
    if (v == null) {
      v = new HypNode(nt, value);
      nodes.put(key, v);
    }
    return v;
  }

  private boolean nodesContains(HypNode n) {
    HypNode n2 = nodes.get(new Pair<>(n.getNodeType(), n.getValue()));
    return n2 == n;
  }
  private boolean nodesContains(HypEdge e) {
    if (!nodesContains(e.getHead()))
      return false;
    int n = e.getNumTails();
    for (int i = 0; i < n; i++)
      if (!nodesContains(e.getTail(i)))
        return false;
    return true;
  }

  public void addEdgeToState(HypEdge e) {
    Log.info(e.toString());
    assert nodesContains(e);
    state.add(e);
    TNode.match(this, e, trie);
  }
  public void addEdgeToAgenda(HypEdge e) {
    Log.info(e.toString());
    assert nodesContains(e);
    Adjoints score = new Adjoints.Constant(rand.nextGaussian());
    agenda.add(e, score);
  }

  public void addEdgeType(Relation r) {
    Relation old = relations.put(r.getName(), r);
    assert old == null;
  }
  public Relation getEdgeType(String name) {
    return relations.get(name);
  }

  // TODO a version that can parse input like "pos(i,*) => pos(i+1,*)"

  public void addGlobalFactor(String... terms) {
    throw new RuntimeException("implement me");
  }

  /**
   * Lets do a proof of concept with POS, NER, coref
   *
   * Transitions:
   * pos(i,*) => pos(i+1,*)
   * pos(i,N) => ner(j,i,PER) forall j s.t. i-j \in [0..K]
   * ner(i,j,PER) ^ ner(k,l,PER) => coref(i,j,k,l)
   *
   * Factors:
   * pos(i,*) ~ word(i)
   * pos(i,*) ~ pos(i-1,*)
   * ner(i,j,PER) ~ pos(i,*) ^ pos(j,*)
   * ner(i,j,PER) ~ word(k) if i<=k<=j
   * coref(i,j,k,l) ~ dist(j,k)
   */
  public static void main(String[] args) {
    UbertsFrontend u = new UbertsFrontend(new Random(9001));
    NodeType tokenIndex = new NodeType("tokenIndex");
    NodeType word = new NodeType("word");
    NodeType posTag = new NodeType("posTag");
    NodeType nerTag = new NodeType("nerTag");
    NodeType bool = new NodeType("bool");
    u.addEdgeType(new Relation("word", tokenIndex, word));
    u.addEdgeType(new Relation("pos", tokenIndex, posTag));
    u.addEdgeType(new Relation("ner", tokenIndex, tokenIndex, nerTag));
    u.addEdgeType(new Relation("coref", tokenIndex, tokenIndex, tokenIndex, tokenIndex, bool));

    List<HypNode> tokens = new ArrayList<>();
    String[] sent = new String[] {"<s>", "John", "loves", "Mary", "more", "than", "himself"};
    for (int i = 0; i < sent.length; i++) {
      Relation edgeType = u.getEdgeType("word");
      HypNode token = u.lookupNode(tokenIndex, i);
      HypNode head = u.lookupNode(word, sent[i]);
      HypEdge e = new HypEdge(edgeType, head, new HypNode[] {token});
      u.addEdgeToState(e);
      tokens.add(u.lookupNode(tokenIndex, i));
    }

    // This is how we convert "pos(i,*) => pos(i+1,*)" into code
    List<HypNode> posTags = new ArrayList<>();
    for (String p : Arrays.asList("N", "V", "PP", "PRP", "DT", "A", "J"))
      posTags.add(u.lookupNode(posTag, p));

    // This captures the actual POS tag.
    TNode t;
//    t = u.trie.lookup(new TKey[] {
//        new TKey(u.getEdgeType("pos")),
//        new TKey(posTag),
//        TNode.GOTO_PARENT,
//        new TKey(tokenIndex)}, true);
    // If we don't actually need the POS tag, I suppose we could write:
    t = u.trie.lookup(new TKey[] {
        new TKey(u.getEdgeType("pos")),
        new TKey(tokenIndex)}, true);
    assert t.getValue().tg == null;
    t.getValue().tg = new TransitionGenerator() {
      @Override
      public Iterable<HypEdge> generate(GraphTraversalTrace lhsValues) {
        int i = (Integer) lhsValues.getValueFor(tokenIndex).getValue();
        i++;
        HypNode[] tail = new HypNode[] { tokens.get(i) };
        Relation r = u.getEdgeType("pos");
        List<HypEdge> pos = new ArrayList<>();
        for (HypNode pt : posTags) {
          Log.info("adding pos(" + i + ", " + pt.getValue() + ")");
          pos.add(new HypEdge(r, pt, tail));
        }
        return pos;
      }
    };
    t.getValue().u = u;

    // Lets have a global factor of -inf for pos(i,T) => pos(i,S) if S!=T.
    t.getValue().gf = new AtMost1(u.getEdgeType("pos"), tokenIndex);

    // This should kick off pos(i,*) => pos(i+1,*)
    HypNode head = u.lookupNode(posTag, "<s>");
    HypNode tail = u.lookupNode(tokenIndex, 0);
    HypEdge e = new HypEdge(u.getEdgeType("pos"), head, new HypNode[] {tail});
    u.addEdgeToState(e);

    for (int i = 0; i < sent.length - 1; i++) {
      Log.info("chosing the best action, i=" + (i++) + " size=" + u.agenda.size() + " cap=" + u.agenda.capacity());
      u.agenda.dbgShowScores();
      HypEdge best = u.agenda.pop();
      Log.info("best=" + best);
      u.addEdgeToState(best);
    }
  }
}
