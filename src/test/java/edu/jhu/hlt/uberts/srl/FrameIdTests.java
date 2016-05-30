package edu.jhu.hlt.uberts.srl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.serialization.iterators.TarGzArchiveEntryCommunicationIterator;
import edu.jhu.hlt.fnparse.features.precompute.Alphabet;
import edu.jhu.hlt.fnparse.inference.frameid.FrameSchemaHelper;
import edu.jhu.hlt.fnparse.inference.frameid.Wsabie;
import edu.jhu.hlt.tutils.ConcreteDocumentMapping;
import edu.jhu.hlt.tutils.ConcreteToDocument;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.ling.Language;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;

/**
 * @deprecated This was designed to make use of PredPatt and a bunch of other
 * defunct machinery.
 *
 * @author travis
 */
public class FrameIdTests {

  private List<PredPatt.Target> targets;
  private Uberts u;
  private NodeType tokenIndex;
  private NodeType posTag;
  private Relation posRel;

  /**
   * Read the first {@link Communication} in:
   * data/concretely-annotated-gigaword/apw_eng_200009.tar.gz
   */
  public static Communication getExampleCagCommunication() throws IOException {
    File f = new File("data/concretely-annotated-gigaword/apw_eng_200009.tar.gz");
    if (!f.isFile())
      throw new RuntimeException("can't find: " + f.getPath());
    Log.info("reading CAG Communication from " + f.getPath());
    try (InputStream is = new FileInputStream(f)) {
      TarGzArchiveEntryCommunicationIterator itr =
          new TarGzArchiveEntryCommunicationIterator(is);
      assert itr.hasNext();
      Communication c = itr.next();
      return c;
    }
  }

  @Before
  public void setup() throws IOException {
    PredPatt.DEBUG = true;
    FrameId.DEBUG = true;
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

//    tdoc = new Document(docId, 0, new MultiAlphabet());
//    TokenItr t = tdoc.getTokenItr(0);
//    t.setWord("John");

    // Rather than create my own tutils.Document, lets get one from a converted
    // Gigaword Concrete Communication (which we can also test in the process).
    Communication comm = getExampleCagCommunication();
    Language lang = Language.EN;
    ConcreteToDocument cio = new ConcreteToDocument(null, null, null, lang);
    cio.readConcreteStanford();
    ConcreteDocumentMapping c2d = cio.communication2Document(comm, 0, new MultiAlphabet(), lang);
//    tdoc = c2d.getDocument();
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
//    Alphabet features = new Alphabet(new File("data/frameid/feb15a/coherent-shards/alphabet.txt.gz"), false);
    // BAD: zcat data/frameid/feb15a/coherent-shards/alphabet.txt.gz | awk -F"\t" '$1 == 1226 || $1 == 689 || $1 == 890 || $1 == 582' | gzip -c >data/frameid/feb15a/coherent-shards/alphabet.tiny.txt.gz
    // MAYBE: zcat data/frameid/feb15a/coherent-shards/alphabet.txt.gz | head -n 10000 | gzip -c >data/frameid/feb15a/coherent-shards/alphabet.tiny.txt.gz
    Alphabet features = new Alphabet(new File("data/frameid/feb15a/coherent-shards/alphabet.tiny.txt.gz"), false);
    FrameId fid = new FrameId(u, wsabie, features);

    // Kick off the cascade of new edges
    for (int i = 0; i < 10; i++) {
      HypNode idx = u.lookupNode(tokenIndex, i, true);
      HypNode tag = u.lookupNode(posTag, "tag" + (i%3), true);
      HypEdge e = u.makeEdge(posRel, idx, tag);
      Log.info("adding on " + e);
      u.addEdgeToState(e, Adjoints.Constant.ZERO);
    }

    // See what edges where added to the agenda.
    Log.info("agenda after adding POS tags:");
    u.getAgenda().dbgShowScores();

    // See what predictions can be made
    u.dbgRunInference();
  }
}
