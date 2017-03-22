package edu.jhu.hlt.entsum;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.entsum.CluewebLinkedSentence.SegmentedTextAroundLink;
import edu.jhu.hlt.entsum.DbpediaDistSup.FeatExData;
import edu.jhu.hlt.entsum.DepNode.ShortestPath;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.Span;

/**
 * A infobox fact mapped onto mentions in a sentence.
 *
 * @author travis
 */
public class DistSupFact implements Serializable {
  private static final long serialVersionUID = 9071814326941981522L;

  /** int values are mention indices */
  public static class Arg2Mention implements Serializable {
    private static final long serialVersionUID = -3949971279075889401L;
    int[] subj;     // e.g. [1,2]
    int[] verb;     // e.g. []
    int[] obj;      // e.g. [0]
    
    /**
     * @param repr e.g. "s=1,s=2,o=3"
     */
    public Arg2Mention(String repr) {
      this(Arrays.asList(repr.split(",")));
    }
    
    /**
     * @param alignments e.g. ["s=1","s=2","o=3"]
     */
    public Arg2Mention(List<String> alignments) {
      this.subj = fm(alignments, "s");
      this.verb = fm(alignments, "v");
      this.obj = fm(alignments, "o");
    }
    
    public static int[] fm(List<String> all, String key) {
      List<String> is = new ArrayList<>();
      for (String kv : all) {
        String[] ar = kv.split("=");
        assert ar.length == 2;
        if (key.equals(ar[0]))
          is.add(ar[1]);
      }
      int[] a = new int[is.size()];
      for (int i = 0; i < a.length; i++)
        a[i] = Integer.parseInt(is.get(i));
      return a;
    }
  }

  private CluewebLinkedSentence sent;   // may be null
  private DepNode[] parse;              // may be null
  private byte[] sentHash;
  private Arg2Mention argMapping;
  private String subj, verb, obj;
  private List<Feat> distsupExFeats;       // features supporting the distsup selection of this fact
  
  public DistSupFact(
      CluewebLinkedSentence sent,
      DepNode[] parse,
      byte[] sentHash,
      Arg2Mention argMapping,
      String subj, String verb, String obj,
      List<Feat> exFeats) {
    this.sent = sent;
    this.parse = parse;
    this.sentHash = sentHash;
    this.argMapping = argMapping;
    this.subj = subj;
    this.verb = verb;
    this.obj = obj;
    this.distsupExFeats = exFeats;
  }
  
  public CluewebLinkedSentence sentence() { return sent; }
  public DepNode[] parse() { return parse; }
  
  public List<Feat> distsupExtractionFeatures() { return distsupExFeats; }
  public byte[] sentenceHash() { return sentHash; }

  public String subject() { return subj; }
  public String verb() { return verb; }
  public String object() { return obj; }
  
  public String subjectMid() {
    int m = argMapping.subj[0];
    return sent.getLink(m).getMid(sent.getMarkup());
  }
  public String objectMid() {
    int m = argMapping.obj[0];
    return sent.getLink(m).getMid(sent.getMarkup());
  }
  
  @Override
  public String toString() {
    return "(DSFact s=" + subj + " v=" + verb + " o=" + obj + ")";
  }
  
