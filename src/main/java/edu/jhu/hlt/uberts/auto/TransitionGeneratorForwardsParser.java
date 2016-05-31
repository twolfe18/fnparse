package edu.jhu.hlt.uberts.auto;

import java.io.File;
import java.io.IOException;
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
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.tutils.ArgMin;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Relation.EqualityArray;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.TNode;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.factor.LocalFactor;
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
 * Supports Hobbs' prime notation, e.g.
 *   srl2'(s2,s1,e1) & event2'(e2,e1,f) & role(f,k) => srl3(s2,e2,k)
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
  public static boolean DEBUG = false;

  // OLD_WAY is broken
  public static boolean OLD_WAY = false;

  /**
   * Used for finding the index of a common var name.
   * @deprecated Almost no reason to use this, you should probably be using
   * {@link TypeInference}.
   */
  static class Edge {
    Term ta, tb;      // end points
    int ca, cb;       // indices of common arg in ta/tb.argNames
    public Edge(String varName, Term ta, Term tb) {
      this.ta = ta;
      this.tb = tb;
      if (varName.equals(ta.factArgName)) {
        ca = Integer.MAX_VALUE;
      } else {
        ca = -1;
        for (int i = 0; i < ta.argNames.length; i++) {
          String a = ta.argNames[i];
          if (a.equals(varName)) {
            assert ca < 0;
            ca = i;
          }
        }
      }
      if (varName.equals(tb.factArgName)) {
        cb = Integer.MAX_VALUE;
      } else {
        cb = -1;
        for (int i = 0; i < tb.argNames.length; i++) {
          String a = tb.argNames[i];
          if (a.equals(varName)) {
            assert cb < 0;
            cb = i;
          }
        }
      }
    }
    public boolean isValid() {
      return ca >= 0 && cb >= 0;
    }
    public String argName() {
      if (ca == Integer.MAX_VALUE) {
//        return Uberts.getWitnessNodeTypeName(ta.rel);
        return tb.argNames[cb];
      }
      return ta.argNames[ca];
    }
    public NodeType argType() {
      if (ca == Integer.MAX_VALUE)
        return tb.rel.getTypeForArg(cb);
      if (cb == Integer.MAX_VALUE)
        return ta.rel.getTypeForArg(ca);
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



  /*
   * There are two graphs that we care about:
   * 1) auto.Arg with the same name (and therefore type), see TypeInference
   * 2) NodeType -- Relation, which is the graph we want to walk for LHS/TransitionGenerator
   *
   * This class is concerned with 2, and specifically creating a linearization
   * of a spanning tree of only the Relation nodes in 2 (we only need the Relations
   * since TNode.match will return us the HypEdges which match, and therefore
   * we get all the values with the HypEdge).
   *
   * Its important to note that we are are talking about SETS OF HYPEDGES, not
   * a Relation. In rules with a LHS containing two instances of the same Relation,
   * they will be matched to two distinct HypEdges (who happen to have the same
   * Relation).
   */

  static class Graph3 {
    Uberts u;
    Rule rule;

    Map<TermNode, List<ArgNode>> edgeTA;
    Map<ArgNode, List<TermNode>> edgeAT;
    Map<ArgNode, List<ArgNode>> edgeAA;

    ArgNode[][] argNodes;
    ArgNode[] termHeadNodes;
    TermNode[] termNodes;

    PriorityQueue<TermNode> notDone;
    boolean[] notDoneTermIdx;
    Set<Object> dfsSeen;

    static class IDFCATP {
      TermNode t1, t2;
      ArgNode a1, a2;
      public IDFCATP(TermNode t1, ArgNode a1, ArgNode a2, TermNode t2) {
        this.t1 = t1;
        this.a1 = a1;
        this.a2 = a2;
        this.t2 = t2;
      }
      public NodeType getCommonNodeType() {
        assert a1.getNodeType() == a2.getNodeType();
        return a1.getNodeType();
      }
      private TKey t, ta, at;
      public TKey getT() {
        if (t == null)
          t = new TKey(State.HEAD_ARG_POS, t1.getRelation());
        return t;
      }
      public TKey getTA() {
        if (ta == null)
          ta = new TKey(a1.argIdx, a1.getNodeType());
        return ta;
      }
      public TKey getAT() {
        if (at == null) {
          int ap = a2.argIdx < 0 ? State.HEAD_ARG_POS : a2.argIdx;
          at = new TKey(ap, t2.getRelation());
        }
        return at;
      }
      public static boolean eq(IDFCATP a, IDFCATP b) {
        return a.t1 == b.t1 && a.t2 == b.t2;
      }
      public static boolean chain(IDFCATP first, IDFCATP second) {
        return first.t2 == second.t1;
      }
      public String toString() {
        return t1 + " -> " + a1 + " -> " + a2 + " -> " + t2;
      }
    }

    public void halfRecursive(TermNode root, LHS lhsAddTo) {
      neighborsAUnion = true;

      // Walk the graph to get a spanning tree as a set of edges
      List<IDFCATP> trace = new ArrayList<>();
      Deque<Object> stack = new ArrayDeque<>();
      stack.push(root);
      Set<Object> seen = new HashSet<>();
      outer:
      while (!stack.isEmpty()) {
        Object cur = stack.pop();
        Object prev = stack.isEmpty() ? null : stack.peek();
        stack.push(cur);
        if (DEBUG)
          Log.info("prev=" + prev + " cur=" + cur + " stack.size=" + stack.size());
        boolean newCur = seen.add(cur);

        if (cur instanceof TermNode && cur != root && newCur) {
          // we made it!
          TermNode tSink = (TermNode) stack.pop();
          ArgNode aSink = (ArgNode) stack.pop();
          ArgNode aSource = (ArgNode) stack.pop();
          TermNode tSource = (TermNode) stack.peek();
          stack.push(aSource);
          stack.push(aSink);
          stack.push(tSink);
          IDFCATP i = new IDFCATP(tSource, aSource, aSink, tSink);
          if (trace.isEmpty() || !IDFCATP.eq(trace.get(trace.size()-1), i)) {
            if (DEBUG)
              Log.info(tSource + " -> " + aSource + " -> " + aSink + " -> " + tSink);
            trace.add(i);
          }
        }

        List<?> neighbors = neighborsFUUUUU(cur, prev);
        if (neighbors != null) {
          for (Object n : neighbors) {
            if (!seen.contains(n)) {
              stack.push(n);
              continue outer;
            } else {
              if (DEBUG)
                Log.info("skipping " + n);
            }
          }
        }
        stack.pop();
      }


      // Linearize the set of edges
      if (trace.isEmpty()) {
        // Special case, no t1->...->t2 walk, just include the root
        lhsAddTo.add(new TKey(State.HEAD_ARG_POS, root.getRelation()), root.termIdx);
        notDone.remove(root);
      } else {
        stack = null; // this is going to cause typo bugs
        Deque<IDFCATP> st = new ArrayDeque<>();
        outer:
        for (int i = 0; i < trace.size(); i++) {
          IDFCATP prev = null;
          IDFCATP cur = trace.get(i);
          while (!st.isEmpty()) {
            prev = st.peek();
            if (prev.t2 == cur.t1) {
              break;
            }
            st.pop();
            lhsAddTo.add(TNode.GOTO_PARENT, -1);  // back to NT
            // Maybe we don't need to go all the way to prev.t1
            if (prev.getCommonNodeType() == cur.getCommonNodeType()) {
              int ap = cur.a2.argIdx < 0 ? State.HEAD_ARG_POS : cur.a2.argIdx;
              lhsAddTo.add(new TKey(ap, cur.t2.getRelation()), cur.t2.termIdx);
              notDone.remove(cur.t2);
              prev = cur;
              st.push(cur);
              continue outer;
            } else if (prev.t1 == cur.t1) {
              lhsAddTo.add(TNode.GOTO_PARENT, -1);  // back to Rel
              prev = cur;
              break;
            } else {
              prev = null;
              lhsAddTo.add(TNode.GOTO_PARENT, -1);  // back to Rel
            }
          }
          if (prev == null) {
            lhsAddTo.add(new TKey(State.HEAD_ARG_POS, cur.t1.getRelation()), cur.t1.termIdx);
            notDone.remove(cur.t1);
          }
          int ap1 = cur.a1.argIdx < 0 ? State.HEAD_ARG_POS : cur.a1.argIdx;
          lhsAddTo.add(new TKey(ap1, cur.getCommonNodeType()), -1);
          int ap2 = cur.a2.argIdx < 0 ? State.HEAD_ARG_POS : cur.a2.argIdx;
          lhsAddTo.add(new TKey(ap2, cur.t2.getRelation()), cur.t2.termIdx);
          notDone.remove(cur.t2);
          st.push(cur);
        }
      }
      neighborsAUnion = false;
    }

    public int leftmostA(List<ArgNode> args, Object exclude) {
      int min = rule.lhs.length;
      if (args == null)
        return min;
      for (ArgNode a : args) {
        for (TermNode t : edgeAT.get(a)) {
          if (notDoneTermIdx[t.termIdx] && t.termIdx < min && t != exclude) {
            min = a.termIdx;
//            Log.info("a=" + a + " newMin=" + t + " exclude=" + exclude);
          }
        }
      }
      return min;
    }
    public int leftmostT(List<TermNode> terms) {
      int min = rule.lhs.length;
      if (terms == null)
        return min;
      for (TermNode t : terms) {
        if (notDoneTermIdx[t.termIdx] && t.termIdx < min)
          min = t.termIdx;
      }
      return min;
    }

    private List<?> neighborsFUUUUU(Object cur, Object prev) {
      if (prev instanceof ArgNode && cur instanceof ArgNode) {
        // AT
        Comparator<TermNode> cAT = new Comparator<TermNode>() {
          @Override
          public int compare(TermNode o1, TermNode o2) {
            return o1.termIdx - o2.termIdx;
          }
        };
        List<TermNode> lAT = edgeAT.get(cur);
        if (lAT == null) lAT = Collections.emptyList();
        Collections.sort(lAT, cAT);
        if (DEBUG)
          Log.info("leaving AT " + cur + ": " + lAT);
        return lAT;

      } else if (prev instanceof TermNode && cur instanceof ArgNode) {
        // AA
        Comparator<ArgNode> c = new Comparator<ArgNode>() {
          @Override
          public int compare(ArgNode o1, ArgNode o2) {
            List<TermNode> l1 = edgeAT.get(o1);
            List<TermNode> l2 = edgeAT.get(o2);
            return leftmostT(l1) - leftmostT(l2);
          }
        };
        List<ArgNode> lAA = edgeAA.get(cur);
        if (lAA == null) lAA = Collections.emptyList();
        Collections.sort(lAA, c);
        if (DEBUG)
          Log.info("leaving AA " + cur + ": " + lAA);
        return lAA;
        
      } else {
        // TA
        Comparator<ArgNode> c = new Comparator<ArgNode>() {
          @Override
          public int compare(ArgNode o1, ArgNode o2) {
            List<ArgNode> a1 = edgeAA.get(o1);
            List<ArgNode> a2 = edgeAA.get(o2);
//            return leftmostA(a1, (TermNode) prev) - leftmostA(a2, (TermNode) prev);
            return leftmostA(a1, null) - leftmostA(a2, null);
          }
        };
        List<ArgNode> l = edgeTA.get(cur);
        if (l == null) l = Collections.emptyList();
        Collections.sort(l, c);
        if (DEBUG)
          Log.info("leaving TA " + cur + ": " + l);
        return l;
      }
    }

    boolean neighborsAUnion = false;
    private List<?> neighborsA(Object key) {

      Comparator<TermNode> cAT = new Comparator<TermNode>() {
        @Override
        public int compare(TermNode o1, TermNode o2) {
          return o1.termIdx - o2.termIdx;
        }
      };
      List<TermNode> lAT = edgeAT.get(key);
      Collections.sort(lAT, cAT);
      if (DEBUG)
        Log.info("leaving AT " + key + ": " + lAT);

      Comparator<ArgNode> c = new Comparator<ArgNode>() {
        @Override
        public int compare(ArgNode o1, ArgNode o2) {
          List<TermNode> l1 = edgeAT.get(o1);
          List<TermNode> l2 = edgeAT.get(o2);
          return leftmostT(l1) - leftmostT(l2);
        }
      };
      List<ArgNode> lAA = edgeAA.get(key);
      if (lAA != null)
        Collections.sort(lAA, c);
      if (DEBUG)
        Log.info("leaving AA " + key + ": " + lAA);

      List<Object> l = new ArrayList<>();
      if (lAT != null) l.addAll(lAT);
      if (lAA != null) l.addAll(lAA);
      return l;
    }

    private List<?> neighbors(Object key) {
      if (DEBUG)
        Log.info("of " + key + " ntMaybe=" + ntMaybe + " ntMaybeFrom=" + ntMaybeFrom);
      
      
      if (neighborsAUnion && key instanceof ArgNode)
        return neighborsA(key);
      
      
      // Using dynamic sorting because they depend on the set of visited nodes
      if (key instanceof TermNode) {
        Comparator<ArgNode> c = new Comparator<ArgNode>() {
          @Override
          public int compare(ArgNode o1, ArgNode o2) {
            List<ArgNode> a1 = edgeAA.get(o1);
            List<ArgNode> a2 = edgeAA.get(o2);
            return leftmostA(a1, (TermNode) key) - leftmostA(a2, (TermNode) key);
          }
        };
        List<ArgNode> l = edgeTA.get(key);
        if (l != null)
          Collections.sort(l, c);
        if (DEBUG)
          Log.info("leaving TA " + key + ": " + l);
        return l;
      } else if (key instanceof ArgNode) {
        if (ntMaybe != null) {
          // Then we've already seen one ArgNode in our DFS and the
          // next node we want is a TermNode, so only consider a->t edges
          Comparator<TermNode> cAT = new Comparator<TermNode>() {
            @Override
            public int compare(TermNode o1, TermNode o2) {
              return o1.termIdx - o2.termIdx;
            }
          };
          List<TermNode> l = edgeAT.get(key);
          Collections.sort(l, cAT);
          if (DEBUG)
            Log.info("leaving AT " + key + ": " + l);
          return l;
        } else {
          Comparator<ArgNode> c = new Comparator<ArgNode>() {
            @Override
            public int compare(ArgNode o1, ArgNode o2) {
              List<TermNode> l1 = edgeAT.get(o1);
              List<TermNode> l2 = edgeAT.get(o2);
              return leftmostT(l1) - leftmostT(l2);
            }
          };
          List<ArgNode> l = edgeAA.get(key);
          if (l != null)
            Collections.sort(l, c);
          if (DEBUG)
            Log.info("leaving AA " + key + ": " + l);
          return l;
        }
      } else {
        throw new RuntimeException();
      }
    }

    private void add(TermNode t, ArgNode a) {
      List<ArgNode> l = edgeTA.get(t);
      if (l == null) {
        l = new ArrayList<>();
        edgeTA.put(t, l);
      }
      l.add(a);
      List<TermNode> lt = edgeAT.get(a);
      if (lt == null) {
        lt = new ArrayList<>();
        edgeAT.put(a, lt);
      }
      lt.add(t);
    }

    private void add(ArgNode a1, ArgNode a2) {
      List<ArgNode> l = edgeAA.get(a1);
      if (l == null) {
        l = new ArrayList<>();
        edgeAA.put(a1, l);
      }
      l.add(a2);
      l = edgeAA.get(a2);
      if (l == null) {
        l = new ArrayList<>();
        edgeAA.put(a2, l);
      }
      l.add(a1);
    }

    public Graph3(Uberts u, Rule r) {
      this.u = u;
      this.rule = r;
      this.edgeAA = new HashMap<>();
      this.edgeTA = new HashMap<>();
      this.edgeAT = new HashMap<>();

      // Create nodes in an addressable collection
      int n = r.lhs.length;
      argNodes = new ArgNode[n][];
      termNodes = new TermNode[n];
      termHeadNodes = new ArgNode[n];
      for (int i = 0; i < n; i++) {
        termNodes[i] = new TermNode(i);
        int J = termNodes[i].getNumArgs();
        argNodes[i] = new ArgNode[J];
        for (int j = 0; j < J; j++)
          argNodes[i][j] = new ArgNode(i, j);
      }

      // Add ArgNode -- ArgNode edges
      boolean[] includeT2A = new boolean[n];
      // Regular arg -- regular arg
      for (int t1 = 0; t1 < n-1; t1++) {
        for (int t2 = t1+1; t2 < n; t2++) {
          int na1 = termNodes[t1].getNumArgs();
          int na2 = termNodes[t2].getNumArgs();
          for (int a1 = 0; a1 < na1; a1++) {
            for (int a2 = 0; a2 < na2; a2++) {
              ArgNode x = argNodes[t1][a1];
              ArgNode y = argNodes[t2][a2];
              if (x.getArgName().equals(y.getArgName())) {
                add(x, y);
                includeT2A[t1] = true;
                includeT2A[t2] = true;
              }
            }
          }
        }
      }
      // Fact arg -- regular arg
      for (int t1 = 0; t1 < n; t1++) {
        Term t1t = r.lhs[t1];
        if (t1t.factArgName != null) {
          // fact -> reg
//          ArgNode a1 = new ArgNode(t1, -1);
          ArgNode a1 = termHeadNodes[t1];
          if (a1 == null) {
            a1 = termHeadNodes[t1] = new ArgNode(t1, -1);
          }
          for (int t2 = 0; t2 < n; t2++) {
            if (t1 == t2)
              continue;
            Term t2t = r.lhs[t2];
            for (int j = 0; j < t2t.getNumArgs(); j++) {
//              ArgNode a2 = new ArgNode(t2, j);
              ArgNode a2 = argNodes[t2][j];
              if (t1t.factArgName.equals(a2.getArgName())) {
                add(a1, a2);
                includeT2A[t1] = true;
                includeT2A[t2] = true;
              }
            }
          }
        }
        // reg -> fact
        int na1 = t1t.getNumArgs();
        for (int a1 = 0; a1 < na1; a1++) {
          ArgNode an1 = argNodes[t1][a1];
          for (int t2 = 0; t2 < n; t2++) {
            if (t2 == t1)
              continue;
            Term t2t = r.lhs[t2];
            if (t2t.factArgName == null)
              continue;
            ArgNode anH2 = termHeadNodes[t2];
            if (anH2 == null) {
              anH2 = termHeadNodes[t2] = new ArgNode(t2, -1);
            }
            if (anH2.getArgName().equals(an1.getArgName())) {
              add(an1, anH2);
              includeT2A[t1] = true;
              includeT2A[t2] = true;
            }
          }
        }
      }

      // Add TermNode -- ArgNode edges for relevant TermNodes
      notDone = new PriorityQueue<>(new Comparator<TermNode>() {
        @Override
        public int compare(TermNode o1, TermNode o2) {
          return o1.termIdx - o2.termIdx;
        }
      });
      notDoneTermIdx = new boolean[n];
      for (int i = 0; i < n; i++) {
        TermNode t = new TermNode(i);
        notDone.add(t);
        notDoneTermIdx[i] = true;
        if (includeT2A[i]) {
          for (int j = 0; j < t.getNumArgs(); j++)
            add(t, argNodes[i][j]);
          if (termHeadNodes[i] != null)
            add(t, termHeadNodes[i]);
        }
      }


      boolean first = true;
      dfsTrace = new LHS(r);
      while (!notDone.isEmpty()) {
        if (!first)
          dfsTrace.gotoRoot();
        first = false;
        halfRecursive(notDone.poll(), dfsTrace);
      }
    }

    void dfs(Object cur) {
      if (DEBUG)
        Log.info("cur=" + cur);
      dfsSeen.add(cur);
      notDone.remove(cur);
      if (cur instanceof TermNode)
        notDoneTermIdx[((TermNode)cur).termIdx] = false;
      if (notDone.isEmpty())
        return;
      List<?> nei = neighbors(cur);
      if (nei != null) {
        boolean tried = cur instanceof TermNode; //false;
        for (Object n : nei) {
          if (!dfsSeen.contains(n)) {
            tried |= addToTrace(cur, n);
            dfs(n);
            if (notDone.isEmpty())
              return;
          } else {
            if (DEBUG)
              Log.info("seen, skipping: " + n);
          }
        }
        if (tried) {
          gotoParent(cur, true);
        } else {
          if (DEBUG)
            Log.info("not going to parent because tried=false, cur=" + cur);
        }
      }
    }

    public LHS getLHS() {
      return dfsTrace;
    }

    LHS dfsTrace;
    TKey ntMaybe = null;
    int ntMaybeFrom = -1;
    boolean addToTrace(Object cur, Object next) {
      if (DEBUG)
        Log.info("cur=" + cur + " next=" + next + " ntMaybe=" + ntMaybe + " ntMaybeFrom=" + ntMaybeFrom);
      if (cur instanceof TermNode && next instanceof TermNode) {
        throw new RuntimeException("you should not construct edges like this");
      } else if (cur instanceof TermNode && next instanceof ArgNode) {
        // Use another method for root -> term
        TermNode t = (TermNode) cur;
        ntMaybeFrom = t.termIdx;
        return false;
      } else if (cur instanceof ArgNode && next instanceof TermNode) {
        ArgNode n = (ArgNode) cur;
        TermNode t = (TermNode) next;
        assert ntMaybe != null;
        // Here we finally commit to the ->NT->Rel step since we've now spanned
        // Term/Relation -...-> Term/Relatation
        dfsTrace.add(ntMaybe, ntMaybeFrom);
        int argPos = n.argIdx < 0 ? State.HEAD_ARG_POS : n.argIdx;
        dfsTrace.add(new TKey(argPos, t.getRelation()), t.termIdx);
        ntMaybeFrom = t.termIdx;
        ntMaybe = null;
        return true;
      } else if (cur instanceof ArgNode && next instanceof ArgNode) {
        ArgNode from = (ArgNode) cur;
        ArgNode to = (ArgNode) next;
        assert to.getNodeType() == from.getNodeType();
        assert to.getArgName().equals(from.getArgName());
//        assert ntMaybeFrom >= 0;
        ntMaybeFrom = from.termIdx;
        ntMaybe = new TKey(from.argIdx, from.getNodeType());
        return false;
      } else {
        throw new RuntimeException("wat");
      }
    }

    void gotoParent(Object cur, boolean doubleUp) {
      if (DEBUG)
        Log.info("cur=" + cur + " ntMaybe=" + ntMaybe + " ntMaybeFrom=" + ntMaybeFrom);
      if (cur instanceof TermNode) {
        if (DEBUG)
          Log.info("GOTO_PARENT");
        dfsTrace.gotoParent();
        if (doubleUp)
          dfsTrace.gotoParent();
      } else if (ntMaybe != null) {
        ntMaybe = null;
      } else if (ntMaybeFrom >= 0) {
        ntMaybeFrom = -1;
      }
    }

    // SHOULD NOT NEED to care about this for DFS/spanning tree
    class TermNode {
      int termIdx;
      public TermNode(int termIdx) {
        this.termIdx = termIdx;
      }
      public int getNumArgs() {
        return rule.lhs[termIdx].getNumArgs();
      }
      public Relation getRelation() {
        return rule.lhs[termIdx].rel;
      }
      @Override
      public String toString() {
        return "(TermNode " + termIdx + "/" + getRelation().getName() + ")";
      }
    }
    // SHOULD NOT NEED to care about this for DFS/spanning tree
    class ArgNode {
      int termIdx;
      int argIdx;
      public ArgNode(int termIdx, int argIdx) {
        this.termIdx = termIdx;
        this.argIdx = argIdx;
      }
      public NodeType getNodeType() {
        if (argIdx < 0)
          return u.getWitnessNodeType(rule.lhs[termIdx].relName);
        return rule.lhs[termIdx].getArgType(argIdx);
      }
      public String getArgName() {
        if (argIdx < 0)
          return rule.lhs[termIdx].factArgName;
        return rule.lhs[termIdx].argNames[argIdx];
      }
      @Override
      public String toString() {
        return "(ArgNode " + termIdx + "/" + rule.lhs[termIdx].relName
            + " arg" + argIdx + "/" + getArgName() + ")";
      }
    }

    // Maybe a good(ish) way to explain it:
    // Make a spanning tree using DFS with a set of visited nodes
    // (represent the spanning tree as a set of edges)
    // Prune any leaves which are ArgNodes
    // (do this by removing edges that have an ArgNode end-point which is a leaf (has one outgoing edge))
    // Re-root the tree by selecting the first TermNode
    // Re-order the children by their termIdx (ascending)
    // Output a pre-order traversal of that tree.
  }

  /**
   * Sink-half of an edge in the HypNode-HypEdge bipartite graph.
   * HNodeType.argPos refers to the Relation/HypEdge end-point of this edge.
   *
   * Could be:
   * term -- arg  => argPos is w.r.t. term's relation
   * arg -- arg   => argPos is meaningless
   *
   * This class could be the node type in the following graph:
   * term -- arg
   * lhsTermIdx is always defined, argPos is always w.r.t. term's relation.
   *
   * @deprecated too confusing, can't fix.
   */
  private class TT {
    HNodeType hnt;
    int lhsTermIdx;
    int hc;
    public TT(HNodeType hnt, int lhsTermIdx) {
      this.hnt = hnt;
      this.lhsTermIdx = lhsTermIdx;
      this.hc = Hash.mix(hnt.hashCode(), lhsTermIdx);
    }
    @Override
    public int hashCode() {
      return hc;
    }
    @Override
    public boolean equals(Object other) {
      if (other instanceof TT) {
        TT t = (TT) other;
        return hnt == t.hnt && lhsTermIdx == t.lhsTermIdx;
      }
      return false;
    }
    @Override
    public String toString() {
      return "<TT " + hnt + " @" + lhsTermIdx + ">";
    }
  }

  public boolean verbose = false;
  private Rule rule;
  private Map<TT, LL<TT>> adj;
  private Map<String, HNodeType> uniqHNT = new HashMap<>();

  private void addOneDir(TT a, TT b) {
    adj.put(a, new LL<>(b, adj.get(a)));
  }
  private void addBothDir(TT a, TT b) {
    if (verbose)
      Log.info("adding edges between " + a + " and " + b);
    addOneDir(a, b);
    addOneDir(b, a);
  }

  private void add(String commonVar, int tiIdx, int tjIdx) {
    // For example:
    // ti = ner3(i,t,b)
    // tj = succ(i,j)
    // commonVar = i
    Term ti = rule.lhs[tiIdx];
    Term tj = rule.lhs[tjIdx];
    Edge e = new Edge(commonVar, ti, tj);
    TT vi = new TT(lookupHNT(e.ca, e.argType()), tiIdx);
    TT ri = new TT(lookupHNT(e.ca, ti.rel), tiIdx);
    TT vj = new TT(lookupHNT(e.cb, e.argType()), tjIdx);
    TT rj = new TT(lookupHNT(e.cb, tj.rel), tjIdx);
    addBothDir(ri, vi);
    addBothDir(vj, rj);
  }

  private HNodeType lookupHNT(int argPos, NodeType nt) {
    String key = "nt/" + argPos + "/" + nt.getName();
    if (verbose)
      Log.info("key=" + key);
    HNodeType val = uniqHNT.get(key);
    if (val == null) {
      val = new HNodeType(argPos, nt);
      uniqHNT.put(key, val);
    }
    return val;
  }
  private HNodeType lookupHNT(int argPos, Relation r) {
    String key = "rel/" + argPos + "/" + r.getName();
    if (verbose)
      Log.info("key=" + key);
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
    assert rule == null;
    rule = r;
    adj = new HashMap<>();
    uniqHNT = new HashMap<>();
    int n = r.lhs.length;
    for (int i = 0; i < n - 1; i++) {
      for (int j = i + 1; j < n; j++) {
        Term ti = r.lhs[i];
        Term tj = r.lhs[j];
        Set<String> commonVarNames = new HashSet<>();
        for (String vn : ti.argNames)
          commonVarNames.add(vn);
        if (ti.factArgName != null)
          commonVarNames.add(ti.factArgName);
        if (verbose) {
          Log.info("ti=" + ti + " tj=" + tj + " ti.argNames=" + commonVarNames);
        }
        for (String vn : tj.argNames)
          if (commonVarNames.contains(vn))
            add(vn, i, j);
        if (tj.factArgName != null && commonVarNames.contains(tj.factArgName))
          add(tj.factArgName, i, j);
      }
    }
    if (verbose)
      Log.info("done");
  }

  /**
   * Characterizes the binding of RHS values to LHS variables.
   */
  static class LHS {
    private Rule rule;
    private List<TKey> pat;
    private int[] lhsTermIdx2PatIdx;
    private int relsInPat;

    public LHS(Rule r) {
      relsInPat = 0;
      rule = r;
      pat = new ArrayList<>();
      lhsTermIdx2PatIdx = new int[r.lhs.length];
      Arrays.fill(lhsTermIdx2PatIdx, -1);
    }

    public List<TKey> getPath() {
      return pat;
    }

    public void gotoParent() {
      pat.add(TNode.GOTO_PARENT);
    }
    public void add(TKey key, int lhsTermIdx) {
      if (DEBUG)
        Log.info(key + " lhsTermIdx=" + lhsTermIdx);
      if (key.getMode() == TKey.RELATION) {
        assert lhsTermIdx >= 0 && lhsTermIdx < rule.lhs.length;
        assert lhsTermIdx2PatIdx[lhsTermIdx] == -1;
        lhsTermIdx2PatIdx[lhsTermIdx] = relsInPat++;
      }
      pat.add(key);
    }

    /**
     * Adds enough GOTO_PARENTs so that the resulting path returns to the root,
     * allowing you to extend that path to any other Relation in the state.
     */
    public void gotoRoot() {
      int depth = 0;
      for (TKey tk : pat) {
        if (tk == TNode.GOTO_PARENT) {
          depth--;
          assert depth >= 0;
        } else {
          depth++;
        }
      }
      for (int i = 0; i < depth; i++)
        gotoParent();
    }

    public HypEdge getBoundValue(int lhsTermIdx, GraphTraversalTrace gtt) {
      int patIdx = lhsTermIdx2PatIdx[lhsTermIdx];
      return gtt.getBoundEdge(patIdx);
    }

    public Rule getRule() {
      return rule;
    }

    /** If the last TKey is GOTO_PARENT, then remove it */
    public void maybeTrimGotoParent() {
      int n = pat.size();
      if (pat.get(n - 1) == TNode.GOTO_PARENT)
        pat.remove(n - 1);
      assert pat.get(pat.size() - 1) != TNode.GOTO_PARENT;
    }
  }

  /**
   * @deprecated Use parse2
   */
  public LHS parse(Rule r) {
    assert false : "use parse2, this is old and broken code";

    // Build new adjacency matrix
    buildAdjacencyMatrix(r);

    // Depth first search
    // Construct spanning forest
    if (verbose)
      Log.info("parsing: " + r);
    LHS pat = new LHS(r);
    Set<TT> done2 = new HashSet<>();
    Deque<TT> stack = new ArrayDeque<>();
    while (done2.size() < r.lhs.length) {

      if (stack.isEmpty()) {
        // Find the first LHS term whose Relation we're not done with.
        for (int i = 0; i < r.lhs.length; i++) {
          Relation rel = r.lhs[i].rel;
          HNodeType hnt = lookupHNT(State.HEAD_ARG_POS, rel);
          TT tt = new TT(hnt, i);
          if (!done2.contains(rel)) {
            stack.push(tt);
            done2.add(tt);
            pat.add(hnt.getTKey(), i);
            break;
          }
        }
      }

      TT cur2 = stack.peek();
      LL<TT> neighbors2 = adj.get(cur2);
      if (verbose) {
        Log.info("cur=" + cur2);
        Log.info("cur.isRelation=" + cur2.hnt.isRelation());
        Log.info("neighbors=" + neighbors2);
      }

      TT next = null;
      if (neighbors2 != null) {
        // neighbors:[HNodeType] is either all Relations or all NodeType
        // -> will have opposite type of cur
        ArgMin<TT> m = new ArgMin<>();
        if (cur2.hnt.isRelation()) {
          // if NodeTypes: sort them by min_{r : relations not visited} index(r in LHS)
          for (LL<TT> ncur = neighbors2; ncur != null; ncur = ncur.next) {
            TT ttcur = ncur.item;
            HNodeType ntHNT = ttcur.hnt;
            assert ntHNT.isNodeType();
            for (LL<TT> relcur = adj.get(ttcur); relcur != null; relcur = relcur.next) {
              if (verbose) {
                Log.info("relHNT=" + relcur.item + " (relHNT==cur)=" + (relcur.item == ttcur)
                    + " done.contains(relHNT.getRight)="
                    + done2.contains(relcur.item));
              }
              if (relcur.item == ttcur)
                continue;
              if (!done2.contains(relcur.item))
                m.offer(ttcur, relcur.item.lhsTermIdx);
            }
          }
        } else {
          // if Relations: sort them by their order in the rule LHS
          // filter out the relations we've seen
          for (LL<TT> ncur = neighbors2; ncur != null; ncur = ncur.next) {
            assert ncur.item.hnt.isRelation();
            Relation rel = ncur.item.hnt.getRight();
            boolean d = done2.contains(ncur.item);
            if (verbose)
              Log.info("\trel=" + rel + " done=" + d);
            if (!d)
              m.offer(ncur.item, ncur.item.lhsTermIdx);
          }
        }
        next = m.get();
      }

      if (next == null) {
        if (verbose)
          Log.info("back track...");
        stack.pop();
        pat.gotoParent();
      } else {
        stack.push(next);
        if (next.hnt.isRelation()) {
          done2.add(next);
        }
        pat.add(next.hnt.getTKey(), next.lhsTermIdx);
      }
    }
    pat.maybeTrimGotoParent();

    // cleanup
    adj = null;
    rule = null;
    uniqHNT = null;

    return pat;
  }

  public Pair<List<TKey>, TG> parse2(Rule r, Uberts u) {

//    LHS lhs = parse(r);
    Graph3 g3;
    try {
      g3 = new Graph3(u, r);
    } catch (Exception e) {
      throw new RuntimeException("failed parsing " + r, e);
    }
    LHS lhs = g3.getLHS();

    TG tg = new TG(lhs, r, u);
    if (DEBUG) {
      Log.info("rule: " + r);
      Log.info("pat:\n\t" + StringUtils.join("\n\t", lhs.getPath()));
    }
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
    private Rule rule;
    public LocalFactor feats = null;

    /*
     * If non-null, then take the score of every LHS HypEdge/fact which appears
     * in a LHS and has a relation in this set, and add it to the score of the
     * derived edge.
     *
     * in a & b => c
     *   score(c) += feats(c)
     *   score(c) += 1/2 score(a) + 1/2 score(b)  if a,b in addLhsScoreToRhsScore
     */
    Set<Relation> addLhsScoreToRhsScore = new HashSet<>();
    double lhsInRhsScoreScale = 0.0;

    public TG(LHS match, Rule rule, Uberts u) {
      this.match = match;
      this.rule = rule;
      this.u = u;
    }

    public Rule getRule() {
      return rule;
    }

    @Override
    public String toString() {
      return "(TG for " + rule + ")";
    }

    @Override
    public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {

      // In the TKey[] I generated, I don't know where all the terms are
      // need: Relation -> index in GraphTraversalTrace for HypEdge

      List<HypEdge> builtLhsEdges = null;
      if (lhsInRhsScoreScale > 0)
        builtLhsEdges = new ArrayList<>();

      // go through lhs, for every arg, project in to rhs with Rule.lhs2rhs
      int numRhsBound = 0;
      Rule rule = match.getRule();
      if (VERBOSE) {
        Log.info("rule=" + rule);
        Log.info("pat=\n\t" + StringUtils.join("\n\t", match.pat));
      }
      HypNode[] rhsNodes = new HypNode[rule.rhs.getNumArgs()];
      for (int ti = 0; ti < rule.lhs.length; ti++) {

        Relation r = rule.lhs[ti].rel;
        HypEdge e = match.getBoundValue(ti, lhsValues);
        assert e.getRelation() == r;
        if (lhsInRhsScoreScale > 0 && addLhsScoreToRhsScore.contains(e.getRelation()))
          builtLhsEdges.add(e);

        // Event/fact/witness variable
        int fRhsIdx = rule.lhsFact2rhs[ti];
        if (fRhsIdx >= 0) {
          // Lookup the bound value
          Object val = e.getHead().getValue();
          assert val instanceof EqualityArray;
          if (VERBOSE)
            Log.info("extracted val=" + val + " from " + e);

          // Apply these values as RHS args
          if (rhsNodes[fRhsIdx] == null) {
            rhsNodes[fRhsIdx] = e.getHead();
            numRhsBound++;
          }
          assert rhsNodes[fRhsIdx] == e.getHead();
        }

        // Tail arguments
        for (int ai = 0; ai < rule.lhs2rhs[ti].length; ai++) {
          int rhsIdx = rule.lhs2rhs[ti][ai];
          if (VERBOSE)
            Log.info(rule.lhs[ti] + " " + ai + "th arg maps to the " + rhsIdx + " of the rhs term: " + rule.rhs);
          if (rhsIdx < 0) {
            // This argument is not bound on the RHS
            continue;
          }

          // Lookup the bound value
          Object val = e.getTail(ai).getValue();
          if (VERBOSE)
            Log.info("extracted val=" + val + " from " + e);

          // Apply these values as RHS args
          if (rhsNodes[rhsIdx] == null) {
            rhsNodes[rhsIdx] = e.getTail(ai);
            numRhsBound++;
          }
          assert rhsNodes[rhsIdx] == e.getTail(ai) : "rhsNodes[" + rhsIdx + "]=" + rhsNodes[rhsIdx] + " e.getTail(" + ai + ")=" + e.getTail(ai);
        }
      }
      assert numRhsBound == rule.rhs.getNumArgs()
          : "numRhsBound=" + numRhsBound
          + " expected=" + rule.rhs.getNumArgs()
          + " rule=" + rule
          + " lhsVals=" + lhsValues;

      HypEdge e = u.makeEdge(rule.rhs.rel, rhsNodes);
      Adjoints sc = Adjoints.Constant.ONE;

      if (feats != null) {
//        sc = Adjoints.cacheIfNeeded(feats.score(e, u));
        sc = feats.score(e, u);
      }

      if (lhsInRhsScoreScale > 0 && !builtLhsEdges.isEmpty()) {
        double s = 1d / builtLhsEdges.size();
        Adjoints lhsScore = null;
        for (HypEdge lhsEdge : builtLhsEdges) {
//          Adjoints lhsSc = Adjoints.cacheIfNeeded(u.getState().getScore(lhsEdge));
          Adjoints lhsSc = new Adjoints.Constant(u.getState().getScore(lhsEdge).forwards());
          if (lhsScore == null)
            lhsScore = new Adjoints.Scale(s, lhsSc);
          else
            lhsScore = Adjoints.sum(new Adjoints.Scale(s, lhsSc), lhsScore);
        }
        sc = Adjoints.sum(sc, new Adjoints.Scale(lhsInRhsScoreScale, lhsScore));
      }

      Pair<HypEdge, Adjoints> p = new Pair<>(e, sc);

      if (VERBOSE)
        Log.info("rule=" + match.rule + " generated edge=" + e + " from lhsValues=" + lhsValues);

      return Arrays.asList(p);
    }
  }

  public static void unprimedExample() {
    System.out.println();
    Log.info("starting...");

    TransitionGeneratorForwardsParser tfp = new TransitionGeneratorForwardsParser();
    tfp.verbose = true;
    TG.VERBOSE = true;

    // ner3(i,ti,bi) & succ(i,j) & biolu(bi,ti,bj,tj) => ner3(j,tj,bj)
    Uberts u = new Uberts(new Random(9001));
    NodeType tokenIndexNT = u.lookupNodeType("tokenIndex", true);
    NodeType nerTagNT = u.lookupNodeType("nerTag", true);
    NodeType bioTagNT = u.lookupNodeType("bioTag", true);
    Relation ner3 = u.addEdgeType(new Relation("ner3", tokenIndexNT, nerTagNT, bioTagNT));
    Relation succ = u.addEdgeType(new Relation("succ", tokenIndexNT, tokenIndexNT));
    Relation biolu = u.addEdgeType(new Relation("biolu", bioTagNT, nerTagNT, bioTagNT, nerTagNT));
    Term ner3Term = new Term(ner3, "i", "ti", "bi");
    Term succTerm = new Term(succ, "i", "j");
    Term bioluTerm = new Term(biolu, "bi", "ti", "bj", "tj");
    Term newNer3Term = new Term(ner3, "j", "tj", "bj");

    System.out.println("first/most natural way to write it:");
    Rule r = new Rule(Arrays.asList(ner3Term, succTerm, bioluTerm), newNer3Term);
    System.out.println("rule: " + r);
    List<TKey> lhs = OLD_WAY
        ? tfp.parse(r).getPath()
        : new Graph3(u, r).getLHS().getPath();
    System.out.println("lhs:");
    for (TKey tk : lhs)
      System.out.println("\t" + tk);

    System.out.println();

    System.out.println("another way (no real benefit):");
    Rule r2 = new Rule(Arrays.asList(ner3Term, bioluTerm, succTerm), newNer3Term);
    System.out.println("rule2: " + r2);
    List<TKey> lhs2 = OLD_WAY
        ? tfp.parse(r2).getPath()
        : new Graph3(u, r2).getLHS().getPath();
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
    List<TKey> lhs3 = OLD_WAY
        ? tfp.parse(r3).getPath()
        : new Graph3(u, r3).getLHS().getPath();
    System.out.println("lhs3:");
    for (TKey tk : lhs3)
      System.out.println("\t" + tk);
  }

  public static void primedExample() {
    System.out.println();
    Log.info("starting...");

    TransitionGeneratorForwardsParser tfp = new TransitionGeneratorForwardsParser();
//    tfp.verbose = true;

    Uberts u = new Uberts(new Random(9001));
    u.readRelData("def role <frame> <role>");
    u.readRelData("def event1 <tokenIndex> <tokenIndex>");
    u.readRelData("def event2 <witness-event1> <frame>");
    u.readRelData("def srl1 <tokenIndex> <tokenIndex>");
    u.readRelData("def srl2 <witness-srl1> <witness-event1>");
    u.readRelData("def srl3 <witness-srl2> <witness-event2> <role>");
    Rule ru1 = Rule.parseRule("event1'(e1,i,j) & srl1'(s1,k,l) => srl2(s1,e1)", u);
    ru1.resolveRelations(u);
    System.out.println(ru1);
    List<TKey> lhs1 = OLD_WAY
        ? tfp.parse(ru1).getPath()
        : new Graph3(u, ru1).getLHS().getPath();
    System.out.println("lhs:");
    for (TKey tk : lhs1)
      System.out.println("\t" + tk);

    System.out.println();

    System.out.println("this example requires enforcing a node equality constraint (e1 which appears in the first two LHS terms)");
    Rule ru2 = Rule.parseRule("srl2'(s2,s1,e1) & event2'(e2,e1,f) & role(f,k) => srl3(s2,e2,k)", u);
    ru2.resolveRelations(u);
    System.out.println(ru2);
    List<TKey> lhs2 = OLD_WAY
        ? tfp.parse(ru2).getPath()
        : new Graph3(u, ru2).getLHS().getPath();
    System.out.println("lhs:");
    for (TKey tk : lhs2)
      System.out.println("\t" + tk);
  }

  public static void ruleFileExamples() throws IOException {
//    "event1'(e1,ts,te) & event2'(e2,e1,f) & srl1'(s1,ss,se) & srl2'(s2,s1,e1) & srl3'(s3,s2,e2,k) => srl4(ts,te,f,ss,se,k)";
    DEBUG = true;
    File rules = new File("data/srl-reldata/srl-grammar-moreArgs.hobbs.trans");
    System.out.println();
    Log.info("trying to parse all rules in: " + rules.getPath());
    System.out.println();
    Uberts u = new Uberts(new Random(9001));
    u.addSuccTok(100);
    u.readRelData(new File("data/srl-reldata/propbank/relations.def"));
    TypeInference ti = new TypeInference(u);
    for (Rule untypedRule : Rule.parseRules(rules, u))
      ti.add(untypedRule);
    for (Rule r : ti.runTypeInference()) {
      System.out.println("rule: " + r);
      assert !OLD_WAY;
      List<TKey> lhs1 = new Graph3(u, r).getLHS().getPath();
      System.out.println("lhs:");
      for (TKey tk : lhs1)
        System.out.println("\t" + tk);
      System.out.println();
      System.out.println();
    }
  }

  public static void duplicateRelationExample() {
    System.out.println();
    Log.info("starting...");

    TransitionGeneratorForwardsParser tfp = new TransitionGeneratorForwardsParser();
//    tfp.verbose = true;

    Uberts u = new Uberts(new Random(9001));
    u.readRelData("def csyn3-stanford <tokenIndex> <tokenIndex> <cfgLabel>");
    u.readRelData("def srl1 <tokenIndex> <tokenIndex>");
    Rule ru1 = Rule.parseRule("csyn3-stanford(i,j,lhs) & csyn3-stanford(j,k,lhs2) => srl1(i,k)", u);
    ru1.resolveRelations(u);
    System.out.println("this example requires you to be able to bind a relation twice in the LHS");
    System.out.println(ru1);

    List<TKey> lhs1 = OLD_WAY
        ? tfp.parse(ru1).getPath()
        : new Graph3(u, ru1).getLHS().getPath();
    System.out.println("lhs:");
    for (TKey tk : lhs1)
      System.out.println("\t" + tk);
  }

  /** No spanning tree over relations in LHS */
  public static void forestExample() {
    System.out.println();
    Log.info("starting...");

    TransitionGeneratorForwardsParser tfp = new TransitionGeneratorForwardsParser();
//    tfp.verbose = true;

    Uberts u = new Uberts(new Random(9001));
    u.readRelData("def csyn3-stanford <tokenIndex> <tokenIndex> <cfgLabel>");
    u.readRelData("def role2 <frame> <role>");
    u.readRelData("def foo <tokenIndex> <role>");
    Rule ru1 = Rule.parseRule("csyn3-stanford(i,j,lhs) & role2(f,k) => foo(i,k)", u);
    ru1.resolveRelations(u);
    System.out.println("this example does not have a spanning tree over LHS relations, requires two entries from root");
    System.out.println(ru1);

    List<TKey> lhs1 = OLD_WAY
        ? tfp.parse(ru1).getPath()
        : new Graph3(u, ru1).getLHS().getPath();
    System.out.println("lhs:");
    for (TKey tk : lhs1)
      System.out.println("\t" + tk);
  }

  public static void main(String[] args) throws IOException {
    unprimedExample();
    primedExample();
    duplicateRelationExample();
    ruleFileExamples();
    forestExample();
  }
}
