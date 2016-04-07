package edu.jhu.hlt.uberts.auto;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.ArgMin;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.TNode;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;

/**
 * Parse a transition grammar expression like:
 *   ner3(i,t,b) & succ(i,j) & biolu(b,i,t,r) => ner3(j,r,i)
 *
 * Into a {@link TransitionGenerator} who's LHS is a {@link TKey} array which
 * can be inserted into a {@link TNode}.
 * @see Uberts#addTransitionGenerator(TKey[], TransitionGenerator)
 *
 * The method works by finding a spanning forest of HypEdges in the LHS of the
 * rule. Edges in this graph correspond to the presence of a shared variable
 * (e.g. i in the edge between ner3(i,t,b) and succ(i,j) above).
 *
 * A (maybe trivial) spanning forest always exists. We order siblings in a
 * tree in the same order they appear in the LHS of the rule (e.g. ner3 comes
 * before succ if we chose). We use the min_{n:tree} rank(n, LHS(rule)) for
 * ranking trees in a forest. Once we have a spanning tree we take a pre-order
 * traversal to get a string, which is then stored in a {@link TNode} trie.
 *
 * For the example above, the nodes form a chain: [succ, ner3, biolu].
 * We will choose ner3 as the root of the spanning tree since it is first in the
 * rule. We will choose the pre-order traversal:
 *   ner3         -> succ                    -> biolu
 *   ner3 -DOWN(i)-> succ -UP-> ner3 -DOWN(t)-> biolu
 *
 * In general, there may be shorter traversals, e.g. in this case:
 *   succ         -> ner3         -> biolu
 *   succ -DOWN(i)-> ner3 -DOWN(t)-> biolu
 *
 * But
 * 1) the sparsity of the relations matter (succ is pretty big :)
 * 2) we need a relation we're outputting first to ensure that all
 *    graph-match-via-traversals we find are unique.
 *
 * We assume that the user is smart when the grammar is created, and we don't
 * search over possible spanning-tree-traversals.
 *
 * @author travis
 */
public class TransitionGeneratorForwardsParser {

//  /** @deprecated only Edge used for finding the index of a common var name */
  static class Edge {
    Term ta, tb;      // end points
    int ca, cb;       // indices of common arg in ta/tb.argNames
    public Edge(String varName, Term ta, Term tb) {
      this.ta = ta;
      this.tb = tb;
      ca = -1;
      for (int i = 0; i < ta.argNames.length; i++) {
        String a = ta.argNames[i];
        if (a.equals(varName)) {
          assert ca < 0;
          ca = i;
        }
      }
      cb = -1;
      for (int i = 0; i < tb.argNames.length; i++) {
        String a = tb.argNames[i];
        if (a.equals(varName)) {
          assert cb < 0;
          cb = i;
        }
      }
    }
    public String argName() { return ta.argNames[ca]; }
    public NodeType argType() {
      NodeType nt = ta.rel.getTypeForArg(ca);
      assert nt == tb.rel.getTypeForArg(cb);
      return nt;
    }
    public Relation ra() { return ta.rel; }
    public Relation rb() { return tb.rel; }
    @Override
    public boolean equals(Object other) {
      if (other instanceof Edge) {
        Edge e = (Edge) other;
        if (spans(e.ra(), e.rb()) && argName().equals(e.argName()))
            return true;
      }
      return false;
    }
    public boolean spans(Relation a, Relation b) {
      return (this.ra() == a && this.rb() == b)
          || (this.ra() == b && this.rb() == a);
    }
    @Override
    public String toString() {
      return String.format("<Edge %s~%s~%s>", ra().getName(), argName(), rb().getName());
    }
  }


  public boolean verbose = false;
  private Map<HNodeType, List<HNodeType>> adj;
  private Map<String, HNodeType> uniqHNT = new HashMap<>();

