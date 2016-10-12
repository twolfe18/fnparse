package edu.jhu.hlt.uberts.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import edu.jhu.hlt.acute.archivers.tar.TarArchiver;
import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.MentionArgument;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.concrete.serialization.archiver.ArchivableCommunication;
import edu.jhu.hlt.concrete.serialization.iterators.TarGzArchiveEntryCommunicationIterator;
import edu.jhu.hlt.concrete.uuid.AnalyticUUIDGeneratorFactory;
import edu.jhu.hlt.concrete.uuid.AnalyticUUIDGeneratorFactory.AnalyticUUIDGenerator;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.LabeledSpan;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.auto.UbertsLearnPipeline;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;
import edu.jhu.hlt.uberts.srl.ConcreteToRelations;

/**
 * Takes a bunch of {@link HypEdge} for startdoc|predicate2|argument4 facts
 * and a stream of {@link Communication}s and merges them (adding them as
 * new annotations to the {@link Communication}s).
 * 
 * ARG0 of startdoc should be "<a>/<b>" where <a> is a {@link Communication} UUID
 * and <b> is a {@link Sentence} UUID.
 * 
 * META: This is used for merging annotations made by {@link UbertsLearnPipeline}
 * back into some {@link Communication}s.
 * 
 * @see ConcreteToRelations for how {@link Communication}s are turned into input
 * facts for {@link UbertsLearnPipeline}.
 *
 * @author travis
 */
public class MergeFactsIntoSituationMentionSets {
  
  public static boolean debug = false;

  public static class FactsForOneSentence {
    private List<RelLine> facts;
    private Sentence sentence;
    
    public FactsForOneSentence(String sentUuid, Communication comm) {
      this.facts = new ArrayList<>();
      for (Section section : comm.getSectionList()) {
        for (Sentence sent : section.getSentenceList()) {
          if (sentUuid.equals(sent.getUuid().getUuidString())) {
            if (this.sentence != null) {
              throw new RuntimeException("there are two Sentences with uuid "
                  + sentUuid + " in Communication " + comm.getUuid());
            }
            this.sentence = sent;
          }
        }
      }
      if (this.sentence == null) {
        throw new RuntimeException("couldnt find sentence "
            + sentUuid + " in Communication " + comm.getUuid());
      }
    }
    
    public void addFacts(RelDoc facts) {
      this.facts.addAll(facts.items);
    }

    /**
     * Creates {@link SituationMention}s with kind equal to the predicate2's ARG1 label.
     * You provide the situationType, which naively could be "EVENT", but you may want
     * to pass in something more specific like "propbank" or "framenet".
     */
    public void addSituationMentions(SituationMentionSet sms, String situationType, Supplier<UUID> uuidGen) {
      if (debug)
        Log.info("adding " + facts.size() + " facts");
      // Group facts by SituationMention/target
      Map<LabeledSpan, RelLine> tf2pred2 = new HashMap<>();
      Map<LabeledSpan, List<RelLine>> tf2arg4 = new HashMap<>();
      for (RelLine l : facts) {
        String r = l.tokens[1];
        if (!r.equals("predicate2") && !r.equals("argument4")) {
          if (debug)
            Log.info("skipping: " + l);
          continue;
        }
        
        if (debug)
          Log.info("adding fact: " + l);
        Span t = Span.inverseShortString(l.tokens[2]);
        String f = l.tokens[3];
        LabeledSpan tf = new LabeledSpan(f, t);
        if ("predicate2".equals(r)) {
          Object old = tf2pred2.put(tf, l);
          assert old == null;
        } else if ("argument4".equals(r)) {
          List<RelLine> ll = tf2arg4.get(tf);
          if (ll == null) {
            ll = new ArrayList<>();
            tf2arg4.put(tf, ll);
          }
          ll.add(l);
        } else {
          throw new RuntimeException("wat: " + l);
        }
      }

      // Create the SituationMentions
      assert tf2arg4.size() <= tf2pred2.size() : "tf2args.size=" + tf2arg4.size() + " tf2pred.size=" + tf2pred2.size();
      if (debug)
        Log.info("tf2args.size=" + tf2arg4.size());
      for (LabeledSpan tf : tf2pred2.keySet()) {
        SituationMention sm = new SituationMention();
        sm.setUuid(uuidGen.get());
        sm.setSituationType(situationType);
        sm.setSituationKind(tf.label);
        sm.setTokens(convert(tf.getSpan()));
        sm.setArgumentList(new ArrayList<>());
        sm.setConfidence(extractScore(tf2pred2.get(tf)));

        List<RelLine> a4 = tf2arg4.getOrDefault(tf, Collections.emptyList());
        for (RelLine a : a4) {
          Span arg = Span.inverseShortString(a.tokens[4]);
          if (arg == Span.nullSpan)
            continue;
          MentionArgument ma = new MentionArgument();
          ma.setTokens(convert(arg));
          ma.setRole(a.tokens[5]);
          ma.setConfidence(extractScore(a));
          sm.addToArgumentList(ma);
        }
        
        sms.addToMentionList(sm);
      }
    }
    
