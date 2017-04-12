package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import edu.jhu.hlt.entsum.CluewebLinkedPreprocess.EntityMentionRanker.ScoredPassage;
import edu.jhu.hlt.entsum.CluewebLinkedPreprocess.EntitySplit.DataSetSplit;
import edu.jhu.hlt.entsum.CluewebLinkedSentence.SegmentedTextAroundLink;
import edu.jhu.hlt.entsum.CluewebLinkedSentence.ValidatorIterator;
import edu.jhu.hlt.entsum.DbpediaDistSup.FeatExData;
import edu.jhu.hlt.entsum.DbpediaDistSup.SentenceInterestingnessFeatures;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.ComputeIdf;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.ArgMax;
import edu.jhu.hlt.tutils.ArgMin;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.OrderStatistics;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.prim.list.DoubleArrayList;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;
import edu.jhu.util.MultiMap;

/**
 * 1) extract counts for all entities, use this to produce a train/dev/test set
 * 2) for each, extract all those sentences, put them into a separate file/DB
 * 3) for each entity file, parse and tag them with PMP
 * 4) TODO loop through each entity, lookup sentences, re-rank them
 * 
 * NOTE: This implements the steps before {@link DistSupSetup}
 *
 * @author travis
 */
public class CluewebLinkedPreprocess {
  
  /**
   * First pass: get an approximate frequency for all the entities
   */
  static class EntCounts implements Serializable {
    private static final long serialVersionUID = 7277345287463990963L;

    private StringCountMinSketch midCounts;
    private int maxMidFreq;
    private long nMidObservations;
    
    public EntCounts() {
      int nhash = 12;
      int logb = 23;
      midCounts = new StringCountMinSketch(nhash, logb, true);
      maxMidFreq = 0;
      nMidObservations = 0;
    }
    
    public int getCount(String mid) {
      return midCounts.apply(mid, false);
    }
    
    public void observe(CluewebLinkedSentence sent) {
      int n = sent.numLinks();
      for (int i = 0; i < n; i++) {
        CluewebLinkedSentence.Link l = sent.getLink(i);
        String mid = l.getMid(sent.getMarkup());
        int c = midCounts.apply(mid, true);
        maxMidFreq = Math.max(maxMidFreq, c);
        nMidObservations++;
      }
    }
    
    public int maxMidFrequency() {
      return maxMidFreq;
    }
    
    public long numMidObservations() {
      return nMidObservations;
    }
  }
  
  /**
   * Logic for actually splitting entities into train/dev/test partitions by frequency.
   */
  static class EntitySplit {
    static final Charset UTF8 = Charset.forName("UTF-8");
    
    // Ahead of time measure how frequent each mid is (approximately)
    private EntCounts entCounts;
    private Random rand;
    private Set<String> seenMids = new HashSet<>();   // TODO O(n) memory...
    
    // These two are indexed together
    private double[] thresholds;
    // samples[i] is draw from mids with freq in (thresholds[i-1], thresholds[i]]
    private ReservoirSample<String>[] samples;

    private int nDev, nTest;
    private double pTrain;    // nTrain = (pTrain - 1) * (nDev + nTest)
    
    // Diagnostics
    private Counts<String> ec;
    
    public EntitySplit(EntCounts entCounts, Random rand, int nDev, int nTest, double pTrain) {
      if (pTrain <= 1 || nDev < 1 || nTest < 1)
        throw new IllegalArgumentException();
      this.entCounts = entCounts;
      this.rand = rand;
      this.nDev = nDev;
      this.nTest = nTest;
      this.pTrain = pTrain;
      this.ec = new Counts<>();
    }

    @SuppressWarnings("unchecked")
    public void init(int nFreqBuckets, int cMin) {
      ec.increment("init");
      int n = (int) (0.5d + (nDev + nTest) * pTrain);
      samples = new ReservoirSample[nFreqBuckets];
      for (int i = 0; i < samples.length; i++)
        samples[i] = new ReservoirSample<>(n, rand);
      
      double cMax = entCounts.maxMidFreq * 0.5;
      double cRange = cMax / cMin;
      double mult = Math.pow(cRange, 1d / (nFreqBuckets-1));
      Log.info("maxMidFreq=" + entCounts.maxMidFreq + " nFreqBuckets=" + nFreqBuckets + " mult=" + mult);
      thresholds = new double[nFreqBuckets];
      for (int i = 0; i < thresholds.length; i++) {
        thresholds[i] = cMax;
        cMax /= mult;
      }
      Log.info("n=" + n + " thresh=" + Arrays.toString(thresholds));
    }
    
