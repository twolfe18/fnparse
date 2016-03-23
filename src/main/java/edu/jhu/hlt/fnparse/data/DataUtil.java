package edu.jhu.hlt.fnparse.data;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.MentionArgument;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.concrete.uuid.AnalyticUUIDGeneratorFactory;
import edu.jhu.hlt.concrete.uuid.AnalyticUUIDGeneratorFactory.AnalyticUUIDGenerator;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance.PropbankDataException;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ConcreteDocumentMapping;
import edu.jhu.hlt.tutils.Document.Constituent;
import edu.jhu.hlt.tutils.Document.ConstituentItr;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.prim.tuple.Pair;

public class DataUtil {
  public static boolean DEBUG = false;

//  private static Map<HashableUUID, Tokenization> indexTokenizations(Communication c) {
//    Map<HashableUUID, Tokenization> m = new HashMap<>();
//    for (Section s : c.getSectionList()) {
//      for (edu.jhu.hlt.concrete.Sentence sent : s.getSentenceList()) {
//        Tokenization t = sent.getTokenization();
//        Tokenization old = m.put(new HashableUUID(t.getUuid()), t);
//        assert old == null;
//      }
//    }
//    return m;
//  }

  /**
   * Adds {@link FNParse} to either tutils.Document (Constituents, like cons_propbank_auto)
   * or concrete.Communication (as {@link SituationMention}s).
   *
   * NOTE: This code has NOT BEEN TESTED.
   */
  public static void exportParses(
      List<FNParse> parses,
      ConcreteDocumentMapping cdm,
      boolean exportToTutilsDocument,
      boolean exportToConcreteCommunication) {

    if (exportToTutilsDocument)
      throw new RuntimeException("implement me");

    if (!exportToConcreteCommunication) {
      return;
    }

    AnnotationMetadata meta = new AnnotationMetadata();
    meta.setTimestamp(System.currentTimeMillis() / 1000);
    meta.setTool("fnparse");

    Communication c = cdm.getCommunication();
    edu.jhu.hlt.tutils.Document d = cdm.getDocument();
//    Map<HashableUUID, Tokenization> toks = indexTokenizations(c);

    AnalyticUUIDGeneratorFactory idDoc = new AnalyticUUIDGeneratorFactory(c);
    AnalyticUUIDGenerator idTool = idDoc.create();

    SituationMentionSet sms = new SituationMentionSet();
    sms.setMetadata(meta);
    sms.setUuid(idTool.next());

    c.addToSituationMentionSetList(sms);
    for (FNParse y : parses) {
      Sentence sent = y.getSentence();
      if (sent.tutilsSentenceConsIdx < 0) {
        throw new RuntimeException("this Sentence doesn't have a"
            + " mapping back to tutils elements (on the way to concrete elements)");
      }

      // Lookup the relevant Tokenization
      UUID tokUUID = cdm.get(d.getConstituent(sent.tutilsSentenceConsIdx));
      assert tokUUID != null;
//      Tokenization tokenization = toks.get(tokUUID);

      for (FrameInstance fi : y.getFrameInstances()) {
        SituationMention sm = new SituationMention();
        sm.setUuid(idTool.next());
        sms.addToMentionList(sm);

        TokenRefSequence target = new TokenRefSequence();
        target.setTokenizationId(tokUUID);
        Span t = fi.getTarget();
        for (int i = t.start; i < t.end; i++)
          target.addToTokenIndexList(i);
        sm.setTokens(target);

        Frame f = fi.getFrame();
        sm.setSituationKind(f.getName());

        int K = f.numRoles();
        for (int k = 0; k < K; k++) {
          Span a = fi.getArgument(k);
          if (a == Span.nullSpan)
            continue;
          assert fi.getContinuationRoleSpans(k).isEmpty();
          assert fi.getReferenceRoleSpans(k).isEmpty();

          TokenRefSequence arg = new TokenRefSequence();
          arg.setTokenizationId(tokUUID);
          for (int i = a.start; i < a.end; i++)
            arg.addToTokenIndexList(i);

          MentionArgument ma = new MentionArgument();
          ma.setSituationMentionId(sm.getUuid());
          ma.setRole(f.getRole(k));
          ma.setTokens(arg);
          sm.addToArgumentList(ma);
        }
      }
    }
  }