  private void addOneDir(HNodeType a, HNodeType b) {
    List<HNodeType> n = adj.get(a);
    if (n == null) {
      n = new ArrayList<>();
      adj.put(a, n);
    }
    n.add(b);
  }
  private void addBothDir(HNodeType a, HNodeType b) {
    addOneDir(a, b);
    addOneDir(b, a);
  }

  private void add(String commonVar, Term ti, Term tj) {
    // ti = ner3(i,t,b)
    // tj = succ(i,j)
    // commonVar = i
    Edge e = new Edge(commonVar, ti, tj);
    HNodeType vi = lookupHNT(e.ca, e.argType());
    HNodeType ri = lookupHNT(e.ca, ti.rel);
    HNodeType vj = lookupHNT(e.cb, e.argType());
    HNodeType rj = lookupHNT(e.cb, tj.rel);
    addBothDir(ri, vi);
    addBothDir(vj, rj);
  }

  private HNodeType lookupHNT(int argPos, NodeType nt) {
    String key = "nt/" + argPos + "/" + nt.getName();
    HNodeType val = uniqHNT.get(key);
    if (val == null) {
      val = new HNodeType(argPos, nt);
      uniqHNT.put(key, val);
    }
    return val;
  }
  private HNodeType lookupHNT(int argPos, Relation r) {
    String key = "rel/" + argPos + "/" + r.getName();
    HNodeType val = uniqHNT.get(key);
    if (val == null) {
      val = new HNodeType(argPos, r);
      uniqHNT.put(key, val);
    }
    return val;
  }

  private void buildAdjacencyMatrix(Rule r) {
    if (verbose)
      Log.info("building adjacency matrix for rule: " + r);
    adj = new HashMap<>();
    uniqHNT = new HashMap<>();
    List<Term> ts = r.getAllTerms();
    int n = ts.size();
    for (int i = 0; i < n - 1; i++) {
      for (int j = i + 1; j < n; j++) {
        Term ti = ts.get(i);
        Term tj = ts.get(j);
        Set<String> commonVarNames = new HashSet<>();
        for (String vn : ti.argNames)
          commonVarNames.add(vn);
        for (String vn : tj.argNames)
          if (commonVarNames.contains(vn))
            add(vn, ti, tj);
      }
    }
    if (verbose)
      Log.info("done");
  }

  private int order(Relation r, Rule rule) {
    int i = 0;
    while (i < rule.lhs.length) {
      Relation r2 = rule.lhs[i].rel;
      if (r == r2)
        break;
      i++;
    }
    return i;
  }

  static class LHS {
    private Rule rule;
    private List<TKey> pat;
    private Map<Relation, Integer> rel2patIdx;
    public LHS(Rule r) {
      rule = r;
      pat = new ArrayList<>();
      rel2patIdx = new HashMap<>();
    }
    public List<TKey> getPath() {
      return pat;
    }
    public void add(TKey tkey) {
      pat.add(tkey);
    }
    public void add(HNodeType hnt) {
      if (hnt.isRelation()) {
        int i = pat.size();
        Integer old = rel2patIdx.put(hnt.getRight(), i);
        assert old == null;
      }
      pat.add(hnt.getTKey());
    }
//    /**
//     * Return the index of this relation in the pattern. This value can be used
//     * with {@link GraphTraversalTrace#getBoundEdge(int)} to retrieve the
//     * {@link HypEdge} which provides bound values for a {@link Relation}.
//     */
//    public int getIndexOfRelationInLhsPat(Relation r) {
//      return rel2patIdx.get(r);
//    }
    public HypEdge getBoundValue(Relation r, GraphTraversalTrace gtt) {
      Integer patIdx = rel2patIdx.get(r);
      if (patIdx == null) {
        throw new RuntimeException("no index for: r=" + r
            + " rule=" + rule
            + " pat=" + pat
            + " rel2patIdx=" + rel2patIdx);
      }
      HypEdge e = gtt.getBoundEdge(patIdx);
      assert e != null;
      return e;
    }
    public Rule getRule() {
      return rule;
    }
  }

