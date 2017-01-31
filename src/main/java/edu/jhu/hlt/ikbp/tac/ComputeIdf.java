package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.io.Text;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumulo;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloConfig;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.MultiTimer.TB;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;

/**
 * Offers the document frequency of words and related functionality like IDF(word).
 *
 * TODO Add support for a {@link StringCountMinSketch} based version
 */
public class ComputeIdf implements Serializable {
  private static final long serialVersionUID = -8768250745761524407L;

  // null keys not allowed!
  private HashMap<String, Long> termFreq;
  private StringCountMinSketch termFreqApprox;    // only one of the [exact, approx] versions should be non-null
  private long numDocs;

  public ComputeIdf(File f) throws IOException {
    boolean approx = false;
    boolean exact = false;

    approx |= f.getName().endsWith(".jser");
    approx |= f.getName().endsWith(".jser.gz");

    exact |= f.getName().endsWith(".tsv");
    exact |= f.getName().endsWith(".txt");

    if (!(exact || approx))
      throw new IllegalArgumentException("can't determine type of: " + f.getPath());

    if (exact) {
      termFreq = new HashMap<>();
      numDocs = 0;
      addFromDisk(f);
    } else {
      Log.info("loading count-min sketch from " + f.getPath());
      //        termFreqApprox = (StringCountMinSketch) FileUtil.deserialize(f);
      //        numDocs = -1; // TODO
      ComputeIdf c = (ComputeIdf) FileUtil.deserialize(f);
      termFreqApprox = c.termFreqApprox;
      numDocs = c.numDocs;
      assert termFreqApprox != null;
      assert numDocs > 0;
    }
  }

  /** Exact counting constructor */
  public ComputeIdf() {
    this.termFreq = new HashMap<>();
    this.numDocs = 0;
  }

  /** Approximate counting constructor */
  public ComputeIdf(int nhash, int logb) {
    Log.info("nhash=" + nhash + " logb=" + logb);
    boolean conservativeUpdates = true;
    this.termFreqApprox = new StringCountMinSketch(nhash, logb, conservativeUpdates);
    this.numDocs = 0;
  }
  
  public int freq(String t) {
    long a;
    if (termFreq != null) {
      a = termFreq.getOrDefault(t, 1L);
    } else {
      a = Math.max(1L, termFreqApprox.apply(t, false));
      a = Math.min(a, numDocs);
    }
    assert a <= Integer.MAX_VALUE;
    return (int) a;
  }
  
  public long numDocs() {
    return numDocs;
  }

  public double idf(String t) {
    assert numDocs > 0;
    long c = freq(t);
    assert c > 0;
    assert c <= numDocs;
    return Math.log(numDocs / c);
  }

  public List<String> importantTerms(StringTermVec a, int k) {
    return importantTerms(a, k, false);
  }
  public List<String> importantTerms(StringTermVec a, int k, boolean debug) {
    List<Pair<String, Double>> t = new ArrayList<>();
    for (Entry<String, Double> tf : a) {
      double w = idf(tf.getKey());
      double s = tf.getValue() * w;
      t.add(new Pair<>(tf.getKey(), s));
      if (debug)
        Log.info(String.format("%.16s tf=%d idf=%.2f tfidf=%.2f", tf.getKey(), tf.getValue(), w, s));
    }
    if (debug)
      Log.info("numTerms=" + t.size());
    Collections.sort(t, new Comparator<Pair<String, Double>>() {
      @Override
      public int compare(Pair<String, Double> a, Pair<String, Double> b) {
        double s1 = a.get2();
        double s2 = b.get2();
        if (s1 > s2)
          return -1;
        if (s1 < s2)
          return +1;
        return 0;
      }
    });
    if (debug) {
      for (Pair<String, Double> x : t)
        Log.info("after sorting: " + x);
    }
    if (k > t.size())
      k = t.size();
    double prevScore = 0;
    List<String> p = new ArrayList<>(k);
    if (debug)
      Log.info("k=" + k);
    for (int i = 0; i < k; i++) {
      Pair<String, Double> term = t.get(i);
      //        Log.info(term);
      assert i == 0 || term.get2() <= prevScore;
      prevScore = term.get2();
      if (term.get2() < 1e-4)
        break;
      p.add(term.get1());
    }
    //      System.out.println();
    return p;
  }

