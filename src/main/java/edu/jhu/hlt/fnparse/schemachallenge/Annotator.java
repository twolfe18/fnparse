package edu.jhu.hlt.fnparse.schemachallenge;

import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.serialization.TarGzCompactCommunicationSerializer;
import edu.jhu.hlt.fnparse.agiga.ConcreteSentenceAdapter;
import edu.jhu.hlt.fnparse.agiga.FNAnnotator;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.role.span.LatentConstituencyPipelinedParser;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer;
import edu.jhu.hlt.fnparse.util.Describe;

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
  public boolean debug = false;

  /**
   * @param frameId
   * @param argId may be null in which case the arg id model won't be loaded or used.
   */
  public Annotator(File frameId, File argId) {
    if (frameId == null || !frameId.isDirectory()) {
      throw new IllegalArgumentException(
          "frameId must be a directory: " + frameId.getPath());
    }
    if (argId != null && !argId.isFile()) {
      throw new IllegalArgumentException(
          "argId must be a jser file: " + argId.getPath());
    }

    LatentConstituencyPipelinedParser parser =
        new LatentConstituencyPipelinedParser();
    parser.quiet();

    LOG.info("[Annoator init] loading frame id model...");
    parser.setFrameIdStage(new FrameIdStage(parser.getGlobalParameters(), ""));
    parser.loadModel(frameId, true, false, false);
    this.frameId = (FrameIdStage) parser.getFrameIdStage();

    if (argId == null) {
      LOG.warn("[Annoator init] NOT loading arg id model (given null)");
    } else {
      LOG.info("[Annoator init] loading arg id model...");
      // Load arg id model
      try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(argId))) {
        this.argId = (Reranker) ois.readObject();
        this.argId.showWeights();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
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
    if (debug) {
      for (FNTagging ft : frames) {
        LOG.info("\n" + Describe.sentenceWithDeps(ft.getSentence()));
        LOG.info(Describe.fnTagging(ft) + "\n");
      }
    }

    // Run arg id
    List<FNParse> parses;
    if (argId == null) {
      LOG.warn("[annotate] not predicting arguments because no arg id model was given");
      parses = DataUtil.convertTaggingsToParses(frames);
    } else {
      List<edu.jhu.hlt.fnparse.rl.State> initialStates = new ArrayList<>();
      for (FNTagging p : frames)
        initialStates.add(RerankerTrainer.getInitialStateWithPruning(p, null));
      parses = argId.predict(initialStates);
    }

    // Add parses to the Communication
    FNAnnotator.addSituations(doc, cSentences, parses, 1, true);
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4) {
      System.err.println("please provide:");
      System.err.println("1) a frame id model directory");
      System.err.println("2) an arg id model file");
      System.err.println("3) an input concrete tar gz file");
      System.err.println("4) an output concrete tar gz file");
      System.exit(-1);
    }
    File frameId = new File(args[0]);
    File argId = new File(args[1]);
    File concreteInput = new File(args[2]);
    File concreteOutput = new File(args[3]);

    if (!argId.exists())
      argId = null;

    Annotator anno = new Annotator(frameId, argId);
    anno.debug = true;

    TarGzCompactCommunicationSerializer ts = new TarGzCompactCommunicationSerializer();
    Iterator<Communication> itr = ts.fromTarGz(Files.newInputStream(concreteInput.toPath()));
    List<Communication> annotated = new ArrayList<>();
    while (itr.hasNext()) {
      Communication c = itr.next();
      anno.annotate(c);
      annotated.add(c);
    }
    LOG.info("Saving " + annotated.size() + " Communications to " + concreteOutput.getPath());
    ts.toTarGz(annotated, concreteOutput.toPath());
  }
}
