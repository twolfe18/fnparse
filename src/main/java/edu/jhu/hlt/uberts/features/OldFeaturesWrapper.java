package edu.jhu.hlt.uberts.features;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

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
import edu.jhu.hlt.fnparse.features.precompute.TemplateTransformerTemplate;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Relation.EqualityArray;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.UbertsPipeline;
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
public class OldFeaturesWrapper {
  public static int DEBUG = 1;

  public static class Strings extends FeatureExtractionFactor<Pair<TemplateAlphabet, String>> {
    private OldFeaturesWrapper inner;
    public Strings(OldFeaturesWrapper inner, Double pSkipNeg) {
      this.inner = inner;
      super.customRefinements = inner.customRefinements;
      super.pSkipNeg = pSkipNeg;
    }
    @Override
    public List<Pair<TemplateAlphabet, String>> features(HypEdge yhat, Uberts x) {
      if (pSkipNeg != null && !x.getLabel(yhat) && x.getRandom().nextDouble() < pSkipNeg)
        return SKIP;
      return inner.features(yhat, x);
    }
    public OldFeaturesWrapper getInner() {
      return inner;
    }
  }

  public static class Ints extends FeatureExtractionFactor<Integer> {
    private OldFeaturesWrapper inner;
    private int mask;
    private int numBits;

    private AveragedPerceptronWeights theta;

    // NEW
    public AveragedPerceptronWeights w0;
    public Map<String, AveragedPerceptronWeights> wf;
    public AveragedPerceptronWeights w1;
    public AveragedPerceptronWeights getWf(String f) {
      AveragedPerceptronWeights w = wf.get(f);
      if (w == null) {
        w = new AveragedPerceptronWeights(1 << numBits, 0);
        wf.put(f, w);
      }
      return w;
    }

    public Ints(OldFeaturesWrapper inner, int numBits) {
      this.numBits = numBits;
      mask = (1 << numBits) - 1;
      super.customRefinements = inner.customRefinements;
      this.inner = inner;
      theta = new AveragedPerceptronWeights(1 << numBits, 0);

      w0 = new AveragedPerceptronWeights(1 << numBits, 0);
      w1 = new AveragedPerceptronWeights(1 << numBits, 0);
      wf = new HashMap<>();
    }

    @Override
    public void completedObservation() {
      theta.completedObservation();
    }

    @Override
    public Adjoints score(HypEdge yhat, Uberts x) {
      int[] y = null;
      if (customRefinements != null)
        y = customRefinements.apply(yhat);

      List<Pair<TemplateAlphabet, String>> f1 = inner.features(yhat, x);
      int n = f1.size();
      int[] f3;

      if (customRefinements != null)
        f3 = new int[n * y.length];
      else
        f3 = new int[n];

      for (int i = 0; i < n; i++) {
        Pair<TemplateAlphabet, String> p = f1.get(i);
        int t = p.get1().index;
        int f = Hash.hash(p.get2());
        if (customRefinements == null) {
          f3[i] = Hash.mix(t, f) & mask;
        } else {
          for (int j = 0; j < y.length; j++)
            f3[i * y.length + j] = Hash.mix(y[j], t, f) & mask;
        }
      }
      if (useAvg)
        return theta.averageView().score(f3, false);
      return theta.score(f3, false);
    }

    public int[] featuresNoRefine(HypEdge yhat, Uberts x) {
      List<Pair<TemplateAlphabet, String>> f1 = inner.features(yhat, x);
      int n = f1.size();
      int[] f3 = new int[n];
      for (int i = 0; i < n; i++) {
        Pair<TemplateAlphabet, String> p = f1.get(i);
        int t = p.get1().index;
        int f = Hash.hash(p.get2());
        f3[i] = Hash.mix(t, f) & mask;
      }
      return f3;
    }

    @Override
    public List<Integer> features(HypEdge yhat, Uberts x) {
//      List<Pair<TemplateAlphabet, String>> f1 = inner.features(yhat, x);
//      int n = f1.size();
//      int T = inner.features.size();
//      List<Integer> f2 = new ArrayList<>(n);
//      for (int i = 0; i < n; i++) {
//        Pair<TemplateAlphabet, String> p = f1.get(i);
//        int t = p.get1().index;
//        int h = Hash.hash(p.get2()) & mask;
//        f2.add(h * T + t);
//      }
//      return f2;
      throw new RuntimeException("should be going through score or inner.features()");
    }