  public LHS parse(Rule r) {
    // Build new adjacency matrix
    buildAdjacencyMatrix(r);

    // Depth first search
    // Construct spanning forest
    LHS pat = new LHS(r);
    Set<Relation> done = new HashSet<>();
    Deque<HNodeType> stack = new ArrayDeque<>();
    stack.push(lookupHNT(State.HEAD_ARG_POS, r.lhs[0].rel));
    done.add(r.lhs[0].rel);
    pat.add(stack.peek());
    while (done.size() < r.lhs.length) {
      if (stack.isEmpty())
        throw new RuntimeException("no spanning tree");
      HNodeType cur = stack.peek();
      List<HNodeType> neighbors = adj.get(cur);
      if (verbose) {
        Log.info("cur=" + cur);
        Log.info("adjacent=" + neighbors);
      }

      HNodeType next = null;
      if (neighbors != null) {

        // neighbors:[HNodeType] is either all Relations or all NodeType
        // -> will have opposite type of cur
        ArgMin<HNodeType> m = new ArgMin<>();
        if (cur.isRelation()) {
          // if NodeTypes: sort them by min_{r : relations not visited} index(r in LHS)
          for (HNodeType ntHNT : neighbors) {
            assert ntHNT.isNodeType();
            for (HNodeType relHNT : adj.get(ntHNT)) {
              if (relHNT == cur)
                continue;
              if (!done.contains(relHNT.getRight()))
                m.offer(ntHNT, order(relHNT.getRight(), r));
            }
          }
        } else {
          // if Relations: sort them by their order in the rule LHS
          // filter out the relations we've seen
          for (HNodeType relHNT : neighbors) {
            assert relHNT.isRelation();
            Relation rel = relHNT.getRight();
            if (!done.contains(rel))
              m.offer(relHNT, order(rel, r));
          }
        }
        next = m.get();
      }

      if (next == null) {
        if (verbose)
          Log.info("back track...");
        stack.pop();
        pat.add(TNode.GOTO_PARENT);
      } else {
        stack.push(next);
        if (next.isRelation())
          done.add(next.getRight());
        pat.add(next);
      }
    }
    return pat;
  }

  public Pair<List<TKey>, TG> parse2(Rule r, Uberts u) {
    LHS lhs = parse(r);
    TG tg = new TG(lhs, u);
    Log.info("pat:\n\t" + StringUtils.join("\n\t", lhs.getPath()));
    return new Pair<>(lhs.getPath(), tg);
  }

  /**
   * Extract the values matched on the lhs (from GraphTraversalTrace) to
   * produce a new HypEdge.
   */
  public static class TG implements TransitionGenerator {
    public static boolean VERBOSE = false;

    private LHS match;
    private Uberts u;

    public FeatureExtractionFactor feats = null;

    public TG(LHS match, Uberts u) {
      this.match = match;
      this.u = u;
    }