  /**
   * Convenience wrapper around
   * {@link Sentence#convertFromTutils(String, String, edu.jhu.hlt.tutils.Document, int, int, boolean)}
   */
  public static List<Sentence> getSentences(edu.jhu.hlt.tutils.Document doc,
      boolean addGoldParse,
      boolean addStanfordCParse,
      boolean addStanfordBasicDParse,
      boolean addStanfordCollapsedDParse,
      boolean takeGoldPos) {
    Log.info("doc=" + doc.getId() + " cons_sentences=" + doc.cons_sentences + " cons_propbank_gold=" + doc.cons_propbank_gold + " cons_propbank_auto=" + doc.cons_propbank_auto);
    List<Sentence> sents = new ArrayList<>();

    // TODO add gold parse?

    int sentIndex = 0;
    ConstituentItr sent = doc.getConstituentItr(doc.cons_sentences);
    for (; sent.isValid(); sent.gotoRightSib()) {
      if (DEBUG)
        System.out.println("reading new sentence @ " + sent.getFirstToken());
      int paragraph = sent.getLhs();
      assert paragraph <= sentIndex;

      // Build the sentence that the parses lies in
      String dataset = null;
      String id = doc.getId() + "/" + (sentIndex++);
      Sentence s = Sentence.convertFromTutils(dataset, id, doc,
          sent.getFirstToken(), sent.getLastToken(),
          addGoldParse, addStanfordCParse,
          addStanfordBasicDParse, addStanfordCollapsedDParse,
          takeGoldPos);
      if (DEBUG) {
        System.out.println("working on sentence " + id);
        System.out.println(Describe.sentence(s));
      }
      sents.add(s);
    }
    Log.info("read " + sents.size() + " sentences");
    return sents;
  }

