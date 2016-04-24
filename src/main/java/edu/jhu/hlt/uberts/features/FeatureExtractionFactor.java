package edu.jhu.hlt.uberts.features;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.concrete.Constituent;
import edu.jhu.hlt.concrete.Parse;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.StringLabeledDirectedGraph;
import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.TemplateContext;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.TemplateAlphabet;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HNode;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Relation.EqualityArray;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.StateEdge;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Arg;
import edu.jhu.hlt.uberts.srl.Srl3EdgeWrapper;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;

public abstract class FeatureExtractionFactor<T> {

  // Set this to non-null values to enable.
  // When enabled, an empty list, this.SKIP will be return from features().
  public Double pSkipNeg = null;
  public List<T> SKIP = new ArrayList<>();

  public abstract List<T> features(HypEdge yhat, Uberts x);


  public static class Weight<T> {
    int nObs = 0;
    double theta = 0;
    final T item;
    public Weight(T item) {
      this.item = item;
      this.nObs = 0;
      this.theta = 0;
    }
    public void increment(double amount) {
      theta += amount;
      nObs++;
    }
    @Override
    public String toString() {
      return String.format("(%s %+.2f n=%d)", item.toString(), theta, nObs);
    }
  }

  public static class WeightAdjoints<T> implements Adjoints {
    private List<T> fx;
    private Map<T, Weight<T>> theta;

    public WeightAdjoints(List<T> features, Map<T, Weight<T>> weights) {
      this.fx = features;
      this.theta = weights;
    }

    public List<T> getFeatures() {
      return fx;
    }

    @Override
    public double forwards() {
      double s = 0;
      for (T index : fx) {
        Weight<T> w = theta.get(index);
        if (w != null)
          s += w.theta;
      }
      return s;
    }

    @Override
    public void backwards(double dErr_dForwards) {
      for (T index : fx) {
        Weight<T> w = theta.get(index);
        if (w == null) {
          w = new Weight<>(index);
          theta.put(index, w);
        }
        w.increment(-dErr_dForwards);
      }
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("(Adj");
      for (T index : fx) {
        Weight<T> w = theta.get(index);
        if (w == null)
          w = new Weight<>(index);
        sb.append(' ');
        sb.append(w.toString());
        if (sb.length() > 200) {
          sb.append("...");
          break;
        }
      }
      sb.append(')');
      return sb.toString();
    }
  }

  private Map<Relation, Map<T, Weight<T>>> theta = new HashMap<>();

  /**
   * Returns a non-caching Adjoints.
   */
  public Adjoints score(HypEdge yhat, Uberts x) {
    // Look up weight vector based on Relation of the given edge
    Map<T, Weight<T>> t = theta.get(yhat.getRelation());
    if (t == null) {
      t = new HashMap<>();
      theta.put(yhat.getRelation(), t);
    }
    List<T> feats = features(yhat, x);
    return new WeightAdjoints<>(feats, t);
  }

  /**
   * Uses the same features defined in {@link BasicFeatureTemplates}.
   *
   * NOTE: This contains some general purpose state graph => Sentence code.
   *
   * NOTE: This is hard-coded to a particular transition system.
   */
  public static class OldFeaturesWrapper extends FeatureExtractionFactor<Pair<TemplateAlphabet, String>> {
    public static boolean DEBUG = false;

    // See TemplatedFeatures.parseTemplate(String), etc
    private edu.jhu.hlt.fnparse.features.precompute.Alphabet features;
    private Sentence sentCache;
    private TemplateContext ctx;
    private Alphabet<String> depGraphEdges;
    private HeadFinder hf;
    private MultiTimer timer;
    private Counts<String> skipped;

    /** This will read all the features in */
    public OldFeaturesWrapper(BasicFeatureTemplates bft, Double pSkipNeg) {
      this.pSkipNeg = pSkipNeg;
      timer = new MultiTimer();
      timer.put("convert-sentence", new Timer("convert-sentence", 100, true));
      timer.put("compute-features", new Timer("compute-features", 10000, true));

      skipped = new Counts<>();
      depGraphEdges = new Alphabet<>();
      ctx = new TemplateContext();
      sentCache = null;
      edu.jhu.hlt.fnparse.features.precompute.Alphabet alph =
          new edu.jhu.hlt.fnparse.features.precompute.Alphabet(bft);
      this.hf = alph.getHeadFinder();
      this.features = alph;
    }

    public edu.jhu.hlt.fnparse.features.precompute.Alphabet getFeatures() {
      return features;
    }