    public void add(String smid) {
      ec.increment("add/mid/inst");
      if (!seenMids.add(smid))
        return;
      ec.increment("add/mid/type");
      // Find which frequency bucket this mid falls into
      int c = entCounts.midCounts.apply(smid, false);
      for (int i = 0; i < this.samples.length; i++) {
        if (c >= this.thresholds[i]) {
          this.samples[i].add(smid);
          return;
        }
      }
    }
    
    public void add(CluewebLinkedSentence sent) {
      ec.increment("add/sent");
      int n = sent.numLinks();
      for (int i = 0; i < n; i++)
        add(sent.getLink(i).getMid(sent.getMarkup()));
    }
    
    static class DataSetSplit {
      int types;
      double cMin, cMax;
      List<String> train, dev, test;
      
      public DataSetSplit(int nDev, int nTest, List<String> all, double cMin, double cMax) {
        if (nDev + nTest >= all.size()) {
          Log.info("WARNING: there aren't enough instances: nDev=" + nDev + " nTest=" + nTest + " all=" +all.size());
        }
        this.cMin = cMin;
        this.cMax = cMax;
        this.train = new ArrayList<>();
        this.dev = new ArrayList<>();
        this.test = new ArrayList<>();
        
        int ptr = 0;
        while (test.size() < nTest && ptr < all.size())
          test.add(all.get(ptr++));
        while (dev.size() < nDev && ptr < all.size())
          dev.add(all.get(ptr++));
        while (ptr < all.size())
          train.add(all.get(ptr++));
      }
      
      public List<Pair<String, String>> getStats() {
        List<Pair<String, String>> s = new ArrayList<>();
        s.add(new Pair<>("cMin", ""+cMin));
        s.add(new Pair<>("cMax", ""+cMax));
        s.add(new Pair<>("nTrain", ""+train.size()));
        s.add(new Pair<>("nDev", ""+dev.size()));
        s.add(new Pair<>("nTest", ""+test.size()));
        s.add(new Pair<>("types", ""+types));
        return s;
      }
    }
    
    public DataSetSplit[] draw() {
      ec.increment("draw");
      Log.info("counts: " + ec);
      DataSetSplit[] ds = new DataSetSplit[thresholds.length];
      double cMax = Double.POSITIVE_INFINITY;
      for (int i = 0; i < thresholds.length; i++) {
        List<String> all = new ArrayList<>();
        for (String mid : samples[i])
          all.add(mid);
        ds[i] = new DataSetSplit(nDev, nTest, all, thresholds[i], cMax);
        ds[i].types = samples[i].numObservations();
        cMax = thresholds[i];
      }
      return ds;
    }
  }
  
  public static void buildTrainDevTestSplit(ExperimentProperties config) throws IOException {
    TimeMarker tm = new TimeMarker();

    File linkedCluewebRoot = config.getExistingDir("linkedCluewebRoot",
        new File("/home/travis/code/data/clueweb09-freebase-annotation/extractedAnnotation"));
    List<File> fs = FileUtil.find(linkedCluewebRoot, "glob:**/*.gz");
    int nfs = fs.size();
    Log.info("found " + nfs + " *.gz files in linkedCluewebRoot=" + linkedCluewebRoot.getPath());
    
    File outputDir = config.getOrMakeDir("outputDir");
    Log.info("outputDir=" + outputDir.getPath());
    
    // Checkpoint pass 1 (counting mids)
    File ecFile = new File(outputDir, "freebase-mid-mention-frequency.cms.jser");
    EntCounts ec;
    if (ecFile.isFile()) {
      Log.info("loading entity counts from " + ecFile.getPath());
      ec = (EntCounts) FileUtil.deserialize(ecFile);
    } else {
      Log.info("starting Pass 1: count entities' mention frequencies...");
      ec = new EntCounts();
      for (int i = 0; i < nfs; i++) {
        File f = fs.get(i);
        Log.info("reading " + (i+1) + " of " + nfs + " " + f.getPath());
        try (ValidatorIterator iter = new ValidatorIterator(f)) {
          while (iter.hasNext()) {
            CluewebLinkedSentence sent = iter.next();
            ec.observe(sent);
            if (tm.enoughTimePassed(10))
              Log.info("pass1: " + Describe.memoryUsage());
          }
        }
      }
      Log.info("saving entity counts to " + ecFile.getPath());
      FileUtil.serialize(ec, ecFile);
    }
    
    Log.info("starting Pass 2: sample entities...");
    Random rand = config.getRandom();
    int nDev = config.getInt("nDev", 100);
    int nTest = config.getInt("nTest", 100);
    double pTrain = config.getDouble("pTrain", 20);
    EntitySplit es = new EntitySplit(ec, rand, nDev, nTest, pTrain);
    int nFreqBuckets = config.getInt("nFreqBuckets", 8);
    int cMin = config.getInt("cMin", 4);
    es.init(nFreqBuckets, cMin);
    for (int i = 0; i < nfs; i++) {
      File f = fs.get(i);
      Log.info("reading " + (i+1) + " of " + nfs + " " + f.getPath());
      try (ValidatorIterator iter = new ValidatorIterator(f)) {
        while (iter.hasNext()) {
          CluewebLinkedSentence sent = iter.next();
          es.add(sent);
          if (tm.enoughTimePassed(10))
            Log.info("pass2: " + Describe.memoryUsage());
        }
      }
    }
    
    Log.info("writing out mids to " + outputDir.getPath());
    DataSetSplit[] tdt = es.draw();
    for (int i = 0; i < tdt.length; i++) {
      File dir = new File(outputDir, "rare" + i);
      dir.mkdirs();
      DataSetSplit ds = tdt[i];
      FileUtil.writeLines(ds.train, new File(dir, "mids.train.txt"));
      FileUtil.writeLines(ds.dev, new File(dir, "mids.dev.txt"));
      FileUtil.writeLines(ds.test, new File(dir, "mids.test.txt"));
      try (BufferedWriter w = FileUtil.getWriter(new File(dir, "stats.txt"))) {
        for (Pair<String, String> p : ds.getStats()) {
          w.write(p.get1() + "\t" + p.get2());
          w.newLine();
        }
      }
    }
    
    Log.info("done");
  }
  
