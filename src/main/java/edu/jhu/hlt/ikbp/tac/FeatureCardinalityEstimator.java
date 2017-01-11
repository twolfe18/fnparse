package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.io.Text;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;
import com.google.common.hash.Funnels;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloConfig;
import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.FeatureCardinalityEstimator.New.HeavyHitter;
import edu.jhu.hlt.tutils.Beam;
import edu.jhu.hlt.tutils.Beam.Item;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.map.IntIntHashMap;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.CountMinSketch;
import edu.jhu.util.MaxMinSketch;

/**
 * Provides an upper bound on the number of entries corresponding
 * in a given row. For example, if the row is a term in an inverted
 * index, this will give you an upper bound on the number of documents
 * which contains that term.
 *
 * This is compact by approximating the count by buckets.
 * We take the hash of a row id and consider the worst feature
 * which falls into that bucket.
 * 
 * This is specialized to store Tokenization and Communication frequencies.
 * 
 * TODO Consider doing log-log trick and only storing the order of magnitude
 * within a bucket.
 * 
 * @see CountMinSketch alternative (doesn't have exact top-K, but should be considerable smaller)
 *
 * @author travis
 */
public class FeatureCardinalityEstimator implements Serializable {
  private static final long serialVersionUID = 2268949277518188236L;
  public static final Charset utf8 = Charset.forName("UTF-8");
  
  /**
   * Stores both tokenization and document frequencies. Uses a small set of
   * heavy hitters for exact counts on the large features/keys, which everything
   * else going in a {@link MaxMinSketch} (thus heavy hitters receive exact
   * frequencies and everything else is an upper bound).
   */
  public static class New implements Serializable {
    private static final long serialVersionUID = -4269816162008028564L;
    
    public static class HeavyHitter implements Serializable, Comparable<HeavyHitter> {
      private static final long serialVersionUID = -2417638175364354834L;
      byte[] name;  // utf-8
      int tokFreq;
      int docFreq;
      
      public HeavyHitter(String name, int tokFreq, int docFreq) {
        this.name = name.getBytes(utf8);
        this.tokFreq = tokFreq;
        this.docFreq = docFreq;
      }
      
      public double frequency() {
        return tokFreq + docFreq + f1(tokFreq, docFreq);
      }
      
      @Override
      public String toString() {
        return String.format("(HH %s t=%d d=%d)", new String(name, utf8), tokFreq, docFreq);
      }

      @Override
      public int compareTo(HeavyHitter other) {
        return BY_PRIORITY_ASC.compare(this, other);
      }
      
      public static final Comparator<HeavyHitter> BY_PRIORITY_ASC = new Comparator<HeavyHitter>() {
        @Override
        public int compare(HeavyHitter o1, HeavyHitter o2) {
          double p1 = o1.frequency();
          double p2 = o2.frequency();
          if (p1 < p2)
            return -1;
          if (p1 > p2)
            return +1;
          return 0;
        }
      };
    }
    
    public static double f1(IntPair ab) {
      return f1(ab.first, ab.second);
    }
    public static double f1(double a, double b) {
      if (a < 0 || b < 0)
        throw new IllegalArgumentException();
      if (a+b == 0)
        return 0;
      return 2*a*b / (a+b);
    }

    private long numUpdates;    // number of updates is equal the number of features
    private int heavyHitterCapacity;
    private PriorityQueue<HeavyHitter> heavyHitters;
    private MaxMinSketch lightHittersTokFreq;
    private MaxMinSketch lightHittersDocFreq;
    private int numHash;
    private int logBuckets;
    private transient HashFunction hf;
    private transient Map<String, int[]> hhQueryIndex;
    
