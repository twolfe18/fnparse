package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.entsum.CluewebLinkedPreprocess.EntitySplit.DataSetSplit;
import edu.jhu.hlt.entsum.CluewebLinkedSentence.Link;
import edu.jhu.hlt.entsum.CluewebLinkedSentence.ValidatorIterator;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.PTBTokenizer;

/**
 * 1) extract counts for all entities, use this to produce a train/dev/test set
 * 2) for each, extract all those sentences, put them into a separate file/DB
 * 3) for each entity file, parse and tag them with PMP
 * 4) TODO loop through each entity, lookup sentences, re-rank them
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
    
    public void observe(CluewebLinkedSentence sent) {
      int n = sent.numLinks();
      for (int i = 0; i < n; i++) {
        Link l = sent.getLink(i);
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
//        s.add(new Pair<>("nTrainUniq", ""+nUniq(train)));
//        s.add(new Pair<>("nDevUniq", ""+nUniq(dev)));
//        s.add(new Pair<>("nTestUniq", ""+nUniq(test)));
        s.add(new Pair<>("types", ""+types));
        return s;
      }
      
//      public static <T> int nUniq(List<T> items) {
//        Set<T> u = new HashSet<>();
//        u.addAll(items);
//        return u.size();
//      }
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
    Log.info("found " + fs.size() + " *.gz files in linkedCluewebRoot=" + linkedCluewebRoot.getPath());
    
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
      for (File f : fs) {
        Log.info("reading " + f.getPath());
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
    for (File f : fs) {
      Log.info("reading " + f.getPath());
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
        File f = new File(outputDir, MID_SENT_FILE_PREFIX + mid + suf);
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
      for (File f : fs) {
        Log.info("reading " + f.getPath());
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
  public static class PrepareSentecesForParsey implements AutoCloseable {
    private BufferedWriter outputConll;
    private BufferedWriter outputHashes;
    
    public PrepareSentecesForParsey(File outputConll, File outputHashes) throws IOException {
      this.outputConll = FileUtil.getWriter(outputConll);
      this.outputHashes = FileUtil.getWriter(outputHashes);
    }
    
    public void add(CluewebLinkedSentence sent) throws IOException {
      String h = sent.hashHex();
      outputHashes.write(h);
      outputHashes.newLine();
      
      // Tokenize
      // http://nlp.stanford.edu/nlp/javadoc/javanlp/edu/stanford/nlp/process/PTBTokenizer.html
      List<String> toks = new ArrayList<>();
      String options = null;
      String source = sent.getText();
      PTBTokenizer<CoreLabel> tok = new PTBTokenizer<>(new StringReader(source), new CoreLabelTokenFactory(), options);
      while (tok.hasNext())
        toks.add(tok.next().word().toLowerCase());
      
      // CoNLL-X is 10 columns; 1-indexed id, word, then 8 "_"
      // Sentences end with an empty line
      for (int i = 0; i < toks.size(); i++) {
        outputConll.write((i+1) + "\t" + toks.get(i));
        for (int j = 0; j < 8; j++)
          outputConll.write("\t_");
        outputConll.newLine();
      }
    }

    @Override
    public void close() throws Exception {
      outputConll.close();
      outputHashes.close();
    }
  }
  
  public static void convertPerEntitySentencesToConll(ExperimentProperties config) throws Exception {
    File sentencesDir = config.getExistingDir("sentencesDir");
    List<File> keep = new ArrayList<>();
    for (File f : sentencesDir.listFiles())
      if (f.getName().startsWith(ExtractRelevantSentence.MID_SENT_FILE_PREFIX))
        keep.add(f);
    Log.info("found " + keep.size() + " sentence files to output");

    // Sort and dedup all the sentences
    File outputDir = config.getOrMakeDir("outputDir");
    File sentences = new File(outputDir, "sentences.txt");
    String command = "cat " + sentencesDir.getPath()
        + "/" + ExtractRelevantSentence.MID_SENT_FILE_PREFIX + "*"
        + " | sort -u >" + sentences.getPath();
    Log.info("sorting and deduping sentences");
    System.out.println(command);
    ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
    Process proc = pb.start();
    int r = proc.waitFor();
    if (r != 0)
      throw new RuntimeException();
    
    // Go through the sorted+uniq sentence and output conll and a hash/key
    File outputConll = new File(outputDir, "raw.conll");
    File outputHashes = new File(outputDir, "hashes.txt");
    try (PrepareSentecesForParsey p = new PrepareSentecesForParsey(outputConll, outputHashes)) {
      Log.info("computing hashes and generating CoNLL-X for sentences in " + sentences.getPath());
      try (ValidatorIterator iter = new ValidatorIterator(sentences)) {
        while (iter.hasNext()) {
          CluewebLinkedSentence sent = iter.next();
          p.add(sent);
        }
      }
    }
  }
  
  /**
   * Reads in all the parses for sentences in a cluster like rare4 at construction.
   * Has a method to read the sentences in a given mid -> list of mentions file
   * and re-rank them.
   */
  public static class EntityMentionRanker {
    
    /**
     * All sentences within a given cluster, like rare4.
     * Keys are hashes created by {@link CluewebLinkedSentence#hash()}
     */
    private Map<UUID, Token[]> parsedSentences;
    private MultiAlphabet alph;
    
    // TODO Need a way to retrieve a parse...
    // If there are 100 entities * 1000 sentence/entity * 40 words/sentence * (10*2 + 4*2 + 4 + 4) bytes/word
    // = 4M * 36 = 144MB
    
    /**
     * hashes and conll are files with the (abstract) indices.
     * @param hashes contains one entry (and line) per sentence, generated by {@link CluewebLinkedSentence#hash()}
     * @param conll contains one entry (and many lines) per sentence
     * @see PrepareSentecesForParsey
     */
    public EntityMentionRanker(File hashes, File conll) throws Exception {
      alph = new MultiAlphabet();
      parsedSentences = new HashMap<>();
      try (Token.ConllFileReader iter = new Token.ConllFileReader(conll, alph);
          BufferedReader hashReader = FileUtil.getReader(hashes)) {
        while (iter.hasNext()) {
          Token[] sentence = iter.next();
          String hash = hashReader.readLine();
          UUID h = UUID.fromString(hash);
          Object old = parsedSentences.put(h, sentence);
          assert old == null;
        }
      }
    }
    
    public List<Pair<CluewebLinkedSentence, Token[]>> rank(File mentionsOfGivenEntity) {
      

      throw new RuntimeException("implement me");
    }
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
    default:
      throw new RuntimeException("unknown command=" + m);
    }
    Log.info("done");
  }

}