    public static double extractScore(RelLine l) {
      if (l.comment == null)
        return Double.NaN;
      String prefix = "score=";
      int s = l.comment.indexOf(prefix);
      if (s < 0)
        return Double.NaN;
      int e = s + prefix.length();
      while (e < l.comment.length() && !Character.isWhitespace(l.comment.charAt(e)))
        e++;
      String p = l.comment.substring(s + prefix.length(), e);
      return Double.parseDouble(p);
    }
    
    public TokenRefSequence convert(Span s) {
      UUID uuid = sentence.getTokenization().getUuid();
      TokenRefSequence trs = new TokenRefSequence();
      trs.setTokenizationId(uuid);
      assert s.start < s.end;
      for (int i = s.start; i < s.end; i++)
        trs.addToTokenIndexList(i);
      return trs;
    }
  }


  public static class FactsForOneDocument {
    private Communication comm;
    private Map<String, FactsForOneSentence> facts;
    private AnalyticUUIDGenerator uuidGen;

    public FactsForOneDocument(Communication c) {
      this.comm = c;
      this.facts = new HashMap<>();
      AnalyticUUIDGeneratorFactory f = new AnalyticUUIDGeneratorFactory(c);
      uuidGen = f.create();
    }

    public Communication getCommunication() {
      return comm;
    }

    public void addSituationMentionSetToCommunication(AnnotationMetadata meta, String situationType) {
      // Create a new SituationMentionSet
      SituationMentionSet sms = new SituationMentionSet();
      sms.setMentionList(new ArrayList<>());
      sms.setMetadata(meta);
      sms.setUuid(uuidGen.next());

      // For each sentence, create a bunch of SMs to link into the SMS
      for (FactsForOneSentence ffos : facts.values())
        ffos.addSituationMentions(sms, situationType, uuidGen::next);

      comm.addToSituationMentionSetList(sms);
    }
    
    /**
     * @param factsForOneSentence
     * @return true if the given facts belong in this communication
     * (and thus they were added) and false if these facts don't belong
     * to this instance's {@link Communication}.
     */
    public boolean addSentenceFacts(RelDoc factsForOneSentence) {
      // Check that comm ids match
      String commUuid = parseCommUuidFrom(factsForOneSentence);
      if (!commUuid.equals(comm.getUuid().getUuidString())) {
        return false;
      }
      
      // Add these annotations as a new sentence
      if (debug)
        Log.info("adding new sentence: " + factsForOneSentence.getId());
      String sentUuid = parseSentUuidFrom(factsForOneSentence);
      FactsForOneSentence ffos = new FactsForOneSentence(sentUuid, comm);
      ffos.addFacts(factsForOneSentence);
      Object old = facts.put(sentUuid, ffos);
      if (old != null)
        throw new RuntimeException("duplicate annotations for sentence " + sentUuid);
      
      return true;
    }
  }
  
