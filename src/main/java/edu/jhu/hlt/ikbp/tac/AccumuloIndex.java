package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.ClientConfiguration;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.mapreduce.AccumuloInputFormat;
import org.apache.accumulo.core.client.mapreduce.AccumuloOutputFormat;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.concrete.access.FetchCommunicationService;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumulo;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloConfig;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.features.ConcreteMentionFeatureExtractor;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.TriageSearch.EMQuery;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.EntityEventPathExtraction;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.MturkCorefHit;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.ParmaVw;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.ParmaVw.QResultCluster;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.ShowResult;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SitSearchResult;
import edu.jhu.hlt.ikbp.tac.TacKbp.KbpQuery;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.EfficientUuidList;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.MultiTimer.TB;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.hlt.tutils.Weighted;
import edu.jhu.hlt.utilt.AutoCloseableIterator;
import edu.jhu.prim.map.IntDoubleHashMap;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.DiskBackedFetchWrapper;
import edu.jhu.util.TokenizationIter;


/**
 * Mimics the functionality in {@link IndexCommunications}, but while being backed by accumulo.
 * 
 * Tables:
 * name             row           col_fam           col_qual          value
 * ----------------------------------------------------------------------------------
 * f2t              feat          featType          tokUuid           tf(feat,tok)
 * t2f              tokUuid       featType          feat              tf(feat,tok)
 * t2c              tokUuid       NA                NA                commId
 * c2w              commId        NA                word              tf(word,doc)*idf(word) ???
 * 
 * w2df             word          NA                NA                df(word)    # use org.apache.accumulo.core.iterators.user.SummingCombiner
 *
 * NOTE: t2f and f2t contain the SAME DATA but are permutations of the key fields.
 * featType in [entity, deprel, situation] and feat values are things like "h:John" and "X<nsubj<loves>dobj>PER"
 * 
 * 
 * 
 * TODO Switch from UTF8 UUIDs to raw UUIDs!!!
 * 
 * TODO Prune c2w down to only the top-128 or so words by idf.
 * This is strictly for compute tf-idf, as we later use Fetch to get the whole communication.
 * 
 * TODO Remove t2f, which I don't think I ever use.
 * Concerning values, e.g. tf(feat,tok), I'm never going to use that.
 * I compress all of those down to approximations with count-min sketch anyway.
 * Usually I just need to know whether an f-t edge exists, and weights come for free when looking this up.
 *
 * @author travis
 */
public class AccumuloIndex {
  
  public static boolean SHOW_TRIAGE_FEAT_SCORES = false;

  public final static Counts<String> EC = new Counts<>(); // event counts
  public final static MultiTimer TIMER = new MultiTimer();

  public final static byte[] NA = new byte[0];
  public final static String TABLE_NAMESPACE = "twolfe_cag1_index2";
  
  // These have to be made by hand ahead of time
  public final static Text T_f2t = new Text(TABLE_NAMESPACE + "_f2t");
  public final static Text T_t2f = new Text(TABLE_NAMESPACE + "_t2f");
  public final static Text T_t2c = new Text(TABLE_NAMESPACE + "_t2c");
  public final static Text T_c2w = new Text(TABLE_NAMESPACE + "_c2w");
  
  // TODO See if this is worth it
  public static final Text T_w2df = new Text(TABLE_NAMESPACE + "_w2df");


  public static byte[] encodeCount(int count) {
    if (count <= 0)
      throw new IllegalArgumentException("count=" + count + " must be >0");
    int lim = Byte.MAX_VALUE;
    byte b = (byte) (count > lim ? lim : count);
    return new byte[] {b};
  }

  public static int decodeCount(byte[] count) {
    assert count.length == 1;
    int c = (int) count[0];
    assert c > 0;
    return c;
  }

  public static String words(Span s, Tokenization t) {
    StringBuilder sb = new StringBuilder();
    for (int i = s.start; i < s.end; i++) {
      if (i > s.start)
        sb.append('_');
      sb.append(t.getTokenList().getTokenList().get(i).getText());
    }
    return sb.toString();
  }

  /** Returns the set of inserts for this comm, all things we are indexing (across all tables) */
  public static List<Pair<Text, Mutation>> buildMutations(Communication comm) {
    List<Pair<Text, Mutation>> mut = new ArrayList<>();
    CharSequence c = comm.getId();
    byte[] cb = comm.getId().getBytes();

    // c2w
    // TODO Only index the top 128 or so terms in the document
    // This requires building a count-min sketch for document-frequency ahead of time.
    Counts<String> terms = IndexCommunications.terms2(comm);
    for (Entry<String, Integer> t : terms.entrySet()) {
      String w = t.getKey();
      int cn = t.getValue();
      Mutation m = new Mutation(c);
      m.put(NA, w.getBytes(), encodeCount(cn));
      mut.add(new Pair<>(T_c2w, m));
    }


    // t2c
    Map<String, Tokenization> tokMap = new HashMap<>();
    for (Tokenization tok : new TokenizationIter(comm)) {
      CharSequence t = tok.getUuid().getUuidString();
      Mutation m = new Mutation(t);
      m.put(NA, NA, cb);
      mut.add(new Pair<>(T_t2c, m));

      Object old = tokMap.put(tok.getUuid().getUuidString(), tok);
      assert old == null;
    }

    // f2t and t2f
    TokenObservationCounts tokObs = null;
    TokenObservationCounts tokObsLc = null;
    new AddNerTypeToEntityMentions(comm);
    for (EntityMention em : IndexCommunications.getEntityMentions(comm)) {

      /*
       * TODO!!!
       * Don't use the UTF-8 encoding of a UUID!
       * That is 32+4 chars => 36 bytes
       * Use the damn byte[] encoding and it goes down to 16 bytes!
       * That is more than a 2x speedup for free!
       * But it will break all of my code :)
       * Price I should pay...
       */
      byte[] t = em.getTokens().getTokenizationId().getUuidString().getBytes();

      boolean takeNnCompounts = true;
      boolean allowFailures = true;
      String head = IndexCommunications.headword(em.getTokens(), tokMap, takeNnCompounts, allowFailures);
      List<String> feats = IndexCommunications.getEntityMentionFeatures(
          em.getText(), new String[] {head}, em.getEntityType(), tokObs, tokObsLc);
      Counts<String> featsU = new Counts<>();
      for (String f : feats)
        featsU.increment(f);
      for (Entry<String, Integer> e : featsU.entrySet()) {
        byte[] f = e.getKey().getBytes();
        byte[] cn = encodeCount(e.getValue());

        Mutation m_t2f = new Mutation(t);
        m_t2f.put(NA, f, cn);
        mut.add(new Pair<>(T_t2f, m_t2f));

        Mutation m_f2t = new Mutation(f);
        m_f2t.put(NA, t, cn);
        mut.add(new Pair<>(T_f2t, m_f2t));
      }
    }
    return mut;
  }

  
  
  /**
   * Works.
   */
  public static class BuildIndexRegular implements AutoCloseable {
    private Connector conn;
    private Map<Text, BatchWriter> table2writer;
    
    public BuildIndexRegular(Connector conn) {
      this.conn = conn;
      this.table2writer = new HashMap<>();
    }
    
