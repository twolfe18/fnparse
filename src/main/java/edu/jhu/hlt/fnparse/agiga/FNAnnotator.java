package edu.jhu.hlt.fnparse.agiga;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TSimpleJSONProtocol;
import org.apache.thrift.transport.TIOStreamTransport;

import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.MentionArgument;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.serialization.ThreadSafeCompactCommunicationSerializer;
import edu.jhu.hlt.concrete.util.ConcreteUUIDFactory;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.datatypes.WeightedFrameInstance;
import edu.jhu.hlt.fnparse.datatypes.WeightedFrameInstance.ArgTheory;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.pruning.ArgPruner;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.inference.role.span.LatentConstituencyPipelinedParser;
import edu.jhu.hlt.fnparse.inference.role.span.RoleSpanLabelingStage;

/**
 * TODO add 1-best annotations.
 * TODO define and add "high recall" annotations.
 * 
 * @author travis
 */
public class FNAnnotator implements DummyAnnotator {
  public static final Logger LOG = Logger.getLogger(FNAnnotator.class);

  public static File frameIdModel =
      new File("/home/hltcoe/twolfe/fnparse/saved-models/agiga/frameId.ser.gz");
  public static File roleLabelingModel =
      new File("/home/hltcoe/twolfe/fnparse/saved-models/agiga/roleLabel.ser.gz");
  public static int kBest = 5;
  public static boolean includeSituationMentionText = true;

  private LatentConstituencyPipelinedParser parser;
  private ConcreteUUIDFactory uuidFactory;

  @Override
  public void init() {
    uuidFactory = new ConcreteUUIDFactory();

    LOG.info("loading models...");
    parser = new LatentConstituencyPipelinedParser();
    parser.loadFrameIdStage(frameIdModel);
    parser.loadRoleSpanLabelingStage(roleLabelingModel);

    if (kBest > 1) {
      LOG.info("setting high recall mode on");
      FrameIdStage fid = (FrameIdStage) parser.getFrameIdStage();
      double recallBias = 8d / (10d + kBest);
      fid.configure("recallBias.FrameIdStage", String.valueOf(recallBias));
      RoleSpanLabelingStage rsl = parser.getRoleLabelingStage();
      rsl.maxSpansPerArg = kBest;
    }

    // Attempt to load static resources ahead of time
    LOG.info("loading other static resources...");
    ArgPruner.getInstance();
    TargetPruningData.getInstance().getWordnetDict();

    LOG.info("done init");
  }

  @Override
  public Communication annotate(Communication c) throws Exception {
    LOG.info("annotating " + c.getId());

    // Make a copy of the Communication (so I don't modify the given one)
    Communication addTo = new Communication(c);

    // Make a list of sentences
    List<Sentence> sentences = new ArrayList<>();
    List<ConcreteSentenceAdapter> cSentences = new ArrayList<>();
    for (Section section : c.getSectionList()) {
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

    // Call the parser
    LOG.info("conversion complete, calling parser");
    List<FNParse> parses = parser.parse(sentences, null);

    // Convert each FNParses into a SituationSet and SituationMentionSet
    addSituations(addTo, cSentences, parses);

    LOG.info("done annotating " + c.getId());
    return addTo;
  }

  public static String join(String[] toks, String sep) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < toks.length; i++) {
      if (i > 0)
        sb.append(sep);
      sb.append(toks[i]);
    }
    return sb.toString();
  }

  public void addSituations(
      Communication c,
      List<ConcreteSentenceAdapter> sentences,
      List<FNParse> parses) {
    if (sentences.size() != parses.size())
      throw new IllegalArgumentException();
    SituationMentionSet sms = new SituationMentionSet();
    sms.setUuid(uuidFactory.getConcreteUUID());
    AnnotationMetadata meta = new AnnotationMetadata();
    meta.setTool("JHU-fnparse");
    meta.setTimestamp(System.currentTimeMillis() / 1000);
    meta.setKBest(kBest);
    sms.setMetadata(meta);
    List<SituationMention> smsMentionList = new ArrayList<>();
    for (int i = 0; i < sentences.size(); i++) {
      ConcreteSentenceAdapter sentence = sentences.get(i);
      FNParse parse = parses.get(i);
      for (FrameInstance fi : parse.getFrameInstances()) {
        Frame f = fi.getFrame();
        WeightedFrameInstance wfi = (WeightedFrameInstance) fi;
        SituationMention sm = new SituationMention();
        sm.setUuid(uuidFactory.getConcreteUUID());
        sm.setSituationKind(f.getName());
        sm.setTokens(sentence.getTokenRefSequence(fi.getTarget()));
        if (includeSituationMentionText)
          sm.setText(join(sentence.getSentence().getWordFor(fi.getTarget()), " "));
        sm.setConfidence(wfi.getTargetWeight());
        List<MentionArgument> args = new ArrayList<>();
        int K = f.numRoles();
        for (int k = 0; k < K; k++) {
          for (ArgTheory at : wfi.getArgumentTheories(k)) {
            if (at.span == Span.nullSpan)
              continue;
            assert !Double.isNaN(at.weight);
            MentionArgument arg = new MentionArgument();
            arg.setRole(f.getRole(k));
            arg.setTokens(sentence.getTokenRefSequence(at.span));
            arg.setConfidence(at.weight);
            args.add(arg);
          }
        }
        sm.setArgumentList(args);
        smsMentionList.add(sm);
      }
    }
    sms.setMentionList(smsMentionList);
    c.addToSituationMentionSetList(sms);
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 2) {
      frameIdModel = new File(args[0]);
      roleLabelingModel = new File(args[1]);
      assert frameIdModel.isFile();
      assert roleLabelingModel.isFile();
    }
    FNAnnotator anno = new FNAnnotator();
    anno.init();
    File f = new File("agiga2/input-data/concrete-3.8.0-post-stanford");
    assert f.isDirectory();
    ThreadSafeCompactCommunicationSerializer deser =
        new ThreadSafeCompactCommunicationSerializer();
    for (File commFile : f.listFiles()) {
      if (commFile.getName().endsWith(".json"))
        continue;
      if (commFile.getName().contains(".anno"))
        continue;
      LOG.info("reading " + commFile.toPath());
      Communication comm = deser.fromPath(commFile.toPath());

      //LOG.info(comm);
      comm.write(new TSimpleJSONProtocol(
          new TIOStreamTransport(
              new FileOutputStream(
                  new File(commFile.getCanonicalPath() + ".json")))));

      Communication commAnno = anno.annotate(comm);
      commAnno.write(new TSimpleJSONProtocol(
          new TIOStreamTransport(
              new FileOutputStream(
                  new File(commFile.getCanonicalPath() + ".anno.json")))));
      commAnno.write(new TCompactProtocol(
          new TIOStreamTransport(
              new FileOutputStream(
                  new File(commFile.getCanonicalPath() + ".anno.compact")))));
    }
  }

}