  /**
   * Converts a tutils.Document with a Propbank parse (as a constituency parse)
   * into a collection of FNParses. Alphabet must be set for doc.
   */
  public static List<FNParse> convert(
      edu.jhu.hlt.tutils.Document doc,
      boolean addGoldParse,
      boolean addStanfordParse,
      boolean addStanfordBasicDParse,
      boolean addStanfordCollapsedDParse,
      boolean takeGoldPos) {
    if (doc.getAlphabet() == null)
      throw new IllegalArgumentException();
    List<FNParse> l = new ArrayList<>();
    MultiAlphabet alph = doc.getAlphabet();
    FrameIndex propbank = FrameIndex.getPropbank();

    if (DEBUG)
      System.out.println("\n[DataUtil.convert] doc: " + doc.getId() + " cons_sentences=" + doc.cons_sentences + " cons_propbank_gold=" + doc.cons_propbank_gold + " cons_propbank_auto=" + doc.cons_propbank_auto);

    // TODO add gold parse?

    int sentIndex = 0;
    ConstituentItr sent = doc.getConstituentItr(doc.cons_sentences);
    ConstituentItr prop = doc.getConstituentItr(doc.cons_propbank_gold);
    for (; sent.isValid(); sent.gotoRightSib()) {
      if (DEBUG)
        System.out.println("reading new sentence @ " + sent.getFirstToken());
      int paragraph = sent.getLhs();
      assert paragraph <= sentIndex;

      // Build the sentence that the parses lies in
      String dataset = null;
      String id = doc.getId() + "/" + (sentIndex++);
//      boolean addGoldParse = true;
//      boolean addStanfordParse = true;
//      boolean addStanfordBasicDParse = true;
//      boolean addStanfordCollapsedDParse = true;
      Sentence s = Sentence.convertFromTutils(dataset, id, doc,
          sent.getFirstToken(), sent.getLastToken(),
          addGoldParse, addStanfordParse,
          addStanfordBasicDParse, addStanfordCollapsedDParse,
          takeGoldPos);
      if (DEBUG) {
        System.out.println("working on sentence " + id);
        System.out.println(Describe.sentence(s));
      }

      // Add every proposition which is in this sentence
      List<FrameInstance> fis = new ArrayList<>();
      for (; prop.isValid() && prop.getLastToken() <= sent.getLastToken(); prop.gotoRightSib()) {
        if (DEBUG)
          System.out.println("reading new proposition " + prop.getIndex());

        // Empty Situation/proposition
        if (prop.getFirstToken() == edu.jhu.hlt.tutils.Document.NONE)
          continue;

        String lhs = alph.srl(prop.getLhs());
        if (DEBUG) {
          System.out.println("  adding prop [" + prop.getFirstToken() + ", " + prop.getLastToken() + "] " + lhs);
          System.out.println(" sent.first=" + sent.getFirstToken() + " sent.last=" + sent.getLastToken());
        }
        assert prop.getFirstToken() >= sent.getFirstToken() : "prop crosses sentence";
        assert "propbank".equals(lhs);

        // Lookup the frame
        Constituent pred = prop.getLeftChildC();
        String frameName = "propbank/" + alph.srl(pred.getLhs());
        if (DEBUG)
          System.out.println("frameName=" + frameName);
        Frame f = propbank.getFrame(frameName);
        if (f == null) {
          //throw new RuntimeException("no frame for: " + alph.srl(pred.getLhs()));
          Log.warn("found error, missing frame in " + s.getId()
              + " paragraph " + paragraph + ": " + alph.srl(pred.getLhs()));
          continue;
        }
        int start = pred.getFirstToken() - sent.getFirstToken();
        int end = (pred.getLastToken() - sent.getFirstToken()) + 1;
        Span target = Span.getSpan(start, end);

        if (DEBUG) {
          System.out.println("rightSib=" + pred.getRightSib());
          System.out.println("prop.rightSib=" + prop.getRightSib());
          System.out.println("target=" + target);
          System.out.println("sentIndex=" + sentIndex);
          System.out.println("paragraph=" + paragraph);
        }

        // Add the arguments
        List<Pair<String, Span>> args = new ArrayList<>();
        for (ConstituentItr argItr = doc.getConstituentItr(pred.getRightSib());
            argItr.isValid();
            argItr.gotoRightSib()) {
          String roleName = alph.srl(argItr.getLhs());
          if (DEBUG)
            System.out.println("  argItr.index=" + argItr.getIndex() + " lhs=" + roleName);
          start = argItr.getFirstToken() - sent.getFirstToken();
          end = (argItr.getLastToken() - sent.getFirstToken()) + 1;
          args.add(new Pair<>(roleName, Span.getSpan(start, end)));
        }

        try {
          fis.add(FrameInstance.buildPropbankFrameInstance(f, target, args, s));
        } catch (PropbankDataException e) {
          Log.warn("found error in " + s.getId()
              + " paragraph " + paragraph + ": " + e.getMessage());
        }
      }
      if (DEBUG)
        System.out.println("fis.size=" + fis.size());
      l.add(new FNParse(s, fis));
    }

    return l;
  }

  public static List<Sentence> stripAnnotations(
      List<? extends FNTagging> tagged) {
    List<Sentence> raw = new ArrayList<Sentence>();
    for(FNTagging t : tagged)
      raw.add(t.getSentence());
    return raw;
  }

  public static Set<Span> getAllArgSpans(FrameInstance fi) {
    Set<Span> s = new HashSet<>();
    for (int k = 0; k < fi.getFrame().numRoles(); k++)
      s.add(fi.getArgument(k));
    s.remove(Span.nullSpan);
    return s;
  }

