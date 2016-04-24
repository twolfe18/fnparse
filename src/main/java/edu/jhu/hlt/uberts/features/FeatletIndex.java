package edu.jhu.hlt.uberts.features;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Uberts;

/**
 * Similar to {@link BasicFeatureTemplates}, but for state-graph based feature
 * extraction.
 *
 * @deprecated I'm just going to make a proper wrapper for {@link BasicFeatureTemplates},
 * there is no reason to duplicate all of that work.
 *
 * @author travis
 */
public class FeatletIndex {

  interface Feature {
    String getName();
  // TODO Lets start with Strings first, then worry about indexing
//    List<Object> extract(HypEdge y, State x);
    List<String> extract(HypEdge y, Uberts x);
  }

  static class Context<T> {
    public final State x;
    public final T y;
    public final Uberts u;
    public Context(T y, State x, Uberts u) {
      this.y = y;
      this.x = x;
      this.u = u;
    }
  }

  interface NamedFunc<A,B> extends Function<Context<A>,B> {
    public String getName();
  }


  // TODO
  static class Path {
    HypNode[] nodes;
    HypEdge[] edges;
  }
  // TODO
  /**
   * Follows HypEdge between tokenIndex:HypNode nodes, only following:
   *   tok1:tokenIndex:HypNode --ARG1--> dsyn:HypEdge --ARG0--> tok2:tokenIndex:HypNode
   */
  static class ToRoot {
    State x;
    HypNode startToken;
    Relation dsyn3;
    // Accumulate these as a cache, allow multiple outputs to read these values.
    List<HypNode> nodes;
    List<HypEdge> edges;
//    BiFunction<HypNode, State, String> nodeLabel;
//    BiFunction<HypEdge, State, String> edgeLabel;
  }
  static class CommonParent {
    // Run both of these, build a set of left nodes, walk up right until you hit
    // a node in left's nodes set.
    ToRoot left, right;
  }


  private Map<String, Feature> featlets;
  private List<Relation> relations;   // only extract features for HypEdge.getRelation in this list
  private NodeType tokenIndex;

  // Extractors
  private List<NamedFunc<HypEdge, Span>> spanExtractors;
  private List<NamedFunc<HypEdge, Path>> pathExtractors;
  private List<NamedFunc<HypEdge, Integer>> tokenExtractors;

  // Converters
  private List<NamedFunc<Span, Integer>> span2tok;  // e.g. first, last, left, right, head

  // Resolvers
  private List<NamedFunc<Span, String>> spanResolvers;
  private List<NamedFunc<Path, String>> pathResolvers;
  private List<NamedFunc<Integer, String>> tokenResolvers;

  public FeatletIndex(NodeType tokenIndex, List<Relation> relevant) {
    this.tokenIndex = tokenIndex;
    this.relations = relevant;
    buildExtractors();
    buildConverters();
    buildResolvers();
    compose();
  }

  public List<Feature> getFeatures() {
    List<Feature> l = new ArrayList<>(featlets.values());
    return l;
  }
  public Feature getFeatureByName(String name) {
    return featlets.get(name);
  }

  private void buildExtractors() {
    this.spanExtractors = new ArrayList<>();
    this.pathExtractors = new ArrayList<>();
    this.tokenExtractors = new ArrayList<>();

    for (Relation r : relations) {

      // Find any potential spans in r's arguments.
      // A potential span is two tokenIndex
      List<IntPair> spanArgs = new ArrayList<>();
      for (int i = 1; i < r.getNumArgs(); i++) {
        NodeType prev = r.getTypeForArg(i-1);
        NodeType cur = r.getTypeForArg(i);
        if (prev == tokenIndex && cur == tokenIndex) {
          spanArgs.add(new IntPair(i-1, i));
          i++;  // lets say you had (i,j,k,l)
          // we don't want (i,j), (j,k), and (k,l)
          // we want non-overlapping pairs of tokenIndex.
          // TODO This heuristic is lousy, figure out how to improve.
        }
      }

      for (IntPair ij : spanArgs) {
        spanExtractors.add(new NamedFunc<HypEdge, Span>() {
          private final Relation rel = r;
          private final int i = ij.first, j = ij.second;
          private final String name = "(Span " + i + " " + j + " " + rel.getName() + ")";
          @Override
          public Span apply(Context<HypEdge> t) {
            HypEdge e = t.y;
            if (e.getRelation() != rel)
              return null;
            int ii = coerceTokenIndex(e.getTail(i));
            int jj = coerceTokenIndex(e.getTail(j));
            return Span.getSpan(ii, jj);
          }
          @Override
          public String getName() {
            return name;
          }
        });
      }
    }

    // TODO pathExtractors and tokenExtractors
  }

