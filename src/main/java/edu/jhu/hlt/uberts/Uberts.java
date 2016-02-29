package edu.jhu.hlt.uberts;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
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
  public TNode getGraphFragments() {
    return trie;
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

  // TODO Add methods which add TransitionGenerators and GlobalFactors without
  // having to directly mutate the trie:TNode

  // TODO a version that can parse input like "pos(i,*) => pos(i+1,*)"

  private Map<String, NodeType> witnessNodeTypes = new HashMap<>();
  public NodeType getWitnessNodeType(String relationName) {
    return getWitnessNodeType(getEdgeType(relationName));
  }
  public NodeType getWitnessNodeType(Relation relation) {
    String wntName = "witness-" + relation.getName();
    NodeType nt = witnessNodeTypes.get(wntName);
    if (nt == null) {
      nt = new NodeType(wntName);
      witnessNodeTypes.put(wntName, nt);
    }
    return nt;
  }
//  private Object witnessValue = "yup";
//  private Counts<NodeType> numNodesByType = new Counts<>();
  private int factCounter = 0;
  public HypEdge makeEdge(String relationName, HypNode... tail) {
    Relation r = getEdgeType(relationName);
    NodeType headType = getWitnessNodeType(r);
////    int c = numNodesByType.increment(headType);
//    HypNode head = lookupNode(headType, witnessValue);
    HypNode head = lookupNode(headType, factCounter++);
    return new HypEdge(r, head, tail);
  }

}