    public New(int heavyHitterCapacity, int numHash, int logBuckets) {
      this.numHash = numHash;
      this.logBuckets = logBuckets;
      this.heavyHitterCapacity = heavyHitterCapacity;
      this.heavyHitters = new PriorityQueue<>(heavyHitterCapacity);//, HeavyHitter.BY_PRIORITY_ASC);    // causes problems with serialization?
      this.lightHittersTokFreq = new MaxMinSketch(numHash, logBuckets);
      this.lightHittersDocFreq = new MaxMinSketch(numHash, logBuckets);
      this.numUpdates = 0;
    }
    
    public long getNumUpdates() {
      return numUpdates;
    }
    
    public long getBytesUsedEstimate() {
      return getBytesUsedEstimate(heavyHitters == null);
    }
    public long getBytesUsedEstimate(boolean assumeNoMoreUpdates) {
      long b = 0;
      double avgStrLen = 12;
      double ptrSize = 8;
      
      // heavyHitters PQ
      if (assumeNoMoreUpdates) {
        double hhSize = avgStrLen + 4 + 4;
        b += (long) (heavyHitterCapacity * (hhSize + ptrSize));
      }
      
      // hhQueryIndex
      double hhEntrySize = 2 * avgStrLen + ptrSize + 2*4;
      b += heavyHitterCapacity * hhEntrySize * 1.5;

      // max-min sketches
      b += lightHittersTokFreq.getNumberOfBytes();
      b += lightHittersDocFreq.getNumberOfBytes();

      return b;
    }
    
    /** Saves a little space */
    public void noMoreUpdates() {
      if (hhQueryIndex == null)
        buildHHQueryIndex();
      heavyHitters = null;
    }
    
    public void update(String feature, IntPair tokDocFreq) {
      update(feature, tokDocFreq.first, tokDocFreq.second);
    }
    
    public void update(String feature, int tokFreq, int docFreq) {
      if (heavyHitters == null)
        throw new IllegalStateException("did you call noMoreUpdates?");

      numUpdates++;
      HeavyHitter hhNew = new HeavyHitter(feature, tokFreq, docFreq);
      
      // Put into HH
      if (heavyHitters.size() < heavyHitterCapacity) {
        heavyHitters.offer(hhNew);
        hhQueryIndex = null;
        return;
      }
      
      // Maybe evict and put lighter hitter in sketch
      HeavyHitter lightHitter;
      HeavyHitter hhOld = heavyHitters.peek();
      if (hhOld.frequency() < hhNew.frequency()) {
        lightHitter = hhOld;
        heavyHitters.poll();
        heavyHitters.offer(hhNew);
        hhQueryIndex = null;
      } else {
        lightHitter = hhNew;
      }
      
      if (hf == null)
        hf = Hashing.goodFastHash(numHash * logBuckets);
      byte[] keyHash = hf.hashBytes(lightHitter.name).asBytes();
      lightHittersTokFreq.update(keyHash, lightHitter.tokFreq);
      lightHittersDocFreq.update(keyHash, lightHitter.docFreq);
    }
    
    void buildHHQueryIndex() {
      hhQueryIndex = new HashMap<>(heavyHitters.size());
      for (HeavyHitter hh : heavyHitters) {
        String key = new String(hh.name, utf8);
        int[] value = new int[] {hh.tokFreq, hh.docFreq};
        Object old = hhQueryIndex.put(key, value);
        assert old == null;
      }
    }
    
    /** @return (tokFreq, docFreq), both upper bounds on their real values */
    public IntPair getFrequency(String feature) {
      if (hhQueryIndex == null)
        buildHHQueryIndex();
      int[] hh = hhQueryIndex.get(feature);
      if (hh != null) {
        assert hh.length == 2;
        return new IntPair(hh[0], hh[1]);
      }
      if (hf == null)
        hf = Hashing.goodFastHash(numHash * logBuckets);
      byte[] keyHash = hf.hashBytes(feature.getBytes(utf8)).asBytes();
      int tf = lightHittersTokFreq.getUpperBoundOnCount(keyHash);
      int df = lightHittersDocFreq.getUpperBoundOnCount(keyHash);
      return new IntPair(tf, df);
    }