    @Override
    public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {

      // In the TKey[] I generated, I don't know where all the terms are
      // need: Relation -> index in GraphTraversalTrace for HypEdge

      // go through lhs, for every arg, project in to rhs with Rule.lhs2rhs
      int numRhsBound = 0;
      Rule rule = match.getRule();
      if (VERBOSE) {
        Log.info("rule=" + rule);
        Log.info("pat=\n\t" + StringUtils.join("\n\t", match.pat));
      }
      Object[] rhsVals = new Object[rule.rhs.getNumArgs()];
      for (int ti = 0; ti < rule.lhs.length; ti++) {
        for (int ai = 0; ai < rule.lhs2rhs[ti].length; ai++) {
          int rhsIdx = rule.lhs2rhs[ti][ai];
          if (VERBOSE)
            Log.info(rule.lhs[ti] + " " + ai + "th arg maps to the " + rhsIdx + " of the rhs term: " + rule.rhs);
          if (rhsIdx < 0) {
            // This argument is not bound on the RHS
            continue;
          }

          // Lookup the bound value
          Relation r = rule.lhs[ti].rel;
          HypEdge e = match.getBoundValue(r, lhsValues);
          assert e.getRelation() == r;
          Object val = e.getTail(ai).getValue();
          if (VERBOSE)
            Log.info("extracted val=" + val + " from " + e);

          // Apply these values as RHS args
          if (rhsVals[rhsIdx] == null) {
            rhsVals[rhsIdx] = val;
            numRhsBound++;
          }
          assert rhsVals[rhsIdx] == val : "rhsVals[" + rhsIdx + "]=" + rhsVals[rhsIdx] + " newVal=" + val;
        }
      }
      assert numRhsBound == rule.rhs.getNumArgs();

      // Resolve rhs values to HypNodes
      boolean addIfNotPresent = true;
      HypNode[] rhsNodes = new HypNode[rhsVals.length];
      for (int i = 0; i < rhsNodes.length; i++) {
        NodeType nt = rule.rhs.rel.getTypeForArg(i);
        rhsNodes[i] = u.lookupNode(nt, rhsVals[i], addIfNotPresent);
      }

      HypEdge e = u.makeEdge(rule.rhs.rel, rhsNodes);
      Adjoints sc = Adjoints.Constant.ONE;
      if (feats != null) {
//        sc = Adjoints.cacheIfNeeded(feats.score(e, u));
        sc = feats.score(e, u);
      }
      Pair<HypEdge, Adjoints> p = new Pair<>(e, sc);
      return Arrays.asList(p);
    }
  }

  public static void main(String[] args) {
    TransitionGeneratorForwardsParser tfp = new TransitionGeneratorForwardsParser();
//    tfp.verbose = true;

    // ner3(i,ti,bi) & succ(i,j) & biolu(bi,ti,bj,tj) => ner3(j,tj,bj)
    NodeType tokenIndexNT = new NodeType("tokenIndex");
    NodeType nerTagNT = new NodeType("nerTag");
    NodeType bioTagNT = new NodeType("bioTag");
    Relation ner3 = new Relation("ner3", tokenIndexNT, nerTagNT, bioTagNT);
    Relation succ = new Relation("succ", tokenIndexNT, tokenIndexNT);
    Relation biolu = new Relation("biolu", bioTagNT, nerTagNT, bioTagNT, nerTagNT);
    Term ner3Term = new Term(ner3, "i", "ti", "bi");
    Term succTerm = new Term(succ, "i", "j");
    Term bioluTerm = new Term(biolu, "bi", "ti", "bj", "tj");
    Term newNer3Term = new Term(ner3, "j", "tj", "bj");

    System.out.println("first/most natural way to write it:");
    Rule r = new Rule(Arrays.asList(ner3Term, succTerm, bioluTerm), newNer3Term);
    System.out.println("rule: " + r);
    List<TKey> lhs = tfp.parse(r).getPath();
    System.out.println("lhs:");
    for (TKey tk : lhs)
      System.out.println("\t" + tk);

    System.out.println();

    System.out.println("another way (no real benefit):");
    Rule r2 = new Rule(Arrays.asList(ner3Term, bioluTerm, succTerm), newNer3Term);
    System.out.println("rule2: " + r2);
    List<TKey> lhs2 = tfp.parse(r2).getPath();
    System.out.println("lhs2:");
    for (TKey tk : lhs2)
      System.out.println("\t" + tk);

    System.out.println();

//    System.out.println("shortest path, but doesn't start with ner3, so this will fire every time");
    System.out.println("this optimized path will never fire!");
    System.out.println("the reason is that uberts will constrain the first relation in the path to match the new edge");
    System.out.println("this rule will fire every time biolu is updated, but not on ner3 updates");
    Rule r3 = new Rule(Arrays.asList(bioluTerm, ner3Term, succTerm), newNer3Term);
    System.out.println("rule3: " + r3);
    List<TKey> lhs3 = tfp.parse(r3).getPath();
    System.out.println("lhs3:");
    for (TKey tk : lhs3)
      System.out.println("\t" + tk);
  }
}