    public OldFeaturesWrapper getInner() {
      return inner;
    }
  }

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

  public Function<HypEdge, int[]> customRefinements = null;

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


  public OldFeaturesWrapper(
      BasicFeatureTemplates bft,
      String featureSetWithPluses,
      File bialph, // e.g. data/mimic-coe/framenet/coherent-shards/alphabet.txt.gz
      File fcounts // e.g. data/mimic-coe/framenet/feature-counts/all.txt.gz
      ) throws IOException {

    TemplateTransformerTemplate ttt = new TemplateTransformerTemplate(fcounts, bialph, featureSetWithPluses);
    Map<String, Template> extra = ttt.getSpecialTemplates(bft);
    ttt.free();

    this.features = new edu.jhu.hlt.fnparse.features.precompute.Alphabet(bft, false);
    try {
      for (Template t : TemplatedFeatures.parseTemplates(featureSetWithPluses, bft, extra)) {
        int i = features.size();
        this.features.add(new TemplateAlphabet(t, "t" + i, i));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    Log.info("setup with " + features.size() + " features");

    customRefinements = e -> {
      assert e.getNumTails() == 6;
      if (UbertsPipeline.isNullSpan(e))
        return new int[] {0};
//      String f = (String) e.getTail(2).getValue();
//      String k = (String) e.getTail(5).getValue();
      HypNode f = e.getTail(2);
      HypNode k = e.getTail(5);
      int mask = (1<<14)-1;
      int ff = (f.hashCode() & mask) * 2 + 0;
      int kk = (k.hashCode() & mask) * 2 + 1;
//      return new int[] {1, 2 + ff, 2 + kk};
      return new int[] {1, 2 + (k.hashCode() & mask)};
    };

    timer = new MultiTimer();
    timer.put("convert-sentence", new Timer("convert-sentence", 100, true));
    timer.put("compute-features", new Timer("compute-features", 10000, true));

    ctx = new TemplateContext();
    depGraphEdges = new Alphabet<>();
    hf = new SemaforicHeadFinder();
    skipped = new Counts<>();
  }

  /** Starts up with some dummy features, for debugging */
  public OldFeaturesWrapper(BasicFeatureTemplates bft) {

    // This should be enough to over-fit
//    String w = "Bc256/8";
    String w = "Word";
    String[] tempNames = new String[] {
//        "Bc256/8-2-grams-between-Head1-and-Span2.Last",
//        "Head1Head2-PathNgram-Basic-LEMMA-DIRECTION-len2",
//        "Head1Head2-PathNgram-Basic-POS-DEP-len2",
        "Head1Head2-Path-Basic-LEMMA-DEP-t",
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
//        "lexPredArg", "lexArgMod", "lexPredMod",
    };

    Template[] temps = new Template[tempNames.length];
    for (int i = 0; i < temps.length; i++) {
      temps[i] = bft.getBasicTemplate(tempNames[i]);
      if (temps[i] == null)
        throw new RuntimeException("couldn't look up: " + tempNames[i]);
    }

    // For my current ad-hoc SRL, I only have one relation type: srlArg, which
    // is normally how FeatureExtractionFactor stores its weights (one set of
    // weights for every Relation).
//    Template role = bft.getBasicTemplate("roleArg");        // fires role if someSpan
//    Template arg = bft.getBasicTemplate("arg");            // fires for nullSpan vs someSpan
//    Template frame = bft.getBasicTemplate("frameRoleArg");  // fires (frame,role) if someSpan

//    customRefinements = e -> {
//      assert e.getNumTails() == 6;
//      if (UbertsPipeline.isNullSpan(e))
//        return new int[] {0};
////      String f = (String) e.getTail(2).getValue();
////      String k = (String) e.getTail(5).getValue();
//      HypNode f = e.getTail(2);
//      HypNode k = e.getTail(5);
//      int mask = (1<<14)-1;
//      int ff = (f.hashCode() & mask) * 2 + 0;
//      int kk = (k.hashCode() & mask) * 2 + 1;
////      return new int[] {1, 2 + ff, 2 + kk};
//      return new int[] {1, 2 + (k.hashCode() & mask)};
//    };

    this.features = new edu.jhu.hlt.fnparse.features.precompute.Alphabet(bft, false);
    // UNIGRAMS
    for (int i = 0; i < temps.length; i++) {
//      ap(role, temps[i], "k*" + temps[i]);
//      ap(arg, temps[i], "b*" + temps[i]);
//      ap(frame, temps[i], "fr*" + temps[i]);
      features.add(new TemplateAlphabet(temps[i], tempNames[i], features.size()));
    }
    // BIGRAMS
    for (int i = 0; i < temps.length-1; i++) {
      for (int j = i+1; j < temps.length; j++) {
        Template prod = new TemplatedFeatures.TemplateJoin(temps[i], temps[j]);
        String name = tempNames[i] + "*" + tempNames[j];
        features.add(new TemplateAlphabet(prod, name, features.size()));
//        ap(role, prod, "k*" + name);
//        ap(arg, prod, "b*" + name);
//        ap(frame, prod, "fr*" + name);
      }
    }

    timer = new MultiTimer();
    timer.put("convert-sentence", new Timer("convert-sentence", 100, true));
    timer.put("compute-features", new Timer("compute-features", 10000, true));

    ctx = new TemplateContext();
    depGraphEdges = new Alphabet<>();
    hf = new SemaforicHeadFinder();
    skipped = new Counts<>();
  }

//  /** This will read all unigram templates in */
//  public OldFeaturesWrapper(BasicFeatureTemplates bft, Double pSkipNeg) {
//    this.pSkipNeg = pSkipNeg;
//    timer = new MultiTimer();
//    timer.put("convert-sentence", new Timer("convert-sentence", 100, true));
//    timer.put("compute-features", new Timer("compute-features", 10000, true));
//
//    skipped = new Counts<>();
//    depGraphEdges = new Alphabet<>();
//    ctx = new TemplateContext();
//    sentCache = null;
//    edu.jhu.hlt.fnparse.features.precompute.Alphabet alph =
//        new edu.jhu.hlt.fnparse.features.precompute.Alphabet(bft);
//    this.hf = alph.getHeadFinder();
//    this.features = alph;
//  }

  public edu.jhu.hlt.fnparse.features.precompute.Alphabet getFeatures() {
    return features;
  }

  private static String getSentenceId(Uberts u) {
    State s = u.getState();
    Relation rel = u.getEdgeType("startDoc");
    HypEdge e = s.match2(rel).item;
    return (String) e.getTail(0).getValue();
  }

  public static boolean NEW_CSYN_MODE = true;  // true means csyn5-*, false means csyn6-*
  private static ConstituencyParse buildCP(Uberts u, String sentenceId) {
    // def csyn6-stanford id parentId head start end label
    // def csyn5-stanford id parentId head span phrase
    Relation consRel = NEW_CSYN_MODE
        ? u.getEdgeType("csyn5-stanford")
        : u.getEdgeType("csyn6-stanford");
    State st = u.getState();
    List<Pair<Integer, edu.jhu.hlt.concrete.Constituent>> cons = new ArrayList<>();
    Map<Integer, edu.jhu.hlt.concrete.Constituent> id2con = new HashMap<>();
    for (LL<HypEdge> cur = st.match2(consRel); cur != null; cur = cur.next) {
      HypEdge e = cur.item;
      assert e.getNumTails() == (NEW_CSYN_MODE ? 5 : 6);
      int cid = Integer.parseInt((String) e.getTail(0).getValue());
      int parent = Integer.parseInt((String) e.getTail(1).getValue());
      int headToken = Integer.parseInt((String) e.getTail(2).getValue());
      Span s;
      String lhs;
      if (NEW_CSYN_MODE) {
        String ss = (String) e.getTail(3).getValue();
        s = Span.inverseShortString(ss);
        lhs = (String) e.getTail(4).getValue();
      } else {
        int startToken = Integer.parseInt((String) e.getTail(3).getValue());
        int endToken = Integer.parseInt((String) e.getTail(4).getValue());
        s = Span.getSpan(startToken, endToken);
        lhs = (String) e.getTail(5).getValue();
      }

      edu.jhu.hlt.concrete.Constituent c = new Constituent();
      c.setId(cid);
      c.setStart(s.start);
      c.setEnding(s.end);
      c.setTag(lhs);
      c.setHeadChildIndex(headToken);  // Need to convert token -> child index

      if (DEBUG > 1)
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
    if (depRel == null)
      return null;
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
      HypNode tok = u.lookupNode(tokenIndex, String.valueOf(i), false);
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

//      HypNode lemma = st.match1(0, lemmaRel, tok).getTail(1);
      LL<HypEdge> lemma = st.match(0, lemmaRel, tok);

      wordL.add((String) word.getValue());
      posL.add((String) pos.getValue());
//      lemmaL.add((String) lemma.getValue());
      if (lemma != null) {
        assert lemma.next == null : "two lemmas?";
        lemmaL.add((String) lemma.item.getTail(1).getValue());
      } else {
        lemmaL.add("na");
      }
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
    boolean allowNull = true;
    sentCache.setCollapsedDeps2(getDepGraph(u, n, u.getEdgeType("dsyn3-col", allowNull)));
    sentCache.setCollapsedCCDeps2(getDepGraph(u, n, u.getEdgeType("dsyn3-colcc", allowNull)));
    sentCache.setStanfordParse(buildCP(u, id));
    sentCache.computeShapes();
    sentCache.getWnWord(0);
    timer.stop("convert-sentence");
  }

  public List<Pair<TemplateAlphabet, String>> features(HypEdge yhat, Uberts x) {
    checkForNewSentence(x);
    timer.start("compute-features");
    Span t = null, s = null;
    String f = null, k = null;
    ctx.clear();
    ctx.setSentence(sentCache);
    if (customEdgeCtxSetup == null) {
      switch (yhat.getRelation().getName()) {
      case "srl1":
        s = extractSpan(yhat, 0, 1);
        break;
      case "srl2":
        Object arg0 = yhat.getTail(0).getValue();
        Object arg1 = yhat.getTail(1).getValue();
        if (arg0 instanceof EqualityArray && arg1 instanceof EqualityArray) {
          // Old form:
          EqualityArray s1 = (EqualityArray) arg0;
          EqualityArray e1 = (EqualityArray) arg1;
          t = extractSpan(e1, 0, 1);
          s = extractSpan(s1, 0, 1);
        } else if (arg0 instanceof String && arg1 instanceof String) {
          // new form, use Span
          t = Span.inverseShortString((String) arg0);
          s = Span.inverseShortString((String) arg1);
        } else {
          throw new RuntimeException("don't know how to handle: " + yhat);
        }
        break;
      case "srl3":
        Srl3EdgeWrapper s3 = new Srl3EdgeWrapper(yhat);
        t = s3.t;
        s = s3.s; // may be null if srl3(t,f,k)
        ctx.setFrame(FrameIndex.getFrameWithSchemaPrefix(s3.f));
        ctx.setRoleS(s3.k);
        break;
      case "srl4":
        t = UbertsPipeline.getTargetFromSrl4(yhat);
        s = UbertsPipeline.getArgFromSrl4(yhat);
        f = (String) yhat.getTail(2).getValue();
        k = (String) yhat.getTail(5).getValue();
        break;
      case "event1":
        t = Span.inverseShortString((String) yhat.getTail(0).getValue());
        break;
      case "predicate2": // (t,f)
        t = Span.inverseShortString((String) yhat.getTail(0).getValue());
        f = (String) yhat.getTail(1).getValue();
        break;
      case "argument4": // (t,f,s,k)
        assert yhat.getNumTails() == 4;
        t = Span.inverseShortString((String) yhat.getTail(0).getValue());
        f = (String) yhat.getTail(1).getValue();
        s = Span.inverseShortString((String) yhat.getTail(2).getValue());
        k = (String) yhat.getTail(3).getValue();
        break;
      default:
        skipped.increment(yhat.getRelation().getName());
//        if (skipped.getTotalCount() % 1000 == 0)
//          Log.info("skipped: " + skipped.toString());
        Log.warn("skipped: " + skipped.toString());
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
    if (f != null)
      ctx.setFrame(FrameIndex.getFrameWithSchemaPrefix(f));
    if (k != null)
      ctx.setRoleS(k);

    // Actually compute the features
    if (DEBUG > 1) {
      Log.info("computing features for " + yhat);
      TemplateContext.showContext(ctx);
    }
    List<Pair<TemplateAlphabet, String>> feats = new ArrayList<>();
    for (TemplateAlphabet ftemp : features) {
      Iterable<String> fts = ftemp.template.extract(ctx);
      if (fts != null) {
        for (String ft : fts) {
          Pair<TemplateAlphabet, String> p = new Pair<>(ftemp, ft);
          if (DEBUG > 1)
            System.out.println("\t" + ftemp.name + "\t" + ft);
          feats.add(p);
        }
      }
    }
    if (DEBUG > 1)
      System.out.println(feats.size() + " features for " + yhat);

    timer.stop("compute-features");
    return feats;
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