  public static String parseCommUuidFrom(String startdocArg0) {
    String[] cs = startdocArg0.split("/");
    assert cs.length == 2;
    return cs[0];
  }
  public static String parseCommUuidFrom(RelDoc sentenceFacts) {
    String startDocArg0 = sentenceFacts.getId();
    return parseCommUuidFrom(startDocArg0);
  }

  public static String parseSentUuidFrom(String startdocArg0) {
    String[] cs = startdocArg0.split("/");
    assert cs.length == 2;
    return cs[1];
  }
  public static String parseSentUuidFrom(RelDoc sentenceFacts) {
    String startDocArg0 = sentenceFacts.getId();
    return parseSentUuidFrom(startDocArg0);
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 5) {
      System.err.println("please provide:");
      System.err.println("1) an input Communication .tgz file");
      System.err.println("2) an input fact file");
      System.err.println("3) an output Communication .tgz file");
      System.err.println("4) a toolname to use for AnnotationMetadata");
      System.err.println("5) a SituationType to use for all SituationMentions, e.g. \"propbank\", \"framenet\", or \"EVENT\"");
      return;
    }
    File commTgzIn = new File(args[0]);
    File facts = new File(args[1]);
    File commTgzOut = new File(args[2]);
    String tool = args[3];
    String situationType = args[4];
    Log.info("reading comms from: " + commTgzIn.getPath());
    Log.info("reading facts from: " + facts.getPath());
    Log.info("writing comms to: " + commTgzOut.getPath());
    
    AnnotationMetadata meta = new AnnotationMetadata();
    meta.setTimestamp(System.currentTimeMillis() / 1000);
    meta.setTool(tool);
    Log.info("with tool=" + tool + " and situationType=" + situationType + " metadata=" + meta);
    
    boolean dedup = true;
    try (OutputStream os = FileUtil.getOutputStream(commTgzOut);
        TarArchiver out = new TarArchiver(os);
        InputStream is = FileUtil.getInputStream(facts);
        RelationFileIterator rfi = new RelationFileIterator(is, l -> l.commentContains("pred=false"));
        ManyDocRelationFileIterator mdi = new ManyDocRelationFileIterator(rfi, dedup);
        FileInputStream commFisIn = new FileInputStream(commTgzIn);
        TarGzArchiveEntryCommunicationIterator commItrIn = new TarGzArchiveEntryCommunicationIterator(commFisIn)) {
      
      int n = 0, d = 0;
      TimeMarker tm = new TimeMarker();
      
      assert commItrIn.hasNext() : "no comms?";
      Communication comm = commItrIn.next();
      FactsForOneDocument doc = new FactsForOneDocument(comm);
      
      // Iterate over sentences
      while (mdi.hasNext()) {
        RelDoc sentence = mdi.next();
        n++;
        if (tm.enoughTimePassed(5)) {
          System.out.println("parsed " + n + " sentences and wrote "
              + d + " comms out in " + tm.secondsSinceFirstMark() + " seconds");
        }
        
        boolean added = doc.addSentenceFacts(sentence);
        if (!added) {
          // Write out completed Communication
          doc.addSituationMentionSetToCommunication(meta, situationType);
          Communication commAnno = doc.getCommunication();
          out.addEntry(new ArchivableCommunication(commAnno));
          d++;
          if (debug && d == 5)    // For debugging
            break;

          // Read next Communication and add facts to that
          assert commItrIn.hasNext() : "ran out of comms?";
          comm = commItrIn.next();
          doc = new FactsForOneDocument(comm);
          added = doc.addSentenceFacts(sentence);
          assert added : "no Communication for facts in sentence: " + sentence.getId();
        }
      }
      
      if (!debug)
        assert !commItrIn.hasNext() : "didn't consume all communications?";
    }
    Log.info("done");
  }
}
