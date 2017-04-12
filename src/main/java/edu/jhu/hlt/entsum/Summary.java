package edu.jhu.hlt.entsum;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.prim.list.DoubleArrayList;
import edu.jhu.util.MultiMap;

public class Summary implements Serializable {
  private static final long serialVersionUID = 5796571498596058534L;

  public static class Concept implements Serializable {
    private static final long serialVersionUID = -5686978313618341650L;
    public String name;
    public int sentence;
    public Span tokens;
    public double utility;
    public double mentionCost;

    public Concept(String name, int sentence, Span tokens, double utility, double mentionCost) {
      this.name = name;
      this.sentence = sentence;
      this.tokens = tokens;
      this.utility = utility;
      this.mentionCost = mentionCost;
    }
    
    public Concept changeSentence(int newSentence) {
      return new Concept(name, newSentence, tokens, utility, mentionCost);
    }
    
    public double bangForBuck() {
      if (mentionCost == 0)
        return Double.POSITIVE_INFINITY;
      return utility / mentionCost;
    }
    
    public static final Comparator<Concept> BY_BANG_FOR_BUCK_DESC = new Comparator<Concept>() {
      @Override
      public int compare(Concept o1, Concept o2) {
        if (o1.mentionCost == 0 && o2.mentionCost == 0) {
          if (o1.utility > o2.utility)
            return -1;
          if (o2.utility > o1.utility)
            return +1;
          return 0;
        }
        if (o1.mentionCost == 0)
          return -1;
        if (o2.mentionCost == 0)
          return +1;
        double r1 = o1.bangForBuck();
        double r2 = o2.bangForBuck();
        if (r1 > r2)
          return -1;
        if (r2 > r1)
          return +1;
        return 0;
      }
    };
  }

  public String subject;            // e.g. a mid
  public String system;             // which produced this summary
  public DoubleArrayList sentenceCosts;
  public List<EffSent> sentences;
  public List<Concept> concepts;
  public List<VwInstance> conceptPredictions;
  
  
  public Summary(String subject, String system) {
    this.subject = subject;
    this.system = system;
    this.sentenceCosts = new DoubleArrayList();
    this.sentences = new ArrayList<>();
    this.concepts = new ArrayList<>();
    this.conceptPredictions = new ArrayList<>();
  }

  public Summary(String subject, String system, List<EffSent> sentences) {
    this.subject = subject;
    this.system = system;
    this.sentenceCosts = new DoubleArrayList();
    this.sentences = sentences;
    this.concepts = new ArrayList<>();
    this.conceptPredictions = new ArrayList<>();
  }
  
  private static class Sent implements Comparable<Sent> {
    double score;
    double sentenceCost = Double.NaN;
    int oldIdx;
    EffSent sent;
    List<Concept> concepts;
    @Override
    public int compareTo(Sent o) {
      if (score > o.score)
        return -1;
      if (score < o.score)
        return +1;
      return 0;
    }
  }
  private void add(Sent si, int[] old2newSentIdx) {
    sentences.add(si.sent);
    if (!Double.isNaN(si.sentenceCost))
      sentenceCosts.add(si.sentenceCost);
    for (Concept ci : si.concepts)
      concepts.add(ci.changeSentence(old2newSentIdx[ci.sentence]));
  }
  public Summary orderSentencesByUtility() {
    List<Sent> s = new ArrayList<>();
    for (int i = 0; i < sentences.size(); i++) {
      Sent si = new Sent();
      si.oldIdx = i;
      if (sentenceCosts != null)
        si.sentenceCost = sentenceCosts.get(i);
      si.sent = sentences.get(i);
      si.concepts = conceptsIn(i);
      for (Concept c : si.concepts)
        si.score += c.utility - c.mentionCost;
      s.add(si);
    }
    Collections.sort(s);

    // Change the sentence indices
    int[] old2new = new int[s.size()];
    for (int i = 0; i < s.size(); i++) {
      Sent si = s.get(i);
      old2new[si.oldIdx] = i;
    }

    Summary sn = new Summary(subject, system);
    for (Sent si : s)
      sn.add(si, old2new);
    
    // These VwInstances have bogus sentence indices.
    // I believe they are global indices (in full set of sentences to choose from when summarizing).
    // These indices DO NOT correspond to a local index in this.sentences.
    // For now I'm just wiping it out and hoping I don't need it.
    for (VwInstance i : conceptPredictions)
      sn.conceptPredictions.add(i.mapSentenceIndex(-1));
    
    return sn;
  }
  