    BatchWriter getWriter(Text table) {
      BatchWriter w = table2writer.get(table);
      if (w == null) {
        try {
          String t = table.toString();
          Log.info("opening writer to " + t);
          BatchWriterConfig c = new BatchWriterConfig();
          w = conn.createBatchWriter(t, c);
          assert w != null;
          table2writer.put(table, w);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      return w;
    }

    @Override
    public void close() throws Exception {
      for (BatchWriter w : table2writer.values())
        w.close();
      table2writer.clear();
    }
    
    public static void main(ExperimentProperties config) throws Exception {
      SimpleAccumuloConfig saConf = SimpleAccumuloConfig.fromConfig(config);
      Log.info("starting with " + saConf);
      SimpleAccumulo sa = new SimpleAccumulo(saConf);
      sa.connect(config.getString("accumulo.username"), new PasswordToken(config.getString("accumulo.password")));
      Connector conn = sa.getConnector();
      TimeMarker tm = new TimeMarker();
      Counts<String> writes = new Counts<>();
      double interval = config.getDouble("interval", 10);
      try (AutoCloseableIterator<Communication> iter = IndexCommunications.getCommunicationsForIngest(config);
          BuildIndexRegular bi = new BuildIndexRegular(conn)) {
        while (iter.hasNext()) {
          Communication comm = iter.next();
          writes.increment("read/comm");
          for (Pair<Text, Mutation> m : buildMutations(comm)) {
            Text table = m.get1();
            BatchWriter w = bi.getWriter(table);
            w.addMutation(m.get2());
            writes.increment("write/" + table.toString());
            if (tm.enoughTimePassed(interval))
              Log.info("written: " + writes);
          }
        }
      }
      Log.info("done, written: " + writes);
    }
  }
  
  
  /**
   * Expects {key=commUuid, value=comm} input.
   */
  public static class BuildIndexReducer extends Reducer<WritableComparable<?>, Writable, Text, Mutation> {
    public final static TDeserializer deser = new TDeserializer(SimpleAccumulo.COMM_SERIALIZATION_PROTOCOL);
    
    public void reduce(WritableComparable<?> key, Iterable<Text> values, Context ctx) {
      for (Text v : values) {
        Communication comm = new Communication();
        try {
          deser.deserialize(comm, v.getBytes());
        } catch (Exception e) {
          e.printStackTrace();
        }
        for (Pair<Text, Mutation> m : buildMutations(comm)) {
          try {
            ctx.write(m.get1(), m.get2());
          } catch (Exception e) {}
        }
      }
    }
  }
  
  /**
   * Doesn't work.
   */
  public static class BuildIndexMR {
    public static void main(ExperimentProperties config) throws ConfigurationException, IOException, InterruptedException, ClassNotFoundException, AccumuloSecurityException {
      Log.info("starting");
      Job job = Job.getInstance();
      job.setJobName(config.getString("jobname", "buildAccIndex"));
      ClientConfiguration cc = new ClientConfiguration()
          .withInstance(config.getString("accumulo.instance"))
          .withZkHosts(config.getString("accumulo.zookeepers"));

      job.setMapperClass(Mapper.class); // identity mapper
      job.setReducerClass(BuildIndexReducer.class);
      
      String principal = config.getString("accumulo.username");
      AuthenticationToken token = new PasswordToken(config.getProperty("accumulo.password"));

      job.setInputFormatClass(AccumuloInputFormat.class);
      AccumuloInputFormat.setZooKeeperInstance(job, cc);
      AccumuloInputFormat.setConnectorInfo(job, principal, token);
      AccumuloInputFormat.setInputTableName(job, config.getString("sourceTable", "simple_accumulo_dev"));

      job.setOutputFormatClass(AccumuloOutputFormat.class);
      AccumuloOutputFormat.setZooKeeperInstance(job, cc);
      AccumuloOutputFormat.setConnectorInfo(job, principal, token);
      AccumuloOutputFormat.setBatchWriterOptions(job, new BatchWriterConfig());
      AccumuloOutputFormat.setCreateTables(job, true);
      
      if (config.getBoolean("simulation", false)) {
        Log.info("setting simulation");
        AccumuloOutputFormat.setSimulationMode(job, true);
      }

      Log.info("submitting");
      job.submit();
      Log.info("monitoring");
      boolean succ = job.monitorAndPrintJob();
      Log.info("done, succ=" + succ);
    }

  }

  
  
  public static class ComputeIdf implements Serializable {
    private static final long serialVersionUID = -8768250745761524407L;

    // null keys not allowed!
    private HashMap<String, Long> termFreq;
    private long numDocs;
    
    public ComputeIdf(File termTabCount) throws IOException {
      this();
      addFromDisk(termTabCount);
    }
    
    public ComputeIdf() {
      this.termFreq = new HashMap<>();
      this.numDocs = 0;
    }
    
    public double idf(String t) {
      long c = termFreq.getOrDefault(t, 1L);
      return Math.log(numDocs / c);
    }

    public List<String> importantTerms(StringTermVec a, int k) {
      return importantTerms(a, k, false);
    }
    public List<String> importantTerms(StringTermVec a, int k, boolean debug) {
      List<Pair<String, Double>> t = new ArrayList<>();
      for (Entry<String, Integer> tf : a) {
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
      for (Entry<String, Integer> word : a) {
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
      for (Entry<String, Integer> word : b) {
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
    
    /**
     * Assumes key.row are "documents" for computing numDocs.
     * In a sense we must make this assumption to have O(1) memory (accumulo only sorts by row) for counting.
     */
    public void count(
        String table,
        Function<Entry<Key, Value>, String> whatToCount,       // can return null if there is nothing to count
        String username, AuthenticationToken password,
        String instanceName, String zookeepers) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {

      TimeMarker tm = new TimeMarker();
      Instance inst = new ZooKeeperInstance(instanceName, zookeepers);
      Connector conn = inst.getConnector(username, password);
      Scanner s = conn.createScanner(table, new Authorizations());
      long numEntries = 0;
      Text prevRow = null;
      for (Entry<Key, Value> e : s) {
        String t = whatToCount.apply(e);
        long c = termFreq.getOrDefault(t, 0l);
        termFreq.put(t, c+1);
        if (!e.getKey().getRow().equals(prevRow)) {
          numDocs++;
          prevRow = e.getKey().getRow();
        }
        numEntries++;
        if (tm.enoughTimePassed(5))
          Log.info("numEntries=" + numEntries + " numKeys=" + termFreq.size() + " numDocs=" + numDocs + " curKey=" + t + "\t" + Describe.memoryUsage());
      }
      Log.info("done, numEntries=" + numEntries + " numKeys=" + termFreq.size() + " numDocs=" + numDocs + "\t" + Describe.memoryUsage());
    }
    
    public void saveToDisk(File f) throws IOException {
      Log.info("f=" + f.getPath());
      try (BufferedWriter w = FileUtil.getWriter(f)) {
        w.write(numDocs + "\n");
        for (Entry<String, Long> x : termFreq.entrySet()) {
          w.write(x.getKey() + "\t" + x.getValue());
          w.newLine();
        }
      }
    }

    /** reads c2w and writes to a file */
    public static void main(ExperimentProperties config) throws Exception {
      Log.info("starting");
      ComputeIdf idf = new ComputeIdf();
      Function<Entry<Key, Value>, String> k_c2w = e -> e.getKey().getColumnQualifier().toString();
      File f = config.getFile("output");
      Log.info("going through accumulo to compute idf");
      String username = config.getString("accumulo.username");
      AuthenticationToken password = new PasswordToken(config.getString("accumulo.password"));
      String instanceName = config.getString("accumulo.instance");
      String zookeepers = config.getString("accumulo.zookeepers");
      idf.count(T_c2w.toString(), k_c2w, username, password, instanceName, zookeepers);
      idf.saveToDisk(f);
      Log.info("done");
    }
  }
  
  
  /**
   * Some features appear in a ton of mentions/tokenizations. This really slow down retrieval
   * and are not informative. This class makes a pass over the f2t table and builds a bloom
   * filter for features which return more than K tokenizations. You can do what you like with
   * these features given the BF (e.g. eliminating them may not be a good idea, but using them
   * as a last resort if more selective features don't return a good result is an option).
   * 
   * @deprecated
   * @see FeatureCardinalityEstimator
   */
  public static class BuildBigFeatureBloomFilters {
    
    public static void main(ExperimentProperties config) throws Exception {
      File writeTo = config.getFile("output");
      Log.info("writing bloom filter to " + writeTo.getPath());
      int minToks = config.getInt("minToks", 32000);
      int minDocs = config.getInt("minDocs", 16000);
      BuildBigFeatureBloomFilters bbfbf = new BuildBigFeatureBloomFilters(minToks, minDocs, writeTo);
      bbfbf.count(
          config.getString("accumulo.username"),
          new PasswordToken(config.getString("accumulo.password")),
          config.getString("accumulo.instance"),
          config.getString("accumulo.zookeepers"));
      Log.info("done");
    }

    // If either of these trip, then this is a "big feature"
    private int minToks;
    private int minDocs;
    private BloomFilter<String> bf;
    private File writeTo;
    
    // Meta/debugging
    Counts<String> ec;

    public BuildBigFeatureBloomFilters(int minToks, int minDocs, File writeTo) {
      this.writeTo = writeTo;
      this.minDocs = minDocs;
      this.minToks = minToks;
      int expectedInsertions = 10 * 1000 * 1000;
      double fpp = 0.01;
      bf = BloomFilter.create(Funnels.stringFunnel(Charset.forName("UTF8")), expectedInsertions, fpp);
      ec = new Counts<>();
      Log.info("minToks=" + minDocs + " minDocs=" + minDocs + "expectedInserts=" + expectedInsertions + " fpp=" + fpp);
    }
    
    /**
     * Assumes key.row are "documents" for computing numDocs.
     * In a sense we must make this assumption to have O(1) memory (accumulo only sorts by row) for counting.
     */
    public void count(
        String username, AuthenticationToken password,
        String instanceName, String zookeepers) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
      Log.info("starting, username=" + username);

      TimeMarker tm = new TimeMarker();
      Instance inst = new ZooKeeperInstance(instanceName, zookeepers);
      Connector conn = inst.getConnector(username, password);
      Scanner s = conn.createScanner(T_f2t.toString(), new Authorizations());

      // Statistics tracked for each row
      String prevFeat = null;
      int prevToks = 0;
      HashSet<String> prevDocs = new HashSet<>();   // determined by tokUuid 32-bit prefix

      for (Entry<Key, Value> e : s) {
        ec.increment("entry");
        String feat = e.getKey().getRow().toString();
        String tokUuid = e.getKey().getColumnQualifier().toString();
        String commUuid = tokUuid.substring(0, (8+1)+1);  // 8 hex = 4 bytes = 32 bits, +1 for a dash, +1 for exclusive end
        
        if (!feat.equals(prevFeat)) {
          output(prevFeat, prevToks, prevDocs);
          prevFeat = feat;
          prevToks = 0;
          prevDocs.clear();
        }

        prevToks++;
        prevDocs.add(commUuid);

        if (tm.enoughTimePassed(10))
          Log.info(ec + " curFeat=" + feat + "\t" + Describe.memoryUsage());
      }
      output(prevFeat, prevToks, prevDocs);

      Log.info("done, " + ec);
      writeToDisk();
    }
    
    private TimeMarker _outputTM = new TimeMarker();
    private void output(String feat, int numToks, Set<String> docs) {
      ec.increment("feat");
      boolean a = numToks > minToks;
      boolean b = docs.size() > minDocs;
      if (a || b) {
        bf.put(feat);
        ec.increment("feat/kept");
        if (a && b) ec.increment("feat/kept/both");
        else if (a) ec.increment("feat/kept/toks");
        else if (b) ec.increment("feat/kept/docs");

        if (_outputTM.enoughTimePassed(1 * 60))
          writeToDisk();
      }
    }
    
    private void writeToDisk() {
      Log.info("serializing to " + writeTo.getPath());
      FileUtil.serialize(bf, writeTo);
    }
  }


  public static String getCommUuidPrefixFromTokUuid(String tokUuid) {
    int bytes = 4;    // 32 bits
    int chars = 0;
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < tokUuid.length() && chars < 2*bytes; i++) {
      char c = tokUuid.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        out.append(c);
        chars++;
      }
    }
    return out.toString();
  }
  
  /**
   * @deprecated
   * @see Feat
   */
  public static class WeightedFeature implements Serializable {
    private static final long serialVersionUID = 5810788524410495193L;
    public final String feature;
    public final double weight;
    public WeightedFeature(String f, double w) {
      this.feature = f;
      this.weight = w;
    }
  }

  public static class TriageSearch implements Serializable {
    private static final long serialVersionUID = -5875667519520042444L;

    // Accumulo-related fields
    private transient Authorizations auths;
    private transient Connector conn;

    /** Stores how common triage features are, uncommon features are weighted highly, common features are ignored */
    private FeatureCardinalityEstimator.New triageFeatureFrequencies;

    int numQueryThreads;
    int batchTimeoutSeconds = 60;

    int maxResults = 10 * 1000;
    double triageFeatNBPrior = 50;    // higher values penalize very frequent features less
    
    // If true, use a batch scanner to do the words query given a communication
    boolean batchC2W;

    // Stop searching through inverted indices of features belonging to any
    // EMQuery once this many documents have been searched through.
    // TODO This is a bad fix for the problem of hitting really slow features
    // in the product space like {fa=*, fb="hi:mr"}. Perhaps filtering based
    // on product feature score is a better idea.
    private int maxDocsForMultiEntSearch = 500_000;

    public TriageSearch(
        FeatureCardinalityEstimator.New triageFeatureFrequencies,
        int maxResults,
        int maxDocsForMultiEntSearch) throws Exception {
      this(SimpleAccumuloConfig.DEFAULT_INSTANCE,
          SimpleAccumuloConfig.DEFAULT_ZOOKEEPERS,
          "reader",
          new PasswordToken("an accumulo reader"),
          triageFeatureFrequencies,
          4,
          maxResults,
          maxDocsForMultiEntSearch,
          30,
          true);
    }

    /** Use this for cases where you don't need to talk to accumulo, only compute feature frequencies/scores */
    public TriageSearch(FeatureCardinalityEstimator.New triageFeatureFrequencies) {
      this.triageFeatureFrequencies = triageFeatureFrequencies;
    }
    
    public TriageSearch(String instanceName, String zks, String username, AuthenticationToken password,
        FeatureCardinalityEstimator.New triageFeatureFrequencies,
        int nThreads,
        int maxResults,
        int maxDocsForMultiEntSearch,
        double triageFeatNBPrior,
        boolean batchC2W) throws Exception {
      Log.info("maxResults=" + maxResults
          + " maxDocsForMultiEntSearch=" + maxDocsForMultiEntSearch
          + " triageFeatureFrequencies=" + triageFeatureFrequencies
          + " batchC2W=" + batchC2W
          + " triageFeatNBPrior=" + triageFeatNBPrior
          + " instanceName=" + instanceName
          + " username=" + username
          + " nThreads=" + nThreads
          + " zks=" + zks);
      this.maxResults = maxResults;
      this.maxDocsForMultiEntSearch = maxDocsForMultiEntSearch;
      this.triageFeatNBPrior = triageFeatNBPrior;
      this.triageFeatureFrequencies = triageFeatureFrequencies;
      this.auths = new Authorizations();
      this.numQueryThreads = nThreads;
      this.batchC2W = batchC2W;
      if (batchC2W && nThreads == 1)
        Log.info("warning: you asked for batchC2W but only one thread");
      Instance inst = new ZooKeeperInstance(instanceName, zks);
      this.conn = inst.getConnector(username, password);
    }
    
    public FeatureCardinalityEstimator.New getTriageFeatureFrequencies() {
      return triageFeatureFrequencies;
    }
    
    private static <R> double rank(Counts.Pseudo<R> weights, int rank) {
      List<R> keys = weights.getKeysSortedByCount(true);
      if (rank >= keys.size())
        rank = keys.size()-1;
      return weights.getCount(keys.get(rank));
    }
    
    /** returns null if any of the common features don't have a score unless computeFeatFreqScoresAsNeeded=true */
    public Double scoreTriageFeatureIntersectionSimilarity(List<String> triageFeatsSource, List<String> triageFeatsTarget, boolean verbose) {//, boolean computeFeatFreqScoresAsNeeded, boolean verbose) {
      if (verbose)
        Log.info("source=" + triageFeatsSource + " target=" + triageFeatsTarget);// + " computeFeatFreqScoresAsNeeded=" + computeFeatFreqScoresAsNeeded);
      if (triageFeatsSource == null || triageFeatsTarget == null)
        throw new IllegalArgumentException();
      Set<String> common = new HashSet<>();
      common.addAll(triageFeatsSource);
      double score = 0;
      for (String f : triageFeatsTarget)
        if (common.contains(f))
          score += getFeatureScore(f);
      if (verbose)
        Log.info("done");
      return score;
    }

    public IntPair getFeatureFrequency(String triageFeature) {
      return triageFeatureFrequencies.getFrequency(triageFeature);
    }
    
    public double getFeatureScore(String f) {
      IntPair c = getFeatureFrequency(f);
      return getFeatureScore(c.first, c.second);
    }

    public double getFeatureScore(IntPair tcFreq) {
      return getFeatureScore(tcFreq.first, tcFreq.second);
    }

    public double getFeatureScore(int numToks, int numDocs) {

      // Assume we've seen everything at least a few times
      int m = 3;
      if (numToks < m) {
        Log.info("numToks correction: " + numToks + " => " + m);
        numToks = m;
      }
      if (numDocs < m) {
        Log.info("numDocs correction: " + numDocs + " => " + m);
        numDocs = m;
      }

      double freq = (2d * numToks * numDocs) / (numToks + numDocs);
      double p = (triageFeatNBPrior + 1) / (triageFeatNBPrior + freq);
      if (SHOW_TRIAGE_FEAT_SCORES) {
        System.out.println("triage:"
            + " numToks=" + numToks
            + " numDocs=" + numDocs
            + " freq=" + freq
            + " triageFeatNBPrior=" + triageFeatNBPrior
            + " p=" + p);
      }
      return p;
    }
    
    /**
     * Wraps up all the info on features for an entity.
     * {@link TriageSearch} only handles triage and attr feats.
     */
    static class EMQuery {
      String id;
      double weight;
      List<String> triageFeats;
      List<Feat> attrFeats;
      StringTermVec context;
      
      public EMQuery(String id, double weight) {
        assert weight > 0;
        this.id = id;
        this.weight = weight;
        this.triageFeats = new ArrayList<>();
        this.attrFeats = new ArrayList<>();
        this.context = new StringTermVec();
      }
      
      @Override
      public String toString() {
        return String.format("(EMQuery id=%s w=%.2f)", id, weight);
      }
    }

    // Only called by commented-out section of searchMulti
    public Counts.Pseudo<String> findIntersectionPlus(List<String> triageFeats) throws Exception {
      TIMER.start("f2t/triage");
      Counts.Pseudo<String> tokUuid2score = new Counts.Pseudo<>();

      // Features which have a score below this value cannot introduce new
      // tokenizations to the result set, only add to the score of tokenizations
      // retrieved using more selective features.
      double minScoreForNewTok = 1e-6;
      int tokFeatTooSmall = 0;

      // TODO Consider computing a bound based on the K-th best result so far:
      // given the gap between that score and the K+1-th, may not need to continue
      // of remaining features have little mass to contribute.
      Map<String, List<WeightedFeature>> tokUuid2MatchedFeatures = new HashMap<>();
      for (int fi = 0; fi < triageFeats.size(); fi++) {
        String f = triageFeats.get(fi);

        // Collect all of the tokenizations which this feature co-occurs with
        List<String> toks = new ArrayList<>();
        Set<String> commUuidPrefixes = new HashSet<>();
        try (Scanner f2tScanner = conn.createScanner(T_f2t.toString(), auths)) {
          f2tScanner.setRange(Range.exact(f));
          for (Entry<Key, Value> e : f2tScanner) {
            String tokUuid = e.getKey().getColumnQualifier().toString();
            toks.add(tokUuid);
            commUuidPrefixes.add(getCommUuidPrefixFromTokUuid(tokUuid));
          }
        }

        // Compute a score based on how selective this feature is
        int numToks = toks.size();
        int numDocs = commUuidPrefixes.size();
        double p = getFeatureScore(numToks, numDocs);

        // Check that the estimate is valid
        IntPair c = getFeatureFrequency(f);
        if (numToks > c.first)
          Log.info("WARNING: f=" + f + " numToks=" + numToks + " approxFreq=" + c + " [probably means approx counts are still being built]");
        if (numDocs > c.second)
          Log.info("WARNING: f=" + f + " numDocs=" + numDocs + " approxFreq=" + c + " [probably means approx counts are still being built]");
        assert c.first >= c.second;

        // Update the running score for all tokenizations
        boolean first = true;
        for (String t : toks) {
          boolean canAdd = p > minScoreForNewTok || tokUuid2score.getCount(t) > 0;
          if (!canAdd) {
            tokFeatTooSmall++;
            continue;
          }
          tokUuid2score.update(t, p);

          int nt = tokUuid2score.numNonZero();
          if (first && nt % 20000 == 0) {
            System.out.println("numToks=" + nt
                + " during featIdx=" + (fi+1)
                + " of=" + triageFeats.size()
                + " featStr=" + f
                + " p=" + p
                + " minScoreForNewTok=" + minScoreForNewTok
                + " tokFeatTooSmall=" + tokFeatTooSmall);
          }
          first = false;

          List<WeightedFeature> wfs = tokUuid2MatchedFeatures.get(f);
          if (wfs == null) {
            wfs = new ArrayList<>();
            tokUuid2MatchedFeatures.put(t, wfs);
          }
          wfs.add(new WeightedFeature(f, p));
        }


        // Find the score of the maxToksPreDocRetrieval^th Tokenization so far
        // Measure the ratio between this number and an upper bound on the amount of mass to be given out
        // You can get an upper bound by taking this feat's score (p) and assuming that all the remaining features get the same score
        int remainingFeats = triageFeats.size() - (fi+1);
        if (tokUuid2score.numNonZero() > 50 && remainingFeats > 0) {
          double upperBoundOnRemainingMass = p * remainingFeats;
          double lastTokScore = rank(tokUuid2score, maxResults);
          double goodTokScore = rank(tokUuid2score, 50);
          double safetyFactor = 5;
          if ((lastTokScore+goodTokScore)/2 > safetyFactor * upperBoundOnRemainingMass) {
            Log.info("probably don't need any other feats,"
                + " fi=" + fi
                + " remainingFeats=" + remainingFeats
                + " boundOnRemainingMass=" + upperBoundOnRemainingMass
                + " lastTokScore=" + lastTokScore
                + " goodTokScore=" + goodTokScore
                + " maxToksPreDocRetrieval=" + maxResults);
            break;
          }
        }
        // NOTE: If you do a good job of sorting features you can break out of this loop
        // after a certain amount of score has already been dolled out.
      }
      TIMER.stop("f2t/triage");
      return tokUuid2score;
    }
    
    public static <T> Counts.Pseudo<T> normalizeToMaxTimesWeight(Counts.Pseudo<T> m, double weight) {
      double mx = 1e-8;
      for (Entry<T, Double> x : m.entrySet())
        mx = Math.max(mx, x.getValue());
      Counts.Pseudo<T> out = new Counts.Pseudo<>();
      for (Entry<T, Double> x : m.entrySet())
        out.update(x.getKey(), (weight * x.getValue()) / mx);
      return out;
    }
    
    class FeatSearch {
      String feat;
//      EfficientUuidList toks;
      List<String> toks2;
      Set<String> commUuidPrefixes;
      IntPair tcFreq;

      public FeatSearch(String feat) {
        this.feat = feat;
//        toks = new EfficientUuidList(16);
        toks2 = new ArrayList<>();
        commUuidPrefixes = new HashSet<>();
        Log.info("scanning for feat=" + feat);
        try (Scanner f2tScanner = conn.createScanner(T_f2t.toString(), auths)) {
          f2tScanner.setRange(Range.exact(feat));
          for (Entry<Key, Value> e : f2tScanner) {
            String tokUuid = e.getKey().getColumnQualifier().toString();
            // TODO compare byte[] => String => UUID
            // to byte[] => ByteBuffer.wrap => UUID

//            toks.add(tokUuid);
            toks2.add(tokUuid);

            commUuidPrefixes.add(getCommUuidPrefixFromTokUuid(tokUuid));
          }
        } catch (TableNotFoundException e) {
          throw new RuntimeException(e);
        }
//        tcFreq = new IntPair(toks.size(), commUuidPrefixes.size());
        tcFreq = new IntPair(toks2.size(), commUuidPrefixes.size());
        Log.info("done, freq=" + tcFreq + " est. mem. usage=" + estimatedMemUseInBytes()/(1<<20) + " MB");
      }
      
      public long estimatedMemUseInBytes() {
        long t = 16;  // overhead
        t += 8 + feat.length() * 2L;
//        t += 8 + toks.size() * 16L;
        t += 8 + toks2.size() * (32+4)*2L;
        t += 8 + (long) (1.5d * commUuidPrefixes.size() * 16 * 2);
        t += 8 + 3*4;
        return t;
      }
      
      public double getScore() {
        return getFeatureScore(tcFreq);
      }
    }
    
    public static boolean same(EfficientUuidList toks, List<String> toks2) {
      int n = toks.size();
      if (n != toks2.size())
        return false;
      for (int i = 0; i < n; i++) {
        String a = toks.getString(i);
        String b = toks2.get(i);
        if (!a.equals(b))
          return false;
      }
      return true;
    }

    public void benchmark(String feat, EfficientUuidList toks, List<String> toks2, Set<String> commUuidPrefixes) {
      Log.info("scanning for feat=" + feat);
      try (Scanner f2tScanner = conn.createScanner(T_f2t.toString(), auths)) {
        f2tScanner.setRange(Range.exact(feat));
        for (Entry<Key, Value> e : f2tScanner) {
          String tokUuid = e.getKey().getColumnQualifier().toString();
          // TODO compare byte[] => String => UUID
          // to byte[] => ByteBuffer.wrap => UUID
          if (toks != null)
            toks.add(tokUuid);
          if (toks2 != null)
            toks2.add(tokUuid);
          if (commUuidPrefixes != null)
            commUuidPrefixes.add(getCommUuidPrefixFromTokUuid(tokUuid));
        }
      } catch (TableNotFoundException e) {
        throw new RuntimeException(e);
      }
      Log.info("done");
    }

    public void benchmarkAll(List<String> feats) {
      getTriageFeatureFrequencies().sortByFreqUpperBoundAsc(feats);
      EfficientUuidList r1;
      List<String> r2;
      MultiTimer t = new MultiTimer();
      for (String f : feats) {
        r1 = new EfficientUuidList(16);
        r2 = new ArrayList<>();
        try (TB tb = t.new TB("effTok")) {
          benchmark(f, r1, null, null);
        }
        try (TB tb = t.new TB("strTok")) {
          benchmark(f, null, r2, null);
        }

        r1 = new EfficientUuidList(16);
        r2 = new ArrayList<>();
        try (TB tb = t.new TB("effTokComm")) {
          benchmark(f, r1, null, new HashSet<>());
        }
        try (TB tb = t.new TB("strTokComm")) {
          benchmark(f, null, r2, new HashSet<>());
        }
        System.out.println(t);
        assert same(r1, r2);
      }
    }
    
//    public Map<java.util.UUID, Double> intersectiveQuery(EMQuery a, EMQuery b, Map<String, FeatSearch> searchCache) {
    public Counts.Pseudo<String> intersectiveQuery(EMQuery a, EMQuery b, Map<String, FeatSearch> searchCache) {
      // score(t, fa, fb) = score(t, fa) * score(t, fb)
      // where score(t,f)=0  =>  score(t, f, *)=0, so you really are intersecting inverted lists for fa and fb
      boolean debug = true;
      
      // Create an agenda of intersective queries sorted by score
      PriorityQueue<Weighted<Pair<String, String>>> agenda = new PriorityQueue<>(10, Weighted.byScoreDesc());
      for (String fa : a.triageFeats) {
        for (String fb : b.triageFeats) {
          double sa = getFeatureScore(fa);
          double sb = getFeatureScore(fb);
          double s = sa * sb;
          if (debug) {
            Log.info("pusing onto agenda fa=" + fa + " fb=" + fb
                + " sa=" + sa + " sb=" + sb + " s=" + s);
          }
          Pair<String, String> p = new Pair<>(fa, fb);
          agenda.add(new Weighted<>(p, s));
        }
      }

      // Add up the score across all pairs of features
      Set<String> docsA = new HashSet<>();
      Set<String> docsB = new HashSet<>();
//      Map<java.util.UUID, Double> tok2score = new HashMap<>();
      Counts.Pseudo<String> tok2score = new Counts.Pseudo<>();
      while (!agenda.isEmpty()) {
        Weighted<Pair<String, String>> p = agenda.poll();
        Pair<String, String> f = p.item;

        // Memoizing the FeatSearches
        // 125k UUID * 16 bytes/UUID * 10 feats * 2 queries = 38MB

        FeatSearch f1 = searchCache.get(f.get1());
        if (f1 == null) {
          if (docsA.size() > maxDocsForMultiEntSearch) {
            if (debug)
              Log.info("skipping fa=" + f.get1() + " docsA=" + docsA.size() + " maxDocsForMultiEntSearch=" + maxDocsForMultiEntSearch);
            continue;
          }
          f1 = new FeatSearch(f.get1());
          searchCache.put(f1.feat, f1);
          docsA.addAll(f1.commUuidPrefixes);
        }

        FeatSearch f2 = searchCache.get(f.get2());
        if (f2 == null) {
          if (docsB.size() > maxDocsForMultiEntSearch) {
            if (debug)
              Log.info("skipping fb=" + f.get2() + " docsB=" + docsB.size() + " maxDocsForMultiEntSearch=" + maxDocsForMultiEntSearch);
            continue;
          }
          f2 = new FeatSearch(f.get2());
          searchCache.put(f2.feat, f2);
          docsB.addAll(f2.commUuidPrefixes);
        }

        double score = f1.getScore() * f2.getScore();
//        EfficientUuidList common = EfficientUuidList.hashJoin(f1.toks, f2.toks);
        List<String> common = hashJoin(f1.toks2, f2.toks2);
        int n = common.size();
        for (int i = 0; i < n; i++) {
//          java.util.UUID t = common.get(i);
//          double prev = tok2score.getOrDefault(t, 0d);
//          tok2score.put(t, prev + score);
          String t = common.get(i);
          tok2score.update(t, score);
        }
      }
      return tok2score;
    }
    
    public List<String> hashJoin(List<String> a, List<String> b) {
      Set<String> seen = new HashSet<>();
      seen.addAll(a);
      List<String> out = new ArrayList<>();
      for (String s : b)
        if (seen.remove(s))
          out.add(s);
      return out;
    }
    
    public static Counts.Pseudo<String> convert(Map<java.util.UUID, Double> t2s) {
      Counts.Pseudo<String> c = new Counts.Pseudo<>();
      for (Entry<java.util.UUID, Double> x : t2s.entrySet()) {
        String k = x.getKey().toString();
        c.update(k, x.getValue());
      }
      return c;
    }

    public List<SitSearchResult> searchMulti(List<EMQuery> qs, ComputeIdf df) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
      boolean debug = true;

      Log.info("starting, qs=" + qs);
      if (qs.isEmpty())
        throw new IllegalArgumentException();
      if (batchTimeoutSeconds > 0)
        Log.info("[filter] using a timeout of " + batchTimeoutSeconds + " seconds for f2t query");
      
      // Sort features by an upper bound on their cardinality (number of toks which contain this feature)
      for (EMQuery q : qs) {
        triageFeatureFrequencies.sortByFreqUpperBoundAsc(q.triageFeats);
        Log.info("after sorting q[" + q.id + "] feats by freq: " + triageFeatureFrequencies.showFreqUpperBounds(q.triageFeats));
      }
      
      if (qs.size() != 2)
        throw new RuntimeException("implement me");
//      Map<java.util.UUID, Double> t2s = intersectiveQuery(qs.get(0), qs.get(1), new HashMap<>());
//      Counts.Pseudo<String> tokUuid2score = convert(t2s);
      Counts.Pseudo<String> tokUuid2score = intersectiveQuery(qs.get(0), qs.get(1), new HashMap<>());
      
      /*
      // score(tok, qs) = prod_{q in qs} 1+score(tok,q)
      // where the score(tok,q) is the already implemented bit
      Map<String, Double> tokUuid2ScoreMap = new HashMap<>();
      Map<String, LL<Feat>> tok2sources = new HashMap<>();
      int K = 0;
      for (EMQuery q : qs) {
        if (debug)
          Log.info("processing toks from " + q);
        try {
          
          // This map contains keys which are tok UUIDs and the values are total
          // scores for this sentence mentioning this query entity.
          Counts.Pseudo<String> t2s = findIntersectionPlus(q.triageFeats);
          
          // We are going to normalize this mapping so that the maximum value is the entity weight;
          t2s = normalizeToMaxTimesWeight(t2s, q.weight);
          
          K = Math.max(K, t2s.numNonZero());
          Set<String> uniq = new HashSet<>();
          for (Entry<String, Double> tok : t2s.entrySet()) {
            assert tok.getValue() > 0;
            double prev = tokUuid2ScoreMap.getOrDefault(tok, 1d);
            double cur = prev * (1 + tok.getValue());
            if (debug) {
              Log.info("overlap: tok=" + tok.getKey()
                  + " prev=" + prev
                  + " cur=1+" + tok.getValue()
                  + " q=" + q
                  + " q.tf=" + q.triageFeats);
            }
            tokUuid2ScoreMap.put(tok.getKey(), cur);
            
            String k = tok.getKey();
            Feat f = new Feat(q.id, tok.getValue());
            tok2sources.put(k, new LL<>(f, tok2sources.get(k)));
            assert uniq.add(k);
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
      K += 10;

      if (debug) {
        Log.info("K=" + K);
        List<String> keys = new ArrayList<>(tok2sources.keySet());
        Collections.sort(keys, new Comparator<String>() {
          @Override
          public int compare(String o1, String o2) {
            List<Feat> a = LL.toList(tok2sources.get(o1));
            List<Feat> b = LL.toList(tok2sources.get(o2));
            if (a.size() > b.size())
              return -1;
            if (a.size() < b.size())
              return +1;
            return 0;
          }
        });
        for (String k : keys) {
          List<Feat> sources = LL.toList(tok2sources.get(k));
          if (sources.size() > 1) {
            Log.info("sources: " + k + "\t" + sources + "\t" + tokUuid2ScoreMap.get(k));
          }
        }
      }
      
      // Trim to only take at most K
      // where K = 10 + max_{q : qs} sizeof(triageSearch(q))
      // This will mimic the performance characteristics (mainly memory) of the existing single-mention implementation
      List<Feat> toks = Feat.deindex(tokUuid2ScoreMap.entrySet());
      Counts.Pseudo<String> tokUuid2score = new Counts.Pseudo<>();
      for (Feat f : Feat.sortAndPrune(toks, K)) {
        tokUuid2score.update(f.name, f.weight);
        if (debug)
          Log.info("topTok: " + f.name + " " + f.weight);
      }
      */

      // Now we have scores for every tokenization
      // Need to add in the document tf-idf score
      
      // 1) Make a batch scanner to retrieve all the commUuids from t2c
      Map<String, String> tokUuid2commId = getCommIdsFor(tokUuid2score);
      
      // 2) Make a batch scanner to retrieve the words from the most promising comms, look in c2w
      Collection<String> toRet = tokUuid2commId.values();
      Map<String, StringTermVec> commId2terms = getWordsForComms(toRet);
      
      // 3) Go through each tok and re-score
      Counts<String> filterReasons = new Counts<>();
      List<SitSearchResult> res = new ArrayList<>();
      for (Entry<String, String> tc : tokUuid2commId.entrySet()) {
        String tokUuid = tc.getKey();
        String commId = tc.getValue();
        List<Feat> score = new ArrayList<>();
        SitSearchResult ss = new SitSearchResult(tokUuid, null, score);
        ss.setCommunicationId(commId);
        
        // Instead of tf-idf being ONE number, it is now N numbers
        // I'll take the sum rather than the product
        StringTermVec commVec = commId2terms.get(commId);
        double ts = 0;
        for (EMQuery q : qs)
          ts += df.tfIdfCosineSim(commVec, q.context);
        score.add(new Feat("tfidf", ts));
        
        // Triage features have already been reduced to a single number
        double triageFeatScore = tokUuid2score.getCount(tokUuid);
        score.add(new Feat("entMatch").setWeight(triageFeatScore));

        double prod = triageFeatScore * (0.1 + ts);
        score.add(new Feat("prod").setWeight(prod).rescale("goodfeat", 10.0));

        // Filtering
        if (triageFeatScore < 0.0001) {
          EC.increment("resFilter/name");
          filterReasons.increment("badNameMatch");
          continue;
        }
        if (ts < 0.05 && triageFeatScore < 0.001) {
          EC.increment("resFilter/prod");
          filterReasons.increment("badNameAndTfIdf");
          continue;
        }
        
        res.add(ss);
      }
      Log.info("[filter] reasons for filtering: " + filterReasons);

      // 4) Sort results by final score
      Collections.sort(res, SitSearchResult.BY_SCORE_DESC);
      
      Log.info("returning " + res.size() + " sorted SitSearchResults");
      return res;
    }
    
    
    

    /**
     * @param triageFeats are generated from {@link IndexCommunications#getEntityMentionFeatures(String, String[], String, TokenObservationCounts, TokenObservationCounts)}
     * @param docContext
     * @param df
     */
    public List<SitSearchResult> search(List<String> triageFeats, StringTermVec docContext, ComputeIdf df, Double minScore) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
      Log.info("starting, minScore=" + minScore + " triageFeats=" + triageFeats);
      if (triageFeats.isEmpty())
        throw new IllegalArgumentException();
      if (batchTimeoutSeconds > 0)
        Log.info("[filter] using a timeout of " + batchTimeoutSeconds + " seconds for f2t query");
      
      // Make a batch scanner to retrieve all tokenization which contain any triageFeats
      TIMER.start("f2t/triage");
      Counts.Pseudo<String> tokUuid2score = new Counts.Pseudo<>();
      
      // Sort features by an upper bound on their cardinality (number of toks which contain this feature)
      triageFeatureFrequencies.sortByFreqUpperBoundAsc(triageFeats);
      Log.info("after sorting feats by freq: " + triageFeatureFrequencies.showFreqUpperBounds(triageFeats));
      
      // Features which have a score below this value cannot introduce new
      // tokenizations to the result set, only add to the score of tokenizations
      // retrieved using more selective features.
      double minScoreForNewTok = 1e-6;
      int tokFeatTooSmall = 0;

      // TODO Consider computing a bound based on the K-th best result so far:
      // given the gap between that score and the K+1-th, may not need to continue
      // of remaining features have little mass to contribute.
      Map<String, List<WeightedFeature>> tokUuid2MatchedFeatures = new HashMap<>();
      for (int fi = 0; fi < triageFeats.size(); fi++) {
        String f = triageFeats.get(fi);

        // Collect all of the tokenizations which this feature co-occurs with
        List<String> toks = new ArrayList<>();
        Set<String> commUuidPrefixes = new HashSet<>();
        try (Scanner f2tScanner = conn.createScanner(T_f2t.toString(), auths)) {
          f2tScanner.setRange(Range.exact(f));
          for (Entry<Key, Value> e : f2tScanner) {
            String tokUuid = e.getKey().getColumnQualifier().toString();
            toks.add(tokUuid);
            commUuidPrefixes.add(getCommUuidPrefixFromTokUuid(tokUuid));
          }
        }

        // Compute a score based on how selective this feature is
        int numToks = toks.size();
        int numDocs = commUuidPrefixes.size();
        double p = getFeatureScore(numToks, numDocs);

        // Check that the estimate is valid
        IntPair c = getFeatureFrequency(f);
        if (numToks > c.first)
          Log.info("WARNING: f=" + f + " numToks=" + numToks + " approxFreq=" + c + " [probably means approx counts are still being built]");
        if (numDocs > c.second)
          Log.info("WARNING: f=" + f + " numDocs=" + numDocs + " approxFreq=" + c + " [probably means approx counts are still being built]");
        assert c.first >= c.second;

        // Update the running score for all tokenizations
        boolean first = true;
        for (String t : toks) {
          boolean canAdd = p > minScoreForNewTok || tokUuid2score.getCount(t) > 0;
          if (!canAdd) {
            tokFeatTooSmall++;
            continue;
          }
          tokUuid2score.update(t, p);

          int nt = tokUuid2score.numNonZero();
          if (first && nt % 20000 == 0) {
            System.out.println("numToks=" + nt
                + " during featIdx=" + (fi+1)
                + " of=" + triageFeats.size()
                + " featStr=" + f
                + " p=" + p
                + " minScoreForNewTok=" + minScoreForNewTok
                + " tokFeatTooSmall=" + tokFeatTooSmall);
          }
          first = false;

          List<WeightedFeature> wfs = tokUuid2MatchedFeatures.get(f);
          if (wfs == null) {
            wfs = new ArrayList<>();
            tokUuid2MatchedFeatures.put(t, wfs);
          }
          wfs.add(new WeightedFeature(f, p));
        }


        // Find the score of the maxToksPreDocRetrieval^th Tokenization so far
        // Measure the ratio between this number and an upper bound on the amount of mass to be given out
        // You can get an upper bound by taking this feat's score (p) and assuming that all the remaining features get the same score
        int remainingFeats = triageFeats.size() - (fi+1);
        if (tokUuid2score.numNonZero() > 50 && remainingFeats > 0) {
          double upperBoundOnRemainingMass = p * remainingFeats;
          double lastTokScore = rank(tokUuid2score, maxResults);
          double goodTokScore = rank(tokUuid2score, 50);
          double safetyFactor = 5;
          if ((lastTokScore+goodTokScore)/2 > safetyFactor * upperBoundOnRemainingMass) {
            Log.info("probably don't need any other feats,"
                + " fi=" + fi
                + " remainingFeats=" + remainingFeats
                + " boundOnRemainingMass=" + upperBoundOnRemainingMass
                + " lastTokScore=" + lastTokScore
                + " goodTokScore=" + goodTokScore
                + " maxToksPreDocRetrieval=" + maxResults);
            break;
          }
        }
        // NOTE: If you do a good job of sorting features you can break out of this loop
        // after a certain amount of score has already been dolled out.
      }
      TIMER.stop("f2t/triage");

      // Now we have scores for every tokenization
      // Need to add in the document tf-idf score
      
      // 1) Make a batch scanner to retrieve all the commUuids from t2c
      Map<String, String> tokUuid2commId = getCommIdsFor(tokUuid2score);
      
      // 2) Make a batch scanner to retrieve the words from the most promising comms, look in c2w
      Collection<String> toRet = tokUuid2commId.values();
      Map<String, StringTermVec> commId2terms = getWordsForComms(toRet);
      
      // 3) Go through each tok and re-score
      Counts<String> filterReasons = new Counts<>();
      List<SitSearchResult> res = new ArrayList<>();
      for (Entry<String, String> tc : tokUuid2commId.entrySet()) {
        String tokUuid = tc.getKey();
        String commId = tc.getValue();
        List<Feat> score = new ArrayList<>();
        SitSearchResult ss = new SitSearchResult(tokUuid, null, score);
        ss.setCommunicationId(commId);
        ss.triageFeatures = triageFeats;
        ss.triageFeaturesMatched = tokUuid2MatchedFeatures.get(tokUuid);
        
        StringTermVec commVec = commId2terms.get(commId);
        double tfidf = -10;
        if (commVec == null) {
          Log.info("WARNING: could not lookup words for commId=" + commId);
        } else {
          tfidf = df.tfIdfCosineSim(docContext, commVec);
          ss.importantTerms = df.importantTerms(commVec, 20);
        }
        score.add(new Feat("tfidf").setWeight(tfidf));
        
        double entMatchScore = tokUuid2score.getCount(tokUuid);
        score.add(new Feat("entMatch").setWeight(entMatchScore));

        double prod = entMatchScore * (0.1 + tfidf);
        score.add(new Feat("prod").setWeight(prod).rescale("goodfeat", 10.0));

        // Filtering
        if (entMatchScore < 0.0001) {
          EC.increment("resFilter/name");
          filterReasons.increment("badNameMatch");
          continue;
        }
        if (tfidf < 0.05 && entMatchScore < 0.001) {
          EC.increment("resFilter/prod");
          filterReasons.increment("badNameAndTfIdf");
          continue;
        }
        
        if (minScore != null && Feat.sum(score) < minScore) {
          EC.increment("resFilter/minScore");
          continue;
        }
        
        res.add(ss);
      }
      Log.info("[filter] reasons for filtering: " + filterReasons + " feats: " + triageFeats);

      // 4) Sort results by final score
      Collections.sort(res, SitSearchResult.BY_SCORE_DESC);
      
      Log.info("returning " + res.size() + " sorted SitSearchResults");
      return res;
    }
    
    /** returned map is tokUuid -> commId */
    private Map<String, String> getCommIdsFor(Counts.Pseudo<String> tokUuid2score) throws TableNotFoundException {
      TIMER.start("t2c/getCommIdsFor");

      // TODO Consider filtering based on score?
      List<String> bestToks = tokUuid2score.getKeysSortedByCount(true);
      if (bestToks.size() > maxResults) {
        Log.info("[filter] only taking the " + maxResults + " highest scoring of " + bestToks.size() + " tokenizations");
        bestToks = bestToks.subList(0, maxResults);
      }

      List<Range> rows = new ArrayList<>();
      for (String s : bestToks)
        rows.add(Range.exact(s));

      Map<String, String> t2c = new HashMap<>();
      if (!rows.isEmpty()) {
        int numQueryThreads = 4;
        try (BatchScanner bs = conn.createBatchScanner(T_t2c.toString(), auths, numQueryThreads)) {
          bs.setRanges(rows);
          for (Entry<Key, Value> e : bs) {
            String tokUuid = e.getKey().getRow().toString();
            String commId = e.getValue().toString();
            Object old = t2c.put(tokUuid, commId);
            assert old == null;
          }
        }
      }
      TIMER.stop("t2c/getCommIdsFor");
      return t2c;
    }

    /** keys of returned map are comm ids */
    private Map<String, StringTermVec> getWordsForCommsBatch(Iterable<String> commIdsNonUniq) throws TableNotFoundException {
      TIMER.start("c2w/getWordsForCommsBatch");

      // Collect the ids of all the comm keys which need to be retrieved in c2w
      List<Range> rows = new ArrayList<>();
      Set<String> uniq = new HashSet<>();
      for (String commId : commIdsNonUniq) {
        if (uniq.add(commId))
          rows.add(Range.exact(commId));
      }
      
      int nr = 0;
      Map<String, StringTermVec> c2tv = new HashMap<>();
      try (BatchScanner bs = conn.createBatchScanner(T_c2w.toString(), auths, numQueryThreads)) {
        if (batchTimeoutSeconds > 0) {
          Log.info("[filter] using a timeout of " + batchTimeoutSeconds + " seconds for c2w query");
          bs.setBatchTimeout(batchTimeoutSeconds, TimeUnit.SECONDS);
        }
        bs.setRanges(rows);
        
        for (Entry<Key, Value> e : bs) {
          String commId = e.getKey().getRow().toString();
          String word = e.getKey().getColumnQualifier().toString();
          int count = decodeCount(e.getValue().get());
          StringTermVec tv = c2tv.get(commId);
          if (tv ==  null) {
            tv = new StringTermVec();
            c2tv.put(commId, tv);
          }
          tv.add(word, count);
          nr++;
        }
      }
      Log.info("[filter] retrieved " + c2tv.size() + " of " + uniq.size() + " comms, numWords=" + nr);
      TIMER.stop("c2w/getWordsForCommsBatch");
      return c2tv;
    }

    private Map<String, StringTermVec> getWordsForComms(Iterable<String> commIdsNonUniq) throws TableNotFoundException {
      if (batchC2W)
        return getWordsForCommsBatch(commIdsNonUniq);
      return getWordsForCommsSerial(commIdsNonUniq);
    }
    
    /** keys of returned map are comm ids */
    private Map<String, StringTermVec> getWordsForCommsSerial(Iterable<String> commIdsNonUniq) throws TableNotFoundException {
      TIMER.start("c2w/getWordsForCommsSerial");
      
      // NOTE: This can be made batch to go faster
      // The only thing is that we don't get the results back in a specific order,
      // but since we're loading this all into memory anyway, it doesn't much matter.
      
      // Collect the ids of all the comm keys which need to be retrieved in c2w
      int nt = 0;
      List<Range> rows = new ArrayList<>();
      Set<String> uniq = new HashSet<>();
      for (String commId : commIdsNonUniq) {
        nt++;
        if (uniq.add(commId))
          rows.add(Range.exact(commId));
      }
      Log.info("found " + rows.size() + " commUuids containing all " + nt + " tokUuids");

      int nr = 0;
      Map<String, StringTermVec> c2tv = new HashMap<>();
      for (String commId : uniq) {
        StringTermVec tv = new StringTermVec();
        try (Scanner s = conn.createScanner(T_c2w.toString(), auths)) {
          s.setRange(Range.exact(commId));
          for (Entry<Key, Value> e : s) {
            String word = e.getKey().getColumnQualifier().toString();
            int count = decodeCount(e.getValue().get());
            tv.add(word, count);
            nr++;
          }
        }
        Object old = c2tv.put(commId, tv);
        assert old == null;
      }
      Log.info("[filter] retrieved " + c2tv.size() + " of " + uniq.size() + " comms, numWords=" + nr);
      TIMER.stop("c2w/getWordsForCommsSerial");
      return c2tv;
    }
  }
  
  
  /**
   * Holds tf not idf.
   */
  public static class StringTermVec implements Iterable<Entry<String, Integer>>, Serializable {
    private static final long serialVersionUID = 6842781351153635019L;