  public static Map<Span, FrameInstance> getFrameInstanceByTarget(FNTagging t) {
    Map<Span, FrameInstance> m = new HashMap<>();
    for (FrameInstance fi : t.getFrameInstances()) {
      FrameInstance old = m.put(fi.getTarget(), fi);
      if (old != null) {
        throw new RuntimeException(String.format(
            "%s has two FrameInstances at %s:\n%s",
            t.getId(),
            fi.getTarget(),
            Describe.fnTagging(t)));
      }
    }
    return m;
  }

  /**
   * The return type is deceiving...
   * The keys in the returned map are just (frame,target) and the values have
   * the roles and their locations in them.
   */
  public static Map<FrameInstance, FrameInstance> getFrameInstancesByFrameTarget(FNParse p) {
    Map<FrameInstance, FrameInstance> index = new HashMap<>();
    for (FrameInstance fi : p.getFrameInstances()) {
      FrameInstance t = FrameInstance.frameMention(fi.getFrame(), fi.getTarget(), fi.getSentence());
      FrameInstance oldValue = index.put(t, fi);
      if (oldValue != null)
        throw new RuntimeException();
    }
    return index;
  }

  /**
   * @deprecated You should not be using this, index on target (span)
   */
  public static FrameInstance[] getFrameInstancesIndexByHeadword(
      List<FrameInstance> fis,
      Sentence s,
      HeadFinder hf) {
    int n = s.size();
    FrameInstance[] fiByTarget = new FrameInstance[n];
    for(FrameInstance fi : fis) {
      int targetHead = hf.head(fi.getTarget(), s);
      //assert fiByTarget[targetHead] == null;
      if(fiByTarget[targetHead] != null) {
        Log.warn("[getFrameInstancesIndexByHeadword] frame instance in "
            + fi.getSentence().getId()
            + " has more than one frame-trigger per headword @ "
            + targetHead);
        // keep the FI with more non-null arguments
        if(fi.numRealizedArguments() <
            fiByTarget[targetHead].numRealizedArguments()) {
          continue;
        }
      }
      fiByTarget[targetHead] = fi;
    }
    return fiByTarget;
  }

  /**
   * FNTaggings don't have arguments, this converts them to FNParses with all
   * the arguments set to nullSpan.
   */
  public static List<FNParse> convertTaggingsToParses(List<FNTagging> tags) {
    List<FNParse> parses = new ArrayList<>();
    for(FNTagging t : tags)
      parses.add(convertTaggingToParse(t));
    return parses;
  }

  /**
   * Makes a parse with no arguments
   */
  public static FNParse convertTaggingToParse(FNTagging t) {
    List<FrameInstance> fis = new ArrayList<>();
    for(FrameInstance fi : t.getFrameInstances()) {
      int K = fi.getFrame().numRoles();
      Span[] args = new Span[K];
      Arrays.fill(args, Span.nullSpan);
      fis.add(FrameInstance.newFrameInstance(
          fi.getFrame(), fi.getTarget(), args, fi.getSentence()));
    }
    return new FNParse(t.getSentence(), fis);
  }

  /**
   * Drops all arguments from FrameInstances
   */
  public static List<FNTagging> convertParsesToTaggings(List<FNParse> parses) {
    List<FNTagging> out = new ArrayList<>();
    for(FNParse p : parses)
      out.add(convertParseToTagging(p));
    return out;
  }

  public static FNTagging convertParseToTagging(FNParse p) {
    Sentence s = p.getSentence();
    List<FrameInstance> targets = new ArrayList<>();
    for(FrameInstance fi : p.getFrameInstances())
      targets.add(FrameInstance.frameMention(fi.getFrame(), fi.getTarget(), s));
    return new FNTagging(s, targets);
  }