  private void buildConverters() {
    span2tok = new ArrayList<>();
    // TODO biolu-style (e.g. u), have some not fire based on span width
    span2tok.add(new NamedFunc<Span, Integer>() {
      @Override public Integer apply(Context<Span> t) { return t.y.start - 1; }
      @Override public String getName() { return "(Left)"; }
    });
    span2tok.add(new NamedFunc<Span, Integer>() {
      @Override public Integer apply(Context<Span> t) { return t.y.end; }
      @Override public String getName() { return "(Right)"; }
    });
    span2tok.add(new NamedFunc<Span, Integer>() {
      @Override public Integer apply(Context<Span> t) { return t.y.start; }
      @Override public String getName() { return "(First)"; }
    });
    span2tok.add(new NamedFunc<Span, Integer>() {
      @Override public Integer apply(Context<Span> t) { return t.y.end - 1; }
      @Override public String getName() { return "(Last)"; }
    });
  }

  private void buildResolvers() {
    tokenResolvers = new ArrayList<>();
    spanResolvers = new ArrayList<>();
    pathResolvers = new ArrayList<>();

    for (String tag2 : Arrays.asList("word2", "pos2", "lemma2")) {
      tokenResolvers.add(new NamedFunc<Integer, String>() {
        @Override
        public String apply(Context<Integer> t) {
          if (t.y.intValue() < 0)
            return "<s>";
          NodeType tokenIndexNT = t.u.lookupNodeType("tokenIndex", false);
          HypNode token = t.u.lookupNode(tokenIndexNT, t.y, false);
          if (token == null) {
//            Log.info("not a token: " + t.y);
            return "</s>";
          }
          Relation rel = t.u.getEdgeType(tag2);
          assert rel.getNumArgs() == 2;
          assert rel.getTypeForArg(0) == tokenIndexNT;
          HypEdge relE = t.x.match1(0, rel, token);
          return (String) relE.getTail(1).getValue();
        }
        @Override
//        public String getName() { return "(Arg1of " + tag2 + ")"; }
        public String getName() { return tag2; }
      });
    }
  }

  private void add(Feature f) {
    String key = f.getName();
    Feature old = featlets.put(key, f);
    if (old != null)
      throw new RuntimeException("key=" + key);
  }

  private void compose() {
    featlets = new HashMap<>();
    for (NamedFunc<HypEdge, Span> se : spanExtractors) {
      for (NamedFunc<Span, String> sr : spanResolvers) {
        add(new Feature() {
          private String name = "(" + se.getName() + " " + sr.getName() + ")";
          @Override
          public String getName() { return name; }
          @Override
          public List<String> extract(HypEdge y, Uberts u) {
            State x = u.getState();
            Context<HypEdge> ce = new Context<>(y, x, u);
            Span s = se.apply(ce);
            if (s == null)
              return Collections.emptyList();
            Context<Span> cs = new Context<Span>(s, x, u);
            String st = sr.apply(cs);
            if (st == null)
              return Collections.emptyList();
            return Arrays.asList(st);
          }
        });
      }
      for (NamedFunc<Span, Integer> sc : span2tok) {
        for (NamedFunc<Integer, String> tr : tokenResolvers) {
          add(new Feature() {
            private String name = "(" + se.getName() + " " + sc.getName() + " " + tr.getName() + ")";
            @Override
            public String getName() { return name; }
            @Override
            public List<String> extract(HypEdge y, Uberts u) {
              State x = u.getState();
              Context<HypEdge> ce = new Context<>(y, x, u);
              Span s = se.apply(ce);
              if (s == null)
                return Collections.emptyList();
              Context<Span> cs = new Context<>(s, x, u);
              Integer i = sc.apply(cs);
              if (i == null)
                return Collections.emptyList();
              Context<Integer> ci = new Context<>(i, x, u);
              String st = tr.apply(ci);
              if (st == null)
                return Collections.emptyList();
              return Arrays.asList(st);
            }
          });
        }
      }
    }

    // TODO The rest!
  }

  static int coerceTokenIndex(HypNode tokenIndexNode) {
    Object v = tokenIndexNode.getValue();
    if (v instanceof Integer)
      return ((Integer) v).intValue();
    if (v instanceof String)
      return Integer.parseInt((String) v);
    throw new RuntimeException("don't know how to make this an int: " + v);
  }

  // See what we generate
  public static void main(String[] args) throws IOException {
    Random rand = new Random(9001);
    Uberts u = new Uberts(rand);
    u.readRelData(new File("data/srl-reldata/srl-FNFUTXT1228670.defs"));
    List<Relation> rel = Arrays.asList(
        u.getEdgeType("event1"),
        u.getEdgeType("event2"),
        u.getEdgeType("srl1"),
        u.getEdgeType("srl2"),
        u.getEdgeType("srl3"));
    NodeType tokenIndex = u.lookupNodeType("tokenIndex", false);
    FeatletIndex fi = new FeatletIndex(tokenIndex, rel);
    int n = 0;
    for (Feature f : fi.getFeatures()) {
      System.out.println(f.getName());
      n++;
    }
    System.out.println("created " + n + " features");
  }
}