  /**
   * Extracts features of the sentence by looking at the subject and object mentions.
   */
  public List<Feat> extractLexicoSyntacticFeats(FeatExData fed) {
    boolean debug = false;
    List<Feat> fs = new ArrayList<>();
    
    List<SegmentedTextAroundLink> segs = this.sent.getTextTokenized();
    
    int subj = argMapping.subj[0];
    int obj = argMapping.obj[0];
    
    // (all) dbpedia entity types for subj/obj
    String subjS = sent.getLink(subj).getMid(sent.getMarkup());
    for (String type : fed.getDbpediaSupertypesFromMid(subjS)) {
      type = urlClean(type);
      type = vwFeatureClean(type);
      fs.add(new Feat("s/" + type, 1));
    }
    String objS = sent.getLink(obj).getMid(sent.getMarkup());
    for (String type : fed.getDbpediaSupertypesFromMid(objS)) {
      type = urlClean(type);
      type = vwFeatureClean(type);
      fs.add(new Feat("o/" + type, 1));
    }
    
    // Words between
    IntPair ss = segs.get(subj).getTokLoc();
    IntPair os = segs.get(obj).getTokLoc();
    Span sSpan = Span.getSpan(ss.first, ss.second);
    Span oSpan = Span.getSpan(os.first, os.second);
    Span mid;
    if (sSpan.end < oSpan.start) {
      mid = Span.getSpan(sSpan.end, oSpan.start);
    } else if (oSpan.end < sSpan.start) {
      mid = Span.getSpan(oSpan.end, sSpan.start);
    } else {
      mid = Span.nullSpan;
    }
    MultiAlphabet a = fed.getParseAlph();
    for (int i = mid.start; i < mid.end; i++) {
      String w = a.word(parse[i].word);
      w = vwFeatureClean(w);
      fs.add(new Feat("m/" + w, 1));
    }
    fs.add(new Feat("m/w=" + Math.min(mid.width(), 10), 1));

    // Dependency path ngrams
    int[] heads = DbpediaDistSup.findMentionHeads(segs, parse);
    if (debug) {
      DepNode.show(parse, a);
      System.out.println();
      Span[] s = DbpediaDistSup.findMentionSpans(segs);
      int[] depths = DepNode.depths(parse);
      Log.info("heads=" + Arrays.toString(heads));
      for (int i = 0; i < heads.length; i++) {
        System.out.println("mention=" + i + " span=" + s[i] + " word=" + heads[i] + " depth=" + depths[heads[i]]);
        System.out.println(segs.get(i) + " inside=" + segs.get(i).inside + " outside=" + segs.get(i).outside);
        DepNode.show(parse, s[i], a);
        System.out.println();
      }
    }
    ShortestPath p = new ShortestPath(heads[subj], heads[obj], parse);
    boolean hideEndpoints = true;
    boolean usePosInsteadOfWord = false;
    List<DepNode.Edge> path = p.buildPath(a, hideEndpoints, usePosInsteadOfWord);
    List<DepNode.Edge[]> oneGrams = ShortestPath.ngrams(1, path);
    List<DepNode.Edge[]> twoGrams = ShortestPath.ngrams(2, path);
    for (DepNode.Edge[] ng : oneGrams) {
      String feat = DepNode.Edge.ngramStr(ng);
      fs.add(new Feat("p/" + feat, 1));
    }
    for (DepNode.Edge[] ng : twoGrams) {
      String feat = DepNode.Edge.ngramStr(ng);
      fs.add(new Feat("q/" + feat, 1));
    }
    
    // TODO POS dep trigrams?
    
    // TODO sub-nodes of subj/obj which don't match NNP*
    
    // TODO one edge off of dep path? e.g. neg(release,not) in "Mary did not release Knives with Journalism"
    
    return fs;
  }
  
  /**
   * Inverse of {@link DbpediaDistSup#generateDistSupInstances(edu.jhu.hlt.tutils.ExperimentProperties)}
   * Reads using an {@link ObjectInputStream}
   */
  public static List<DistSupFact> readFacts(File distSupFactJserStream) throws IOException {
    // Read all the facts
    Log.info("reading from distSupFactJserStream=" + distSupFactJserStream.getPath());
    List<DistSupFact> facts = new ArrayList<>();
    try (ObjectInputStream ois = new ObjectInputStream(FileUtil.getInputStream(distSupFactJserStream))) {
      while (true) {
        try {
          DistSupFact f = (DistSupFact) ois.readObject();
          if (f == null)
            break;
          facts.add(f);
        } catch (Exception e) {
          break;
        }
      }
    }
    return facts;
  }
  
  public static String urlClean(String containsUrl) {
    return containsUrl.replace("http://", "").replace("www.", "");
  }

  public static String vwFeatureClean(String feature) {
    return feature.replace(":", "_");
  }

//  static class Iterator implements java.util.Iterator<DistSupFact>, AutoCloseable {
//    public Iterator(File f) {
//      throw new RuntimeException("implement me");
//    }
//    @Override
//    public void close() throws Exception {
//      throw new RuntimeException("implement me");
//    }
//    @Override
//    public boolean hasNext() {
//      throw new RuntimeException("implement me");
//    }
//    @Override
//    public DistSupFact next() {
//      throw new RuntimeException("implement me");
//    }
//  }

}
