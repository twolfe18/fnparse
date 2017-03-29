package edu.jhu.hlt.entsum;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.entsum.CluewebLinkedSentence.SegmentedTextAroundLink;
import edu.jhu.hlt.entsum.DbpediaDistSup.FeatExData;
import edu.jhu.hlt.entsum.DepNode.ShortestPath;
import edu.jhu.hlt.ikbp.tac.ComputeIdf;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.ArgMin;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.util.UniqList;

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
  
  public static List<String> extractLexicoSyntacticFeats(
      int subjHead, Span subjSpan, List<String> subjTypes,
      int objHead, Span objSpan, List<String> objTypes,
      int[] tok2ent,    // indexed by token, value of -1 means regular ^NNP* word, value >= 0 is a mention index
      DepNode[] parse, MultiAlphabet parseAlph, ComputeIdf df) {
    
//    List<String> fs = new ArrayList<>();
    UniqList<String> fs = new UniqList<>();

    // (all) dbpedia entity types for subj/obj
    for (String type : subjTypes) {
      type = urlClean(type);
      type = vwFeatureClean(type);
      fs.add("s/" + type);
    }
    // Edges leaving subj
    List<DepNode.Edge> subjL = DepNode.getEdgesLeavingSpan(subjSpan, parse, parseAlph);
    for (DepNode.Edge e : subjL)
      fs.add("S/" + e);
    fs.add("S/n=" + Math.min(10, subjL.size()));
    for (String type : objTypes) {
      type = urlClean(type);
      type = vwFeatureClean(type);
      fs.add("o/" + type);
    }
    List<DepNode.Edge> objL = DepNode.getEdgesLeavingSpan(objSpan, parse, parseAlph);
    for (DepNode.Edge e : objL)
      fs.add("O/" + e);
    fs.add("O/n=" + Math.min(10, objL.size()));
    
    // Words between
    Span mid;
    if (subjSpan.end < objSpan.start) {
      mid = Span.getSpan(subjSpan.end, objSpan.start);
    } else if (objSpan.end < subjSpan.start) {
      mid = Span.getSpan(objSpan.end, subjSpan.start);
    } else {
      mid = Span.nullSpan;
    }
    Set<String> uniq = new HashSet<>();
    Set<Integer> entsBetween = null;
    if (tok2ent != null)
      entsBetween = new HashSet<>();
    for (int i = mid.start; i < mid.end; i++) {
      if (entsBetween != null && tok2ent[i] >= 0) {
        entsBetween.add(tok2ent[i]);
        continue;
      }
      String w = parseAlph.word(parse[i].word);
      w = vwFeatureClean(w);
      w = w.replaceAll("\\d", "0");
      if (uniq.add(w))
        fs.add("m/" + w);
    }
    fs.add("M/w=" + Math.min(mid.width(), 10));
    if (entsBetween != null)
      fs.add("M/e=" + Math.min(entsBetween.size(), 10));

    // Dependency path ngrams
    ShortestPath p = new ShortestPath(subjHead, objHead, parse);
    List<DepNode.Edge> wordPath = p.buildPath(parseAlph, true, false);
    wordPath = ShortestPath.replaceDigits(wordPath);
    List<DepNode.Edge> posPath = p.buildPath(parseAlph, false, true);
    List<DepNode.Edge[]> w1grams = ShortestPath.ngrams(1, wordPath);
    List<DepNode.Edge[]> w2grams = ShortestPath.ngrams(2, wordPath);
    List<DepNode.Edge[]> w3grams = ShortestPath.ngrams(3, wordPath);
    List<DepNode.Edge[]> p1grams = ShortestPath.ngrams(1, posPath);
    List<DepNode.Edge[]> p2grams = ShortestPath.ngrams(2, posPath);
    List<DepNode.Edge[]> p3grams = ShortestPath.ngrams(3, posPath);
    for (DepNode.Edge[] ng : w1grams) {
      String feat = DepNode.Edge.ngramStr(ng);
      fs.add("p/" + feat);
    }
    for (DepNode.Edge[] ng : p1grams) {
      String feat = DepNode.Edge.ngramStr(ng);
      fs.add("P/" + feat);
    }
    for (DepNode.Edge[] ng : w2grams) {
      String feat = DepNode.Edge.ngramStr(ng);
      fs.add("q/" + feat);
    }
    for (DepNode.Edge[] ng : p2grams) {
      String feat = DepNode.Edge.ngramStr(ng);
      fs.add("Q/" + feat);
    }
    for (DepNode.Edge[] ng : w3grams) {
      String feat = DepNode.Edge.ngramStr(ng);
      fs.add("t/" + feat);
    }
    for (DepNode.Edge[] ng : p3grams) {
      String feat = DepNode.Edge.ngramStr(ng);
      fs.add("T/" + feat);
    }
    int ps = wordPath.size();
    fs.add("d/dp=" + Math.min(12, ps));
    fs.add("d/dp4=" + (ps <= 4 ? "y" : "n"));
    fs.add("d/dp3=" + (ps <= 3 ? "y" : "n"));
    fs.add("d/dp2=" + (ps <= 2 ? "y" : "n"));
    fs.add("d/dp1=" + (ps <= 1 ? "y" : "n"));
    fs.add("d/dir=" + (subjHead < objHead ? "l" : "r"));
    
    
    // Depth of subjHead, objHead, and shallowest node in path
    int[] depth = DepNode.depths(parse);
    fs.add("a/sd=" + depth[subjHead]);
    fs.add("a/od=" + depth[objHead]);
    ArgMin<Integer> pathShallow = new ArgMin<>();
    Set<Integer> entsAlongPath = null;
    if (tok2ent != null)
      entsAlongPath = new HashSet<>();
    BitSet tokensOnPath = new BitSet();
    for (DepNode.Edge e : wordPath) {
      int dh = e.headIdx < 0 ? -1 : depth[e.headIdx];
      pathShallow.offer(e.headIdx, dh);
      int dm = e.modIdx < 0 ? -1 : depth[e.modIdx];
      pathShallow.offer(e.modIdx, dm);
      
      if (e.headIdx >= 0)
        tokensOnPath.set(e.headIdx);
      if (e.modIdx >= 0)
        tokensOnPath.set(e.modIdx);
      
      if (entsAlongPath != null) {
        if (e.headIdx >= 0)
          entsAlongPath.add(tok2ent[e.headIdx]);
        if (e.modIdx >= 0)
          entsAlongPath.add(tok2ent[e.modIdx]);
      }
    }
    int shallow = pathShallow.get();
    if (shallow < 0) {
      fs.add("a/pd=ROOT");
      fs.add("a/ps=ROOT");
    } else {
      fs.add("a/pd=" + depth[shallow]);
      fs.add("a/ps=" + parseAlph.word(parse[shallow].word));
      fs.add("a/ps=" + parseAlph.pos(parse[shallow].pos));
      
      // Children of shallowest node which aren't one the path
      int prev = -1;
      for (int c = parse[shallow].depLeftChildNode; c >= 0; c = parse[c].depRightSibNode) {
        assert c > prev;
        prev = c;
        if (!tokensOnPath.get(c)) {
          DepNode.Edge e = new DepNode.Edge(shallow, c, parse, parseAlph);
          fs.add("A/sc=" + e);
          String h = parseAlph.pos(parse[shallow].pos);
          String m = parseAlph.pos(parse[c].pos);
          fs.add("A/sc=" + new DepNode.Edge(e.label, h, m));
        }
      }
    }
    if (entsAlongPath != null) {
      entsAlongPath.remove(tok2ent[subjHead]);
      entsAlongPath.remove(tok2ent[objHead]);
      fs.add("a/ne=" + Math.min(10, entsAlongPath.size()));
    }

    return fs.getList();
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
      w = w.replaceAll("\\d", "0");
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
    path = ShortestPath.replaceDigits(path);
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
