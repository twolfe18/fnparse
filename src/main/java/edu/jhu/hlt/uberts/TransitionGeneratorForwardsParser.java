package edu.jhu.hlt.uberts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.TransitionGeneratorBackwardsParser.Rule;
import edu.jhu.hlt.uberts.TransitionGeneratorBackwardsParser.Term;
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

  public boolean verbose = false;

  static class Edge {
    Term ta, tb;
    int ca, cb;     // indices of common arg in ta/tb.argNames
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
//    Relation a, b;    // end points
//    String argName;
//    public Edge(String varName, Relation a, Relation b) {
//      if (a == b)
//        throw new IllegalArgumentException();
//      this.argName = varName;
//      this.a = a;
//      this.b = b;
//    }
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

  private static void add(Relation r, Edge e, Map<Relation, List<Edge>> adj) {
    List<Edge> l = adj.get(e.ra());
    if (l == null) {
      l = new ArrayList<>();
      adj.put(e.ra(), l);
    }
    l.add(e);
  }

  private static void add(Edge e, Map<Relation, List<Edge>> adj) {
    add(e.ra(), e, adj);
    add(e.rb(), e, adj);
  }

  private Map<Relation, List<Edge>> buildAdjacencyMatrix(Rule r) {
    if (verbose)
      Log.info("building adjacency matrix for rule: " + r);
    Map<Relation, List<Edge>> adj = new HashMap<>();
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
            add(new Edge(vn, ti, tj), adj);
      }
    }
    if (verbose)
      Log.info("done");
    return adj;
  }

  public List<TKey> parse(Rule r) {
    Map<Relation, List<Edge>> adj = buildAdjacencyMatrix(r);

    // Build order over Relations
    Map<Relation, Integer> relOrder = new HashMap<>();
    for (int i = 0; i < r.lhs.length; i++) {
      Term lt = r.lhs[i];
      Integer old = relOrder.put(lt.rel, i);
      assert old == null;
    }

    // Depth first search
    // Construct spanning forest
    List<TKey> pat = new ArrayList<>();
    Set<Relation> done = new HashSet<>();
    Deque<Relation> stack = new ArrayDeque<>();
    stack.push(r.lhs[0].rel);
    done.add(stack.peek());
    pat.add(new TKey(stack.peek()));
    while (done.size() < r.lhs.length) {
      if (stack.isEmpty())
        throw new RuntimeException("no spanning tree");
      Relation cur = stack.peek();
      List<Edge> neighbors = adj.get(cur);
      if (verbose) {
        Log.info("cur=" + cur);
        Log.info("adjacent=" + neighbors);
      }

      Relation next = null;
      Edge c2n = null;
      if (neighbors != null) {
        // Sort neighboring relations by their position in the LHS of the rule
        // First only take the neighbors we haven't visited
        List<Pair<Relation, Edge>> nextPoss = new ArrayList<>();
        for (Edge e : neighbors) {
          Relation n = e.ra() == cur ? e.rb() : e.ra();
          if (!done.contains(n))
            nextPoss.add(new Pair<>(n, e));
        }
//        Collections.sort(nextPoss, new Comparator<Relation>() {
        Collections.sort(nextPoss, new Comparator<Pair<Relation, Edge>>() {
          @Override
//          public int compare(Relation o1, Relation o2) {
          public int compare(Pair<Relation, Edge> p1, Pair<Relation, Edge> p2) {
            Relation o1 = p1.get1();
            Relation o2 = p2.get1();
            int i1 = relOrder.get(o1);
            int i2 = relOrder.get(o2);
            return i1 - i2;
          }
        });
        if (verbose)
          Log.info("nextPoss=" + nextPoss);
        if (!nextPoss.isEmpty()) {
          next = nextPoss.get(0).get1();
          c2n = nextPoss.get(0).get2();
        }
      }

      if (next == null) {
        if (verbose)
          Log.info("back track...");
        stack.pop();
        pat.add(TNode.GOTO_PARENT);
      } else {
        stack.push(next);
        done.add(next);
        pat.add(new TKey(c2n.argType()));
        pat.add(new TKey(next));
      }
    }
    return pat;
  }

  // TODO extract the values matched on the lhs (from GraphTraversalTrace) to
  // produce a new HypEdge.
  static class TG implements TransitionGenerator {
    private Rule r;
    @Override
    public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
//      lhsValues.
      return null;
    }
  }

  /*
   * I may have goofed here concerning the structure of the graph searched over.
   * I do want a spanning tree over RELATION nodes, but that is just a CONSTRAINT.
   * I really want a spanning tree of [HypNode|HypEdge]-node graphs.
   *
   * HNode = Either<HypNode, HypEdge>
   * HNodeType = Either<NodeType, Relation>
   * 
   * Currently  adjacent:Map<Relation, List<Edge>>
   * Need       adjacent:Map<HNodeType, List<Edge>>
   *
   * Edge connects two Relations, which is also wrong now.
   * Edge should connect two HNodeTypes.
   *
   * Done should also be Set<HNodeType>
   * Basically everywhere you see Relation, your really need HNodeType.
   */

  public static void main(String[] args) {
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
    Rule r = new Rule(Arrays.asList(ner3Term, succTerm, bioluTerm), newNer3Term);
    System.out.println("rule: " + r);

    TransitionGeneratorForwardsParser tfp = new TransitionGeneratorForwardsParser();
    List<TKey> lhs = tfp.parse(r);
    System.out.println("lhs:");
    for (TKey tk : lhs)
      System.out.println("\t" + tk);
  }
}