    private Counts<String> tf;
    
    public StringTermVec() {
      tf = new Counts<>();
    }
    
    public StringTermVec(Communication c) {
      this();
      if (c == null)
        throw new IllegalArgumentException();
      for (String s : IndexCommunications.terms(c))
        add(s, 1);
    }
    
    public int getTotalCount() {
      return tf.getTotalCount();
    }

    public void add(StringTermVec other) {
      for (Entry<String, Integer> e : other.tf.entrySet())
        add(e.getKey(), e.getValue());
    }
    
    public void add(String word, int count) {
      tf.update(word, count);
    }

    @Override
    public Iterator<Entry<String, Integer>> iterator() {
      return tf.entrySet().iterator();
    }
  }
  
  // TODO Want to be able to match "PERSON-nn-nn-Wen" with "PERSON-nn-Wen", though this is technically a parsing error
  private static Pair<Double, List<String>> match(List<String> attrFeatQ, List<String> attrFeatR) {
    List<String> matched = new ArrayList<>();
    Set<String> intersect = new HashSet<>();
    Set<String> union = new HashSet<>();
    union.addAll(attrFeatQ);
    for (String f : attrFeatR) {
      union.add(f);
      if (attrFeatQ.contains(f)) {
        matched.add(f);
        intersect.add(f);
      }
    }
    // adding to denom is same as dirichlet prior on jaccard=0, more attr will wash this out
    double score = intersect.size() / (1d + union.size());
    return new Pair<>(score, matched);
  }
  
