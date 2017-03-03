package edu.jhu.hlt.entsum;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.entsum.CluewebLinkedPreprocess.EntitySplit.DataSetSplit;
import edu.jhu.hlt.entsum.CluewebLinkedSentence.Link;
import edu.jhu.hlt.entsum.CluewebLinkedSentence.ValidatorIterator;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;

public class CluewebLinkedPreprocess {
  
  static class MID {
    byte[] bs;
    int hc;
    
  }
  
  /**
   * First pass: get an approximate frequency for all the entities
   */
  static class EntCounts {
    private StringCountMinSketch midCounts;
    private int maxMidFreq;
    private long nMidObservations;
    
    public EntCounts() {
      int nhash = 12;
      int logb = 22;
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
  
  static class EntitySplit {
    static final Charset UTF8 = Charset.forName("UTF-8");
    
    // Ahead of time measure how frequent each mid is (approximately)
    private EntCounts entCounts;
    private Random rand;
    private Set<String> seenMids = new HashSet<>();
    
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
//          throw new IllegalArgumentException("nDev=" + nDev + " nTest=" + nTest + " all=" +all.size());
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
        s.add(new Pair<>("nTrainUniq", ""+nUniq(train)));
        s.add(new Pair<>("nDevUniq", ""+nUniq(dev)));
        s.add(new Pair<>("nTestUniq", ""+nUniq(test)));
        s.add(new Pair<>("types", ""+types));
        return s;
      }
      
      public static <T> int nUniq(List<T> items) {
        Set<T> u = new HashSet<>();
        u.addAll(items);
        return u.size();
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

    File linkedCluewebRoot = config.getExistingDir("linkedCluewebRoot",
        new File("/home/travis/code/data/clueweb09-freebase-annotation/extractedAnnotation"));
    List<File> fs = FileUtil.find(linkedCluewebRoot, "glob:**/*.gz");
    Log.info("found " + fs.size() + " *.gz files in linkedCluewebRoot=" + linkedCluewebRoot.getPath());
    
    fs = ReservoirSample.sample(fs, 200, new Random(9001));
    
    File outputDir = config.getOrMakeDir("outputDir");
    Log.info("outputDir=" + outputDir.getPath());

    Log.info("starting Pass 1: count entities' mention frequencies...");
    EntCounts ec = new EntCounts();
    for (File f : fs) {
      Log.info("reading " + f.getPath());
      try (ValidatorIterator iter = new ValidatorIterator(f)) {
        while (iter.hasNext()) {
          CluewebLinkedSentence sent = iter.next();
          ec.observe(sent);
        }
      }
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
//    try (BufferedWriter w = FileUtil.getWriter(new File(outputDir, "bucket-mid-frequencies.txt"))) {
//      for (int i = 0; i < tdt.length; i++) {
//        w.write("rare" + i + "\t" + tdt[i].cMin + "\t" + tdt[i].cMax);
//        w.newLine();
//      }
//    }
    
    Log.info("done");
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    buildTrainDevTestSplit(config);
  }

  /*
   * TODO
   * 1) extract counts for all entities, use this to produce a train/dev/test set
   * 2) for each, extract all those sentences, put them into a separate file/DB
   * 3) for each entity file, parse and tag them with PMP
   */
}