  public void addConcept(int sentence, Span tokens, String name, double utility, double mentionCost) {
    this.concepts.add(new Concept(name, sentence, tokens, utility, mentionCost));
  }
  
  public List<Concept> conceptsIn(int sentence) {
    List<Concept> out = new ArrayList<>();
    for (Concept c : concepts) {
      assert c.sentence >= 0 && c.sentence < sentences.size();
      if (c.sentence == sentence)
        out.add(c);
    }
    return out;
  }
  
  public void show(MultiAlphabet a) {
    for (int i = 0; i < sentences.size(); i++) {
      EffSent s = sentences.get(i);
      double sc = sentenceCosts.get(i);
      List<Concept> ci = conceptsIn(i);
      Collections.sort(ci, Concept.BY_BANG_FOR_BUCK_DESC);
      System.out.println("sentence=" + i + " nWord=" + s.parse().length + " nConcept=" + ci.size());
      s.showConllStyle(a);
      System.out.print("concepts:");
      for (Concept c : ci) {
        String loc = c.tokens == Span.nullSpan ? "?" : c.tokens.shortString();
        System.out.printf("  [%s u=%.2f c=%.2f @%s]", c.name, c.utility, c.mentionCost, loc);
      }
      System.out.printf("\nsentenceCost: %.3f\n", sc);
      System.out.println("\n");
    }
  }
  
  /**
   * Given {@link Summary} objects which have been serialized in
   *   $ENTITY_DIR/summary/*.jser
   * Produce HITs which are put in
   *   $ENTITY_DIR/summary-hits
   */
  public static class WriteHits {
    private static final Charset UTF8 = Charset.forName("UTF8");

    private File entityDir;
    private Random rand;
    private HashFunction hf;
    private String mid;
    
    public WriteHits(File entityDir, Random rand) {
      Log.info("entityDir=" + entityDir.getPath() + " firstRand=" + Integer.toHexString(rand.nextInt()));
      this.mid = entityDir.getName().replace("m.", "/m/");
      this.entityDir = entityDir;
      this.rand = rand;
      this.hf = Hashing.murmur3_128(9001);
    }
    
    public String getHitString(Summary s, MultiAlphabet a) {
      StringBuilder sb = new StringBuilder();
      int sIdx = 0;
      for (EffSent sent : s.sentences) {

//        for (int i = 0; i < sent.numTokens(); i++) {
//          if (i > 0)
//            sb.append(' ');
//          sb.append(a.word(sent.parse(i).word));
//        }
        sb.append(sent.showWithMidHighlighted(mid, a));
        
        List<Concept> cs = s.conceptsIn(sIdx);
        sb.append(" <!-- concepts:");
        for (Concept c : cs)
          sb.append(" " + c.name);
        sb.append(" -->");
        
        sb.append('\n');
        sb.append("<br/>\n");
        sIdx++;
      }
      return sb.toString();
    }
  
    public String keyFunc(Summary s, boolean includeConcepts) {
      Hasher h = hf.newHasher();
      h.putInt(s.sentences.size());
      for (EffSent sent : s.sentences)
        for (DepNode d : sent.parse())
          h.putInt(d.word);
      if (includeConcepts) {
        h.putInt(s.concepts.size());
        for (Concept c : s.concepts) {
          h.putString(c.name, UTF8);
          h.putInt(c.sentence);
          h.putInt(c.tokens.start);
          h.putInt(c.tokens.end);
        }
      }
      return h.hash().toString();
    }