  public static void attrFeatureReranking(KbpQuery q, List<SitSearchResult> res) {
    // FIXED: Acceptable bug here: should really only look at the mention highlighed by the query.
    // E.g. if a doc mentions Bill and Hillary Clinton, the attr feats will be collected off
    // of any Clinton match, thus adding Bill features, which is what these were specifically
    // designed to avoid.
    assert q.entityMention != null;
    String tokUuidQ = q.entityMention.getTokens().getTokenizationId().getUuidString();
    attrFeatureReranking(q.name, tokUuidQ, q.sourceComm, res);
  }

  public static void attrFeatureReranking(String sourceName, String sourceTok, Communication sourceComm, List<SitSearchResult> res) {
    String nameHeadQ = NNPSense.extractNameHead(sourceName);
    List<String> attrCommQ = NNPSense.extractAttributeFeatures(null, sourceComm, nameHeadQ);
    List<String> attrTokQ = NNPSense.extractAttributeFeatures(sourceTok, sourceComm, nameHeadQ);
    Log.info(sourceName + " attribute features: " + attrCommQ);
    attrFeatureReranking(attrCommQ, attrTokQ, res);
  }

  public static void attrFeatureReranking(List<String> attrCommQ, List<String> attrTokQ, List<SitSearchResult> res) {
    TIMER.start("attrFeatureReranking");
    for (SitSearchResult r : res) {

//      // This is not only dis-allowed in evaluation, but will screw up
//      // the scores by having all the attribute features trivially match
//      if (sourceComm.getId().equals(r.getCommunicationId())) {
//        continue;
//      }

      if (r.yhatQueryEntityHead < 0)
        throw new IllegalArgumentException();
      String nameHeadR = r.getEntityHeadGuess();

      List<String> attrCommR = NNPSense.extractAttributeFeatures(null, r.getCommunication(), nameHeadR, nameHeadR);
      List<String> attrTokR = NNPSense.extractAttributeFeatures(r.tokUuid, r.getCommunication(), nameHeadR, nameHeadR);
      
      Pair<Double, List<String>> mCC = match(attrCommQ, attrCommR);
      Pair<Double, List<String>> mCT = match(attrCommQ, attrTokR);
      Pair<Double, List<String>> mTC = match(attrTokQ, attrCommR);
      Pair<Double, List<String>> mTT = match(attrTokQ, attrTokR);
      
      double scale = 1;
      r.addToScore("attrFeatCC", 1 * scale * mCC.get1());
      r.addToScore("attrFeatCT", 2 * scale * mCT.get1());
      r.addToScore("attrFeatTC", 2 * scale * mTC.get1());
      r.addToScore("attrFeatTT", 8 * scale * mTT.get1());

      r.attributeFeaturesQ = attrTokQ;
      r.attributeFeaturesR = attrTokR;
      r.attributeFeaturesMatched = mTT.get2();
    }

    Collections.sort(res, SitSearchResult.BY_SCORE_DESC);
    
    TIMER.stop("attrFeatureReranking");
  }

