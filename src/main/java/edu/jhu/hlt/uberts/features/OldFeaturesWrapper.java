package edu.jhu.hlt.uberts.features;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import edu.jhu.hlt.concrete.Constituent;
import edu.jhu.hlt.concrete.Parse;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.StringLabeledDirectedGraph;
import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.TemplateContext;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.TemplateAlphabet;
import edu.jhu.hlt.fnparse.features.precompute.FeatureSet;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Relation.EqualityArray;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.srl.Srl3EdgeWrapper;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;

/**
 * Uses the same features defined in {@link BasicFeatureTemplates}.
 *
 * NOTE: This contains some general purpose state graph => Sentence code.
 *
 * NOTE: This is hard-coded to a particular transition system.
 */
public class OldFeaturesWrapper extends FeatureExtractionFactor<Pair<TemplateAlphabet, String>> {
  public static boolean DEBUG = false;

  // See TemplatedFeatures.parseTemplate(String), etc
  private edu.jhu.hlt.fnparse.features.precompute.Alphabet features;
  private Sentence sentCache;
  private TemplateContext ctx;
  private Alphabet<String> depGraphEdges;
  private HeadFinder hf;
  private MultiTimer timer;
  private Counts<String> skipped;

  // If you can setup a TemplateContext given a HypEdge, then you can use this class.
  // Sentence setup and conversion is handled for you (so you can access ctx.getSentence()).
  // If you leave this as null, only srl1,srl2,srl3 will work (maybe more later).
  public BiConsumer<Pair<HypEdge, Uberts>, TemplateContext> customEdgeCtxSetup = null;

  public OldFeaturesWrapper(BasicFeatureTemplates bft, BiAlph bialph, File featureSet) {
    this.features = new edu.jhu.hlt.fnparse.features.precompute.Alphabet(bft, false);
    for (String[] feat : FeatureSet.getFeatureSet3(featureSet)) {
      Template prod = bft.getBasicTemplate(feat[0]);
      for (int i = 1; i < feat.length; i++)
        prod = new TemplatedFeatures.TemplateJoin(prod, bft.getBasicTemplate(feat[i]));
      features.add(new TemplateAlphabet(prod, StringUtils.join("*", feat), features.size()));
    }
    ctx = new TemplateContext();
    depGraphEdges = new Alphabet<>();
    hf = new SemaforicHeadFinder();
    timer = new MultiTimer();
    skipped = new Counts<>();
  }

  /** Starts up with some dummy features, for debugging */
  public OldFeaturesWrapper(BasicFeatureTemplates bft) {

    // This should be enough to over-fit
    String w = "Bc256/8";
    String[] tempNames = new String[] {
        "Bc256/8-2-grams-between-Head1-and-Span2.Last",
        "Head1Head2-PathNgram-Basic-LEMMA-DIRECTION-len2",
        "Head1Head2-PathNgram-Basic-POS-DEP-len2",
        "Span2-PosPat-FULL_POS-3-1",
        "Span2-First-" + w,
        "Span2-Last-" + w,
        "Span1-PosPat-FULL_POS-3-1",
        "Span1-First-" + w,
        "Span1-Last-" + w,
        "Span1-Width-Div2",
        "Head1-Child-Basic-" + w,
        "Head1-Parent-Basic-" + w,
        "Head1-Grandparent-Basic-" + w,
        "Head1-RootPath-Basic-POS-DEP-t",
//        "Head1-RootPathNgram-Basic-LEMMA-DIRECTION-len3",
        "Head2-RootPath-Basic-POS-DEP-t",
//        "Head2-RootPathNgram-Basic-LEMMA-DIRECTION-len3",
        "Head2-Child-Basic-" + w,
        "Head2-Parent-Basic-" + w,
        "Head2-Grandparent-Basic-" + w,
    };
    Template[] temps = new Template[tempNames.length];
    for (int i = 0; i < temps.length; i++) {
      temps[i] = bft.getBasicTemplate(tempNames[i]);
      if (temps[i] == null)
        throw new RuntimeException("couldn't look up: " + tempNames[i]);
    }

    this.features = new edu.jhu.hlt.fnparse.features.precompute.Alphabet(bft, false);
    for (int i = 0; i < temps.length; i++)
      this.features.add(new TemplateAlphabet(temps[i], tempNames[i], features.size()));
    for (int i = 0; i < temps.length-1; i++) {
      for (int j = i+1; j < temps.length; j++) {
        Template prod = new TemplatedFeatures.TemplateJoin(temps[i], temps[j]);
        String name = tempNames[i] + "*" + tempNames[j];
        this.features.add(new TemplateAlphabet(prod, name, features.size()));
      }
    }

    ctx = new TemplateContext();
    depGraphEdges = new Alphabet<>();
    hf = new SemaforicHeadFinder();
    timer = new MultiTimer();
    skipped = new Counts<>();
  }

  /** This will read all unigram templates in */
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
    ctx.clear();
    ctx.setSentence(sentCache);
    if (customEdgeCtxSetup == null) {
      switch (yhat.getRelation().getName()) {
      case "srl1":
        s = extractSpan(yhat, 0, 1);
        break;
      case "srl2":
        EqualityArray s1 = (EqualityArray) yhat.getTail(0).getValue();
        EqualityArray e1 = (EqualityArray) yhat.getTail(1).getValue();
        t = extractSpan(e1, 0, 1);
        s = extractSpan(s1, 0, 1);
        break;
      case "srl3":
        Srl3EdgeWrapper s3 = new Srl3EdgeWrapper(yhat);
        t = s3.t;
        s = s3.s;
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
    } else {
      customEdgeCtxSetup.accept(new Pair<>(yhat, x), ctx);
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
}