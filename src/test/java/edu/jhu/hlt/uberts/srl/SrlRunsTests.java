package edu.jhu.hlt.uberts.srl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.fnparse.features.precompute.Alphabet;
import edu.jhu.hlt.fnparse.inference.frameid.FrameSchemaHelper;
import edu.jhu.hlt.fnparse.inference.frameid.Wsabie;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.rl.full.FModel;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer.RTConfig;
import edu.jhu.hlt.tutils.ConcreteDocumentMapping;
import edu.jhu.hlt.tutils.ConcreteToDocument;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.ling.Language;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;

public class SrlRunsTests {

  private List<PredPatt.Target> targets;
  private Uberts u;
  private NodeType tokenIndex;
  private NodeType posTag;
  private Relation posRel;

  @Before
  public void setup() throws IOException {
    PredPatt.DEBUG = true;
    FrameId.DEBUG = true;
    Srl.DEBUG = true;
    ExperimentProperties.init();

    String docId = "doc1";
    targets = new ArrayList<>();
    targets.add(new PredPatt.Target(docId, Span.getSpan(3, 4)));
    targets.add(new PredPatt.Target(docId, Span.getSpan(7, 9)));

    u = new Uberts(new Random(9001));

    // This is how we trigger PredPatt to add edges
    tokenIndex = u.lookupNodeType("tokenIndex", true);
    posTag = u.lookupNodeType("posTag", true);
    posRel = u.addEdgeType(new Relation("pos", tokenIndex, posTag));

    Communication comm = FrameIdTests.getExampleCagCommunication();
    Language lang = Language.EN;
    ConcreteToDocument cio = new ConcreteToDocument(null, null, null, lang);
    cio.readConcreteStanford();
    ConcreteDocumentMapping c2d = cio.communication2Document(comm, 0, new MultiAlphabet(), lang);
    u.setDocument(c2d.getDocument());
  }

  @Test
  public void test0() {

    // Add target id
    PredPatt pp = new PredPatt(u, targets);

    // Add frame id
    FrameSchemaHelper fsh = new FrameSchemaHelper(
        new File("data/frameid/feb15a/raw-shards/job-0-of-256/role-names.txt.gz"));
    int numTemplates = 2004;
    Wsabie wsabie = new Wsabie(fsh, numTemplates);
    Alphabet features = new Alphabet(new File("data/frameid/feb15a/coherent-shards/alphabet.tiny.txt.gz"), false);
    FrameId fid = new FrameId(u, wsabie, features);

    // Add SRL
    FModel fm = getSrlModel();
    SrlViaFModel srl = new SrlViaFModel(u, fm);
    File bialphFile = new File("data/debugging/coherent-shards-filtered-small/alphabet.txt");
    File featureSetFile = new File("data/debugging/propbank-16-1280.noK.fs");
    srl.setupFeatures(bialphFile, featureSetFile);

    // Kick off the cascade of new edges
    for (int i = 0; i < 10; i++) {
      HypNode idx = u.lookupNode(tokenIndex, i, true);
      HypNode tag = u.lookupNode(posTag, "tag" + (i%3), true);
      HypEdge e = u.makeEdge(posRel, idx, tag);
      Log.info("adding on " + e);
      u.addEdgeToState(e);
    }

    // See what edges where added to the agenda.
    Log.info("agenda after adding POS tags:");
    u.getAgenda().dbgShowScores();

    // See what predictions can be made
    u.dbgRunInference();
  }

  public FModel getSrlModel() {
//    File fmodelFile = new File("foo");
//    FModel fm = (FModel) FileUtil.deserialize(fmodelFile);
    File workingDir = new File("/tmp/srl-tests-fmodel-wd");
    if (!workingDir.isFile())
      workingDir.mkdir();
    Random rand = u.getRandom();
    RTConfig rtConf = new RTConfig("foo", workingDir, rand);
    DeterministicRolePruning.Mode pruningMode =
        DeterministicRolePruning.Mode.XUE_PALMER_HERMANN;
    int numShards = 1;
    FModel fm = new FModel(rtConf, pruningMode, numShards);
    return fm;
  }
}