  /**
   * Converts "/m/01ctvk" to "m.01ctvk".
   * Useful, e.g. if you want to put a mid in a filename without extra directories.
   */
  public static String normalizeMid(String mid) {
    assert mid.charAt(0) == '/';
    assert mid.charAt(1) == 'm';
    assert mid.charAt(2) == '/';
    return "m." + mid.substring(3);
  }
  
  /**
   * Scans through sentences looking for a set of relevant mid's
   * which are specified in a text file at construction.
   * Sentences containing a relevant mid are put into a separate file.
   */
  static class ExtractRelevantSentence implements AutoCloseable {
    public static final String MID_SENT_FILE_PREFIX = "sentences-containing-";
    
    private Map<String, BufferedWriter> mids;
    private Counts<String> midsWritten;
    private Counts<String> ec;
    
    public ExtractRelevantSentence(File outputDir, File inputMidFile, boolean gzipOutput) throws IOException {
      ec = new Counts<>();
      midsWritten = new Counts<>();
      mids = new HashMap<>();
      String suf = gzipOutput ? ".txt.gz" : ".txt";
      for (String mid : FileUtil.getLines(inputMidFile)) {
        assert mid.split("\\s+").length == 1;
        String midNorm = normalizeMid(mid);
        File f = new File(outputDir, MID_SENT_FILE_PREFIX + midNorm + suf);
        BufferedWriter w = FileUtil.getWriter(f);
        Object old = mids.put(mid, w);
        assert old == null;
      }
    }
    
    public void process(File f) throws IOException {
      Set<String> seen = new HashSet<>();
      try (ValidatorIterator iter = new ValidatorIterator(f)) {
        while (iter.hasNext()) {
          ec.increment("lines/read");
          CluewebLinkedSentence sent = iter.next();
          seen.clear();
          int n = sent.numLinks();
          for (int i = 0; i < n; i++) {
            String mid = sent.getLink(i).getMid(sent.getMarkup());
            if (seen.add(mid)) {
              BufferedWriter w = mids.get(mid);
              if (w != null) {
                ec.increment("lines/kept");
                midsWritten.increment(mid);
                w.write(sent.getMarkup());
                w.newLine();
              }
            }
          }
        }
      }
    }

    @Override
    public void close() throws Exception {
      Log.info("nOpen=" + mids.size());
      List<String> keys = new ArrayList<>();
      keys.addAll(mids.keySet());
      for (String k : keys) {
        BufferedWriter w = mids.remove(k);
        w.close();
      }
    }
  }
  
  public static void extractSentencesForEachMid(ExperimentProperties config) throws Exception {
    File midFile = config.getExistingFile("midFile");
    Log.info("reading from midFile=" + midFile.getPath());

    File linkedCluewebRoot = config.getExistingDir("linkedCluewebRoot");
    List<File> fs = FileUtil.find(linkedCluewebRoot, "glob:**/*.gz");
    Log.info("found " + fs.size() + " *.gz files in linkedCluewebRoot=" + linkedCluewebRoot.getPath());
    
    File outputDir = config.getOrMakeDir("outputDir");
    Log.info("outputDir=" + outputDir.getPath());
    
    TimeMarker tm = new TimeMarker();
    boolean gzipOutput = config.getBoolean("gzipOutput", true);
    try (ExtractRelevantSentence e = new ExtractRelevantSentence(outputDir, midFile, gzipOutput)) {
      int n = fs.size();
      for (int i = 0; i < n; i++) {
        File f = fs.get(i);
        Log.info("reading " + (i+1) + " of " + n + " " + f.getPath());
        e.process(f);
        if (tm.enoughTimePassed(10))
          Log.info(Describe.memoryUsage() + "\t" + e.ec);
      }
    }
  }
  