  /**
   * In this version of the method, you should just combine tok and doc versions
   * of attrFeats and weight them accordingly.
   * e.g. attrTokFeats get a weight of 2 and attrDocFeats get a weight of 1.
   */
  public static void attrFeatureReranking(List<Feat> attrQ, List<SitSearchResult> res) {
    TIMER.start("attrFeatureReranking");
    for (SitSearchResult r : res) {
      if (r.yhatQueryEntityHead < 0)
        throw new IllegalArgumentException();
      String nameHeadR = r.getEntityHeadGuess();

      List<String> attrCommR = NNPSense.extractAttributeFeatures(null, r.getCommunication(), nameHeadR, nameHeadR);
      List<String> attrTokR = NNPSense.extractAttributeFeatures(r.tokUuid, r.getCommunication(), nameHeadR, nameHeadR);
      
      List<Feat> attrR = Feat.vecadd(Feat.promote(2, attrTokR), Feat.promote(1, attrCommR));
      Pair<Double, List<Feat>> c = Feat.cosineSim(attrR, attrQ);
      
      r.attrFeatQ = attrQ;
      r.attrFeatR = attrR;
      
      double scale = 1;
      r.addToScore("attrFeat", scale * c.get1());
    }

    Collections.sort(res, SitSearchResult.BY_SCORE_DESC);
    
    TIMER.stop("attrFeatureReranking");
  }
  
  public static class AttrFeatMatch {
    List<String> attrCommQ;
    List<String> attrTokQ;

    List<String> attrCommR;
    List<String> attrTokR;

    Pair<Double, List<String>> mCC;
    Pair<Double, List<String>> mCT;
    Pair<Double, List<String>> mTC;
    Pair<Double, List<String>> mTT;
    
//    public AttrFeatMatch(List<String> attrCommQ, List<String> attrTokQ, SitSearchResult r) {
//      String nameHeadR = r.getEntityHeadGuess();
    public AttrFeatMatch(List<String> attrCommQ, List<String> attrTokQ, String nameHeadR, Tokenization tR, Communication cR) {
      this.attrCommQ = attrCommQ;
      this.attrTokQ = attrTokQ;
//      attrCommR = NNPSense.extractAttributeFeatures(null, r.getCommunication(), nameHeadR, nameHeadR);
//      attrTokR = NNPSense.extractAttributeFeatures(r.tokUuid, r.getCommunication(), nameHeadR, nameHeadR);
      attrCommR = NNPSense.extractAttributeFeatures(null, cR, nameHeadR, nameHeadR);
      attrTokR = NNPSense.extractAttributeFeatures(tR.getUuid().getUuidString(), cR, nameHeadR, nameHeadR);
      mCC = match(attrCommQ, attrCommR);
      mCT = match(attrCommQ, attrTokR);
      mTC = match(attrTokQ, attrCommR);
      mTT = match(attrTokQ, attrTokR);
    }
    