  /**
   * takes parses with regular spans as arguments and converts them to arguments
   * with width-1 (head) spans.
   */
  public static List<FNParse> convertArgumenSpansToHeads(
      List<FNParse> fullParses,
      HeadFinder hf) {
    List<FNParse> out = new ArrayList<>();
    for(FNParse p : fullParses) {
      Sentence sent = p.getSentence();
      List<FrameInstance> oldFis = p.getFrameInstances();
      List<FrameInstance> newFis = new ArrayList<>(oldFis.size());
      for(FrameInstance fi : oldFis) {
        FrameInstance fic = fi.clone();
        int K = fic.numArguments();
        assert K == fic.getFrame().numRoles();
        for(int k=0; k<K; k++) {
          Span a = fic.getArgument(k);
          if(a.width() > 1) {
            int h = hf.head(a, sent);
            fic.setArgument(k, Span.widthOne(h));
          }
        }
        newFis.add(fic);
      }
      out.add(new FNParse(p.getSentence(), newFis));
    }
    return out;
  }

  /**
   * In the FN data, there are some parses which have two different
   * FrameInstances with the same target. Every instance of this I've seen has
   * just been a mistake (the same Frame, just double tagged). My code is really
   * pedantic and throws an exception if I produce a parse that has two
   * FrameInstances with the same target, and this will happen if I use gold
   * frameId through no fault of my own code. This method selects a FNTagging
   * that doesn't violate this constraint.
   */
  public static FNTagging filterOutTargetCollisions(FNTagging input) {
    Map<Span, FrameInstance> keep = new HashMap<Span, FrameInstance>();
    boolean someViolation = false;
    for(FrameInstance fi : input.getFrameInstances()) {
      FrameInstance collision = keep.put(fi.getTarget(), fi);
      if(collision != null) {
        someViolation = true;
        // choose the FI with more realized arguments
        if(collision.numRealizedArguments() > fi.numRealizedArguments())
          keep.put(fi.getTarget(), fi);
      }
    }
    if(!someViolation)
      return input;
    else {
      List<FrameInstance> fis = new ArrayList<FrameInstance>(keep.values());
      return new FNTagging(input.getSentence(), fis);
    }
  }

  public static Map<Sentence, List<FrameInstance>> groupBySentence(
      List<FrameInstance> fis) {
    Map<Sentence, List<FrameInstance>> m =
        new HashMap<Sentence, List<FrameInstance>>();
    for(FrameInstance fi : fis) {
      List<FrameInstance> fiList = m.get(fi.getSentence());
      if(fiList == null) fiList = new ArrayList<FrameInstance>();
      fiList.add(fi);
      m.put(fi.getSentence(), fiList);
    }
    return m;
  }

  /**
   * Draw a sample with replacement.
   */
  public static <T> List<T> resample(List<T> population, int samples, Random rand) {
    List<T> l = new ArrayList<>();
    for (int i = 0; i < samples; i++) {
      int j = rand.nextInt(population.size());
      l.add(population.get(j));
    }
    return l;
  }

  public static String[] parseFrameIndexXML(File f, int numFrames) {
    try {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      DocumentBuilder db = dbf.newDocumentBuilder();
      Document doc = db.parse(f);
      String name[] = new String[numFrames];

      // Parse frameIndex.xml(in fileName) to populate the id and name arrays
      NodeList list = doc.getElementsByTagName("frame");
      assert numFrames == list.getLength();
      for(int i=0; i < numFrames; i++){
        Element element = (Element)list.item(i);
        name[i] = element.getAttribute("name");
      }
      return name;
    }
    catch(Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static <T> List<T> iter2list(Iterator<T> iter) {
    List<T> list = new ArrayList<T>();
    while (iter.hasNext())
      list.add(iter.next());
    return list;
  }

  public static <T> Stream<T> iter2stream(Iterator<T> iter) {
    return StreamSupport.stream(new Iterable<T>() {
      @Override
      public Iterator<T> iterator() {
        return iter;
      }
    }.spliterator(), false);
  }

  public static <T extends FNTagging> List<T> filterBySentenceLength(
      List<T> all, int maxLength) {
    List<T> list = new ArrayList<>();
    for(T t : all)
      if(t.getSentence().size() <= maxLength)
        list.add(t);
    return list;
  }
}
