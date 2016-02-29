package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.factor.AtMost1;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;

public class Uberts {

  private State state;
  private Agenda agenda;
  private Map<String, Relation> relations;
  private TNode trie;
  private Random rand;

  // Alphabet of HypNodes which appear in either state or agenda.
  private Map<Pair<NodeType, Object>, HypNode> nodes;

  public Uberts(Random rand) {
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
    if (e.getHead() != null && !nodesContains(e.getHead())) {
      Log.warn("missing head=" + e.getHead());
      return false;
    }
    int n = e.getNumTails();
    for (int i = 0; i < n; i++) {
      if (!nodesContains(e.getTail(i))) {
        Log.info("missing: tail[" + i + "]=" + e.getTail(i));
        return false;
      }
    }
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

  private Map<String, NodeType> witnessNodeTypes = new HashMap<>();
  private NodeType getWitnessNodeType(Relation r) {
    String wntName = "witness-" + r.getName();
    NodeType nt = witnessNodeTypes.get(wntName);
    if (nt == null) {
      nt = new NodeType(wntName);
      witnessNodeTypes.put(wntName, nt);
    }
    return nt;
  }
  private Object witnessValue = "yup";
//  private Counts<NodeType> numNodesByType = new Counts<>();
  public HypEdge makeEdge(String relationName, HypNode... tail) {
    Relation r = getEdgeType(relationName);
    NodeType headType = getWitnessNodeType(r);
//    int c = numNodesByType.increment(headType);
    HypNode head = lookupNode(headType, witnessValue);
    return new HypEdge(r, head, tail);
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
  public static void main(String[] arg) {
    Uberts u = new Uberts(new Random(9001));
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
      HypNode[] tail = new HypNode[] {
          u.lookupNode(tokenIndex, i),
          u.lookupNode(word, sent[i]),
      };
      HypEdge e = u.makeEdge("word", tail);
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
        List<HypEdge> pos = new ArrayList<>();
        for (HypNode pt : posTags) {
          Log.info("adding pos(" + i + ", " + pt.getValue() + ")");
          HypNode[] tail = new HypNode[] { tokens.get(i), pt };
          pos.add(u.makeEdge("pos", tail));
        }
        return pos;
      }
    };
    t.getValue().u = u;

    // Lets have a global factor of -inf for pos(i,T) => pos(i,S) if S!=T.
    t.getValue().gf = new AtMost1(u.getEdgeType("pos"), tokenIndex);

    // This should kick off pos(i,*) => pos(i+1,*)
    HypNode[] tail = new HypNode[] {
        u.lookupNode(tokenIndex, 0),
        u.lookupNode(posTag, "<s>"),
    };
    HypEdge e = u.makeEdge("pos", tail);
    u.addEdgeToState(e);

//    for (int i = 0; i < sent.length - 1; i++) {
    for (int i = 0; u.agenda.size() > 0; i++) {
      Log.info("choosing the best action, i=" + i + " size=" + u.agenda.size() + " cap=" + u.agenda.capacity());
      u.agenda.dbgShowScores();
      HypEdge best = u.agenda.pop();
      Log.info("best=" + best);
      u.addEdgeToState(best);
    }

    // Print out the state graph
    u.state.dbgShowEdges();
  }
}