    public List<Feat> getFeatures() {
      List<Feat> fs = new ArrayList<>();
      double scale = 1;
      fs.add(new Feat("attrFeatCC", 1 * scale * mCC.get1()));
      fs.add(new Feat("attrFeatCT", 2 * scale * mCT.get1()));
      fs.add(new Feat("attrFeatTC", 2 * scale * mTC.get1()));
      fs.add(new Feat("attrFeatTT", 8 * scale * mTT.get1()));
      return fs;
    }
    
    public void addFeaturesTo(SitSearchResult r) {
      for (Feat f : getFeatures())
        r.addToScore(f.name, f.weight);
    }
  }
  
  /**
   * Does triage plus extras like:
   * 1) fetching communications
   * 2) re-scoring methods like attribute features
   */
  public static class KbpSearching implements Serializable, AutoCloseable {
    private static final long serialVersionUID = 8767537711510822918L;
    
    public static DiskBackedFetchWrapper buildFetchWrapper(File cacheDir, String host, int port) throws TTransportException {
      Log.info("building DiskBackedFetchWrapper cacheDir=" + cacheDir.getPath() + " fetchHost=" + host + " fetchPort=" + port);
      TTransport transport = new TFramedTransport(new TSocket(host, port), Integer.MAX_VALUE);
      transport.open();
      TProtocol protocol = new TCompactProtocol(transport);
      FetchCommunicationService.Client failOver = new FetchCommunicationService.Client(protocol);
      boolean saveFetchedComms = true;
      boolean compressionForSavedComms = true;
      DiskBackedFetchWrapper db = new DiskBackedFetchWrapper(failOver, transport, cacheDir, saveFetchedComms, compressionForSavedComms);
//      db.debug = true;
//      db.disableCache = true;
      return db;
    }

    private transient DiskBackedFetchWrapper commRetFetch;
    private HashMap<String, Communication> commRetCache;  // contains everything commRetFetch ever gave us
    private TriageSearch triageSearch;
    private ComputeIdf df;
    private Double minTriageScore;

    public KbpSearching(
        TriageSearch triageSearch,
        ComputeIdf df,
        Double minTriageScore,
        DiskBackedFetchWrapper commRetFetch,
        HashMap<String, Communication> commRetCache) throws Exception {
      this.commRetFetch = commRetFetch;
      this.commRetCache = commRetCache;
      this.triageSearch = triageSearch;
      this.minTriageScore = minTriageScore;
      this.df = df;
    }
    
    public void setMaxResults(int mr) {
      Log.info("setting maxResults=max(10, " + mr + ")");
      triageSearch.maxResults = Math.max(10, mr);
    }
    
    public ComputeIdf getTermFrequencies() {
      return df;
    }
    
    public TriageSearch getTriageSearch() {
      return triageSearch;
    }
    
    public void clearCaches() {
      Log.info("clearing cache");
      commRetCache.clear();
    }
    
    public Communication getCommCaching(String commId) {
      if (commId == null)
        throw new IllegalArgumentException();
      Communication c = commRetCache.get(commId);
      if (c != null)
        return c;
      try {
        c = commRetFetch.fetch(commId);
        commRetCache.put(commId, c);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return c;
    }

    public List<SitSearchResult> multiEntityMentionSearch(List<EMQuery> qs) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
      
      // 3) Search
      List<SitSearchResult> res = triageSearch.searchMulti(qs, df);

      // 4-5) Retrieve communications and prune
      // TODO Batch retrieval
      {
      TIMER.start("getCommsForSitSearchResults");
      List<SitSearchResult> resKeep = new ArrayList<>();
      int failed = 0;
      for (SitSearchResult r : res) {
        Communication c = getCommCaching(r.getCommunicationId());
        if (c == null) {
          Log.info("warning: pruning result! Could not find Communication with id " + r.getCommunicationId());
          failed++;
        } else {
          r.setCommunication(c);
          resKeep.add(r);
        }
      }
      Log.info("[filter] resultsGiven=" + res.size() + " resultsFailed=" + failed + " resultsKept=" + resKeep.size());
      res = resKeep;
      TIMER.stop("getCommsForSitSearchResults");
      }
      
      // 6) Find entities and situations
      // TODO or not? SitSearchResult is not what I want for this, I want MultiEntityMention
      // Currently this is implemented in PbkpSearching
      
      // 7) Rescore according to attribute features
//      for (EMQuery q : qs) {
//        if (q.attrFeats.size() > 0)
//          throw new RuntimeException("implement me");
//        /*
//         * There is a real problem here.
//         * If you want to extract attribute features, you have to already have decided where the
//         * entity mentions are, which is currently done client-side not server-side.
//         */
//      }
      
      return res;
    }

    /**
     * Triage retrieval, resolving Communications, and finally attribute feature reranking.
     */
    public List<SitSearchResult> entityMentionSearch(List<String> triageFeats, List<Feat> attrFeats, StringTermVec context) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
      
      // 3) Search
      List<SitSearchResult> res = triageSearch.search(triageFeats, context, df, minTriageScore);

      // 4-5) Retrieve communications and prune
      // TODO Batch retrieval
      {
      TIMER.start("getCommsForSitSearchResults");
      List<SitSearchResult> resKeep = new ArrayList<>();
      int failed = 0;
      for (SitSearchResult r : res) {
        Communication c = getCommCaching(r.getCommunicationId());
        if (c == null) {
          Log.info("warning: pruning result! Could not find Communication with id " + r.getCommunicationId());
          failed++;
        } else {
          r.setCommunication(c);
          resKeep.add(r);
        }
      }
      Log.info("[filter] resultsGiven=" + res.size() + " resultsFailed=" + failed + " resultsKept=" + resKeep.size());
      res = resKeep;
      TIMER.stop("getCommsForSitSearchResults");
      }

      // 6) Find entities and situations
      // (technically situations are not needed, only entities for attribute features)
      {
      List<SitSearchResult> withEntAndSit = new ArrayList<>();
      for (SitSearchResult r : res)
        if (findEntitiesAndSituations(r, df, false))
          withEntAndSit.add(r);
      Log.info("[filter] lost " + (res.size()-withEntAndSit.size()) + " SitSearchMention b/c ent/sit finding");
      res = withEntAndSit;
      }
      
      // 7) Rescore according to attribute features
      attrFeatureReranking(attrFeats, res);
      
      return res;
    }


    /**
     * Triage retrieval, resolving Communications, and finally attribute feature reranking.
     */
    public List<SitSearchResult> entityMentionSearch(PkbpEntity.Mention query) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
      Log.info("working on " + query);
      assert query.getCommunicationId() != null;
      assert query.nerType != null;
      assert query.head >= 0;
      TIMER.start("kbpQuery/setup");

      // 1a) Retrieve the context Communication
      if (query.getCommunication() == null) {
        Communication c = getCommCaching(query.getCommunicationId());
        if (c == null) {
          Log.info("skipping query b/c failed to retreive document: " + query);
          EC.increment("kbpQuery/failResolveSourceDoc");
          return null;
        }
        query.setCommunication(c);
      }

//      // 1b) Create an EntityMention for the query mention (for parma features I think?)
//      boolean addEmToCommIfMissing = true;
//      findEntityMention.resolve(q, addEmToCommIfMissing);

      // 1c) Build the context vector
//      if (query.context == null)
//        query.context = new StringTermVec(query.getCommunication());
      query.getContext();
//      if (query.importantTerms == null)
//        query.importantTerms = df.importantTerms(queryContext, 20);
      
      // 2) Extract entity mention features
      if (query.span == null) {
        DependencyParse deps = IndexCommunications.getPreferredDependencyParse(query.getTokenization());
        query.span = IndexCommunications.nounPhraseExpand(query.head, deps);
      }
      // TODO Remove headwords, switch to purely a key-word based retrieval model.
      // NOTE that headwords must match the headwords extracted during the indexing phrase.
      String entityName = query.getEntitySpanGuess();
      String[] headwords = entityName.split("\\s+");    // TODO Filter to NNP words?
      String entityType = query.nerType;
      TokenObservationCounts tokObs = null;
      TokenObservationCounts tokObsLc = null;
      List<String> triageFeats = IndexCommunications.getEntityMentionFeatures(entityName, headwords, entityType, tokObs, tokObsLc);
      TIMER.stop("kbpQuery/setup");
      
      // 3) Search
      List<SitSearchResult> res = triageSearch.search(triageFeats, query.getContext(), df, minTriageScore);
      // Set all results to be the same NER type as input
      for (SitSearchResult r : res)
        r.yhatQueryEntityNerType = query.nerType;

      // 4-5) Retrieve communications and prune
      // TODO Batch retrieval
      {
      TIMER.start("getCommsForSitSearchResults");
      List<SitSearchResult> resKeep = new ArrayList<>();
      int failed = 0;
      for (SitSearchResult r : res) {
        Communication c = getCommCaching(r.getCommunicationId());
        if (c == null) {
          Log.info("warning: pruning result! Could not find Communication with id " + r.getCommunicationId());
          failed++;
        } else {
          r.setCommunication(c);
          resKeep.add(r);
        }
      }
      Log.info("[filter] resultsGiven=" + res.size() + " resultsFailed=" + failed + " resultsKept=" + resKeep.size());
      res = resKeep;
      TIMER.stop("getCommsForSitSearchResults");
      }

      // 6) Find entities and situations
      // (technically situations are not needed, only entities for attribute features)
      {
      List<SitSearchResult> withEntAndSit = new ArrayList<>();
      for (SitSearchResult r : res)
        if (findEntitiesAndSituations(r, df, false))
          withEntAndSit.add(r);
      Log.info("[filter] lost " + (res.size()-withEntAndSit.size()) + " SitSearchMention b/c ent/sit finding");
      res = withEntAndSit;
      }
      
      // 7) Rescore according to attribute features
      // We need to have the Communications (specifically deps, POS, NER) to do this
      // attributeFeatures look like "PERSON-nn-Dr." and are generated by {@link NNPSense}
//      if (query.attrTokFeatures == null)
//        query.attrTokFeatures = Feat.promote(1, NNPSense.extractAttributeFeatures(query.tokUuid, query.getCommunication(), headwords));
//      if (query.attrCommFeatures == null)
//        query.attrCommFeatures = Feat.promote(1, NNPSense.extractAttributeFeatures(null, query.getCommunication(), headwords));
      String sourceTok = query.getTokenization().getUuid().getUuidString();
      attrFeatureReranking(entityName, sourceTok, query.getCommunication(), res);
      
      return res;
    }