  public double tfIdfCosineSim(StringTermVec a, StringTermVec b) {

    double ssa = 0;
    double na = a.getTotalCount();
    Map<String, Double> tfa_idf = new HashMap<>();
    for (Entry<String, Double> word : a) {
      double idf = Math.sqrt(idf(word.getKey()));
      double tfa = word.getValue() / na;
      double sa = tfa * idf;
      if (tfa * idf > 0) {
        tfa_idf.put(word.getKey(), sa);
        ssa += sa * sa;
      }
    }

    double ssb = 0;
    double nb = b.getTotalCount();
    double dot = 0;
    for (Entry<String, Double> word : b) {
      double sa = tfa_idf.getOrDefault(word.getKey(), 0d);
      double idf = Math.sqrt(idf(word.getKey()));
      double tfb = word.getValue() / nb;
      double sb = tfb * idf;
      dot += sa * sb;
      ssb += sb * sb;
    }

    if (dot == 0)
      return 0;
    if (ssa * ssb == 0)
      return 0;

    return dot / (Math.sqrt(ssa) * Math.sqrt(ssb));
  }

  /**
   * First line is numDocs, thereafter <word> <tab> <docFreq>
   * Only works for string counts.
   */
  public void addFromDisk(File termTabCount) throws IOException {
    if (termFreqApprox != null)
      throw new RuntimeException("implement me");
    Log.info("reading from " + termTabCount.getPath());
    try (BufferedReader r = FileUtil.getReader(termTabCount)) {
      String line0 = r.readLine();
      numDocs += Long.parseUnsignedLong(line0);
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] tc = line.split("\t");
        long c = termFreq.getOrDefault(tc[0], 0l);
        c += Long.parseUnsignedLong(tc[1]);
        termFreq.put(tc[0], c);
      }
    }
    Log.info("done");
  }
  
  private void increment(String word) {
    if (termFreqApprox != null) {
      termFreqApprox.apply(word, true);
    } else {
      long c = termFreq.getOrDefault(word, 0l);
      termFreq.put(word, c+1);
    }
  }

  /**
   * Assumes key.row are "documents" for computing numDocs.
   * In a sense we must make this assumption to have O(1) memory (accumulo only sorts by row) for counting.
   */
  public void count(
      String table,
      Function<Entry<Key, Value>, String> whatToCount,       // can return null if there is nothing to count
      String username, AuthenticationToken password,
      String instanceName, String zookeepers,
      Consumer<Long> callEveryOnceInAWhile) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {

    TimeMarker tm = new TimeMarker();
    TimeMarker tmSlow = new TimeMarker();

    Instance inst = new ZooKeeperInstance(instanceName, zookeepers);
    Connector conn = inst.getConnector(username, password);
    Scanner s = conn.createScanner(table, new Authorizations());
    long numEntries = 0;
    Text prevRow = null;
    for (Entry<Key, Value> e : s) {
      String t = whatToCount.apply(e);

      increment(t);

      if (!e.getKey().getRow().equals(prevRow)) {
        numDocs++;
        prevRow = e.getKey().getRow();
      }
      numEntries++;
      if (tm.enoughTimePassed(5)) {
        Log.info("numEntries=" + numEntries
            + " numKeys=" + (termFreq == null ? "???" : termFreq.size())
            + " numDocs=" + numDocs
            + " curKey=" + t
            + "\t" + Describe.memoryUsage());
        if (tmSlow.enoughTimePassed(4 * 60))
          callEveryOnceInAWhile.accept(numEntries);
      }
    }
    Log.info("done, numEntries=" + numEntries
        + " numKeys=" + (termFreq == null ? "???" : termFreq.size())
        + " numDocs=" + numDocs
        + "\t" + Describe.memoryUsage());
  }

  public void countWordsSimpleAccumulo(
      String namespace,
      Consumer<Long> callEveryOnceInAWhile) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {

    TDeserializer deser = new TDeserializer(SimpleAccumulo.COMM_SERIALIZATION_PROTOCOL);
    TimeMarker tm = new TimeMarker();
    TimeMarker tmSlow = new TimeMarker();
    MultiTimer t = new MultiTimer();
    
    Instance inst = new ZooKeeperInstance(SimpleAccumuloConfig.DEFAULT_INSTANCE, SimpleAccumuloConfig.DEFAULT_ZOOKEEPERS);
    Connector conn = inst.getConnector("reader", new PasswordToken("an accumulo reader"));
    Scanner s = conn.createScanner(SimpleAccumuloConfig.DEFAULT_TABLE, new Authorizations());
    s.fetchColumn(new Text(namespace), new Text("comm_bytes"));
    byte[] value;
    Communication comm;
    for (Entry<Key, Value> e : s) {

      try (TB tb = t.new TB("getBytes")) {
        value = e.getValue().get();
      }
      try (TB tb = t.new TB("deserialize")) {
        comm = new Communication();
        deser.deserialize(comm, value);
      } catch (TException te) {
        te.printStackTrace();
        continue;
      }
      
      try (TB tb = t.new TB("count")) {
        boolean normalizeNumbers = false;
        for (String word : IndexCommunications.terms(comm, normalizeNumbers))
          increment(word);
        numDocs++;
      }
      
      if (tm.enoughTimePassed(5)) {
        Log.info(" numKeys=" + (termFreq == null ? "???" : termFreq.size())
            + " numDocs=" + numDocs
            + "\t" + Describe.memoryUsage());
        if (tmSlow.enoughTimePassed(4 * 60)) {
          Log.info("timer:\n" + t);
          callEveryOnceInAWhile.accept(numDocs);
        }
      }
    }

  }

  public void saveToDisk(File f) throws IOException {
    Log.info("f=" + f.getPath() + " approx=" + (termFreqApprox != null));
    if (termFreqApprox != null) {
      FileUtil.serialize(this, f);
    } else {
      try (BufferedWriter w = FileUtil.getWriter(f)) {
        w.write(numDocs + "\n");
        for (Entry<String, Long> x : termFreq.entrySet()) {
          w.write(x.getKey() + "\t" + x.getValue());
          w.newLine();
        }
      }
    }
  }

  /** reads c2w and writes to a file */
  public static void main(ExperimentProperties config) throws Exception {
    Log.info("starting");
    ComputeIdf idf = new ComputeIdf(
        config.getInt("nhash"),
        config.getInt("logb"));
    String namespace = config.getString("namespace");
    Log.info("namespace=" + namespace);
    File f = config.getFile("output");

//    Function<Entry<Key, Value>, String> k_c2w = e -> e.getKey().getColumnQualifier().toString();
//    String username = config.getString("accumulo.username", "reader");
//    AuthenticationToken password = new PasswordToken(config.getString("accumulo.password", "an accumulo reader"));
//    String instanceName = config.getString("accumulo.instance", SimpleAccumuloConfig.DEFAULT_INSTANCE);
//    String zookeepers = config.getString("accumulo.zookeepers", SimpleAccumuloConfig.DEFAULT_ZOOKEEPERS);

    Consumer<Long> everyOnceInAWhile = numEntries -> {
      try {
        idf.saveToDisk(f);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    };

//    idf.count(AccumuloIndex.T_c2w.toString(), k_c2w, username, password, instanceName, zookeepers, everyOnceInAWhile);

    // Use simpleaccumulo because this should happen BEFORE building c2w,
    // which ideally will be heavily pruned, perhaps 128 words/comm.
    idf.countWordsSimpleAccumulo(namespace, everyOnceInAWhile);
    idf.saveToDisk(f);
    Log.info("done");
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    main(config);
  }
}