    /**
     * Writes out:
     *   $OUTDIR/$TAG-hit.csv has the columns [subj, tag, entityName, sum1sys, sum2sys, sum3sys, nasys, sum1, sum2, sum3]
     */
    public void writeSummaryChoices(File outdir, String tag, Random rand, MultiAlphabet a, Summary... summaries) throws IOException {
      Log.info("outdir=" + outdir.getPath() + " tag=" + tag + " nSummaries=" + summaries.length);
      
      if (summaries.length == 0) {
        Log.info("nothing to do, returning");
        return;
      }

      String en = EntsumUtil.getEntityName(entityDir);
      Log.info("entity name: " + en);
      if (en == null) {
        Log.info("skipping un-named entity");
        return;
      }

      // Group the summaries (find duplicates)
      MultiMap<String, Summary> dups = new MultiMap<>();
      boolean includeConcepts = false;
      for (Summary s : summaries)
        dups.add(keyFunc(s, includeConcepts), s);
      int nshow = 3;
      if (dups.numKeys() < nshow) {
        Log.info("there aren't enough distinct summaries, skipping");
        return;
      }

      // Choose systems presented to turkers
      String[] sum2sys = new String[nshow+1]; // last is na/not shown
      String[] sum2text = new String[nshow];
      for (int i = 0; i < nshow; i++) {
        String k = dups.sampleKeyBasedOnNumEntries(rand);
        List<Summary> systemsWithThisSummary = dups.remove(k);
        List<String> sys = new ArrayList<>();
        for (Summary s : systemsWithThisSummary)
          sys.add(s.system);

        Summary show = systemsWithThisSummary.get(0);
        sum2sys[i] = StringUtils.join(" ", sys);
        sum2text[i] = getHitString(show, a);
      }

      // Systems not presented to turkers
      List<String> nasys = new ArrayList<>();
      for (String k : dups.keySet()) {
        List<Summary> systemsWithThisSummary = dups.get(k);
        for (Summary s : systemsWithThisSummary) {
          nasys.add(s.system);
        }
      }
      sum2sys[nshow] = StringUtils.join(" ", nasys);

      // Write to CSV
      String[] all = new String[3 + sum2sys.length + sum2text.length];
      all[0] = mid;
      all[1] = tag;
      all[2] = en;
      System.arraycopy(sum2sys, 0, all, 3, sum2sys.length);
      System.arraycopy(sum2text, 0, all, 3 + sum2sys.length, sum2text.length);
      File csv = new File(outdir, tag + "-hit.csv");
      Log.info("writing csv to " + csv.getPath());
      try (BufferedWriter wCsv = FileUtil.getWriter(csv);
          CSVPrinter cw = new CSVPrinter(wCsv, CSVFormat.DEFAULT)) {
        // Remove any newlines which may be in there
        for (int i = 0; i < all.length; i++)
          all[i] = all[i].replaceAll("\n", " ");
        cw.printRecord(all);
      }

      File hit = new File(outdir, tag + "-hit.html");
      
//      try (BufferedWriter wHit = FileUtil.getWriter(hit);
////          BufferedWriter wSys = FileUtil.getWriter(systems);
//          BufferedWriter wCsv = FileUtil.getWriter(csv);
//          CSVPrinter cw = new CSVPrinter(wCsv, CSVFormat.DEFAULT)) {
//        
//        String[] ca = new String[9];
//        ca[0] = mid;
//        ca[1] = tag;
//        assert nshow * 2 + 3 >= ca.length;
//
//        wHit.write("<style type=\"text/css\">");
//        wHit.newLine();
//        wHit.write("span.subj { color: blue; font-weight: bold; }");
//        wHit.newLine();
//        wHit.write("</style>");
//        wHit.newLine();
//        wHit.write("<h2>" + tag + " sumary of " + mid + "</h2>");
//        wHit.newLine();
//        wHit.write("<center><table border=\"1\" cellpadding=\"10\" width=\"80%\"> <!-- summary of " + mid + " -->");
//        wHit.newLine();
//        wHit.write("</table></center>");
//        wHit.newLine();
//
//          wHit.write("<!-- systems: " + StringUtils.join(" ", sys) + " -->\n");
//          wHit.write("<tr>");
//          wHit.write("<td> systems: " + StringUtils.join(" ", sys) + "</td>");
//          wHit.write("<td>");
//          wHit.newLine();
//          wHit.write(hs);
////          wHit.newLine();
//          wHit.write("</td>");
//          wHit.write("<tr>");
//          wHit.newLine();
//
//        ca[5] = StringUtils.join(" ", nasys);
//        
//        // Remove any newlines which may be in there
//        for (int i = 0; i < ca.length; i++)
//          ca[i] = ca[i].replaceAll("\n", " ");
//        cw.printRecord(ca);
//      }
    }