    @Override
    public void close() throws Exception {
      if (commRetFetch != null)
        commRetFetch.close();
    }
  }
  

  public static void kbpSearching(ExperimentProperties config) throws Exception {
//    // TUNNEL DEBUGGING
//    Instance inst = new ZooKeeperInstance("minigrid", "localhost:8099");
//    AuthenticationToken pw = new PasswordToken("an accumulo reader");
//    Connector conn = inst.getConnector("reader", pw);
//    Scanner s = conn.createScanner("simple_accumulo_dev", new Authorizations());
//    for (Entry<Key, Value> e : s) {
//      System.out.println(e.getKey().getRow().toString());
//    }
    
    // One file per query goes into this folder, each containing a:
    // Pair<KbpQuery, List<SitSearchResult>>
    File dirForSerializingResults = config.getOrMakeDir("serializeQueryResponsesDir");
    
    // Finds EntityMentions for query documents which just come with char offsets.
    TacQueryEntityMentionResolver findEntityMention =
        new TacQueryEntityMentionResolver("tacQuery");
    
    // Gets Communications (and contents/annotations) given an id
    SimpleAccumuloCommRetrieval commRet = new SimpleAccumuloCommRetrieval();

    // TODO Include OfflineBatchParseyAnnotator working dir and logic
    // Extract comms to parse @COE, copy to laptop and parse there

    Set<String> debugQueriesDoFirst = new HashSet<>();
    for (String s : config.getString("debugQueriesDoFirst", "").split(",")) {
      Log.info("debugQueriesDoFirst: " + s);
      debugQueriesDoFirst.add(s);
    }

    // Load the feature cardinality estimator, which is used during triage to
    // search through the most selective features first.
    FeatureCardinalityEstimator.New fce =
        (FeatureCardinalityEstimator.New) FileUtil.deserialize(config.getExistingFile("featureCardinalityEstimator"));
//    // You can provide a separate TSV file of special cases in case the giant FCE scan hasn't finished yet
//    // e.g.
//    // grep '^triage: feat=' mt100.o* | key-values numToks feat | sort -run >/export/projects/twolfe/sit-search/feature-cardinality-estimate/adhoc-b100-featureCardManual.txt
//    // /export/projects/twolfe/sit-search/feature-cardinality-estimate/adhoc-b100-featureCardManual.txt
//    File extraCards = config.getFile("featureCardinalityManual", null);
//    if (extraCards != null)
//      fce.addFromFile(extraCards);

    // How many results per KBP query.
    // Note: each result must have its Communication fetched from the DB,
    // which is currently the most costly part of querying, so set this carefully,
    // and in coordination with maxToksPreDocRetrieval
    int maxResultsPerQuery = config.getInt("maxResultsPerQuery", 30);
    // This affects pruning early in the pipeline
    double maxToksPruningSafetyRatio = config.getDouble("maxToksPruningSafetyRatio", 2);
    int maxToksPreDocRetrieval = (int) Math.max(50, maxToksPruningSafetyRatio * maxResultsPerQuery);
    
    int maxDocsForMultiEntSearch = config.getInt("maxDocsForMultiEntSearch", 250_000);
    
    Log.info("[filter] maxResultsPerQuery=" + maxResultsPerQuery
        + " maxToksPruningSafetyRatio=" + maxToksPruningSafetyRatio
        + " maxToksPreDocRetrieval=" + maxToksPreDocRetrieval);

    TriageSearch search = new TriageSearch(
      SimpleAccumuloConfig.DEFAULT_INSTANCE,
      SimpleAccumuloConfig.DEFAULT_ZOOKEEPERS,
      "reader",
      new PasswordToken("an accumulo reader"),
      fce,
      config.getInt("nThreadsSearch", 4),
      maxToksPreDocRetrieval,
      maxDocsForMultiEntSearch,
      config.getDouble("triageFeatNBPrior", 10),
      config.getBoolean("batchC2W", true));
//      config.getBoolean("cacheFeatureFrequencies", true));
    
    assert !config.containsKey("expensiveFeatureBloomFilter");
//    File bf = config.getFile("expensiveFeatureBloomFilter", null);
//    if (bf != null)
//      search.ignoreFeaturesViaBF(bf);

    Log.info("loading word frequencies...");
    ComputeIdf df = new ComputeIdf(config.getExistingFile("wordDocFreq"));

    Log.info("getting queries...");
    String sfName = config.getString("slotFillQueries", "sf13+sf14");
    List<KbpQuery> queries = TacKbp.getKbpSfQueries(sfName);
    if (debugQueriesDoFirst.size() > 0) {
      Collections.sort(queries, new Comparator<KbpQuery>() {
        @Override
        public int compare(KbpQuery a, KbpQuery b) {
          int aa = debugQueriesDoFirst.contains(a.id) ? 1 : 0;
          int bb = debugQueriesDoFirst.contains(b.id) ? 1 : 0;
          return bb - aa;
        }
      });
    }

    boolean show = config.getBoolean("show", false);

    Log.info("starting on nQueries=" + queries.size());
    for (int qi = 0; qi < queries.size(); qi++) {
      KbpQuery q = queries.get(qi);
      EC.increment("kbpQuery");
      TIMER.start("kbpQuery");
      Log.info("rank=" + (qi+1) + " of=" + queries.size() + "\t" + q);

      TIMER.start("kbpQuery/setup");
      // 1a) Retrieve the context Communication
      q.sourceComm = commRet.get(q.docid);
      if (q.sourceComm == null) {
        Log.info("skipping query b/c failed to retreive document: " + q);
        EC.increment("kbpQuery/failResolveSourceDoc");
        continue;
      }
      
      // 1b) Create an EntityMention for the query mention (for parma features I think?)
      boolean addEmToCommIfMissing = true;
      findEntityMention.resolve(q, addEmToCommIfMissing);

      // 1c) Build the context vector
      StringTermVec queryContext = new StringTermVec(q.sourceComm);
      q.docCtxImportantTerms = df.importantTerms(queryContext, 20);
      
      // 2) Extract entity mention features
      // TODO Remove headwords, switch to purely a key-word based retrieval model.
      // NOTE that headwords must match the headwords extracted during the indexing phrase.
      String[] headwords = new String[] {};
      String entityName = q.name;
      String entityType = TacKbp.tacNerTypesToStanfordNerType(q.entity_type);
      TokenObservationCounts tokObs = null;
      TokenObservationCounts tokObsLc = null;
      List<String> triageFeats = IndexCommunications.getEntityMentionFeatures(entityName, headwords, entityType, tokObs, tokObsLc);
      TIMER.stop("kbpQuery/setup");
      
      // 3) Search
      List<SitSearchResult> res = search.search(triageFeats, queryContext, df, null);

      // 4-5) Retrieve communications and prune
      {
      TIMER.start("getCommsForSitSearchResults");
      List<SitSearchResult> resKeep = new ArrayList<>();
      int failed = 0;
      for (SitSearchResult r : res) {
        Communication c = q.getCommWithDefault(r.getCommunicationId(), commRet::get);
        if (c == null) {
          Log.info("warning: pruning result! Could not find Communication with id " + r.getCommunicationId());
          failed++;
        } else {
          r.setCommunication(c);
          resKeep.add(r);
          if (maxResultsPerQuery > 0 && resKeep.size() >= maxResultsPerQuery)
            break;
        }
      }
      Log.info("[filter] resultsGiven=" + res.size() + " resultsFailed=" + failed + " resultsKept=" + resKeep.size());
      res = resKeep;
      TIMER.stop("getCommsForSitSearchResults");
      }

      // 6) Find entities and situations
      // (technically situations are not needed, only entities for attribute features)
      findEntitiesAndSituations(q, res, df, PARMA_SIT_TOOL);
      
      // 7) Rescore according to attribute features
      // We need to have the Communications (specifically deps, POS, NER) to do this
      // attributeFeatures look like "PERSON-nn-Dr." and are generated by {@link NNPSense}
      attrFeatureReranking(q, res);

      // 8) Show results
      if (show) {
        TIMER.start("showResults");
        for (SitSearchResult r : res) {
          ShowResult sr = new ShowResult(q, r);
          sr.show(Collections.emptyList());
        }
        TIMER.stop("showResults");
      }
      
      // 9) Serialize results
      // Query stores all comms, results store none (reset them upon deserialization)
      TIMER.start("serializeResults");
      assert q.sourceComm != null;
      for (SitSearchResult r : res) {
        assert r.getCommunication() != null;
        r.setCommunication(null);
      }
      Pair<KbpQuery, List<SitSearchResult>> toSer = new Pair<>(q, res);
      File toSerTo = new File(dirForSerializingResults,
        q.id + "-" + q.name.replaceAll(" ", "_") + ".qrs.jser");
      Log.info("serializing " + res.size() + " results to " + toSerTo.getPath());
      FileUtil.serialize(toSer, toSerTo);
      TIMER.stop("serializeResults");

      TIMER.stop("kbpQuery");
      System.out.println(TIMER);
    } // END of query loop
  }
  
  /** Restore the {@link Communication} stored in each {@link SitSearchResult} from the {@link KbpQuery} */
  public static void getCommsFromQuery(KbpQuery q, List<SitSearchResult> res) {
    for (SitSearchResult r : res) {
      assert r.getCommunication() == null;
      Communication c = q.getCommWithDefault(r.getCommunicationId(), null);
      assert c != null;
      r.setCommunication(c);
    }
  }
  
  public static List<SitSearchResult> removeResultsInSameDocAsQuery(KbpQuery q, List<SitSearchResult> res) {
    List<SitSearchResult> out = new ArrayList<>();
    for (SitSearchResult r : res)
      if (!r.getCommunicationId().equals(q.docid))
        out.add(r);
    return out;
  }
  
  /**
   * Removes duplicate {@link Communication}s (by looking at their id).
   * You can get duplicates when you serialize, upon deserialization java
   * doesn't re-construct the original contents of memory by having shared
   * pointers point to a single item in memory.
   * (for an example see SerializationTest)
   * 
   * This method does not check that communications that share an id are
   * byte-for-byte identical, so be careful.
   */
  public static void dedupCommunications(List<SitSearchResult> res) {
    int overwrote = 0, kept = 0;
    Map<String, Communication> m = new HashMap<>();
    for (SitSearchResult r : res) {
      Communication c = r.getCommunication();
      if (c == null)
        throw new IllegalArgumentException();
      Communication old = m.put(c.getId(), c);
      if (old != null) {
        r.setCommunication(old);
        m.put(c.getId(), old);
        overwrote++;
      } else {
        kept++;
      }
    }
    Log.info("done, kept=" + kept + " overwrote=" + overwrote);
  }
  
  public static List<Communication> extractCommunications(List<SitSearchResult> res) {
    Set<String> ids = new HashSet<>();
    List<Communication> l = new ArrayList<>();
    for (SitSearchResult s : res) {
      if (ids.add(s.getCommunicationId())) {
        Communication c = s.getCommunication();
        assert c != null;
        l.add(c);
      }
    }
    return l;
  }
  

  /**
   * Sets the {@link SitSearchResult#yhatEntitySituation} and
   * {@link SitSearchResult#yhatQueryEntityHead} fields and
   * creates {@link SituationMention}s for parma (via {@link ConcreteMentionFeatureExtractor}).
   * 
   * @param parmaSitTool is the tool name use for new {@link SituationMentionSet}s
   * If this is null, then {@link SituationMention}s will NOT be added to.
   * 
   * Precondition: {@link SitSearchResult} must have their {@link Communication}s set.
   */
  public static void findEntitiesAndSituations(KbpQuery q, List<SitSearchResult> res, ComputeIdf df, String parmaSitTool) {
    Log.info("nResults=" + res.size() + " query=" + q.toString() + " insitu=" + q.findMentionHighlighted());
    TIMER.start("findEntitiesAndSituations");
    ExperimentProperties config = ExperimentProperties.getInstance();
    boolean verbose = config.getBoolean("findEntSitVerbose", false);
    List<SitSearchResult> resWithSituations = new ArrayList<>();
    for (int resultIdx = 0; resultIdx < res.size(); resultIdx++) {
      SitSearchResult r = res.get(resultIdx);
      if (r.getCommunication() == null)
        throw new IllegalArgumentException();

      // Figure out what events are interesting
      if (r.triageFeatures == null) {
        TIMER.start("computeTriageFeats");
        TokenObservationCounts tokObs = null;
        TokenObservationCounts tokObsLc = null;
        String nerType = TacKbp.tacNerTypesToStanfordNerType(q.entity_type);
        String[] headwords = q.name.split("\\s+");  // TODO
        r.triageFeatures = IndexCommunications.getEntityMentionFeatures(q.name, headwords, nerType, tokObs, tokObsLc);
        TIMER.stop("computeTriageFeats");
        EC.increment("computeTriageFeats");
      }

      // Search for an interesting situation
      EntityEventPathExtraction eep = new EntityEventPathExtraction(r);
      if (verbose)
        eep.verboseEntSelection = true;
      IntPair entEvent = eep.findMostInterestingEvent(df, verbose);
      r.yhatQueryEntityHead = entEvent.first;
      r.yhatEntitySituation = entEvent.second;

      if (r.yhatEntitySituation < 0)
        EC.increment("result/skip/noSit");
      if (r.yhatQueryEntityHead < 0)
        EC.increment("result/skip/noEnt");
      if (r.yhatEntitySituation < 0 || r.yhatQueryEntityHead < 0)
        continue;
      EC.increment("result/entSitsSelected");
      resWithSituations.add(r);

      if (parmaSitTool != null) {
        SituationMention sm = ParmaVw.makeSingleTokenSm(r.yhatEntitySituation, r.tokUuid, eep.getClass().getName());
        sm.setUuid(new UUID("mention" + resultIdx));
//        Log.info("created smUuid=" + sm.getUuid().getUuidString()
//            + " for SitSearchResult tokUuid=" + r.tokUuid
//            + " in " + r.getCommunicationId()
//            + " sm.trs=" + sm.getTokens()
//            + "\t" + r.getWordsInTokenizationWithHighlightedEntAndSit());
        ParmaVw.addToOrCreateSitutationMentionSet(r.getCommunication(), sm, parmaSitTool);
      }
    }
    Log.info("[filter] lost " + (res.size() - resWithSituations.size()) +  " results due to ent/sit finding failures");
    if (verbose)
      System.out.println(EC);
    res.clear();
    res.addAll(resWithSituations);
    TIMER.stop("findEntitiesAndSituations");
  }

  /** returns false if it failed */
  public static boolean findEntitiesAndSituations(SitSearchResult r, ComputeIdf df, boolean verbose) {
    if (r.getCommunication() == null)
      throw new IllegalArgumentException();

    // Figure out what events are interesting
    if (r.triageFeatures == null)
      throw new IllegalArgumentException();

    // Search for an interesting situation
    EntityEventPathExtraction eep = new EntityEventPathExtraction(r);
    if (verbose)
      eep.verboseEntSelection = true;
    IntPair entEvent = eep.findMostInterestingEvent(df, verbose);
    r.yhatQueryEntityHead = entEvent.first;
    r.yhatEntitySituation = entEvent.second;

    if (r.yhatEntitySituation < 0)
      EC.increment("findEntitiesAndSituations/noSit");
    if (r.yhatQueryEntityHead < 0)
      EC.increment("findEntitiesAndSituations/noEnt");
    if (r.yhatEntitySituation < 0 || r.yhatQueryEntityHead < 0)
      return false;
    EC.increment("findEntitiesAndSituations/entSitsSelected");

    // Set the entity span
    DependencyParse deps = IndexCommunications.getPreferredDependencyParse(r.getTokenization());
    r.yhatQueryEntitySpan = IndexCommunications.nounPhraseExpand(r.yhatQueryEntityHead, deps);

    return true;
  }

  public static List<SitSearchResult> parmaDedup(ParmaVw parma, KbpQuery q, List<SitSearchResult> res, int maxResults, boolean show) {
    // Call parma to remove duplicates
    // NOTE: ParmaVw expect that the situations are *in the communications* when dedup is called.
    //        parma.verbose(true);
    Log.info("starting dedup for " + q);

    TIMER.start("parmaDedup");
    List<QResultCluster> dedupped = parma.dedup(res, maxResults);
    System.out.println();
    TIMER.stop("parmaDedup");

    if (show) {
      Log.info("showing dedupped results for " + q);
      for (QResultCluster clust : dedupped) {
        ShowResult sr = new ShowResult(q, clust.canonical);
        sr.show(Collections.emptyList());
        EC.increment("result/dedup/canonical");

        // Show things we thought were coref with the canonical above:
        int nr = clust.numRedundant();
        System.out.println("this mention is canonical for " + nr + " other mentions");
        for (int i = 0; i < nr; i++) {
          System.out.println("\tredundant: " + clust.getRedundant(i).getWordsInTokenizationWithHighlightedEntAndSit());
          EC.increment("result/dedup/redundant");
        }
      }
      System.out.println();
    }
    
    List<SitSearchResult> resDedup = new ArrayList<>();
    for (QResultCluster w : dedupped)
      resDedup.add(w.canonical);
    return resDedup;
  }

  // This is the tool name for SituationMentionSets containing events which parma is intended to dedup
  public static final String PARMA_SIT_TOOL = EntityEventPathExtraction.class.getName();
  
  public static void kbpSearchingMemo(ExperimentProperties config) throws Exception {
    // One file per query goes into this folder, each containing a:
    // Pair<KbpQuery, List<SitSearchResult>>
//    File dirForSerializingResults = config.getOrMakeDir("serializeQueryResponsesDir", new File("data/sit-search/old/maxToks100"));
//    File dirForSerializingResults = config.getOrMakeDir("serializeQueryResponsesDir", new File("data/sit-search/maxToks1000"));
    File dirForSerializingResults = config.getOrMakeDir("serializeQueryResponsesDir", new File("data/sit-search/maxRes30"));
    
    File wdf = config.getExistingFile("wordDocFreq");
    ComputeIdf df = new ComputeIdf(wdf);
    
    // Load parma
    ParmaVw parma = null;
    boolean useParma = config.getBoolean("useParma", false);
    Log.info("useParma=" + useParma);
    if (useParma) {
      File modelFile = config.getFile("dedup.model");
      IntDoubleHashMap idf = null;
      String parmaEntTool = null;
      int parmaVwPort = config.getInt("vw.port", 8094);
      parma = new ParmaVw(modelFile, idf, PARMA_SIT_TOOL, parmaEntTool, parmaVwPort);
    }

    // Attribute feature reranking
    // (now handled in first stage of pipeline, may want to add back if this code changes)
    boolean attrFeatureRerank = config.getBoolean("attributeFeatureReranking", false);
    
    boolean show = config.getBoolean("show", true);
    
    // m-turk HIT output
    File mturkCorefCsv = config.getFile("mturkCorefCsv", null);
    CSVPrinter mturkCorefCsvW = null;
    if (mturkCorefCsv != null) {
      String[] mturkCorefCsvCols = MturkCorefHit.getMturkCorefHitHeader();
      CSVFormat csvFormat = CSVFormat.DEFAULT.withHeader(mturkCorefCsvCols);
      mturkCorefCsvW = new CSVPrinter(FileUtil.getWriter(mturkCorefCsv), csvFormat);
    }

    File mturkHtmlDebugDir = new File("/tmp/mturk-html-debug");
    if (mturkHtmlDebugDir.isDirectory()) {
      Log.info("cleaning out mturkHtmlDebugDir=" + mturkHtmlDebugDir.getPath());
      FileUtil.rm_rf(mturkHtmlDebugDir);
    }
    mturkHtmlDebugDir.mkdirs();

    // Iterate over (query, result*), one per file
    for (File f : dirForSerializingResults.listFiles()) {
      EC.increment("query");
      TIMER.start("query");
      TIMER.start("deser/input");
      FileUtil.VERBOSE = true;
      @SuppressWarnings("unchecked")
      Pair<KbpQuery, List<SitSearchResult>> p = (Pair<KbpQuery, List<SitSearchResult>>) FileUtil.deserialize(f);
      TIMER.stop("deser/input");
      KbpQuery q = p.get1();
      List<SitSearchResult> res = p.get2();

      // Compute important terms in the query doc (I think this was FUBAR the first time)
      StringTermVec queryContext = new StringTermVec(q.sourceComm);
      q.docCtxImportantTerms = df.importantTerms(queryContext, 20);
      
      // Dedup communications (introduced during serialization process)
      // TODO set the comms to null and serialize a Map<String, Communication> separately
//      dedupCommunications(res);
      getCommsFromQuery(q, res);
      
      // This adds SituationMentions which are used by parmaDedup
//      findEntitiesAndSituations(q, res, df, PARMA_SIT_TOOL);

      // TODO NER type matching
      // e.g. "Hollister" may refer to a person or an organization

      // Attribute features
      if (attrFeatureRerank) {
        Log.info("performing attribute feature reranking");
        attrFeatureReranking(q, res);
      }

      if (useParma) {
        int maxResults = config.getInt("numDeduppedResults", 10); // NOTE: Parma is O(n^2) in this value!
        res = parmaDedup(parma, q, res, maxResults, show);
      } else if (show) {
        for (int i = 0; i < res.size(); i++) {
          SitSearchResult r = res.get(i);
          ShowResult sr = new ShowResult(q, r);
          System.out.println("rank=" + (i+1) + " of=" + res.size());
          sr.show(Collections.emptyList());
        }
      }
      
      // TODO Output things dedupped by parma too?
      if (mturkCorefCsv != null) {
        for (int i = 0; i < res.size(); i++) {
          SitSearchResult r = res.get(i);
          MturkCorefHit mtc = new MturkCorefHit(q, r);
          IntPair rank = new IntPair(i+1, res.size());
          String[] csv = mtc.emitMturkCorefHit(mturkHtmlDebugDir, rank, r.getScore());
          mturkCorefCsvW.printRecord(csv);
          mturkCorefCsvW.flush();
        }
      }

      System.out.println("timer:");
      System.out.println(TIMER);
      System.out.println();

      TIMER.stop("query");
    }

    System.out.println("events counts:");
    System.out.println(EC);
    System.out.println();

    if (parma != null)
      parma.close();
    if (mturkCorefCsvW != null)
      mturkCorefCsvW.close();

    Log.info("done");
  }


  /**
   * @deprecated
   * @see BuildBigFeatureBloomFilters
   * @see FeatureCardinalityEstimator
   */
  public static class ComputeFeatureFrequencies {
    public static void main(ExperimentProperties config) throws Exception {
      File out = config.getFile("output");
      Log.info("writing <feature> <tab> <tokFrequency> to " + out.getPath());
      TimeMarker tm = new TimeMarker();
      double interval = config.getDouble("interval", 10);
      Instance inst = new ZooKeeperInstance(
          config.getString("accumulo.instance"),
          config.getString("accumulo.zookeepers"));
      Connector conn = inst.getConnector(
          config.getString("accumulo.username"),
          new PasswordToken(config.getString("accumulo.password")));
      Counts<String> ec = new Counts<>();
      try (Scanner s = conn.createScanner(T_f2t.toString(), new Authorizations());
          BufferedWriter w = FileUtil.getWriter(out)) {
        Text prevRow = null;
        int prevCount = 0;
        for (Entry<Key, Value> e : s) {
          ec.increment("entries");
          Text row = e.getKey().getRow();
          if (!row.equals(prevRow)) {
            ec.increment("rows");
            if (prevCount > 10) ec.increment("bigrow10");
            if (prevCount > 100) ec.increment("bigrow100");
            if (prevCount > 1000) ec.increment("bigrow1000");
            if (prevCount > 10000) ec.increment("bigrow10000");

            if (row != null)
              w.write(row.toString() + "\t" + prevCount + "\n");
            prevRow = row;
            prevCount = 0;
          }
          prevCount++;
          
          if (tm.enoughTimePassed(interval))
            Log.info(ec + "\t" + Describe.memoryUsage());
        }
        if (prevRow != null)
          w.write(prevRow.toString() + "\t" + prevCount + "\n");
      }
      Log.info("done\t" + ec);
    }
  }

  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