  /* NOTE: Currently not necessary
   * Stores a list of hashes/UUIDs of sentences containing a given Freebase entity (mid).
   * Serializable and memory efficient for doing quick experiments.
  public static class EntityMentions implements Serializable {
    private static final long serialVersionUID = 4084640528229989977L;

    private String mid;
    private EfficientUuidList hashesOfSentencesContaining;
    
    public EntityMentions(String mid) {
      this.mid = mid;
      this.hashesOfSentencesContaining = new EfficientUuidList(16);
    }
    
    public String getMid() {
      return mid;
    }
    
    public void add(CluewebLinkedSentence sent) {
      byte[] h = sent.hash();
      this.hashesOfSentencesContaining.add(h);
    }
    
    public UUID getMention(int i) {
      return hashesOfSentencesContaining.get(i);
    }
    
    public int numMentions() {
      return hashesOfSentencesContaining.size();
    }
  }
   */
  
  /**
   * Take a bunch of possibly overlapping files of sentences and put them
   * into a single CoNLL-X file for parsing and tagging.
   * 
   * Additionally a stand-off file listing the murmur3_128 hashes of the markup for
   * each sentence in the CoNLL-X output file (generated with {@link CluewebLinkedSentence#hash()}).
   * This can be used as a key to store parses in a stand-off map.
   */
  public static class PrepareSentencesForParsey implements AutoCloseable {

    // Many lines for every sentence
    private BufferedWriter outputConll;

    // One line for every sentence
    private BufferedWriter outputHashes;

    // One line for every sentence
    // mention := <mid> <space> <startTokOffset> <dash> <endTokOffset>
    // line := <mention> (<tab> <mention>)*
    private BufferedWriter outputMentionLocs;
//    // If true then output one token per line using
//    // B-<mid>, I, and O tags. Otherwise use format above.
//    private boolean mentionLocsConllMode = true;
    
    // Looks like sentences.txt (contains xuchen/clueweb markup) but only contains sentences
    // which this class has had add called for. This is needed to ensure that
    // (conll, hashes, mentionLocs, markup) are all perfectly parallel.
    private BufferedWriter outputMarkup;
    
    private Counts<String> ec = new Counts<>();
    
    public PrepareSentencesForParsey() {
    }

    public void setOutput(File outputConll, File outputHashes, File outputMentionLocs, File outputMarkup) throws IOException {
      if (this.outputConll != null)
        close();
      this.outputConll = FileUtil.getWriter(outputConll);
      this.outputHashes = FileUtil.getWriter(outputHashes);
      this.outputMentionLocs = FileUtil.getWriter(outputMentionLocs);
      this.outputMarkup = FileUtil.getWriter(outputMarkup);
    }

    @Override
    public void close() throws IOException {
      ec.increment("close");
      if (outputConll == null) {
        ec.increment("close/neverOpened");
      } else {
        outputConll.close();
        outputHashes.close();
        outputMentionLocs.close();
        outputMarkup.close();
      }
      Log.info("counts: " + ec);
    }
    
    public void add(CluewebLinkedSentence sent) throws IOException {
      List<SegmentedTextAroundLink> tx = sent.getTextTokenized();
      ec.increment("sent/output");
      
      // Hash of markup
      String h = sent.hashHex();
      outputHashes.write(h);
      outputHashes.newLine();
      
      // Markup
      outputMarkup.write(sent.getMarkup());
      outputMarkup.newLine();
      
      // Mention Locations
      boolean first = true;
      for (int i = 0; i < tx.size(); i++) {
        SegmentedTextAroundLink txi = tx.get(i);
        if (txi.hasLink()) {
          String mid = txi.getLink().getMid(sent.getMarkup());
          IntPair tokLoc = txi.getTokLoc();
//          assert 0 <= tokLoc.first && tokLoc.first < tokLoc.second : "tokLoc=" + tokLoc + " txi=" + txi;
          if (!(0 <= tokLoc.first && tokLoc.first < tokLoc.second)) {
            Log.info("WARNING: tokLoc=" + tokLoc + " txi=" + txi);
            ec.increment("mention/bogus");
            continue;
          }
          if (!first)
            outputMentionLocs.write('\t');
          first = false;
          outputMentionLocs.write(mid + " " + tokLoc.first + "-" + tokLoc.second);
          ec.increment("mention/output");
        }
      }
      outputMentionLocs.newLine();
      
      // Conll tokens
      int tokIdx = 1;
      for (SegmentedTextAroundLink st : tx) {
        for (String tok : st.allTokens()) {
          outputConll.write(tokIdx + "\t" + tok);
          for (int j = 0; j < 8; j++)
            outputConll.write("\t_");
          outputConll.newLine();
          tokIdx++;
          ec.increment("token/output");
        }
      }
      // Sentences end with an empty line
      outputConll.newLine();
      
//      // Tokenize
//      // http://nlp.stanford.edu/nlp/javadoc/javanlp/edu/stanford/nlp/process/PTBTokenizer.html
//      List<String> toks = new ArrayList<>();
//      String options = null;
//      String source = sent.getText();
//      PTBTokenizer<CoreLabel> tok = new PTBTokenizer<>(new StringReader(source), new CoreLabelTokenFactory(), options);
//      while (tok.hasNext())
//        toks.add(tok.next().word().toLowerCase());
//      
//      // CoNLL-X is 10 columns; 1-indexed id, word, then 8 "_"
//      // Sentences end with an empty line
//      for (int i = 0; i < toks.size(); i++) {
//        outputConll.write((i+1) + "\t" + toks.get(i));
//        for (int j = 0; j < 8; j++)
//          outputConll.write("\t_");
//        outputConll.newLine();
//      }
//      // Sentences end with an empty line
//      outputConll.newLine();
    }
  }
  