    //  public static void buildHits(File entityDir, Random rand) throws IOException {
    public void buildHits(String wTag) throws IOException {
      // $ENTITY_DIR/summary/e_tf-w100.jser
      // $ENTITY_DIR/summary/w_tfidf-e_tf-w100.jser
      List<File> summFs = FileUtil.find(new File(entityDir, "summary"), "glob:**/" + wTag + "/*.jser");
      Summary[] summs = new Summary[summFs.size()];
      for (int i = 0; i < summs.length; i++) {
        summs[i] = (Summary) FileUtil.deserialize(summFs.get(i));
      }
      
      File af = new File(entityDir, "summary/alph.jser");
      MultiAlphabet a = (MultiAlphabet) FileUtil.deserialize(af);

      File output = new File(entityDir, "summary-hits");
      output.mkdirs();
      writeSummaryChoices(output, wTag, rand, a, summs);
    }
  }

  public static void buildSummariesForOneEntity(ExperimentProperties config) throws IOException {
    Random rand = config.getRandom();
    File entityDir = config.getExistingDir("entityDir");
    WriteHits wh = new WriteHits(entityDir, rand);
    String wTag = config.getString("wTag", "w40");
    wh.buildHits(wTag);
  }

  public static void buildSummariesForAllEntities(ExperimentProperties config) throws IOException {
    Random rand = config.getRandom();
    File entityDirParent = config.getExistingDir("entityDirParent");
    for (File entityDir : EntsumUtil.getAllEntityDirs(entityDirParent)) {
      WriteHits wh = new WriteHits(entityDir, rand);
      List<File> sums = FileUtil.findDirs(entityDir, "glob:**/summary/w*");
      for (File s : sums) {
        String wTag = s.getName();    // e.g. "w40"
        try {
          wh.buildHits(wTag);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
  }
  
  /** Just cat together a bunch of CSV files */
  public static void buildHitForAllEntities(ExperimentProperties config) throws IOException {
    File outputCsv = config.getFile("outputCsv");
    File entityDirParent = config.getExistingDir("entityDirParent");
    Log.info("entityDirParent=" + entityDirParent);
    
    // Each one of these is a hit, meaning a snippet of HTML which we want to encode as an entry in one CSV row
    List<File> fs = FileUtil.find(entityDirParent, "glob:**/*-hit.csv");
    Log.info("found " + fs.size() + " hit files to roll up into one CSV");

    Log.info("writing to " + outputCsv.getPath());
    // This is equiavalent to just catting all these files together
    try (BufferedWriter w = FileUtil.getWriter(outputCsv)) {
      w.write("subj,tag,entityName,sum1sys,sum2sys,sum3sys,nasys,sum1text,sum2text,sum3text");
      w.newLine();
      for (File f : fs) {
        boolean replaceNewlinesWithSpaces = false;
        w.write(FileUtil.getContents(f, replaceNewlinesWithSpaces));
      }
    }
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    if (config.getBoolean("summary", true)) {
//      buildSummariesForOneEntity(config);
      buildSummariesForAllEntities(config);
    }
    
    if (config.getBoolean("hit", true))
      buildHitForAllEntities(config);
    
//    String mode = config.getString("mode");
//    switch (mode) {
//    case "summary":
//      buildSummariesForOneEntity(config);
//      break;
//    case "hit":
//      buildHitForAllEntities(config);
//      break;
//    default:
//      throw new RuntimeException("unknown mode: " + mode);
//    }
    Log.info("done");
  }
}