    private static String getSentenceId(Uberts u) {
      State s = u.getState();
      Relation rel = u.getEdgeType("startDoc");
      HypEdge e = s.match2(rel).item;
      return (String) e.getTail(0).getValue();
    }

    private static ConstituencyParse buildCP(Uberts u, String sentenceId) {
      // def csyn5-stanford id parentId start end label
      Relation consRel = u.getEdgeType("csyn6-stanford");
      State st = u.getState();
      List<Pair<Integer, edu.jhu.hlt.concrete.Constituent>> cons = new ArrayList<>();
      Map<Integer, edu.jhu.hlt.concrete.Constituent> id2con = new HashMap<>();
      for (LL<HypEdge> cur = st.match2(consRel); cur != null; cur = cur.next) {
        HypEdge e = cur.item;
        assert e.getNumTails() == 6;
        int cid = Integer.parseInt((String) e.getTail(0).getValue());
        int parent = Integer.parseInt((String) e.getTail(1).getValue());
        int headToken = Integer.parseInt((String) e.getTail(2).getValue());
        int startToken = Integer.parseInt((String) e.getTail(3).getValue());
        int endToken = Integer.parseInt((String) e.getTail(4).getValue());
        String lhs = (String) e.getTail(5).getValue();

        assert startToken < endToken;

        edu.jhu.hlt.concrete.Constituent c = new Constituent();
        c.setId(cid);
        c.setStart(startToken);
        c.setEnding(endToken);
        c.setTag(lhs);
        c.setHeadChildIndex(headToken);  // Need to convert token -> child index

        if (DEBUG)
          Log.info(cid + " -> " + c);
        id2con.put(cid, c);
        cons.add(new Pair<>(parent, c));
      }

      // Add children
      for (Pair<Integer, edu.jhu.hlt.concrete.Constituent> pc : cons) {
        int parent = pc.get1();
        if (parent < 0)
          continue; // ROOT
        edu.jhu.hlt.concrete.Constituent c = pc.get2();
        edu.jhu.hlt.concrete.Constituent p = id2con.get(parent);
        p.addToChildList(c.getId());
      }

      // Set heads
      for (Pair<Integer, edu.jhu.hlt.concrete.Constituent> pc : cons) {
        edu.jhu.hlt.concrete.Constituent c = pc.get2();
        c.setHeadChildIndex(-1);
        if (!c.isSetChildList() || c.getChildListSize() == 0) {
          assert c.getStart()+1 == c.getEnding();
          continue;
        }
        int headToken = c.getHeadChildIndex();
        int headChildIdx = -1;
        int i = 0;
        for (int childId : c.getChildList()) {
          edu.jhu.hlt.concrete.Constituent child = id2con.get(childId);
          if (child.getStart() <= headToken && headToken < child.getEnding()) {
            assert headChildIdx < 0;
            headChildIdx = i;
          }
          i++;
        }
        c.setHeadChildIndex(headChildIdx);
      }

      Parse p = new Parse();
      for (Pair<Integer, edu.jhu.hlt.concrete.Constituent> pc : cons)
        p.addToConstituentList(pc.get2());
      return new ConstituencyParse(sentenceId, p);
    }

    /**
     * @param root should probably be the length of the sentence. It must be >=0.
     * @param depRel should have columns gov, dep, label.
     */
    private StringLabeledDirectedGraph getDepGraph(Uberts u, int root, Relation depRel) {
      if (root < 0)
        throw new IllegalArgumentException("root=" + root + " must be >= 0");
      StringLabeledDirectedGraph g = new StringLabeledDirectedGraph(depGraphEdges);
      State st = u.getState();
      for (LL<HypEdge> cur = st.match2(depRel); cur != null; cur = cur.next) {
        HypEdge e = cur.item;
        assert e.getNumTails() == 3;
        int h = Integer.parseInt((String) e.getTail(0).getValue());
        int m = Integer.parseInt((String) e.getTail(1).getValue());
        assert m >= 0;
        if (h < 0)
          h = root;
        String l = (String) e.getTail(2).getValue();
        g.add(h, m, l);
      }
      return g;
    }

