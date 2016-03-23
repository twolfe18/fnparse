package edu.jhu.hlt.uberts.srl;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.propbank.PropbankReader;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.precompute.Alphabet;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.features.precompute.FeatureSet;
import edu.jhu.hlt.fnparse.rl.full.FModel;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Instance;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;

/**
 * Given labeled targets/predicates (frames), identify and label the semantic
 * arguments to each predicate.
 *
 * Currently uses a de-serialized {@link FModel}.
 *
 * @author travis
 */
public class Srl {
  public static boolean DEBUG = false;

  private Uberts u;
  private FModel model;

  // Needed for feature crap
  // In fnparse/FMode/ShimModel pipeline, this is stored in CachedFeatures/CFLike
  // TODO Consider whether I should actually be instantiating a CachedFeatures
  private BiAlph bialph;              // required
  private int[][] featureSet;         // derived
  private int[] template2cardinality; // derived
  public void setupFeatures(File bialphFile, File featureSetFile) {
    if (DEBUG)
      Log.info("");
    this.bialph = new BiAlph(bialphFile, LineMode.ALPH);
    this.template2cardinality = bialph.makeTemplate2Cardinality();
    this.featureSet = FeatureSet.getFeatureSet2(featureSetFile, bialph);
  }

  // Borrowed (assumed to already exist)
  private NodeType tokenIndex;
  private NodeType frames;
  private NodeType preds;       // head(event1)
  private Relation event1;
  private Relation event2;

  // Created
  private NodeType args;        // head(srl1)
  private NodeType roles;
  private Relation srl1;        // srl1(s)      -- location of arguments
  private Relation srl2;        // srl2(t,s)    -- pred-arg binding
  private Relation srl3;        // srl3(t,s,k)  -- semantic role


  /**
   * Don't create multiple {@link Srl}s per {@link Uberts}s, as the constructor
   * adds without checking if something is there already... TODO fix
   */
  public Srl(Uberts u, FModel model) {
    this.u = u;
    this.model = model;
    this.tokenIndex = u.lookupNodeType("tokenIndex", false);
    this.frames = u.lookupNodeType("frames", false);
    this.roles = u.lookupNodeType("roles", true);
    this.event1 = u.getEdgeType("event1");
    this.event2 = u.getEdgeType("event2");
    this.srl1 = u.addEdgeType(new Relation("srl1", tokenIndex, tokenIndex));
    this.preds = u.getWitnessNodeType(event1);
    this.args = u.getWitnessNodeType(srl1);
    this.srl2 = u.addEdgeType(new Relation("srl2", preds, args));
    this.srl3 = u.addEdgeType(new Relation("srl3", u.getWitnessNodeType(srl2), roles));
//    setupTransitions();
    setupFModelTransitions();
    setupHardFactors();
  }