  /**
   * Old way: create one big [raw.conll, hashes.txt, sentences.txt] package
   * New way: create one dir per mid
   */
  public static void convertPerEntitySentencesToConll(ExperimentProperties config) throws Exception {
    File sentencesDir = config.getExistingDir("sentencesDir");
    File outputDir = config.getOrMakeDir("outputDir");

    List<File> keep = new ArrayList<>();
    for (File f : sentencesDir.listFiles())
      if (f.getName().startsWith(ExtractRelevantSentence.MID_SENT_FILE_PREFIX))
        keep.add(f);
    Log.info("found " + keep.size() + " sentence files to output");

//    // Sort and dedup all the sentences
//    File sentencesPreFilter = new File(outputDir, "sentences-preFilter.txt");
//    String command = "zcat " + sentencesDir.getPath()
//        + "/" + ExtractRelevantSentence.MID_SENT_FILE_PREFIX + "*.gz"
//        + " | sort -u >" + sentencesPreFilter.getPath();
//    Log.info("sorting and deduping sentences");
//    System.out.println(command);
//    ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
//    Process proc = pb.start();
//    int r = proc.waitFor();
//    if (r != 0)
//      throw new RuntimeException();
    
    // Go through the sorted+uniq sentence and output conll and a hash/key
    int maxSentLength = config.getInt("maxSentLength", 80);
    try (PrepareSentencesForParsey p = new PrepareSentencesForParsey()) {
      for (File dups : FileUtil.find(sentencesDir, "glob:**/sentences-containing-*.gz")) {
        Log.info("removing duplicate sentences in: " + dups.getPath());
        
        String pre = "sentences-containing-";
        String suf = ".txt.gz";
        String nam = dups.getName();
        assert nam.startsWith(pre) && nam.endsWith(suf) : "nam=" + nam;
        String mid = nam.substring(pre.length(), nam.length() - suf.length());
        File parent = new File(outputDir, mid);
        assert !parent.isDirectory();
        parent.mkdirs();

        // Remove duplicates (less parsing work)
        File uniq = new File(parent, "sentences-preFilter.txt");
        String command = "zcat " + dups.getPath() + " | sort -u >" + uniq.getPath();
        System.out.println(command);
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
        Process proc = pb.start();
        int r = proc.waitFor();
        if (r != 0)
          throw new RuntimeException();

        // Write out all the data which passes the sentence length filter
        File outputConll = new File(parent, "raw.conll");
        File outputHashes = new File(parent, "hashes.txt");
        File outputMentionLocs = new File(parent, "mentionLocs.txt");
        File outputMarkup = new File(parent, "sentences.txt");
        p.setOutput(outputConll, outputHashes, outputMentionLocs, outputMarkup);

        Log.info("computing hashes and generating CoNLL-X for sentences: " + uniq.getPath());
        try (ValidatorIterator iter = new ValidatorIterator(uniq, maxSentLength)) {
          while (iter.hasNext()) {
            CluewebLinkedSentence sent = iter.next();
            p.add(sent);
          }
        }
      }
    }
  }
  
  /**
   * Reads in all the parses for sentences in a cluster like rare4 at construction.
   * Has a method to read the sentences in a given mid -> list of mentions file
   * and re-rank them.
   * 
   * @deprecated
   */
  public static class EntityMentionRanker {
    
    static int MAX_SENT_LENGTH = 60;
    static boolean MUST_HAVE_NSUBJ = true;
    
    private ParsedSentenceMap parsedSentences;
    private ComputeIdf df;
    