    private void checkForNewSentence(Uberts u) {
      // How do we know how many tokens are in the document?
      String id = getSentenceId(u);
      if (sentCache != null && sentCache.getId().equals(id))
        return;
      timer.start("convert-sentence");

      // See FNParseToRelations for these definitions
      NodeType tokenIndex = u.lookupNodeType("tokenIndex", false);
      Relation wordRel = u.getEdgeType("word2");
      Relation posRel = u.getEdgeType("pos2");
      Relation lemmaRel = u.getEdgeType("lemma2");

      State st = u.getState();
      List<HypNode> tokens = new ArrayList<>();
      List<String> wordL = new ArrayList<>();
      List<String> posL = new ArrayList<>();
      List<String> lemmaL = new ArrayList<>();
      for (int i = 0; true; i++) {
        HypNode tok = u.lookupNode(tokenIndex, String.valueOf(i).intern(), false);
        if (tok == null)
          break;
        tokens.add(tok);
        LL<HypEdge> maybeWord = st.match(0, wordRel, tok);
        if (maybeWord == null)
          break;
        assert maybeWord.next == null : "more than one word at " + tok + ": " + maybeWord;
        HypEdge wordE = maybeWord.item;
        HypNode word = wordE.getTail(1);
        HypNode pos = st.match1(0, posRel, tok).getTail(1);
        HypNode lemma = st.match1(0, lemmaRel, tok).getTail(1);
        wordL.add((String) word.getValue());
        posL.add((String) pos.getValue());
        lemmaL.add((String) lemma.getValue());
      }
      int n = wordL.size();
      String[] wordA = wordL.toArray(new String[n]);
      String[] posA = posL.toArray(new String[n]);
      String[] lemmaA = lemmaL.toArray(new String[n]);

      String dataset = "na";
      sentCache = new Sentence(dataset, id, wordA, posA, lemmaA);

      // Shapes and WN are computed on the fly
      // deps (basic, col, colcc) and constituents need to be added
      Relation depsBRel = u.getEdgeType("dsyn3-basic");
      String[] labB = new String[n];
      int[] govB = new int[n];
      for (int i = 0; i < n; i++) {
        // Find the 0 or 1 tokens which govern this token
        HypNode dep = tokens.get(i);
        LL<HypEdge> gov2dep = st.match(1, depsBRel, dep);
        if (gov2dep == null) {
          govB[i] = -1;
          labB[i] = "UKN";
        } else {
          assert gov2dep.next == null : "two gov (not tree) for basic?";
          HypEdge e = gov2dep.item;
          govB[i] = Integer.parseInt((String) e.getTail(0).getValue());
          labB[i] = (String) e.getTail(2).getValue();
        }
      }
      sentCache.setBasicDeps(new DependencyParse(govB, labB));
      sentCache.setCollapsedDeps2(getDepGraph(u, n, u.getEdgeType("dsyn3-col")));
      sentCache.setCollapsedCCDeps2(getDepGraph(u, n, u.getEdgeType("dsyn3-colcc")));
      sentCache.setStanfordParse(buildCP(u, id));
      sentCache.computeShapes();
      sentCache.getWnWord(0);
      timer.stop("convert-sentence");
    }

    @Override
    public List<Pair<TemplateAlphabet, String>> features(HypEdge yhat, Uberts x) {

      if (pSkipNeg != null && !x.getLabel(yhat) && x.getRandom().nextDouble() < pSkipNeg)
        return SKIP;

      checkForNewSentence(x);
      timer.start("compute-features");
      Span t = null, s = null;
      EqualityArray e1, s1;
      ctx.clear();
      ctx.setSentence(sentCache);
      switch (yhat.getRelation().getName()) {
      case "srl1":
        s = extractSpan(yhat, 0, 1);
        break;
      case "srl2":
        s1 = (EqualityArray) yhat.getTail(0).getValue();
        e1 = (EqualityArray) yhat.getTail(1).getValue();
        t = extractSpan(e1, 0, 1);
        s = extractSpan(s1, 0, 1);
        ctx.clear();
        ctx.setSentence(sentCache);
        break;
      case "srl3":
        EqualityArray s2 = (EqualityArray) yhat.getTail(0).getValue();
        s1 = (EqualityArray) s2.get(0);
        e1 = (EqualityArray) s2.get(1);
        Srl3EdgeWrapper s3 = new Srl3EdgeWrapper(yhat);
        ctx.setFrame(FrameIndex.getFrameWithSchemaPrefix(s3.f));
        ctx.setRoleS(s3.k);
        break;
      default:
        skipped.increment(yhat.getRelation().getName());
        if (skipped.getTotalCount() % 1000 == 0)
          Log.info("skipped: " + skipped.toString());
        break;
      }
      if (s != null && s != Span.nullSpan) {
        ctx.setArg(s);
        ctx.setArgHead(hf.head(s, sentCache));
        ctx.setSpan1(s);
        ctx.setHead1(ctx.getArgHead());
      }
      if (t != null && t != Span.nullSpan) {
        ctx.setTarget(t);
        ctx.setTargetHead(hf.head(t, sentCache));
        ctx.setSpan2(t);
        ctx.setHead2(ctx.getTargetHead());
      }
      List<Pair<TemplateAlphabet, String>> f = new ArrayList<>();
      for (TemplateAlphabet ftemp : features) {
        Iterable<String> fts = ftemp.template.extract(ctx);
        if (fts != null)
          for (String ft : fts)
            f.add(new Pair<>(ftemp, ft.intern()));
      }
      timer.stop("compute-features");
      return f;
    }
  }