//    String c = config.getString("command");
//    if (c.equalsIgnoreCase("buildIndexMR")) {
//      Log.info("you probably don't want to so this, use regular");
//      BuildIndexMR.main(config);
//    } else if (c.equalsIgnoreCase("buildIndexRegular")) {
//      BuildIndexRegular.main(config);
//    } else if (c.equalsIgnoreCase("computeIdf")) {
//      ComputeIdf.main(config);
//    } else if (c.equalsIgnoreCase("kbpSearch")) {
//      kbpSearching(config);
//    } else if (c.equalsIgnoreCase("featureFrequency")) {
//      ComputeFeatureFrequencies.main(config);
//    } else if (c.equalsIgnoreCase("buildBigFeatureBloomFilters")) {
//      BuildBigFeatureBloomFilters.main(config);
//    } else if (c.equalsIgnoreCase("kbpSearchMemo")) {
//      kbpSearchingMemo(config);
//    } else if (c.equalsIgnoreCase("develop")) {
//      IndexCommunications.develop(config);
//    } else {
//      Log.info("unknown command: " + c);
    //    }

    /*
    File cacheDir = config.getOrMakeDir("cacheDir", new File("data/sit-search/fetch-comms-cache"));
    String host = config.getString("host", "localhost");
    int port = config.getInt("port", 9999);
    try (DiskBackedFetchWrapper f = KbpSearching.buildFetchWrapper(cacheDir, host, port)) {
      f.debug = true;
      String[] ids = new String[] {"NYT_ENG_20090825.0083", "AFP_ENG_20090831.0329", "XIN_ENG_20090824.0098", "Afghan_presidential_election,_2009"};
      Log.info("searching for " + Arrays.toString(ids));
      List<Communication> c = f.fetch(ids);
      //    List<Communication> c = f.getFailover().fetch(DiskBackedFetchWrapper.fetchRequest(ids)).getCommunications();
      Log.info("got back " + c.size() + " comms");

      for (Communication comm : c)
        System.out.println(comm.getId() + " " + StringUtils.trim(comm.getText().replaceAll("\n", " "), 100));
    }
    */
    
    List<String> fs = new ArrayList<>();
    fs.add("pb:BBBB_karzai");
    fs.add("h:Karzai");
    fs.add("h:Ghazni");
    fs.add("pb:ramazan_bashardost");
    fs.add("pi:mr");
    fs.add("pi:ghazni");
    fs.add("pb:ghazni_AAAA");

    int maxResults = 100;
    int maxDocsMulti = 100_000;
    File f = config.getExistingFile("triageFeatureFrequencies", new File("/export/projects/twolfe/sit-search/feature-cardinality-estimate_maxMin/fce-mostFreq1000000-nhash12-logb20.jser"));
    Log.info("loading from " + f.getPath());
    FeatureCardinalityEstimator.New fce = (FeatureCardinalityEstimator.New) FileUtil.deserialize(f);
    TriageSearch ts = new TriageSearch(fce, maxResults, maxDocsMulti);
    ts.benchmarkAll(fs);
    
    Log.info("done");
  }
  
  
  

  /* BS for later:
   * Bayes rule:                  p(t|f) = p(f|t) * p(t) / p(f)
   * non-parametric Bayes rule:   p(t|f) = phi(f,t) / Z(f)
   *    where we could model phi(f,t) as co-occurrences of (f,t) and perhaps cluster parents of f and t.
   *    put another way: define some graph for f and t, compute random walk probs for nodes in f and t's graphs, assume f-walk and t-walks are independent, measure prob that f-walk and t-walk end up at same node = sum over nodes
   *    this graph is the encoding of distributional similarity, e.g. f->nsubj->John, f->dobj->PER, etc (hops through resources like PPDB, WN, even word2vec NNs, etc)
   * if you use this random walk model, then perhaps def of conditional prob is useful:
   *    p(t|f) = p(t,f)/p(f) where model the co-occurrence probs with the random walk
   */


}
