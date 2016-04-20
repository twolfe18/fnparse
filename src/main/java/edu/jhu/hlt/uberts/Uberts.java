package edu.jhu.hlt.uberts;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.factor.GlobalFactor;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;
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

  public static boolean DEBUG = false;

  public static final String REC_ORACLE_TRAJ = "recordOracleTrajectory";

  private State state;
  private Agenda agenda;
  private TNode trie;     // stores graph fragments used to match TransitionGenerators and GlobalFactors
  private Random rand;
  private MultiTimer timer;

  // So you can ask for Relations by name and keep them unique
  private Map<String, Relation> relations;

  // Alphabet of HypNodes which appear in either state or agenda.
  private Map<Pair<NodeType, Object>, HypNode> nodes;

  // Never call `new NodeType` outside of Uberts, use lookupNodeType
  private Map<String, NodeType> nodeTypes;

  // Ancillary data for features which don't look at the State graph.
  // New data backend (used to be fnparse.Sentence and FNParse)
  // TODO Update TemplateContext and everything in BasicFeatureTemplates.
  /** @deprecated Switch to a pure State/graph-based representation! */
  private edu.jhu.hlt.tutils.Document doc;
  /** @deprecated Switch to a pure State/graph-based representation! */
  public edu.jhu.hlt.tutils.Document getDoc() {
    return doc;
  }
  /** @deprecated Switch to a pure State/graph-based representation! */
  public void setDocument(edu.jhu.hlt.tutils.Document doc) {
    this.doc = doc;
  }

  // Supervision (set of gold HypEdges)
  // Modules may add to this set as they register TransitionGenerators for example.