    public <T> void sortByFreqUpperBoundAsc(List<T> feats, Function<T, String> view) {
      Collections.sort(feats, new Comparator<T>() {
        @Override
        public int compare(T o1, T o2) {
          double f1 = f1(getFrequency(view.apply(o1)));
          double f2 = f1(getFrequency(view.apply(o2)));
          if (f1 < f2)
            return -1;
          if (f1 > f2)
            return +1;
          return 0;
        }
      });
    }

    public void sortByFreqUpperBoundAsc(List<String> feats) {
      sortByFreqUpperBoundAsc(feats, x -> x);
    }

    public String showFreqUpperBounds(List<String> feats) {
      StringBuilder sb = new StringBuilder();
      for (String f : feats) {
        if (sb.length() > 0)
          sb.append(' ');
        IntPair c = getFrequency(f);
        sb.append("c(" + f + ")=" + c);
      }
      return sb.toString();
    }

    private String showSampleFreqs() {
      List<String> s = new ArrayList<>();
      s.add("pi:american");
      s.add("pi:association");
      s.add("h:Obama");
      s.add("pi:for");
      s.add("pi:the");
      s.add("pi:blavatnik");
      s.add("pb:blavatnik_family");
      s.add("pi:and");
      s.add("pb:service_AAAA");
      s.add("pi:foundation");
      s.add("h:Dworin");
      s.add("h:Gross");
      s.add("h:Alan_Gross");
      s.add("pi:westminster");

      sortByFreqUpperBoundAsc(s);

      return showFreqUpperBounds(s);
    }
  }
  

  enum FreqMode {
    COMM,
    TOK,
  }
  
  private Beam<String> mostFrequent;
  private Map<String, Integer> mostFrequentIndex; // lazily built, same elements as mostFrequent
  
  private IntIntHashMap term2freq;
  private FreqMode mode;
  private int signatureNumBits;
  
  // 20 bits ~= 1M keys * (2 * 4 B/int) = 8MB on disk, or maybe 12MB in a hash table
  public FeatureCardinalityEstimator() {
    this(20, FreqMode.TOK, 1000);
  }

  public FeatureCardinalityEstimator(int nBits, FreqMode mode, int nMostFrequent) {
    Log.info("nBits=" + nBits + " mode=" + mode);
    this.mode = mode;
    this.signatureNumBits = nBits;
    this.term2freq = new IntIntHashMap();
    if (nMostFrequent > 0)
      mostFrequent = Beam.getMostEfficientImpl(nMostFrequent);
  }
  
  public FreqMode getMode() {
    return mode;
  }
  
