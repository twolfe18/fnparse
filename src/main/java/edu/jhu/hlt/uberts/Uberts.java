package edu.jhu.hlt.uberts;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.factor.GlobalFactor;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;

/**
 * An uber transition system for joint predictions. Holds a state and agenda,
 * and you add global features and transition generators to define the state
 * lattice.
 *
 * Remember:
 * {@link TransitionGenerator} => local features
 * {@link GlobalFactor} => global features and hard constraints
 *
 * @author travis
 */
public class Uberts {

  private State state;
  private Agenda agenda;
  private TNode trie;     // stores graph fragments used to match TransitionGenerators and GlobalFactors
  private Random rand;

  // So you can ask for Relations by name and keep them unique
  private Map<String, Relation> relations;

  // Alphabet of HypNodes which appear in either state or agenda.
  private Map<Pair<NodeType, Object>, HypNode> nodes;

  // Never call `new NodeType` outside of Uberts, use lookupNodeType
  private Map<String, NodeType> nodeTypes;

  // Ancillary data for features which don't look at the State graph.
  // New data backend (used to be fnparse.Sentence and FNParse)
  // TODO Update TemplateContext and everything in BasicFeatureTemplates.
  private edu.jhu.hlt.tutils.Document doc;
  public edu.jhu.hlt.tutils.Document getDoc() {
    return doc;
  }
  public void setDocument(edu.jhu.hlt.tutils.Document doc) {
    this.doc = doc;
  }

  // Supervision (set of gold HypEdges)
  // Modules may add to this set as they register TransitionGenerators for example.
  private HashSet<HashableHypEdge> goldEdges;
  public void clearLabels() {
    goldEdges = null;
  }
  public void addLabel(HypEdge e) {
    if (goldEdges == null)
      goldEdges = new HashSet<>();
    boolean added = goldEdges.add(new HashableHypEdge(e));
    assert added : "duplicate edge? " + e;
  }
  /**
   * +1 means a gold edge, good thing to add. -1 means a bad edge which
   * introduces loss. You can play with anything in between.
   *
   * TODO This needs to be expanded upon. There are lots of small issues like
   * you can just add this into the oracle, you have to know when something is
   * perfectly correct vs not... etc.
   */
  public double getLabel(HypEdge e) {
    if (goldEdges == null)
      throw new IllegalStateException("no labels available");
    if (goldEdges.contains(new HashableHypEdge(e)))
      return +1;
    return -1;
  }


  public Uberts(Random rand) {
    this.rand = rand;
    this.relations = new HashMap<>();
    this.agenda = new Agenda();
    this.state = new State();
    this.trie = new TNode(null, null);
    this.nodes = new HashMap<>();
    this.nodeTypes = new HashMap<>();
  }

  /**
   * Pops items off the agenda until score is below 0, then stops. Right now this
   * is a debug method since it prints stuff and inference is not finalized.
   */
  public void dbgRunInference() {
    for (int i = 0; agenda.size() > 0; i++) {
      Log.info("choosing the best action, i=" + i + " size=" + agenda.size() + " cap=" + agenda.capacity());
      agenda.dbgShowScores();
      Pair<HypEdge, Adjoints> p = agenda.popBoth();
      HypEdge best = p.get1();
      double score = p.get2().forwards();
      if (score <= 0)
        break;
      Log.info("best=" + best);
      addEdgeToState(best);
    }
    Log.info("done adding positive-scoring HypEdges");
    state.dbgShowEdges();
  }

  public Random getRandom() {
    return rand;
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
   * Use this rather than calling the {@link HypNode} constructor so that nodes
   * are gauranteed to be unique.
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

  /**
   * Use this rather than calling the {@link NodeType} constructor so that nodes
   * types are gauranteed to be unique.
   */
  public NodeType lookupNodeType(String name, boolean allowNewNodeType) {
    NodeType nt = nodeTypes.get(name);
    if (nt == null) {
      if (!allowNewNodeType)
        throw new RuntimeException("there is no NodeType called " + name);
      nt = new NodeType(name);
      nodeTypes.put(name, nt);
    }
    return nt;
  }

  public void addGlobalFactor(TKey[] lhs, GlobalFactor gf) {
    TNode n = trie.lookup(lhs, true);
    n.getValue().u = this;
    if (n.getValue().gf != null)
      gf = new GlobalFactor.Composite(gf, n.getValue().gf);
    n.getValue().gf = gf;
  }

  public void addTransitionGenerator(TKey[] lhs, TransitionGenerator tg) {
    TNode n = trie.lookup(lhs, true);
    n.getValue().u = this;
    if (n.getValue().tg != null)
      tg = new TransitionGenerator.Composite(tg, n.getValue().tg);
    n.getValue().tg = tg;
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

  public void addEdgeToAgenda(Pair<HypEdge, Adjoints> p) {
    addEdgeToAgenda(p.get1(), p.get2());
  }
  public void addEdgeToAgenda(HypEdge e, Adjoints score) {
    Log.info(e.toString());
    assert nodesContains(e);
//    Adjoints score = new Adjoints.Constant(rand.nextGaussian());
    agenda.add(e, score);
  }

  /** returns its argument */
  public Relation addEdgeType(Relation r) {
    Relation old = relations.put(r.getName(), r);
    assert old == null;
    return r;
  }
  public Relation getEdgeType(String name) {
    Relation r = relations.get(name);
    assert r != null : "no relation called " + name;
    return r;
  }

  // TODO Add methods which add TransitionGenerators and GlobalFactors without
  // having to directly mutate the trie:TNode

  // TODO a version that can parse input like "pos(i,*) => pos(i+1,*)"

  public NodeType getWitnessNodeType(String relationName) {
    return getWitnessNodeType(getEdgeType(relationName));
  }
  public NodeType getWitnessNodeType(Relation relation) {
    String wntName = "witness-" + relation.getName();
    return lookupNodeType(wntName, true);
  }

  // NOT TRUE: Every edge gets its own fact id
  // See Relation.encode for how head HypNodes get their values and why.
//  private int factCounter = 0;
  public HypEdge makeEdge(String relationName, HypNode... tail) {
    Relation r = getEdgeType(relationName);
    return makeEdge(r, tail);
  }
  public HypEdge makeEdge(Relation r, HypNode... tail) {
    NodeType headType = getWitnessNodeType(r);
//    HypNode head = lookupNode(headType, factCounter++);
    Object encoded = r.encodeTail(tail);
    HypNode head = lookupNode(headType, encoded);
    return new HypEdge(r, head, tail);
  }

}