  private void setupFModelTransitions() {
    Log.info("adding TransionGenerator: event2(t,f) => srl3(t,s,k) via FModel");
    TKey[] newEvent2 = new TKey[] {
        new TKey(event2)
    };
    u.addTransitionGenerator(newEvent2, new TransitionGenerator() {
      @Override
      public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
        // Find frame from event2(t,f)
        HypEdge ev2E = lhsValues.getBoundEdge(0);
        assert ev2E.getNumTails() == 2;
        HypNode ev1HN = ev2E.getTail(0);
        HypNode frameN = ev2E.getTail(1);
        assert frameN.getNodeType() == frames;
//        int frame = (Integer) frameN.getValue();
        Frame frame = (Frame) frameN.getValue();

        LL<HypEdge> foo = u.getState().match(event1, ev1HN);
        assert foo != null && foo.next == null;
        HypEdge ev1E = foo.item;
        assert ev1E.getNumTails() == 2;
        assert ev1E.getRelation() == event1;
        int ts = (Integer) ev1E.getTail(0).getValue();
        int te = (Integer) ev1E.getTail(1).getValue();
        Span target = Span.getSpan(ts, te);

        // Create FNParse and run non-joint FModel
        // TODO Update BasicFeatureTemplates so they can natively use tutils.Document
        // instead of doing this conversion to fnparse.Sentence every time.
        Document tdoc = u.getDoc();
        IntPair sentBoundary = FrameId.getSentenceBoundaries(ts, tdoc);
        Sentence sent = Sentence.convertFromTutils(
            tdoc.getId(), tdoc.getId(), tdoc,
            sentBoundary.first, sentBoundary.second,
            false,  // addGoldParse
            true,   // addStanfordCParse
            true,   // addStandordBasicDParse
            true,   // addStanfordColDParse
            false   // takeGoldPos
            );
        FrameInstance fi = FrameInstance.frameMention(frame, target, sent);
        FNParse frameInSent = new FNParse(sent, Arrays.asList(fi));
        setFeatures(frameInSent);
        FNParse args = model.predict(frameInSent);
        assert args.numFrameInstances() == 1;

        // Add edges for only the 1-best predictions of the model
        List<Pair<HypEdge, Adjoints>> eds = new ArrayList<>();
        FrameInstance fiArgs = args.getFrameInstance(0);
        assert fiArgs.getFrame() == frame;
        int K = frame.numRoles();
        for (int k = 0; k < K; k++) {
          Span s = fiArgs.getArgument(k);
          if (s == Span.nullSpan)
            continue;

          // srl1(s)
          HypNode sn = u.lookupNode(Srl.this.args, s);

          // srl2(t,s)
          HypNode tn = ev2E.getHead();

          // srl3(t,s,k)
          HypNode kn = u.lookupNode(Srl.this.roles, frame.getRole(k));

          HypEdge srl3E = u.makeEdge(srl3, tn, sn, kn);
          Adjoints a = Adjoints.Constant.ONE;
          eds.add(new Pair<>(srl3E, a));
        }

        return eds;
      }
    });
  }

  /**
   * Creates a {@link CachedFeatures.Item} populated with features and sets it
   * into the given {@link FNParse}.
   */
  private void setFeatures(FNParse y) {
    y.featuresAndSpans =
        new CachedFeatures.Item(y);
    Alphabet templateAlph = new Alphabet();  // TODO all templates

    // Compute the features
    FeaturePrecomputation.ToMemBuffer fp =
        new FeaturePrecomputation.ToMemBuffer(true, templateAlph);
    fp.emitAllRoleId(y);

    // Retrieve the features and put them into a format CachedFeature.Item likes
    for (Entry<SpanPair, List<Feature>> stfx : fp.getArgFeatures()) {
      Span t = stfx.getKey().get1();
      Span s = stfx.getKey().get2();
      List<Feature> feats = stfx.getValue();
//      int[] templates = new int[feats.size()];
//      int[] features = new int[feats.size()];
//      for (int i = 0; i < templates.length; i++) {
//        Feature f = feats.get(i);
//        templates[i] = f.template;
//        features[i] = f.feature;
//      }
//      BaseTemplates bt = new BaseTemplates(templates, features);
      FeatureFile.Line bt = new FeatureFile.Line(feats, true);
      assert bt.checkFeaturesAreSortedByTemplate();
      y.featuresAndSpans.setFeatures(t, s, bt);
    }
    y.featuresAndSpans.convertToFlattenedRepresentation(
        featureSet, template2cardinality);
  }

  /**
   * This is the setup to be done for when the model is fully uberts-ized.
   * Currently I'm just using an {@link FModel}, which doesn't really need to
   * care about srl1,srl2, just srl3.
   */
  private void setupTransitions() {
    Relation posRel = u.getEdgeType("pos");

    // () => srl1(s)
    // TODO add a table for constituents
    // TODO add a table for constituents derived using XUE_PALMER_HERMANN etc
    // Currently works by taking j from pos(j,t) and creating all srl1(i,j) s.t. j-i \in [0,W] for some max width W.
    TKey[] newSrl1 = new TKey[] {
        new TKey(posRel),
        new TKey(tokenIndex),
    };

    // srl1(s) ^ event1(t) => srl2(t,s)
    
    // srl2(t,s) ^ event2(t,f) => srl3(t,s,k) forall k in roles(f)
    
    throw new RuntimeException("finish me");
  }


  // Soft factors are local features, build into TransitionGenerator
  public void setupHardFactors() {
    // TODO
    Log.warn("TODO");
  }


  /**
   * Make these from {@link PropbankReader} and {@link FileFrameInstanceProvider}.
   */
  class FNParseInstance implements Instance {
    private FNParse y;
    private Set<HashableHypEdge> goldEdges;
    private CachedFeatures.Item it;

    public FNParseInstance(CachedFeatures.Item it) {
//    public FNParseInstance(FNParse y) {
//      this.y = y;
      this.it = it;
      this.y = it.getParse();
      this.goldEdges = new HashSet<>();

      HypNode[] tail;
      for (FrameInstance fi : y.getFrameInstances()) {
        // event1
        Span target = fi.getTarget();
        tail = new HypNode[] {
            new HypNode(tokenIndex, target.start),
            new HypNode(tokenIndex, target.end),
        };
//        NodeType event1HeadNT = u.getWitnessNodeType(event1);
////        int event1HeadValue = Span.index(target);
//        String event1HeadValue = target.shortString();
//        HypNode event1Head = u.lookupNode(event1HeadNT, event1HeadValue);
//        HypEdge ev1 = new HypEdge(event1, event1Head, tail);
        HypEdge ev1 = u.makeEdge(event1, tail);
        goldEdges.add(new HashableHypEdge(ev1));

        // event2
        Frame frame = fi.getFrame();
        tail = new HypNode[] { ev1.getHead(), new HypNode(frames, frame) };
//        String event2HeadValue = event1HeadValue + "-" + frame.getName();
//        HypNode event2Head = u.lookupNode(u.getWitnessNodeType(event2), event2HeadValue);
//        HypEdge ev2 = new HypEdge(event2, event2Head, tail);
        HypEdge ev2 = u.makeEdge(event2, tail);
        goldEdges.add(new HashableHypEdge(ev2));

        Set<Span> uniq = new HashSet<>();
        int K = frame.numRoles();
        for (int k = 0; k < K; k++) {
          // srl1
          // srl2
          // srl3
          // TODO
        }
      }

      throw new RuntimeException("implement me");
    }

    @Override
    public void setupState(Uberts u) {
      // TODO add words, pos, dparse, cparse
      throw new RuntimeException("implement me");
    }

    @Override
    public double label(HypEdge e) {
      // TODO Auto-generated method stub
      if (goldEdges.contains(e))
        return +1;
      return -1;
    }
  }

  public static List<FNParseInstance> getSomeParses(Srl srl) {
    // TODO See ShimModel for how to build these files, which have features in them
//    List<CachedFeatures.Item> train = (List<CachedFeatures.Item>) FileUtil.deserialize(trainF);
//    List<CachedFeatures.Item> dev = (List<CachedFeatures.Item>) FileUtil.deserialize(devF);
//    List<CachedFeatures.Item>  test = (List<CachedFeatures.Item>) FileUtil.deserialize(testF);
    File f = new File("/tmp/foo");
    List<CachedFeatures.Item> train = (List<CachedFeatures.Item>) FileUtil.deserialize(f);
    List<FNParseInstance> ys = new ArrayList<>();
    for (CachedFeatures.Item y : train)
      ys.add(srl.new FNParseInstance(y));
    return ys;

//    FileFrameInstanceProvider fip = FileFrameInstanceProvider.debugFIP;
//    List<FNParseInstance> ys = new ArrayList<>();
//    for (FNParse y : DataUtil.iter2list(fip.getParsedSentences()))
//      ys.add(srl.new FNParseInstance(y));
//    return ys;
  }

//  public static void main(String[] args) {
//    ExperimentProperties.init(args);
//    Uberts u = new Uberts(new Random(9001));
//    Srl srl = new Srl(u);
//    List<FNParseInstance> y = getSomeParses(srl);
//    Training t = new Training(u, y);
//    int maxEpoch = 5;
//    t.train(maxEpoch);
//  }
}
