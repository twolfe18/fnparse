package edu.jhu.hlt.uberts.srl;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.precompute.Alphabet;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdExample;
import edu.jhu.hlt.fnparse.inference.frameid.FrameSchemaHelper;
import edu.jhu.hlt.fnparse.inference.frameid.Wsabie;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.Beam;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TokenToConstituentIndex;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.factor.AtMost1;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;

/**
 * Assumes event1(target) exists and provides event2(target,frame)
 *
 * Current implementation reads in a serialized {@link Wsabie} model.
 *
 * @author travis
 */
public class FrameId {
  public static boolean DEBUG = false;

  // Model which controls HypEdge scores
  private Wsabie wsabie;
  private int topKframes = 10;  // ask wsabie for at most this many frames per target

  // Extracts features for targets
  // (this holds a giant string<=>int mapping for chosen templates).
  private edu.jhu.hlt.fnparse.features.precompute.Alphabet featureTemplates;

  // Borrowed (assumed to already exist)
  private NodeType tokenIndex;
  private Relation event1;      // event1(t)    -- target id

  // Created
  private NodeType frames;
  private Relation event2;      // event2(t,f)  -- target label (frame)

  /**
   * @param u is the {@link Uberts}s which you're adding frame id to.
   * @param wsabieModel is a java-serialized instance of {@link Wsabie}.
   * @param featureFile is a BiAlph.LineMode.ALPH formatted (4 column TSV) file
   * which specifies which features the wsabieModel was trained with.
   */
  public FrameId(Uberts u, File wsabieModel, File featureFile) {

    Log.info("reading wsabie frame id model from " + wsabieModel.getPath());
    wsabie = (Wsabie) FileUtil.deserialize(wsabieModel);

    Log.info("reading feature templates from " + featureFile.getPath());
    featureTemplates = new Alphabet(featureFile);

    init(u, wsabie, featureTemplates);
  }

  public FrameId(Uberts u, Wsabie frameId, Alphabet features) {
    init(u, frameId, features);
  }