    // This is for the distsup interestingness score
    private FeatExData fed;
    private File outputDir;   // features and vw scores are written out here
    
    /**
     * hashes and conll are files with the (abstract) indices.
     * @param hashes contains one entry (and line) per sentence, generated by {@link CluewebLinkedSentence#hash()}
     * @param conll contains one entry (and many lines) per sentence
     * @see PrepareSentencesForParsey
     */
    public EntityMentionRanker(FeatExData fed, File hashes, File conll, File outputDir) throws Exception {
      this.df = fed.df;
      this.fed = fed;
      this.outputDir = outputDir;
      this.parsedSentences = new ParsedSentenceMap(hashes, conll, new MultiAlphabet());
    }

    static class ScoredPassage {
      CluewebLinkedSentence sent;
      DepNode[] parse;
      List<Feat> score;
      
      public ScoredPassage(CluewebLinkedSentence sent, DepNode[] parse) {
        this.sent = sent;
        this.parse = parse;
        this.score = new ArrayList<>();
      }
      
      public CluewebLinkedSentence getSentence() {
        return sent;
      }

      public List<Feat> getScoreReason() {
        return score;
      }

      public double getScore() {
        double s = Feat.sum(score);
        assert !Double.isNaN(s);
        assert Double.isFinite(s);
        return s;
      }

      public static final Comparator<ScoredPassage> BY_SCORE_DESC = new Comparator<ScoredPassage>() {
        @Override
        public int compare(ScoredPassage o1, ScoredPassage o2) {
          double s1 = o1.getScore();
          double s2 = o2.getScore();
          if (s1 > s2)
            return -1;
          if (s2 > s1)
            return +1;
          return 0;
        }
      };
    }
    