  public static Span extractSpan(HypEdge e, int startTailIdx, int endTailIdx) {
    int start = Integer.parseInt((String) e.getTail(startTailIdx).getValue());
    int end = Integer.parseInt((String) e.getTail(endTailIdx).getValue());
    Span s = Span.getSpan(start, end);
    return s;
  }

  public static Span extractSpan(EqualityArray ea, int startTailIdx, int endTailIdx) {
    int start = Integer.parseInt((String) ea.get(startTailIdx));
    int end = Integer.parseInt((String) ea.get(endTailIdx));
    Span s = Span.getSpan(start, end);
    return s;
  }

  /**
   * Does a DFS search starting from each tail of the {@link HypEdge} being
   * featurized. Parameters let you set a max walk length and ignore certain
   * values in the walk which are known to not be discriminative
   * (e.g. "tokenIndex=32" => "tokenIndex=?").
   * You might want to use this for arguments which are head node types, e.g.
   * in `srl1'(s1,...) & event1'(e1,...) => srl2(s1,s2)`, if you where to walk
   * through the srl2 node to its tails, you probably don't want to spit out
   * something like "arg-0-of-srl2=(EqArr 3 5)".
   * but rather "arg-0-of-srl2*arg-0-of-srl1=3"
   * even that... sort of should be compressed
   */
  public static class GraphWalks extends FeatureExtractionFactor<String> {
    private Set<HypNode> seen;
    private Set<HNode> seen2;
    private Set<Arg> seen3;
    private Set<Arg> seen3Exceptions;
    private Deque<String> steps;
    private int curArgs = 0;
    private int curValues = 0;
    private int maxArgs = 4;
    private int maxValues = 4;
    private int minValues = 1;
    private boolean lastStepIncludesValue;
    private Set<String> nodeTypesIgnoreValue;
//    private Set<Arg> args;

    public GraphWalks() {
      steps = new ArrayDeque<>();
      seen = new HashSet<>();
      seen2 = new HashSet<>();
      seen3 = new HashSet<>();
      nodeTypesIgnoreValue = new HashSet<>();
      nodeTypesIgnoreValue.add("tokenIndex");

      // These are relations where one arg is really a label for the rest
      // of the args. We want to allow the walks to hop over to the label node
      // and hop back multiple times, as opposed to it only being able to see
      // one label, as would be the case if you could only cross a (rel,argPos)
      // once.
      seen3Exceptions = new HashSet<>();
      seen3Exceptions.add(new Arg("dsyn3-basic", 2));
      seen3Exceptions.add(new Arg("dsyn3-col", 2));
      seen3Exceptions.add(new Arg("dsyn3-colcc", 2));
      seen3Exceptions.add(new Arg("csyn3-stanford", 2));
      seen3Exceptions.add(new Arg("csyn3-gold", 2));
    }

    @Override
    public List<String> features(HypEdge yhat, Uberts x) {
      // Do DFS on the state graph from every tail node in yhat
      Relation yhatRel = yhat.getRelation();
      List<String> features = new ArrayList<>();
      for (int i = 0; i < yhatRel.getNumArgs(); i++) {

        // Each walk is independently constrained (for now)
        seen.clear();
        seen2.clear();
        seen3.clear();

        // Start a walk here
        HypNode t = yhat.getTail(i);
        curArgs++;
        steps.push(yhat.getRelation().getName() + "=arg" + i);
        dfs2(new HNode(t), x.getState(), features);
        steps.pop();
        curArgs--;
      }
      return features;
    }

