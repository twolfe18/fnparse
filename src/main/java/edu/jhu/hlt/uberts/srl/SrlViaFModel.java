package edu.jhu.hlt.uberts.srl;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

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
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;

public class SrlViaFModel extends Srl {

  private FModel model;

  // Needed for feature crap
  // In fnparse/FMode/ShimModel pipeline, this is stored in CachedFeatures/CFLike
  // TODO Consider whether I should actually be instantiating a CachedFeatures
  private BiAlph bialph;              // required
  private int[][] featureSet;         // derived
  private int[] template2cardinality; // derived

  public SrlViaFModel(Uberts u, FModel model) {
    super(u);
    this.model = model;
  }

  public void setupFeatures(File bialphFile, File featureSetFile) {
    if (DEBUG)
      Log.info("");
    this.bialph = new BiAlph(bialphFile, LineMode.ALPH);
    this.template2cardinality = bialph.makeTemplate2Cardinality();
    this.featureSet = FeatureSet.getFeatureSet2(featureSetFile, bialph);
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

  @Override
  protected void setupTransitions() {
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
        FNParse yhatArgs = model.predict(frameInSent);
        assert yhatArgs.numFrameInstances() == 1;

        // Add edges for only the 1-best predictions of the model
        List<Pair<HypEdge, Adjoints>> eds = new ArrayList<>();
        FrameInstance fiArgs = yhatArgs.getFrameInstance(0);
        assert fiArgs.getFrame() == frame;
        int K = frame.numRoles();
        for (int k = 0; k < K; k++) {
          Span s = fiArgs.getArgument(k);
          if (s == Span.nullSpan)
            continue;

          // srl1(s)
          HypNode sn = u.lookupNode(args, s, true);

          // srl2(t,s)
          HypNode tn = ev2E.getHead();

          // srl3(t,s,k)
          HypNode kn = u.lookupNode(roles, frame.getRole(k), true);

          HypEdge srl3E = u.makeEdge(srl3, tn, sn, kn);
          Adjoints a = Adjoints.Constant.ONE;
          eds.add(new Pair<>(srl3E, a));
        }

        return eds;
      }
    });
  }
}