  /**
   * NOTE: There is no guard in the event where there is overlap
   * between the lines in this files and pre-existing elements
   *
   * @param f should have format: <freq> <tab> <feature>
   */
  public void addFromFile(File f) throws IOException {
    if (f == null || !f.isFile())
      throw new IllegalArgumentException("bad: " + f);
    Log.info("reading from=" + f.getPath());
    int read = 0, updated = 0;
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] ar = line.split("\t", 2);
        int f1 = Integer.parseUnsignedInt(ar[0]);
        int fh = getHash(ar[1]);
        int f0 = term2freq.getWithDefault(fh, 0);
        if (f1 > f0) {
          term2freq.put(fh, f1);
          updated++;
        }
        if (mostFrequent != null) {
          // There is no guard in the event where there is overlap between
          // the lines in this files and pre-existing elements
          mostFrequent.push(ar[1], f1);
          mostFrequentIndex = null;
        }
        read++;
      }
    }
    Log.info("read=" + read + " updated=" + updated);
  }

  /**
   * @param update should accept (feature, (tokFreq, docFreq))
   * @param continuation should accept (currentFeature, totalRowsProcessed) and show progress
   */
  public static void iterateFeatureTokDocCountsViaAccumulo(BiConsumer<String, IntPair> update, Consumer<Pair<String, Long>> continuation, double callContinuationEveryThisManySeconds) throws TableNotFoundException, AccumuloException, AccumuloSecurityException {
    // Bloom filter for tracking docs/feature
    final Funnel<CharSequence> fun = Funnels.stringFunnel(utf8);
    final int expInsertions = 250_000;
    final double fpp = 0.01;
    assert fpp > 0 && 1-fpp > 0;
    BloomFilter<String> curDocSet = BloomFilter.create(fun, expInsertions, fpp);

    TimeMarker tm = new TimeMarker();
    long totalEntries = 0;
    String instance = SimpleAccumuloConfig.DEFAULT_INSTANCE;
    String zookeepers = SimpleAccumuloConfig.DEFAULT_ZOOKEEPERS;
    String username = "reader";
    PasswordToken password = new PasswordToken("an accumulo reader");
    Instance inst = new ZooKeeperInstance(instance, zookeepers);
    Connector conn = inst.getConnector(username, password);
    
    // Iterate over {key=feature, colf="", colq=tokenization, value=???} entries
    try (Scanner s = conn.createScanner(AccumuloIndex.T_f2t.toString(), new Authorizations())) {
      String curFeature = "";
      int curTokCount = 0;
      int curDocCount = 0;

      for (Entry<Key, Value> e : s) {
        String feature = e.getKey().getRow().toString();
        String tokUuid = e.getKey().getColumnQualifier().toString();
        String commUuid = AccumuloIndex.getCommUuidPrefixFromTokUuid(tokUuid);

        if (!curFeature.equals(feature)) {
          // Output old run
          IntPair freq = new IntPair(curTokCount, (int) Math.ceil(curDocCount * (1 - fpp)));
          update.accept(curFeature, freq);
          
          // Update for new run
          curTokCount = 0;
          curDocCount = 0;
          curDocSet = BloomFilter.create(fun, expInsertions, fpp);
          curFeature = feature;
        }
        
        // Update counters
        curTokCount++;
        totalEntries++;
        boolean seen = curDocSet.mightContain(commUuid);
        if (!seen) {
          curDocSet.put(commUuid);
          curDocCount++;
        }
        assert curTokCount > 0 : "overflow";
        assert totalEntries > 0 : "overflow";
        assert curDocCount > 0 : "overflow";
        assert curTokCount >= curDocCount;

        if (tm.enoughTimePassed(callContinuationEveryThisManySeconds))
          continuation.accept(new Pair<>(curFeature, totalEntries));
      }
      
      // Final output
      IntPair freq = new IntPair(curTokCount, (int) Math.ceil(curDocCount * (1 - fpp)));
      update.accept(curFeature, freq);
    }

  }

  /**
   * @param serializeTo Uses java serialization to save here
   * @param everyThisManySeconds
   * @throws TableNotFoundException 
   * @throws AccumuloSecurityException 
   * @throws AccumuloException 
   * 
   * @deprecated use iterateFeatureTokDocCounts instead
   */
  public void addFromAccumulo(File serializeTo, double everyThisManySeconds) throws TableNotFoundException, AccumuloException, AccumuloSecurityException {
    Log.info("starting serializeTo=" + serializeTo.getPath()
        + " everyThisManySeconds=" + everyThisManySeconds);
    
    if (mode == FreqMode.COMM) {
      // prefix of tokUuid is commUuid prefix
      throw new RuntimeException("implement me");
    }

    TimeMarker tm = new TimeMarker();
    long totalRows = 0;
    String instance = SimpleAccumuloConfig.DEFAULT_INSTANCE;
    String zookeepers = SimpleAccumuloConfig.DEFAULT_ZOOKEEPERS;
    String username = "reader";
    PasswordToken password = new PasswordToken("an accumulo reader");
    Instance inst = new ZooKeeperInstance(instance, zookeepers);
    Connector conn = inst.getConnector(username, password);
    try (Scanner s = conn.createScanner(AccumuloIndex.T_f2t.toString(), new Authorizations())) {
      Text curFeat = new Text();
      int curCount = 0;
      for (Entry<Key, Value> e : s) {
        if (e.getKey().compareRow(curFeat) != 0) {
          // Output old feature
          String cf = curFeat.toString();
          int fh = getHash(cf);
          int c = term2freq.getWithDefault(fh, 0);
          if (curCount > c)
            term2freq.put(fh, curCount);
          if (mostFrequent != null) {
            mostFrequent.push(cf, curCount);
            mostFrequentIndex = null;
          }
          
          // Update for new run
          curCount = 0;
          curFeat = new Text(e.getKey().getRow());
        }
        
        curCount++;
        totalRows++;
        assert curCount > 0 : "overflow";
        assert totalRows > 0 : "overflow";
        
        if (curCount % 500 == 0 && tm.enoughTimePassed(everyThisManySeconds)) {
          double sec = tm.secondsSinceFirstMark();
          int nf = term2freq.size();
          int rps = (int) Math.floor((totalRows/1000d) / sec);
          int fps = (int) Math.floor(((double) nf) / sec);
          Log.info("numTerms=" + (term2freq.size()/(1<<10)) + "K"
              + " curFeat=" + curFeat.toString()
              + " curCount=" + curCount
              + " totalRows=" + (totalRows/(1<<20)) + "M"
              + " kRowPerSec=" + rps
              + " featPerSec=" + fps
              + "\n" + Describe.memoryUsage()
              + "\nsample freqs: " + showSampleFreqs()
              + "\nsaving to=" + serializeTo.getPath());
          if (mostFrequent != null) {
            System.out.println("mostFreq.support=" + mostFrequent.size()
                + " mostFreq.minScore=" + mostFrequent.minScore()
                + " mostFreq.maxScore=" + mostFrequent.maxScore());
          }
          FileUtil.serialize(this, serializeTo);
        }
      }
    }
  }
  
  private String showSampleFreqs() {
    List<String> s = new ArrayList<>();
    s.add("pi:american");
    s.add("pi:association");
    s.add("h:Obama");
    s.add("pi:for");
    s.add("pi:the");
    s.add("pi:blavatnik");
    s.add("pb:blavatnik_family");
    s.add("pi:and");
    s.add("pb:service_AAAA");
    s.add("pi:foundation");
    s.add("h:Dworin");
    s.add("h:Gross");
    s.add("h:Alan_Gross");
    s.add("pi:westminster");

    sortByFreqUpperBoundAsc(s);

    return showFreqUpperBounds(s);
  }
  
  public int getHash(String feature) {
    int h = ReversableHashWriter.onewayHash(feature);
    h &= (1<<signatureNumBits)-1; // mask
    return h;
  }
  
  public int getFreqUpperBound(String feature) {
    int h = getHash(feature);
    return term2freq.getWithDefault(h, 0);
  }

  public String showFreqUpperBounds(List<String> feats) {
    StringBuilder sb = new StringBuilder();
    for (String f : feats) {
      if (sb.length() > 0)
        sb.append(' ');
      int c = getFreqUpperBound(f);
      sb.append("c(" + f + ")=" + c);
    }
    return sb.toString();
  }

  public <T> void sortByFreqUpperBoundAsc(List<T> feats, Function<T, String> view) {
    Collections.sort(feats, new Comparator<T>() {
      @Override
      public int compare(T o1, T o2) {
        int f1 = getFreqUpperBound(view.apply(o1));
        int f2 = getFreqUpperBound(view.apply(o2));
        if (f1 < f2)
          return -1;
        if (f1 > f2)
          return +1;
        return 0;
      }
    });
  }
  
  public void sortByFreqUpperBoundAsc(List<String> feats) {
    Collections.sort(feats, new Comparator<String>() {
      @Override
      public int compare(String o1, String o2) {
        int f1 = getFreqUpperBound(o1);
        int f2 = getFreqUpperBound(o2);
        if (f1 < f2)
          return -1;
        if (f1 > f2)
          return +1;
        return 0;
      }
    });
  }
  
  /** returns -1 if this is not one of the most frequent features */
  public int getFreqExactForMostFreq(String feat) {
    if (mostFrequentIndex == null) {
      mostFrequentIndex = new HashMap<>();
      Iterator<Item<String>> iter = mostFrequent.itemIterator();
      while (iter.hasNext()) {
        Item<String> i = iter.next();
        int n = (int) i.getScore();
        assert n >= 0;
        mostFrequentIndex.put(i.getItem(), n);
      }
    }
    return mostFrequentIndex.getOrDefault(feat, -1);
  }
  
  public static void test(ExperimentProperties config) {
    
    System.out.println(HeavyHitter.BY_PRIORITY_ASC.compare(new HeavyHitter("foo", 1, 2), new HeavyHitter("bar", 10, 20)));
    System.out.println(new HeavyHitter("foo", 1, 2).compareTo(new HeavyHitter("bar", 10, 20)));
    
    HeavyHitter hh;
    New n = new New(2, 8, 16);
    n.update("foo", 5, 5);
    System.out.println(n.heavyHitters);
    assert n.heavyHitters.peek().tokFreq == 5;
    
    n.update("bar", 4, 4);
    System.out.println(n.heavyHitters);
    hh = n.heavyHitters.peek();
    assert 2 == n.heavyHitters.size();
    assert "bar".equals(new String(hh.name, utf8));

    n.update("baz", 6, 6);
    System.out.println(n.heavyHitters);
    hh = n.heavyHitters.peek();
    System.out.println(hh);
    assert 2 == n.heavyHitters.size();
    assert "foo".equals(new String(hh.name, utf8));
    
    System.out.println(n.getNumUpdates());
    assert 3 == n.getNumUpdates();
    
    System.out.println(n.getFrequency("foo"));
    System.out.println(n.getFrequency("bar"));
    System.out.println(n.getFrequency("baz"));
    System.out.println(n.getFrequency("quux"));
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    if (config.getBoolean("test", false)) {
      Log.info("running tests then existing");
      test(config);
      return;
    }

    File serializeTo = config.getFile("output");
    double everyThisManySeconds = config.getDouble("interval", 5 * 60);

//    // 250k string * 10 chars * 2 byte/char * 2 b/c hashmap (conservative est.) = 10MB
//    FeatureCardinalityEstimator fce = new FeatureCardinalityEstimator(
//        config.getInt("nBits"),
//        FreqMode.valueOf(config.getString("freqMode", FreqMode.TOK.name())),
//        config.getInt("nMostFrequent", 250_000));
//    fce.addFromAccumulo(serializeTo, everyThisManySeconds);
    
    FeatureCardinalityEstimator.New fcnew = new FeatureCardinalityEstimator.New(
        config.getInt("nMostFrequent", 250_000),
        config.getInt("numHash", 8),
        config.getInt("logBuckets", 16));
    
    boolean assumeCompacted = true;
    Log.info("feature frequency estimates will take up " + (fcnew.getBytesUsedEstimate(assumeCompacted)/(1L<<20)) + " MB");
    
    Consumer<Pair<String, Long>> continuation = p -> {
      Log.info("numEntriesProcessed=" + p.get2()
          + " numFeat=" + fcnew.getNumUpdates()
          + " curFeat=" + p.get1()
          + " savingTo=" + serializeTo.getPath()
          + "\t" + Describe.memoryUsage());
      System.out.println(fcnew.showSampleFreqs());
      FileUtil.serialize(fcnew, serializeTo);
    };
    iterateFeatureTokDocCountsViaAccumulo(fcnew::update, continuation, everyThisManySeconds);

    Log.info("final save");
    fcnew.noMoreUpdates();
    continuation.accept(new Pair<>("<final>", 0L));
    
    Log.info("done");
  }
}