//  private HashSet<HashableHypEdge> goldEdges;
  private Labels goldEdges;
  /**
   * Sets the set of gold edges to null (appears as if no labels were ever provided).
   */
  public void clearLabels() {
    goldEdges = null;
  }
  public void addLabel(HypEdge e) {
    if (goldEdges == null)
      goldEdges = new Labels();
    goldEdges.add(e);;
  }
  public boolean getLabel(HypEdge e) {
    return goldEdges.contains(e);
  }
  /**
   * Sets the set of gold edges to the empty set.
   */
  public void initLabels() {
    goldEdges = new Labels();
  }


  public Uberts(Random rand) {
    this.rand = rand;
    this.relations = new HashMap<>();
    this.agenda = new Agenda();
    this.state = new State();
    this.trie = new TNode(null, null);
    this.nodes = new HashMap<>();
    this.nodeTypes = new HashMap<>();

    this.timer = new MultiTimer();
    this.timer.put(REC_ORACLE_TRAJ, new Timer(REC_ORACLE_TRAJ, 30, true));
  }

  // TODO This is an ugly hack, fixme.
  public Relation addSuccTok(int n) {
    NodeType tokenIndex = lookupNodeType("tokenIndex", true);
    Relation succTok = addEdgeType(new Relation("succTok", tokenIndex, tokenIndex));
    HypNode prev = lookupNode(tokenIndex, String.valueOf(-1).intern(), true);
    for (int i = 0; i < n; i++) {   // TODO figure out a better way to handle this...
      HypNode cur = lookupNode(tokenIndex, String.valueOf(i).intern(), true);
      HypEdge e = makeEdge(succTok, prev, cur);
      e = new HypEdge.WithProps(e, HypEdge.IS_SCHEMA);
      addEdgeToState(e);
      prev = cur;
    }
    return succTok;
  }

  /**
   * Pops items off the agenda until score is below 0, then stops. Right now this
   * is a debug method since it prints stuff and inference is not finalized.
   *
   * @param oracle says whether only edges which are in the gold label set should
   * be added to state (others are just popped off the agenda and discarded).
   */
  public void dbgRunInference(boolean oracle, int actionLimit) {
    Log.info("starting...");
    int applied = 0;
    for (int i = 0; agenda.size() > 0
        && (actionLimit <= 0 || applied < actionLimit); i++) {
//      Log.info("choosing the best action, i=" + i + " size=" + agenda.size() + " cap=" + agenda.capacity());
//      agenda.dbgShowScores();
      Pair<HypEdge, Adjoints> p = agenda.popBoth();
      HypEdge best = p.get1();
      boolean gold = goldEdges.contains(new HashableHypEdge(best));
//      Log.info("best=" + best + " gold=" + gold);
      if (oracle && gold) {
//        Log.info("oracle=true, non-gold edge, skipping");
        continue;
      }
//      Adjoints sc = p.get2();
//      Log.info("score: " + sc);
//      double score = sc.forwards();
//      if (score <= 0) {
//        Log.info("score<0, exiting");
//        break;
//      }
      addEdgeToState(best);
      applied++;
    }
    Log.info("done adding positive-scoring HypEdges");
//    state.dbgShowEdges();
  }
  public void dbgRunInference() {
    dbgRunInference(false, 0);
  }

  public static class Step {
    public final HypEdge edge;
    public final Adjoints score;
    public final boolean gold;
    public Step(HypEdge e, Adjoints score, boolean gold) {
      this.edge = e;
      this.score = score;
      this.gold = gold;
    }
  }

  /**
   * Runs inference, records the HypEdge popped at every step, and adds gold
   * HypEdges to the state graph as it goes (pruning other edges which are
   * popped but not gold).
   *
   * You must have added labels before calling this method.
   *
   * Note on duplicates: this method will add duplicate HypEdges on the
   * assumption that their *features* may not be the same. You may have two
   * ways of deriving a particular fact, e.g.
   *   srl2'(s2,...) & event2'(e2,...) & ... => srl3(s2,e2,...)
   *   event2'(e2,...) & srl2'(s2,...) & ... => srl3(s2,e2,...)
   * Both state minimum requirements for the state graph based on the LHS, but
   * the features could be global: they can reflect on all parts of the state
   * graph, unless you design them otherwise.
   * If you KNOW your features only look at the LHS of the rule (and not further
   * into the state graph), then you can remove duplicates yourself.
   * Though these duplicates are present, oracle recall is handled properly.
   */
  public List<Step> recordOracleTrajectory() {
    if (goldEdges == null)
      throw new IllegalStateException("you must add labels, goldEdges==null");
    timer.start(REC_ORACLE_TRAJ);

    Labels.Perf perf = goldEdges.new Perf();
//    Set<HashableHypEdge> trajUniq = new HashSet<>();
    List<Step> traj = new ArrayList<>();
    while (agenda.size() > 0) {
      Pair<HypEdge, Adjoints> p = agenda.popBoth();
      HypEdge edge = p.get1();
      HashableHypEdge needle = new HashableHypEdge(edge);
//      if (!trajUniq.add(needle))
//        Log.warn("duplicate edge: " + edge);
      boolean gold = perf.add(edge);
      if (DEBUG)
        System.out.println("[recOrcl] gold=" + gold + " " + needle.hashDesc());
      traj.add(new Step(edge, p.get2(), gold));
      if (gold)
        addEdgeToState(edge);
    }
    Log.info("oracleRecall=" + perf.recall());
    System.out.println("goldRelCounts: " + goldEdges.getRelCounts());
    for (HypEdge fn : perf.getFalseNegatives()) {
      if ("srl1".equals(fn.getRelation().getName())) {
        int i = Integer.parseInt((String) fn.getTail(0).getValue());
        int j = Integer.parseInt((String) fn.getTail(1).getValue());
        System.out.println("\tfn: " + fn + "\t" + dbgGetSpan(i, j));
      } else {
        System.out.println("\tfn: " + fn);
      }
    }
    timer.stop(REC_ORACLE_TRAJ);
    return traj;
  }

  /**
   * @param i start token index inclusive
   * @param j end token index exclusive
   */
  public List<String> dbgGetSpan(int i, int j) {
    assert j >= i;
    Relation word2 = getEdgeType("word2");
    NodeType tokenNT = lookupNodeType("tokenIndex", false);
    List<String> s = new ArrayList<>();
    for (int t = i; t < j; t++) {
      Object value = String.valueOf(t).intern();
      HypNode tokenN = lookupNode(tokenNT, value, false);
      HypEdge wt = state.match1(0, word2, tokenN);
      String wordVal = (String) wt.getTail(1).getValue();
      s.add(wordVal);
    }
    return s;
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

  public static String stripComment(String line) {
    int c = line.indexOf('#');
    if (c >= 0)
      return line.substring(0, c);
    return line;
  }

  public List<Relation> readRelData(File f) throws IOException {
    if (!f.isFile())
      throw new IllegalArgumentException("not a file: " + f.getPath());
    Log.info("reading rel data from: " + f.getPath());
    try (BufferedReader r = FileUtil.getReader(f)) {
      return readRelData(r);
    }
  }

  /**
   * read in data like:
   * "def word2 <tokenIndx> <word>", "x word2 0 John", "y pos2 0 NNP", etc
   * and setup state/labels accordingly.
   *
   * TODO Use {@link RelationFileIterator} or at least {@link RelLine}
   *
   * @return a list of new {@link Relation}s defined in this file/reader.
   */
  public List<Relation> readRelData(BufferedReader r) throws IOException  {
    List<Relation> defs = new ArrayList<>();
    for (String line = r.readLine(); line != null; line = r.readLine()) { 
      Relation rr = readRelData(line);
      if (rr != null)
        defs.add(rr);
    }
    return defs;
  }

  public Relation readRelData(String line) {
    String relName;
    Relation def = null;
    line = stripComment(line);
    if (line.isEmpty())
      return null;
    String[] toks = line.split("\\s+");
    for (int i = 0; i < toks.length; i++)
      toks[i] = toks[i].intern();
    String command = toks[0];
    switch (command) {
    case "def":
      relName = toks[1];
      NodeType[] argTypes = new NodeType[toks.length - 2];
      for (int i = 0; i < argTypes.length; i++) {
        String argType = toks[i + 2];
        if (argType.charAt(0) == '<') {
          int n = argType.length();
          assert argType.charAt(n-1) == '>';
          argType = argType.substring(1, n-1);
        }
        argTypes[i] = this.lookupNodeType(argType, true);
      }
      def = new Relation(relName, argTypes);
      this.addEdgeType(def);
      break;
    case "schema":
    case "x":
    case "y":
      relName = toks[1];
      Relation rel = this.getEdgeType(relName);
      HypNode[] args = new HypNode[toks.length-2];
      for (int i = 0; i < args.length; i++) {
        // TODO Consider this:
        // How should we deserialize String => ???
        Object val = toks[i+2].intern();
        args[i] = this.lookupNode(rel.getTypeForArg(i), val, true);
      }
      HypEdge e = this.makeEdge(rel, args);
      if (command.equals("schema"))
        e = new HypEdge.WithProps(e, HypEdge.IS_SCHEMA);
      if (command.equals("y"))
        this.addLabel(e);
      else
        this.addEdgeToState(e);
      break;
    default:
      throw new RuntimeException("unknown-command: " + command);
    }
    return def;
  }

  /**
   * Use this rather than calling the {@link HypNode} constructor so that nodes
   * are guaranteed to be unique.
   */
  public HypNode lookupNode(NodeType nt, Object value, boolean addIfNotPresent) {
    assert !(value instanceof String) || value == ((String)value).intern();
    Pair<NodeType, Object> key = new Pair<>(nt, value);
    HypNode v = nodes.get(key);
    if (v == null && addIfNotPresent) {
      v = new HypNode(nt, value);
      nodes.put(key, v);
    }
    return v;
  }

  /**
   * Prefer lookupNode if you can. Throws exception if this node exists.
   */
  public void putNode(HypNode n) {
    assert !(n.getValue() instanceof String) || n.getValue() == ((String)n.getValue()).intern();
    Pair<NodeType, Object> key = new Pair<>(n.getNodeType(), n.getValue());
    HypNode old = nodes.put(key, n);
    if (old != null)
      throw new RuntimeException("duplicate: " + key);
  }

  /**
   * Use this rather than calling the {@link NodeType} constructor so that nodes
   * types are gauranteed to be unique.
   */
  public NodeType lookupNodeType(String name, boolean allowNewNodeType) {
//    if (DEBUG)
//      Log.info("name=" + name + " allowNewNodeType=" + allowNewNodeType);
    NodeType nt = nodeTypes.get(name);
    if (nt == null) {
      if (!allowNewNodeType)
        throw new RuntimeException("there is no NodeType called " + name);
      if (DEBUG)
        Log.info("adding NodeType for the first time: " + name);
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

  public void addTransitionGenerator(List<TKey> lhs, TransitionGenerator tg) {
    TKey[] lhsA = new TKey[lhs.size()];
    for (int i = 0; i < lhsA.length; i++)
      lhsA[i] = lhs.get(i);
    addTransitionGenerator(lhsA, tg);
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
        if (DEBUG)
          Log.info("missing: tail[" + i + "]=" + e.getTail(i));
        return false;
      }
    }
    return true;
  }

  public void addEdgeToState(HypEdge e) {
    if (DEBUG)
      Log.info(e.toString());
    assert nodesContains(e);
    state.add(e);
    TNode.match(this, e, trie);
  }

  public void addEdgeToAgenda(Pair<HypEdge, Adjoints> p) {
    addEdgeToAgenda(p.get1(), p.get2());
  }
  public void addEdgeToAgenda(HypEdge e, Adjoints score) {
    assert nodesContains(e);
    agenda.add(e, score);
  }

  /** returns its argument */
  public Relation addEdgeType(Relation r) {
    Relation old = relations.put(r.getName(), r);
    if (old != null)
      throw new IllegalStateException("already existed: " + r);
    return r;
  }
  public Relation getEdgeType(String name) {
    return getEdgeType(name, false);
  }
  public Relation getEdgeType(String name, boolean allowNull) {
    Relation r = relations.get(name);
    if (r == null && !allowNull)
      throw new IllegalArgumentException("no relation named: " + name);
    return r;
  }
  public List<Relation> getAllEdgeTypes() {
    List<Relation> l = new ArrayList<>();
    l.addAll(relations.values());
    return l;
  }

  public NodeType getWitnessNodeType(String relationName) {
    return getWitnessNodeType(getEdgeType(relationName));
  }
  public NodeType getWitnessNodeType(Relation relation) {
    String wntName = getWitnessNodeTypeName(relation);
    return lookupNodeType(wntName, true);
  }
  public static String getWitnessNodeTypeName(Relation relation) {
    return getWitnessNodeTypeName(relation.getName());
  }
  public static String getWitnessNodeTypeName(String relationName) {
    return ("witness-" + relationName).intern();
  }

  /**
   * @param lookupHypNodes says whether {@link HypNode}s should be looked up and
   * de-dup'd (this is the case for normal inference where you want, e.g.
   * (tokenIndex 5) to mean the same exact node regardless of where tokenIndex
   * and 5 came from) OR if you just want to call new HypNode (as is the case if
   * you just want to write an edge to a file).
   */
  public HypEdge makeEdge(RelLine line, boolean lookupHypNodes) {
    Relation r = getEdgeType(line.tokens[1]);
    assert r.getNumArgs() == line.tokens.length-2
        : "values=" + Arrays.toString(Arrays.copyOfRange(line.tokens, 2, line.tokens.length))
        + " does not match up with " + r.getDefinitionString()
        + " line=\"" + line.toLine() + "\""
        + " providence=" + line.providence;
    HypNode[] tail = new HypNode[r.getNumArgs()];
    for (int i = 0; i < tail.length; i++) {
      NodeType nt = r.getTypeForArg(i);
      Object value = line.tokens[i+2];
      if (lookupHypNodes)
        tail[i] = this.lookupNode(nt, value, true);
      else
        tail[i] = new HypNode(nt, value);
    }
    if (lookupHypNodes)
      return makeEdge(r, tail);
    return new HypEdge(r, null, tail);
  }
  public HypEdge makeEdge(String relationName, HypNode... tail) {
    Relation r = getEdgeType(relationName);
    return makeEdge(r, tail);
  }
  public HypEdge makeEdge(Relation r, HypNode... tail) {
    NodeType headType = getWitnessNodeType(r);
    Object encoded = r.encodeTail(tail);
    HypNode head = lookupNode(headType, encoded, true);
    return new HypEdge(r, head, tail);
  }

}
