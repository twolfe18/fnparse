package edu.jhu.hlt.uberts.srl;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.propbank.PropbankReader;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Instance;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Training;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.factor.GlobalFactor;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;

/**
 * Defines {@link NodeType}s, {@link Relation}s, {@link TransitionGenerator}s,
 * and {@link GlobalFactor}s for SRL based on the {@link FNParse} data framework.
 *
 * Function<HypEdge, Double> supervision;
 *
 * @author travis
 */
public class Srl {

  private Uberts u;

  private NodeType tokenIndex;
  private NodeType preds;       // head(event1)
  private NodeType frames;
  private NodeType args;        // head(srl1)
  private NodeType roles;

  private Relation event1;      // (t)
  private Relation event2;      // (t,f)
  private Relation srl1;        // (s)
  private Relation srl2;        // (t,s)
  private Relation srl3;        // (t,s,k)

  /*
   * Store params here.
   * Have ability to load params for FN, PB, joint, slot fill, etc.
   *
   * Have a list of train procedures.
   * Each one is a collection of instances.
   * Define instances.
   */

  /**
   * Don't create multiple {@link Srl}s per {@link Uberts}s, as the constructor
   * adds without checking if something is there already... TODO fix
   */
  public Srl(Uberts u) {
    // TODO addEdgeType is probably a problem: want all of these to be idempotent
    this.u = u;
    this.tokenIndex = u.lookupNodeType("tokenIndex", true);
    this.frames = u.lookupNodeType("frames", true);
    this.roles = u.lookupNodeType("roles", true);
    this.event1 = u.addEdgeType(new Relation("event1", tokenIndex, tokenIndex));
    this.preds = u.getWitnessNodeType(event1);
    this.event2 = u.addEdgeType(new Relation("event2", preds, frames));
    this.srl1 = u.addEdgeType(new Relation("srl1", tokenIndex, tokenIndex));
    this.args = u.getWitnessNodeType(srl1);
    this.srl2 = u.addEdgeType(new Relation("srl2", preds, args));
    this.srl3 = u.addEdgeType(new Relation("srl3", u.getWitnessNodeType(srl2), roles));
    setupTransitions();
    setupHardFactors();
  }

  private void setupTransitions() {
    // () => event1(t)
    // From this fragment I can extract the (pos,word) values at every index.
    // TODO Call pred-patt here.
    TKey[] newPosTagAfterWord = new TKey[] {
        new TKey(u.getEdgeType("pos")),
        new TKey(tokenIndex),
        new TKey(u.getEdgeType("word")),
    };
    u.addTransitionGenerator(newPosTagAfterWord, new TransitionGenerator() {
      @Override
      public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {

        // I don't actually need (most of) these values, I just want to ensure
        // they're present before starting event1 predictions.
        HypEdge posTagging = lhsValues.getBoundEdge(0);
        assert posTagging.getNumTails() == 2;
        int i = (Integer) posTagging.getTail(0).getValue();
        String tag = (String) posTagging.getTail(1).getValue();
//
//        HypEdge wordTagging = lhsValues.getBoundEdge(2);
//        assert wordTagging.getNumTails() == 2;
//        String word = (String) wordTagging.getTail(1).getValue();

        // Lets start with single word predicates only for verbs
        if (tag.startsWith("V")) {
          HypNode start = u.lookupNode(tokenIndex, i);
          HypNode end = u.lookupNode(tokenIndex, i+1);
          HypEdge e = u.makeEdge(event1, start, end);
          Adjoints a = Adjoints.Constant.ZERO;    // TODO
          return Arrays.asList(new Pair<>(e, a));
        } else {
          return Collections.emptyList();
        }
      }
    });


    // event1(t) => event2(t,f) forall f in filter(t)
    TKey[] newEvent1 = new TKey[] {
        new TKey(u.getEdgeType("event1")),
    };
    u.addTransitionGenerator(newEvent1, new TransitionGenerator() {
      @Override
      public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
        HypEdge e1 = lhsValues.getBoundEdge(0);
        HypNode pred = e1.getHead();
        assert e1.getRelation() == event1;

        HypNode t0 = e1.getTail(0);
        HypNode t1 = e1.getTail(1);
        assert t0.getNodeType() == tokenIndex;
        assert t1.getNodeType() == tokenIndex;
        int start = (Integer) t0.getValue();
        int end = (Integer) t1.getValue();

//        String pStart = extractPos(t0);
//        String pEnd = extractPos(t1);

        assert start == end-1 : "implement frame triage for spans";

        // TODO Figure out how to properly do this,
        // see FrameConfusionSetCreation

        // Note: These are not obviously safe because these values weren't
        // matched in the LHS graph fragment. I'm relying on an unwritten proof
        // that the only way to have a event1 is to have an pos and word.
        String word = extract("word", t0);
        String tag = extract("pos", t0);
        int senses = 5;
        List<Pair<HypEdge, Adjoints>> l = new ArrayList<>();
        for (int i = 0; i < senses; i++) {
          HypNode frame = u.lookupNode(frames, word + "-" + tag + "-" + i);
          Adjoints a = Adjoints.Constant.ZERO;  // TODO
          HypNode[] tail = new HypNode[] { pred, frame };
          HypEdge e = u.makeEdge(event2, tail);
          l.add(new Pair<>(e, a));
        }
        return l;
      }
      public String extract(String relation, HypNode token) {
        Relation posRel = u.getEdgeType(relation);
        LL<HypEdge> posLL = u.getState().match(posRel, token);
        assert posLL != null && posLL.next == null;
        return (String) posLL.item.getTail(1).getValue();
      }
    });


    // event2(t,f) ^ srl2(t,s) => srl3(t,s,k) for k in roles(f)
    // TODO
    Log.warn("TODO");
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

  public static void main(String[] args) {
    ExperimentProperties.init(args);
    Uberts u = new Uberts(new Random(9001));
    Srl srl = new Srl(u);
    List<FNParseInstance> y = getSomeParses(srl);
    Training t = new Training(u, y);
    int maxEpoch = 5;
    t.train(maxEpoch);
  }
}