  private void init(Uberts u, Wsabie frameId, Alphabet features) {
    this.wsabie = frameId;
    this.featureTemplates = features;

    tokenIndex = u.lookupNodeType("tokenIndex", false);
    frames = u.lookupNodeType("frames", true);
    event1 = u.getEdgeType("event1");
    assert event1 != null : "needs target id (e.g. pred-patt)";
    NodeType ev1Head = u.getWitnessNodeType(event1);
    event2 = u.addEdgeType(new Relation("event2", ev1Head, frames));

    Log.info("adding TransitionGenerator for event1(t) => event2(t,f) forall f in wsabiePredict(t)");
    TKey[] newEvent1 = new TKey[] {
//        new TKey(u.getEdgeType("event1")),  TODO
    };
    u.addTransitionGenerator(newEvent1, new TransitionGenerator() {
      @Override
      public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
        if (DEBUG) {
          Log.info("match on " + lhsValues);
        }

        HypEdge e1 = lhsValues.getBoundEdge(0);
        HypNode pred = e1.getHead();
        assert e1.getRelation() == event1;

        HypNode t0 = e1.getTail(0);
        HypNode t1 = e1.getTail(1);
        assert t0.getNodeType() == tokenIndex;
        assert t1.getNodeType() == tokenIndex;
        int start = (Integer) t0.getValue();
        int end = (Integer) t1.getValue();
        assert start < end : "start=" + start + " end=" + end;

//        String pStart = extractPos(t0);
//        String pEnd = extractPos(t1);

        if (start != end-1) {
          Log.warn("implement frame id for spans");
          return Collections.emptyList();
        }
        Span target = Span.getSpan(start, end);
        if (DEBUG) {
          Log.info("predicting frames for target=" + target);
        }

        // TODO consider using FrameConfusionSetCreation
        // Right now we iterate over all frames in PB and FN

        // Note: These are not obviously safe because these values weren't
        // matched in the LHS graph fragment. I'm relying on an unwritten proof
        // that the only way to have a event1 is to have an pos and word.
//        String word = extract("word", t0);
//        String tag = extract("pos", t0);

        // Build a Sentence off of which we can extract features.
        Document tdoc = u.getDoc();
        // TODO Update BasicFeatureTemplates so they can natively use tutils.Document
        // instead of doing this conversion to fnparse.Sentence every time.
        IntPair sentBoundary = getSentenceBoundaries(start, tdoc);
        Sentence sent = Sentence.convertFromTutils(
            tdoc.getId(), tdoc.getId(), tdoc,
            sentBoundary.first, sentBoundary.second,
            false,  // addGoldParse
            true,   // addStanfordCParse
            true,   // addStandordBasicDParse
            true,   // addStanfordColDParse
            false   // takeGoldPos
            );
        if (DEBUG) {
          Log.info("converted sentence:\n" + Describe.sentenceWithDeps(sent));
        }

        // Get the features for this target
        List<Feature> features = getFeatures(target, sent);
        int[] flatFeats = new int[features.size()];
        int T = FrameId.this.featureTemplates.size();
        for (int i = 0; i < flatFeats.length; i++) {
          Feature f = features.get(i);
          // TODO This needs to match how Wsabie did this!
          
          
          flatFeats[i] = new ProductIndex(f.template, T)
              .destructiveProd(f.feature)
              .getProdFeatureSafe();
        }
        FrameIdExample ex = new FrameIdExample(-1, flatFeats);

        // For now limit frame predictions ot a single schema.
        // This is needed (at least) because FModel doesn't seem cool with
        // multi-FrameIndex predictions.
        FrameSchemaHelper.Schema frameSchema;
        if (ExperimentProperties.getInstance().getBoolean("propbank")) {
          frameSchema = FrameSchemaHelper.Schema.PROPBANK;
        } else {
          frameSchema = FrameSchemaHelper.Schema.FRAMENET;
        }
        List<Integer> frameConfusionSet = wsabie.getSchema().getFramesInSchema(frameSchema);
        ex.setFrameConfusionSet(frameConfusionSet, false);

        // Actually make the frame predictions
        Beam<Integer> yhatK = wsabie.predict(ex, topKframes);
        if (DEBUG) {
          Log.info("asked for up to " + topKframes + " and received " + yhatK.size());
        }

        List<Pair<HypEdge, Adjoints>> l = new ArrayList<>();
        while (yhatK.size() > 0) {
          Beam.Item<Integer> ys = yhatK.popItem();
//          HypNode frame = u.lookupNode(frames, "fr=" + ys.getItem());
//          HypNode frame = u.lookupNode(frames, ys.getItem());
          int frameIdx = ys.getItem();
          FrameSchemaHelper fsh = wsabie.getSchema();
          Frame f = fsh.getFrame(frameIdx);
          if (f == null) {
            // Propbank sometimes can't produce a Frame for a given index...
            continue;
          }
          HypNode frame = u.lookupNode(frames, f, true);

          if (DEBUG) {
            Log.info("adding possible frame: " + f.getName() + " score=" + ys.getScore());
          }

          // TODO Currently Wsabie doesn't offer Adjoints in predict, so we can't
          // update parameters this way. Fix this!
          Adjoints a = new Adjoints.Constant(ys.getScore());

          HypNode[] tail = new HypNode[] { pred, frame };
          HypEdge e = u.makeEdge(event2, tail);
          l.add(new Pair<>(e, a));
        }
        if (l.isEmpty()) {
          Log.warn("no frames for " + Describe.span(target, sent));
          assert false;
        }
        return l;
      }
      public String extract(String relation, HypNode token) {
        Relation posRel = u.getEdgeType(relation);
        LL<HypEdge> posLL = null; // TODO u.getState().match(posRel, token);
        assert posLL != null && posLL.next == null;
        return (String) posLL.item.getTail(1).getValue();
      }
    });

    Log.info("adding GlobalFactor specifying that frames are mutually exclusive");
    TKey[] newEvent2 = new TKey[] {
//        new TKey(u.getEdgeType("event2")),  TODO
    };
    u.addGlobalFactor(newEvent2, new AtMost1.RelNode1(event2, gtt -> {
      HypEdge ev2E = gtt.getBoundEdge(0);
      assert ev2E.getRelation() == event2;
      HypNode pred = ev2E.getTail(0);
      return pred;
    }));

    Log.info("done");
  }

  /**
   * Returns (firstToken, lastToken) for the sentence in tdoc which tokenIndex
   * belongs to.
   */
  public static IntPair getSentenceBoundaries(int tokenIdex, Document tdoc) {
    TokenToConstituentIndex tok2sent = tdoc.getT2cSentence();
    int sentConsIdx = tok2sent.getParent(tokenIdex);
    Document.Constituent sent = tdoc.getConstituent(sentConsIdx);
    IntPair b = new IntPair(sent.getFirstToken(), sent.getLastToken());
    assert b.first <= b.second;
    assert b.first >= 0;
    return b;
  }

  private List<Feature> getFeatures(Span target, Sentence sent) {
    // TODO Need to instantiate a new FeaturePrecomputation.ToMemBuffer every time?
    assert featureTemplates != null;
    FeaturePrecomputation.ToMemBuffer fp = new FeaturePrecomputation.ToMemBuffer(false, featureTemplates);
    FNParse y = new FNParse(sent, Collections.emptyList());
    fp.emitAllFrameId(y);
    List<Feature> fx = fp.getTargetFeatures(target);
    assert fx != null;
    if (DEBUG)
      Log.info("found " + fx.size() + " features for target: " + Describe.span(target, sent));
    return fx;
  }
}
