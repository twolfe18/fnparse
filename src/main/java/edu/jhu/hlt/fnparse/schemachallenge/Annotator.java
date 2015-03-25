package edu.jhu.hlt.fnparse.schemachallenge;

import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.serialization.TarGzCompactCommunicationSerializer;
import edu.jhu.hlt.concrete.util.ConcreteUUIDFactory;
import edu.jhu.hlt.fnparse.agiga.ConcreteSentenceAdapter;
import edu.jhu.hlt.fnparse.agiga.FNAnnotator;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.role.span.LatentConstituencyPipelinedParser;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer;

/**
 * Takes some basic Concrete ingested by Tongfei for the schema-challenge and
 * add fnparse annotations.
 *
 * @author travis
 */
public class Annotator {
  public static final Logger LOG = Logger.getLogger(Annotator.class);

  private FrameIdStage frameId;
  private Reranker argId;

  public Annotator(File frameId, File argId) {
    if (!frameId.isDirectory())
      throw new IllegalArgumentException("frameId must be a directory");
    if (!argId.isFile())
      throw new IllegalArgumentException("argId must be a jser file");
    LOG.info("[Annoator init] loading frame id model...");
    LatentConstituencyPipelinedParser parser =
        new LatentConstituencyPipelinedParser();
    parser.quiet();
    parser.setFrameIdStage(new FrameIdStage(parser.getGlobalParameters(), ""));
    parser.loadModel(frameId, true, false, false);
    this.frameId = (FrameIdStage) parser.getFrameIdStage();

    LOG.info("[Annoator init] loading arg id model...");
    // Load arg id model
    try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(argId))) {
      this.argId = (Reranker) ois.readObject();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    LOG.info("[Annoator init] done");
  }

  public void annotate(Communication doc) {
    // Make a list of sentences (my sentences as well as Concrete's)
    List<Sentence> sentences = new ArrayList<>();
    List<ConcreteSentenceAdapter> cSentences = new ArrayList<>();
    for (Section section : doc.getSectionList()) {
      for (edu.jhu.hlt.concrete.Sentence sentence : section.getSentenceList()) {
        if (!sentence.isSetTokenization()
            || sentence.getTokenization().getTokenList() == null
            || sentence.getTokenization().getTokenTaggingList() == null) {
          continue;
        }
        ConcreteSentenceAdapter csa = new ConcreteSentenceAdapter(sentence);
        sentences.add(csa.getSentence());
        cSentences.add(csa);
      }
    }

    // Run frame id
    List<FNTagging> frames = frameId.setupInference(sentences, null).decodeAll();

    // Run arg id
    List<edu.jhu.hlt.fnparse.rl.State> initialStates = new ArrayList<>();
    for (FNTagging p : frames)
      initialStates.add(RerankerTrainer.getInitialStateWithPruning(p, null));
    List<FNParse> parses = argId.predict(initialStates);

    // Add parses to the Communication
    FNAnnotator.addSituations(
        doc, cSentences, parses, 1, true, new ConcreteUUIDFactory());
    //System.out.println(doc);
  }

  public static void main(String[] args) throws Exception {
    //File frameId = new File("/home/hltcoe/twolfe/fnparse/saved-models/agiga/");
    File frameId = new File("/home/travis/code/fnparse/saved-models/agiga/");
    File argId = new File("/tmp/reranker-train/model.jser");
    File concreteInput = new File("/home/travis/code/schema-challenge/data/dpr/dpr-train.tar.gz");
    File concreteOutput = new File("/home/travis/code/schema-challenge/data/dpr/dpr-train.fnparse.tar.gz");

    Annotator anno = new Annotator(frameId, argId);

    TarGzCompactCommunicationSerializer ts = new TarGzCompactCommunicationSerializer();
//    ThreadSafeCompactCommunicationSerializer deser =
//        new ThreadSafeCompactCommunicationSerializer();
//
//    Communication doc = deser.fromPath(concreteInput.toPath());
//
//    anno.annotate(doc);
    Iterator<Communication> itr = ts.fromTarGz(Files.newInputStream(
        concreteInput.toPath(), StandardOpenOption.READ));
    int i = 0;
    List<Communication> annotated = new ArrayList<>();
    while (itr.hasNext() && i++ < 10) {
      Communication c = itr.next();
      anno.annotate(c);
      annotated.add(c);
    }
    ts.toTarGz(annotated, concreteOutput.toPath());

//    doc.write(new TCompactProtocol(
//        new TIOStreamTransport(
//            new FileOutputStream(concreteOutput))));
  }
}