    /**
     * Returns a list which has the same length as sentences.
     * Each value is a score for how likely this sentence is to mention a fact about the entity for the given mid.
     * 
     * The sentence score is a max over all mention scores in the sentence.
     * The distsup model predicts whether a particular pair of mentions are likely to evoke a fact in the KB.
     */
//    private List<Pair<SentenceInterestingnessFeatures, Double>> scoreByDistSup(String mid, List<CluewebLinkedSentence> sentences, String key) {
    private DoubleArrayList scoreByDistSup(String mid, List<CluewebLinkedSentence> sentences) {
      // Each of these files will have one line per mention pair
      String key = mid.replace("/m/", "m.");
      assert key.indexOf('/') < 0;
      File feats = new File(outputDir, key + "-features.vw");
      File scores = new File(outputDir, key + "-scores.vw");
      Log.info("writing features to=" + feats.getPath());
      // This stores instances, keys should be in the order of the given list.
      MultiMap<CluewebLinkedSentence, IntPair> instances = new MultiMap<>();
      try (BufferedWriter fvw = FileUtil.getWriter(feats)) {
        for (CluewebLinkedSentence sent : sentences) {
          int[][] dbpediaIds = fed.getDbpediaIds(sent);
          // Loop over all mentions of the given entity (by mid)
          for (int link = sent.indexOfMid(mid); link >= 0; link = sent.indexOfMid(mid, link+1)) {
            SentenceInterestingnessFeatures f = new SentenceInterestingnessFeatures(sent, dbpediaIds, link, fed);
            List<Feat> sentFeats = f.getFeatures();
            // Loop over all other mentions in the sentence
            for (int j = 0; j < sent.numLinks(); j++) {
              String jmid = sent.getLink(j).getMid(sent.getMarkup());
              if (mid.equals(jmid))
                continue;
              List<Feat> fs = f.getMentionFeatures(j);
              fs.addAll(sentFeats);

              // Output feats
              DbpediaDistSup.writeVwInstance(fvw, null, fs);

              // Output instance
              instances.add(sent, new IntPair(link, j));
            }
          }
        }
      } catch (Exception e) {
        throw new RuntimeException("problem writing out features", e);
      }
      
      // Run vw
      String[] command = new String[] {
          "vw",
          "-t",
          "-i", "/home/travis/code/data/clueweb09-freebase-annotation/gen-for-entsum/model-qs.vw",
//          "-p", scores.getPath(),
          "-r", scores.getPath(),
          "-d", feats.getPath(),
      };
      Log.info("calling vw with: " + Arrays.toString(command));
      ProcessBuilder pb = new ProcessBuilder(command);
      try {
        Process p = pb.start();
        int ret = p.waitFor();
        Log.info("vw returned ret=" + ret);
        if (ret != 0)
          throw new RuntimeException("ret=" + ret);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      
      // Read in predictions and reduce with max
      Log.info("reading nsent=" + sentences.size()
          + " ninst=" + instances.numEntries()
          + " results from=" + scores.getPath());
      DoubleArrayList max = new DoubleArrayList();
      try (BufferedReader r = FileUtil.getReader(scores)) {
        for (CluewebLinkedSentence sent : sentences) {
          List<IntPair> inst = instances.get(sent);
          ArgMax<IntPair> mm = new ArgMax<>();
          for (IntPair i : inst) {
            String line = r.readLine();
            double s = Double.parseDouble(line);
            mm.offer(i, s);
          }
          max.add(mm.getBestScore());
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      
      return max;
    }
    
    public static final Comparator<Double> DOUBLE_ASC = new Comparator<Double>() {
      @Override
      public int compare(Double o1, Double o2) {
        if (o1 < o2)
          return -1;
        if (o1 > o2)
          return +1;
        return 0;
      }
    };

    public List<ScoredPassage> rank(String mid, File mentionsOfGivenEntity, int maxSentLength) throws IOException {
      Log.info("reading sentences from " + mentionsOfGivenEntity.getPath());
      try (ValidatorIterator iter = new ValidatorIterator(mentionsOfGivenEntity, maxSentLength)) {
        List<CluewebLinkedSentence> l = iter.toList();
        return rank(mid, l);
      }
    }
    public List<ScoredPassage> rank(String mid, List<CluewebLinkedSentence> mentionsOfGivenEntity) {
      Log.info("scoring " + mentionsOfGivenEntity.size() + " sentences");
      
      // Compute features for the input sentences
      DoubleArrayList distsup = scoreByDistSup(mid, mentionsOfGivenEntity);
      

      // DEBUG: check the distsup scores
      OrderStatistics<Double> os = new OrderStatistics<>();
      for (int i = 0; i < distsup.size(); i++)
        os.add(distsup.get(i));
      System.out.println("distsup default: " + os.getOrdersStr(OrderStatistics.DEFAULT_ORDERS, DOUBLE_ASC));
      System.out.println("distsup extreme: " + os.getOrdersStr(OrderStatistics.EXTREME_ORDERS, DOUBLE_ASC));

      
      List<ScoredPassage> s = new ArrayList<>();
      for (int i = 0; i < mentionsOfGivenEntity.size(); i++) {
        CluewebLinkedSentence sent = mentionsOfGivenEntity.get(i);
        UUID h = sent.hashUuid();
        DepNode[] parse = parsedSentences.getParse(h);
        assert parse != null : "h=" + h + " nParsed=" + parsedSentences.numParses()
            + " sent=" + sent.getMarkup();
        ScoredPassage sp = new ScoredPassage(sent, parse);
        double[] st = scoreTokens(mid, parse, sent, parsedSentences.getAlph(), df);
        
        List<SegmentedTextAroundLink> segs = sp.sent.getTextTokenized();
        int[] depths = DepNode.depths(parse);
        int[] heads = DbpediaDistSup.findMentionHeads(segs, parse);
        ArgMin<Integer> dm = new ArgMin<>();
        for (int hh : heads)
          dm.offer(hh, depths[hh]);
        int md = depths[dm.get()];
        assert md > 0;
        
        double s1 = max(st);
        double s2 = avg(st);
        double s3 = distsup.get(i)/100d;
        double s4 = 2d/(1+md);

        sp.score.add(new Feat("bestTrigger", s1));
        sp.score.add(new Feat("avgTrigger", s2));
        sp.score.add(new Feat("distsup", s3));
        sp.score.add(new Feat("minDepth", s4));
        sp.score.add(new Feat("prod", (1 + s1) * (1 + s2) * (1 + s3) * (1 + s4)).rescale("good", 4));
        
        s.add(sp);
      }
      Collections.sort(s, ScoredPassage.BY_SCORE_DESC);
      return s;
    }
    
    private static double max(double[] items) {
      double m = items[0];
      for (int i = 1; i < items.length; i++)
        m = Math.max(m, items[i]);
      return m;
    }

    private static double avg(double[] items) {
      double s = 0;
      for (int i = 0; i < items.length; i++)
        s += items[i];
      return s / items.length;
    }
    
    /** Baseline: max_{t in tokens} idf(t) / (k + depDist(t)) */
    private double[] scoreTokens(String mid, DepNode[] parse, CluewebLinkedSentence sent, MultiAlphabet a, ComputeIdf df) {
      
      // 1) Find the relevant mention (location)
      int[] tokenDepths = DepNode.depths(parse);
      ArgMin<Integer> shallowest = new ArgMin<>();
      BitSet partOfMention = new BitSet(parse.length);
      for (SegmentedTextAroundLink st : sent.getTextTokenized()) {
        if (!st.hasLink())
          continue;
        if (!mid.equals(st.getMid()))
          continue;
        // Choose the head token as the shallowest node
        for (Pair<Integer, String> p : st.getLinkTokensGlobalIndexed()) {
          partOfMention.set(p.get1());
          int d = tokenDepths[p.get1()];
          shallowest.offer(p.get1(), d);
        }
      }
      
      // 2) Compute the distance to the entity head
      double k = 2;
      int source = shallowest.get();
      int[] dists = DepNode.distances(source, parse);
      double[] scores = new double[parse.length];
      for (int i = 0; i < dists.length; i++) {
        if (partOfMention.get(i))
          continue;
        String w = a.word(parse[i].word);
        scores[i] = df.idf(w) / (k + dists[i]);
      }
      return scores;
    }
  }
  
  public static void testScoring(ExperimentProperties config) throws Exception {
    File sp = new File("/home/travis/code/data/clueweb09-freebase-annotation/gen-for-entsum");
    
    File outputDir = config.getOrMakeDir("outputDir");
    File mentionStringFile = new File(outputDir, "mid-to-mention-strings.txt");

    File hashes = config.getExistingFile("hashes", new File(sp, "parsed-sentences-rare4/hashes.txt"));
    File conll = config.getExistingFile("conll", new File(sp, "parsed-sentences-rare4/parsed.conll"));
    File fedFile = new File("../data/clueweb09-freebase-annotation/gen-for-entsum/feature-extracted/fed.jser");
    FeatExData fed = (FeatExData) FileUtil.deserialize(fedFile);
    EntityMentionRanker emr = new EntityMentionRanker(fed, hashes, conll, outputDir);
    
    // Make sure this matches is no higher than the value specified when
    // PrepareSentencesForParsey was run or it will seem like parses are missing!
    int maxSentLength = config.getInt("maxSentLength", 80);
    
    // Keys are mids and values are 
    Map<String, Counts<String>> mid2mentions = new HashMap<>();
    
    for (File sentences : FileUtil.find(new File(sp, "sentences-rare4"), "glob:**/sentences-containing-*")) {
      String[] a = sentences.getName().split("\\W+");
      assert a.length == 6;
      String mid = "/m/" + a[3];
      Counts<String> mentionStrings = new Counts<>();
      Object old = mid2mentions.put(mid, mentionStrings);
      assert old == null;
      
//      if (mid.hashCode() % 10 != 0)
//        continue;
      
      Log.info("working on mid=" + mid);
      List<ScoredPassage> ranked = emr.rank(mid, sentences, maxSentLength);
      int k = 100, taken = 0;
      double cosineThresh = config.getDouble("cosineThresh", 0.5);
      DeduplicatingIterator<ScoredPassage> iter = new DeduplicatingIterator<>(
          ranked.iterator(), ScoredPassage::getSentence, fed.df, cosineThresh);
      
      File rf = new File(outputDir, "results-" + a[3] + ".txt");
      Log.info("writing k=" + k + " results to=" + rf.getPath());
      try (BufferedWriter w = FileUtil.getWriter(rf)) {
        while (iter.hasNext() && taken < k) {
          ScoredPassage s = iter.next();
          w.write("reason: " + s.getScoreReason().toString());
          w.newLine();
          w.write("markup: " + s.sent.getMarkup());
          w.newLine();
//          w.write("words: " + StringUtils.join(" ", s.sent.getAllWords(new ArrayList<>(), false)));
          w.write("words: " + s.sent.getResultsHighlighted(mid));
          w.newLine();
          w.newLine();
          taken++;
          
          for (String ms : s.sent.getMentionStrings(mid))
            mentionStrings.increment(ms);
        }
      }
    }
    
    int k = 5;
    Log.info("writing out mention strings to " + mentionStringFile.getPath());
    try (BufferedWriter w = FileUtil.getWriter(mentionStringFile)) {
      for (String mid : mid2mentions.keySet()) {
        Counts<String> ms = mid2mentions.get(mid);
        List<String> names = ms.getKeysSortedByCount(true);
        if (names.size() > k)
          names = names.subList(0, k);
        int cc = 0;
        for (String n : names) {
          int c = ms.getCount(n);
          cc += c;
          w.write(mid + "\t" + n + "\t" + c);
          w.newLine();
        }
        if (cc < ms.getTotalCount()) {
          w.write(mid + "\t<other>\t" + (ms.getTotalCount()-cc));
          w.newLine();
        }
      }
    }
    
    Log.info("done");
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    String m = config.getString("mode");
    Log.info("starting, mode=" + m);
    switch (m.toLowerCase()) {
    case "split":
      buildTrainDevTestSplit(config);
      break;
    case "sentences":
      extractSentencesForEachMid(config);
      break;
    case "cwsent2conll":
      convertPerEntitySentencesToConll(config);
      break;
    case "testscoring":
      testScoring(config);
      break;
    default:
      throw new RuntimeException("unknown command=" + m);
    }
    Log.info("done");
  }

}
