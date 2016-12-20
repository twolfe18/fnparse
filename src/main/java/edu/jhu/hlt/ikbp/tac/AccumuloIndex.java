package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
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
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.thrift.TDeserializer;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.concrete.access.FetchRequest;
import edu.jhu.hlt.concrete.access.FetchResult;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumulo;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloConfig;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloFetch;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.CommunicationRetrieval;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.EntityEventPathExtraction;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.ParmaVw;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.ParmaVw.QResultCluster;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.ShowResult;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SitSearchResult;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SituationSearch;
import edu.jhu.hlt.ikbp.tac.TacKbp.KbpQuery;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.hlt.utilt.AutoCloseableIterator;
import edu.jhu.prim.map.IntDoubleHashMap;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.SerializationTest;
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
 * TODO create c2f_idf etc tables
 * 
 *
 * @author travis
 */
public class AccumuloIndex {

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

  
  /** Returns the set of inserts for this comm, all things we are indexing (across all tables) */
  public static List<Pair<Text, Mutation>> buildMutations(Communication comm) {
    List<Pair<Text, Mutation>> mut = new ArrayList<>();
    CharSequence c = comm.getId();
    byte[] cb = comm.getId().getBytes();

    // c2w
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

  
  
  public static class ComputeIdf {
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
      List<Pair<String, Double>> t = new ArrayList<>();
      for (Entry<String, Integer> tf : a) {
        double s = tf.getValue() * idf(tf.getKey());
        t.add(new Pair<>(tf.getKey(), s));
      }
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
      if (k > t.size())
        k = t.size();
      List<String> p = new ArrayList<>(k);
      for (int i = 0; i < k; i++)
        p.add(t.get(i).get1());
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
   * Gets {@link Communication}s given an id. Similar to {@link CommunicationRetrieval}.
   */
  public static class AccumuloCommRetrieval {
    private SimpleAccumuloFetch fetch;

    public AccumuloCommRetrieval(ExperimentProperties config) throws Exception {
      int numThreads = 1;
      SimpleAccumuloConfig saConf = SimpleAccumuloConfig.fromConfig(config);
      fetch = new SimpleAccumuloFetch(saConf, numThreads);
      fetch.connect(
          config.getString("accumulo.username"),
          new PasswordToken(config.getString("accumulo.password")));
    }
    
    public Communication get(String commId) {
      TIMER.start("accCommRet");
      FetchRequest fr = new FetchRequest();
      fr.addToCommunicationIds(commId);;
      try {
        FetchResult r = fetch.fetch(fr);
        TIMER.stop("accCommRet");
        if (!r.isSetCommunications() || r.getCommunicationsSize() == 0)
          return null;
        return r.getCommunications().get(0);
      } catch (Exception e) {
        e.printStackTrace();
        TIMER.stop("accCommRet");
        return null;
      }
    }
  }

  
  /**
   * Some features appear in a ton of mentions/tokenizations. This really slow down retrieval
   * and are not informative. This class makes a pass over the f2t table and builds a bloom
   * filter for features which return more than K tokenizations. You can do what you like with
   * these features given the BF (e.g. eliminating them may not be a good idea, but using them
   * as a last resort if more selective features don't return a good result is an option).
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
  
  public static class WeightedFeature implements Serializable {
    private static final long serialVersionUID = 5810788524410495193L;
    public final String feature;
    public final double weight;
    public WeightedFeature(String f, double w) {
      this.feature = f;
      this.weight = w;
    }
  }

  /**
   * Roughly equivalent to {@link SituationSearch}
   * TODO parma integration
   */
  public static class Search {
    private String username;
    private AuthenticationToken password;
    private Authorizations auths;
    private Connector conn;

    int numQueryThreads;
    int batchTimeoutSeconds = 60;

    int maxToksPreDocRetrieval = 10 * 1000;
    double triageFeatNBPrior = 50;    // higher values penalize very frequent features less
    
    /** @deprecated should have just used batchTimeout... */
    private BloomFilter<String> expensiveFeaturesBF;

    public Search(String instanceName, String zks, String username, AuthenticationToken password,
        int nThreads, int maxToksPreDocRetrieval, double triageFeatNBPrior) throws Exception {
      Log.info("maxToksPreDocRetrieval=" + maxToksPreDocRetrieval
          + " triageFeatNBPrior=" + triageFeatNBPrior
          + " instanceName=" + instanceName
          + " username=" + username
          + " nThreads=" + nThreads
          + " zks=" + zks);
      this.maxToksPreDocRetrieval = maxToksPreDocRetrieval;
      this.triageFeatNBPrior = triageFeatNBPrior;
      this.username = username;
      this.password = password;
      this.auths = new Authorizations();
      this.numQueryThreads = nThreads;
      Instance inst = new ZooKeeperInstance(instanceName, zks);
      this.conn = inst.getConnector(username, password);
    }
    
    /**
     * Exclude certain common and un-informative features from triage search.
     * @param bfFile is created by {@link BuildBigFeatureBloomFilters}
     */
    @SuppressWarnings("unchecked")
    public void ignoreFeaturesViaBF(File bfFile) {
      Log.info("deserializing bloom filter from " + bfFile.getPath());
      expensiveFeaturesBF = (BloomFilter<String>) FileUtil.deserialize(bfFile);
    }

    public List<SitSearchResult> search(List<String> triageFeats, StringTermVec docContext, ComputeIdf df) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
      Log.info("starting, triageFeats=" + triageFeats);
      if (batchTimeoutSeconds > 0)
        Log.info("using a timeout of " + batchTimeoutSeconds + " seconds for f2t query");

      if (triageFeats.size() > 8) {
        Log.info("WARNING! something is broken when there are too many features (" + triageFeats.size() + "), skipping");
        return Collections.emptyList();
      }

      if (expensiveFeaturesBF != null) {
        List<String> pruned = new ArrayList<>(triageFeats.size());
        for (String f : triageFeats) {
          if (expensiveFeaturesBF.mightContain(f)) {
            System.out.println("\tpruning expensive feature: " + f);
          } else {
            pruned.add(f);
          }
        }
        Log.info("kept " + pruned.size() + " of " + triageFeats.size() + " features");
        triageFeats = pruned;
      }
      
      // Make a batch scanner to retrieve all tokenization which contain any triageFeats
      TIMER.start("f2t/triage");
      Counts.Pseudo<String> tokUuid2score = new Counts.Pseudo<>();

      // Don't use batch scanner, use one scanner per feature
      // TODO Consider the ordering over features, most discriminative to least
      // (allows you to skip retrieving toks for weak features if you have good signal from other features)
      Map<String, List<WeightedFeature>> tokUuid2MatchedFeatures = new HashMap<>();
      for (String f : triageFeats) {
        try (Scanner f2tScanner = conn.createScanner(T_f2t.toString(), auths)) {
          f2tScanner.setRange(Range.exact(f));

          // Collect all of the tokenizations which this feature co-occurs with
          List<String> toks = new ArrayList<>();
          Set<String> commUuidPrefixes = new HashSet<>();
          for (Entry<Key, Value> e : f2tScanner) {
            String tokUuid = e.getKey().getColumnQualifier().toString();
            toks.add(tokUuid);
            commUuidPrefixes.add(getCommUuidPrefixFromTokUuid(tokUuid));
          }

          // Compute a score based on how selective this feature is
          int numToks = toks.size();
          int numDocs = commUuidPrefixes.size();
          double freq = (2d * numToks * numDocs) / (numToks + numDocs);
          double p = (triageFeatNBPrior + 1) / (triageFeatNBPrior + freq);
          System.out.println("triage:"
              + " feat=" + f
              + " numToks=" + numToks
              + " numDocs=" + numDocs
              + " freq=" + freq
              + " triageFeatNBPrior=" + triageFeatNBPrior
              + " p=" + p);

          // Update the running score for all tokenizations
          for (String t : toks) {
            tokUuid2score.update(t, p);

            List<WeightedFeature> wfs = tokUuid2MatchedFeatures.get(f);
            if (wfs == null) {
              wfs = new ArrayList<>();
              tokUuid2MatchedFeatures.put(t, wfs);
            }
            wfs.add(new WeightedFeature(f, p));
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
      Map<String, StringTermVec> commId2terms = getWordsForComms(tokUuid2commId.values());
      
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
        
        //String commUuid = tokUuid2commUuid.get(tokUuid);
        StringTermVec commVec = commId2terms.get(commId);
        double tfidf = -10;
        if (commVec == null) {
          Log.info("WARNING: could not lookup words for commId=" + commId);
        } else {
          tfidf = df.tfIdfCosineSim(docContext, commVec);
          ss.importantTerms = df.importantTerms(commVec, 20);
        }
        score.add(new Feat("tfidf").setWeight(tfidf));
        
        //score.add(new Feat("entMatch").setWeight(r.getValue()));
        double entMatchScore = tokUuid2score.getCount(tokUuid);
        score.add(new Feat("entMatch").setWeight(entMatchScore));

        double prod = entMatchScore * (0.1 + tfidf);
        score.add(new Feat("prod").setWeight(prod).rescale("goodfeat", 10.0));

        // Filtering
        if (entMatchScore < 0.001) {
          EC.increment("resFilter/name");
          filterReasons.increment("name");
          continue;
        }
        if (tfidf < 0.1 && entMatchScore < 0.01) {
          EC.increment("resFilter/prod");
          filterReasons.increment("prod");
          continue;
        }
        
        res.add(ss);
      }
      Log.info("reasons for filtering results for " + triageFeats + ": " + filterReasons);

      // 4) Sort results by final score
      Collections.sort(res, SitSearchResult.BY_SCORE_DESC);
      
      return res;
    }
    
    /** returned map is tokUuid -> commId */
    private Map<String, String> getCommIdsFor(Counts.Pseudo<String> tokUuid2score) throws TableNotFoundException {
      TIMER.start("t2c/getCommIdsFor");

      // TODO Consider filtering based on score?
      List<String> bestToks = tokUuid2score.getKeysSortedByCount(true);
      if (bestToks.size() > maxToksPreDocRetrieval) {
        Log.info("only taking the " + maxToksPreDocRetrieval + " highest scoring of " + bestToks.size() + " tokenizations");
        bestToks = bestToks.subList(0, maxToksPreDocRetrieval);
      }

      List<Range> rows = new ArrayList<>();
      for (String s : bestToks)
        rows.add(Range.exact(s));

      int numQueryThreads = 4;
      Map<String, String> t2c = new HashMap<>();
      try (BatchScanner bs = conn.createBatchScanner(T_t2c.toString(), auths, numQueryThreads)) {
        bs.setRanges(rows);
        for (Entry<Key, Value> e : bs) {
          String tokUuid = e.getKey().getRow().toString();
          String commId = e.getValue().toString();
          Object old = t2c.put(tokUuid, commId);
          assert old == null;
        }
      }
      TIMER.stop("t2c/getCommIdsFor");
      return t2c;
    }
    
    /** keys of returned map are comm ids */
    private Map<String, StringTermVec> getWordsForComms(Iterable<String> commIdsNonUniq) throws TableNotFoundException {
      TIMER.start("c2w/getWordsForComms");
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
      Map<String, StringTermVec> c2tv = new HashMap<>();
      for (String commId : uniq) {
        StringTermVec tv = new StringTermVec();
        try (Scanner s = conn.createScanner(T_c2w.toString(), auths)) {
          s.setRange(Range.exact(commId));
          for (Entry<Key, Value> e : s) {
            String word = e.getKey().getColumnQualifier().toString();
            int count = decodeCount(e.getValue().get());
            tv.add(word, count);
          }
        }
        Object old = c2tv.put(commId, tv);
        assert old == null;
      }
      Log.info("retrieved " + c2tv.size() + " of " + uniq.size() + " comms");
      TIMER.stop("c2w/getWordsForComms");
      return c2tv;
    }
  }
  
  
  /**
   * Holds tf not idf.
   */
  public static class StringTermVec implements Iterable<Entry<String, Integer>> {
    private Counts<String> tf;
    
    public StringTermVec() {
      tf = new Counts<>();
    }
    
    public StringTermVec(Communication c) {
      this();
      for (String s : IndexCommunications.terms(c))
        add(s, 1);
    }
    
    public int getTotalCount() {
      return tf.getTotalCount();
    }
    
    public void add(String word, int count) {
      tf.update(word, count);
    }

    @Override
    public Iterator<Entry<String, Integer>> iterator() {
      return tf.entrySet().iterator();
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
    
    AccumuloCommRetrieval commRet = new AccumuloCommRetrieval(config);


    Set<String> debugQueriesDoFirst = new HashSet<>();
    for (String s : config.getString("debugQueriesDoFirst", "").split(",")) {
      Log.info("debugQueriesDoFirst: " + s);
      debugQueriesDoFirst.add(s);
    }


    Search search = new Search(
      config.getString("accumulo.instance"),
      config.getString("accumulo.zookeepers"),
      config.getString("accumulo.username"),
      new PasswordToken(config.getString("accumulo.password")),
      config.getInt("nThreadsSearch", 1),
      config.getInt("maxToksPreDocRetrieval", 10*1000),
      config.getDouble("triageFeatNBPrior", 50));
    
    File bf = config.getFile("expensiveFeatureBloomFilter", null);
    if (bf != null)
      search.ignoreFeaturesViaBF(bf);

    Log.info("loading word frequencies...");
    ComputeIdf df = new ComputeIdf(config.getExistingFile("wordDocFreq"));

    Log.info("getting queries...");
    List<KbpQuery> queries = TacKbp.getKbp2013SfQueries();
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

    // How many results per KBP query.
    int limit = config.getInt("maxResultsPerQuery", 500);

    boolean show = config.getBoolean("show", false);

    Log.info("starting...");
    for (KbpQuery q : queries) {
      EC.increment("kbpQuery");
      Log.info(q);

      // 1a) Retrieve the context Communication
      q.sourceComm = commRet.get(q.docid);
      if (q.sourceComm == null) {
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
      
      // 3) Search
      List<SitSearchResult> res = search.search(triageFeats, queryContext, df);

      // 4) Prune
      if (limit > 0 && res.size() > limit) {
        Log.info("pruning " + res.size() + " queries down to " + limit);
        //res = res.subList(0, limit);  // not serializable
        List<SitSearchResult> resPrune = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++)
          resPrune.add(res.get(i));
        res = resPrune;
      }

      // 5) Retrieve communications
      for (SitSearchResult r : res) {
        Communication c = commRet.get(r.getCommunicationId());
        r.setCommunication(c);
      }

      // 6) Show results
      if (show) {
        TIMER.start("showResults");
        for (SitSearchResult r : res) {
          ShowResult sr = new ShowResult(q, r);
          sr.show(Collections.emptyList());
        }
        TIMER.stop("showResults");
      }
      
      // 7) Serialize results
      TIMER.start("serializeResults");
      Pair<KbpQuery, List<SitSearchResult>> toSer = new Pair<>(q, res);
      File toSerTo = new File(dirForSerializingResults,
        q.id + "-" + q.name.replaceAll(" ", "_") + ".qrs.jser");
      Log.info("serializing " + res.size() + " results to " + toSerTo.getPath());
      FileUtil.serialize(toSer, toSerTo);
      TIMER.stop("serializeResults");

      System.out.println(TIMER);
    } // END of query loop
  }
  
  
  /**
   * Removes duplicate {@link Communication}s (by looking at their id).
   * You can get duplicates when you serialize, upon deserialization java
   * doesn't re-construct the original contents of memory by having shared
   * pointers point to a single item in memory.
   * (for an example see {@link SerializationTest})
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
  
  public static void kbpSearchingMemo(ExperimentProperties config) throws Exception {
    // One file per query goes into this folder, each containing a:
    // Pair<KbpQuery, List<SitSearchResult>>
    File dirForSerializingResults = config.getOrMakeDir("serializeQueryResponsesDir");
    
    File wdf = config.getExistingFile("wordDocFreq");
    ComputeIdf df = new ComputeIdf(wdf);
    
    // Load parma
    ParmaVw parma = null;
    boolean useParma = config.getBoolean("useParma", true);
    Log.info("useParma=" + useParma);
    String parmaSitTool = EntityEventPathExtraction.class.getName();
    if (useParma) {
      File modelFile = config.getFile("dedup.model");
      IntDoubleHashMap idf = null;
      String parmaEntTool = null;
      int parmaVwPort = config.getInt("vw.port", 8094);
      parma = new ParmaVw(modelFile, idf, parmaSitTool, parmaEntTool, parmaVwPort);
    }

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
      
      // Dedup communications (introduced during serialization process)
      // TODO set the comms to null and serialize a Map<String, Communication> separately
      dedupCommunications(res);

      Log.info(p);
      Log.info("nResults=" + res.size() + " queryName=" + q.name);
      TIMER.start("eventSelection");
      List<SitSearchResult> resWithSituations = new ArrayList<>();
      for (int resultIdx = 0; resultIdx < res.size(); resultIdx++) {
        SitSearchResult r = res.get(resultIdx);
        
        // Experimental: try to figure out what events are interesting
        if (r.triageFeatures == null) {
          TIMER.start("unNecessaryQueryFeats");
//          Log.info("FIXME: for now I'm recomputing the features");
          TokenObservationCounts tokObs = null;
          TokenObservationCounts tokObsLc = null;
          String nerType = TacKbp.tacNerTypesToStanfordNerType(q.entity_type);
          String[] headwords = q.name.split("\\s+");  // TODO
          r.triageFeatures = IndexCommunications.getEntityMentionFeatures(q.name, headwords, nerType, tokObs, tokObsLc);
          TIMER.stop("unNecessaryQueryFeats");
          EC.increment("unNecessaryQueryFeats");
        }

        // Search for an interesting situation
        EntityEventPathExtraction eep = new EntityEventPathExtraction(r);
        eep.verboseEntSelection = true;
        boolean verbose = !useParma;
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
        
        SituationMention sm = ParmaVw.makeSingleTokenSm(r.yhatEntitySituation, r.tokUuid, eep.getClass().getName());
        sm.setUuid(new UUID("mention" + resultIdx));
//        Log.info("created smUuid=" + sm.getUuid().getUuidString()
//            + " for SitSearchResult tokUuid=" + r.tokUuid
//            + " in " + r.getCommunicationId()
//            + " sm.trs=" + sm.getTokens()
//            + "\t" + r.getWordsInTokenizationWithHighlightedEntAndSit());
        ParmaVw.addToOrCreateSitutationMentionSet(r.getCommunication(), sm, parmaSitTool);

        // Switch findMOstInterestingEvent to verbose=true if you want to see these
        if (!useParma) {
          ShowResult sr = new ShowResult(q, r);
          sr.show(Collections.emptyList());
        }
      }
      res = resWithSituations;
      resWithSituations = null;
      TIMER.stop("eventSelection");


      if (useParma) {
        // Call parma to remove duplicates
        // NOTE: ParmaVw expect that the situations are *in the communications* when dedup is called.
//        parma.verbose(true);
        Log.info("starting dedup for " + q);

        TIMER.start("parmaDedup");
        int maxResluts = config.getInt("numDeduppedResults", 10); // NOTE: Parma is O(n^2) in this value!
        List<QResultCluster> dedupped = parma.dedup(res, maxResluts);
        System.out.println();
        TIMER.stop("parmaDedup");

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
      

      System.out.println("AccumuloIndex timer:");
      System.out.println(TIMER);
      System.out.println("IndexCommunications timer:");
      System.out.println(IndexCommunications.TIMER);
      System.out.println();

      TIMER.stop("query");
    }

    System.out.println("AccumuloIndex events counts:");
    System.out.println(EC);
    System.out.println("IndexCommunications events counts:");
    System.out.println(IndexCommunications.EC);
    System.out.println();

    parma.close();
    Log.info("done");
  }


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
    String c = config.getString("command");
    if (c.equalsIgnoreCase("buildIndexMR")) {
      Log.info("you probably don't want to so this, use regular");
      BuildIndexMR.main(config);
    } else if (c.equalsIgnoreCase("buildIndexRegular")) {
      BuildIndexRegular.main(config);
    } else if (c.equalsIgnoreCase("computeIdf")) {
      ComputeIdf.main(config);
    } else if (c.equalsIgnoreCase("kbpSearch")) {
      kbpSearching(config);
    } else if (c.equalsIgnoreCase("featureFrequency")) {
      ComputeFeatureFrequencies.main(config);
    } else if (c.equalsIgnoreCase("buildBigFeatureBloomFilters")) {
      BuildBigFeatureBloomFilters.main(config);
    } else if (c.equalsIgnoreCase("kbpSearchMemo")) {
      kbpSearchingMemo(config);
    } else if (c.equals("develop")) {
      IndexCommunications.develop(config);
    } else {
      Log.info("unknown command: " + c);
    }
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