    private void dfs2(HNode n, State s, List<String> addTo) {

      assert curArgs >= 0 && curValues >= 0;
      if (curArgs > maxArgs || curValues > maxValues)
        return;

      if (curValues >= minValues) {// && n.isEdge()) {
        StringBuilder sb = new StringBuilder();
        for (String st : steps) {
          if (sb.length() > 0) {
            sb.append(" ||| ");
          }
          sb.append(st);
        }
        addTo.add(sb.toString());
      }

      List<StateEdge> nei = s.neighbors2(n);
      if (nei.size() > 10)
        Log.warn("lots of neighbors of " + n + ", " + nei.size());
      for (StateEdge se : nei) {
        assert se.getSource().equals(n) : "n=" + n + " source=" + se.getSource();
        HNode t = se.getTarget();
        boolean incVal = false;

        // You can only cross an Edge=(relation, argPosition) once.
        String relName;
        if (t.isEdge()) {
          relName = t.getEdge().getRelation().getName();
        } else {
          assert se.getSource().isEdge();
          relName = se.getSource().getEdge().getRelation().getName();
        }
        Arg a = new Arg(relName, se.argPos);
        if (!seen3Exceptions.contains(a) && !seen3.add(a))
          continue;

        String key, value;
        if (t.isNode()) {
          HypNode val = t.getNode();
          if (!seen.add(val))
            continue;
          key = val.getNodeType().getName();
          if (se.argPos == State.HEAD_ARG_POS || nodeTypesIgnoreValue.contains(val.getNodeType().getName())) {
            value = "?";
          } else {
            value = val.getValue().toString();
            incVal = true;
            curValues++;
          }
        } else {
          HypEdge rel = t.getEdge();
          key = rel.getRelation().getName();
          value = "arg" + se.argPos;
        }
        curArgs++;
        steps.push(key + "=" + value);
        dfs2(t, s, addTo);
        steps.pop();
        curArgs--;
        if (incVal)
          curValues--;
      }
    }

    private void dfs(HypNode n, State s, List<String> addTo) {
      // Extract features
      // I think I want to do this before checking seen since I want to include
      // multiple paths.
      if (curValues >= minValues && lastStepIncludesValue) {
        StringBuilder sb = new StringBuilder();
        for (String st : steps) {
          if (sb.length() > 0) {
//            sb.append("_");
            sb.append(" ||| ");
          }
          sb.append(st);
        }
        addTo.add(sb.toString());
      }

      if (!seen.add(n))
        return;

//      if (steps.size() == maxDepth)
//        return;
      assert curArgs >= 0 && curValues >= 0;
      if (curArgs >= maxArgs || curValues >= maxValues)
        return;

      // Recurse
      for (StateEdge se : s.neighbors2(new HNode(n))) {
        HNode rel = se.getTarget();
        assert rel.isRight();
        HypEdge e = rel.getRight();
        int nt = e.getNumTails();
        for (int i = 0; i < nt; i++) {
          HypNode node = e.getTail(i);
          if (node == n)
            continue;
          String step = "arg" + i + "-of-" + e.getRelation().getName() + "=";
          boolean ignoreVal = nodeTypesIgnoreValue.contains(node.getNodeType().getName());
          lastStepIncludesValue = !ignoreVal;
          curArgs++;
          if (ignoreVal) {
            step += "?";
          } else {
            curValues++;
            step += node.getValue().toString();
          }
          steps.push(step);
          dfs(node, s, addTo);
          steps.pop();
          curArgs--;
          if (!ignoreVal)
            curValues--;
        }
        curArgs++;
        steps.push("head-of-" + e.getRelation().getName());
        lastStepIncludesValue = false;
        dfs(e.getHead(), s, addTo);
        steps.pop();
        curArgs--;
      }
    }
  }

  /**
   * Uses {@link FeatletIndex} for features.
   */
  public static class Simple extends FeatureExtractionFactor<String> {
    private List<FeatletIndex.Feature> features;

    public Simple(List<Relation> relevant, Uberts u) {
      NodeType tokenIndex = u.lookupNodeType("tokenIndex", false);
      FeatletIndex featlets = new FeatletIndex(tokenIndex, relevant);
      this.features = featlets.getFeatures();
    }

    public List<String> features(HypEdge yhat, Uberts x) {
      List<String> fx = new ArrayList<>();
      fx.add("INTERCEPT");
      int n = features.size();
      for (int i = 0; i < n; i++) {
        FeatletIndex.Feature f = features.get(i);
        List<String> ls = f.extract(yhat, x);
        for (String fs : ls)
          fx.add(f.getName() + "/" + fs);
      }
      return fx;
    }
  }
}

