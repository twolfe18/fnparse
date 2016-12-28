package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;

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

import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloConfig;
import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.map.IntIntHashMap;

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
 * @author travis
 */
public class FeatureCardinalityEstimator implements Serializable {
  private static final long serialVersionUID = 2268949277518188236L;

  enum FreqMode {
    COMM,
    TOK,
  }
  
  private IntIntHashMap term2freq;
  private FreqMode mode;
  private int signatureNumBits;
  
  // 20 bits ~= 1M keys * (2 * 4 B/int) = 8MB on disk, or maybe 12MB in a hash table
  public FeatureCardinalityEstimator() {
    this(20, FreqMode.TOK);
  }

  public FeatureCardinalityEstimator(int nBits, FreqMode mode) {
    this.mode = mode;
    this.signatureNumBits = nBits;
    this.term2freq = new IntIntHashMap();
  }
  
  public FreqMode getMode() {
    return mode;
  }

  /**
   * @param serializeTo Uses java serialization to save here
   * @param everyThisManySeconds
   * @throws TableNotFoundException 
   * @throws AccumuloSecurityException 
   * @throws AccumuloException 
   */
  public void scanAccumulo(File serializeTo, double everyThisManySeconds) throws TableNotFoundException, AccumuloException, AccumuloSecurityException {
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
          int fh = getHash(curFeat.toString());
          int c = term2freq.getWithDefault(fh, 0);
          if (curCount > c)
            term2freq.put(fh, curCount);
          
          // Update for new run
          curCount = 0;
          curFeat = new Text(e.getKey().getRow());
        }
        
        curCount++;
        totalRows++;
        assert curCount > 0 : "overflow";
        assert totalRows > 0 : "overflow";
        
        if (curCount % 500 == 0 && tm.enoughTimePassed(everyThisManySeconds)) {
          int rps = (int) Math.floor((totalRows/1000d) / tm.secondsSinceFirstMark());
          Log.info("numTerms=" + (term2freq.size()/(1<<10)) + "K"
              + " curFeat=" + curFeat.toString()
              + " curCount=" + curCount
              + " totalRows=" + (totalRows/(1<<20)) + "M"
              + " kRowPerSec=" + rps
              + "\n" + Describe.memoryUsage()
              + "\nsample freqs: " + showSampleFreqs()
              + "\nsaving to=" + serializeTo.getPath());
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

    sortByFreqUpperBoundDesc(s);

    StringBuilder sb = new StringBuilder();
    for (String f : s) {
      if (sb.length() > 0)
        sb.append(' ');
      int c = getFreq(f);
      sb.append("freq(" + f + ")=" + c);
    }
    return sb.toString();
  }
  
  public int getHash(String feature) {
    int h = ReversableHashWriter.onewayHash(feature);
    h &= (1<<signatureNumBits)-1; // mask
    return h;
  }
  
  public int getFreq(String feature) {
    int h = getHash(feature);
    return term2freq.getWithDefault(h, 0);
  }
  
  public void sortByFreqUpperBoundDesc(List<String> feats) {
    Collections.sort(feats, new Comparator<String>() {
      @Override
      public int compare(String o1, String o2) {
        int f1 = getFreq(o1);
        int f2 = getFreq(o2);
        if (f1 < f2)
          return -1;
        if (f1 > f2)
          return +1;
        return 0;
      }
    });
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    FeatureCardinalityEstimator fce = new FeatureCardinalityEstimator(
        config.getInt("nBits"),
        FreqMode.valueOf(config.getString("freqMode", FreqMode.TOK.name())));
    File serializeTo = config.getFile("output");
    double everyThisManySeconds = config.getDouble("interval", 60);
    fce.scanAccumulo(serializeTo, everyThisManySeconds);
    Log.info("done");
  }
}
