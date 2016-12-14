package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.access.FetchRequest;
import edu.jhu.hlt.concrete.access.FetchResult;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumulo;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloConfig;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloFetch;
import edu.jhu.hlt.fnparse.rl.full.GroupBy;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.CommunicationRetrieval;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SitSearchResult;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SituationSearch;
import edu.jhu.hlt.ikbp.tac.TacKbp.KbpQuery;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.hlt.utilt.AutoCloseableIterator;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.HPair;
import edu.jhu.util.TokenizationIter;

/**
 * Mimics the functionality in {@link IndexCommunications}, but while being backed by accumulo.
 * 
 * Tables:
 * name             row           col_fam           col_qual          value
 * ----------------------------------------------------------------------------------
 * f2t              feat          featType          tokUuid           tf(feat,tok)*idf(feat)        # find ways to filter this table? this was what was unsuccessful in scripts/sem-diff/pruning/prune_int_uuid_index_by_count.py
 * t2f              tokUuid       featType          feat              tf(feat,tok)*idf(feat)        # for re-scoring after triage
 * t2c              tokUuid       NA                NA                commUuid
 * c2w              commUuid      NA                word              tf(word,doc)*idf(word)
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
  public final static String TABLE_NAMESPACE = "twolfe_cag1_index1";
  
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
    throw new RuntimeException("implement me");
  }

  
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
      sa.connect(config.getString("username"), new PasswordToken(config.getString("password")));
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
   * Doesn't work.
   */
  public static class BuildIndexMR {
    public static void main(ExperimentProperties config) throws ConfigurationException, IOException, InterruptedException, ClassNotFoundException, AccumuloSecurityException {
      Log.info("starting");
      Job job = Job.getInstance();
      job.setJobName(config.getString("jobname", "buildAccIndex"));
      ClientConfiguration cc = new ClientConfiguration()
          .withInstance(config.getString("instanceName"))
          .withZkHosts(config.getString("zookeepers"));

      job.setMapperClass(Mapper.class); // identity mapper
      job.setReducerClass(BuildIndexReducer.class);
      
      String principal = config.getString("username");
      AuthenticationToken token = new PasswordToken(config.getProperty("password"));

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
      for (Entry<String, Integer> word : a) {
        double sa = tfa_idf.get(word.getKey());
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
      String username = config.getString("username");
      AuthenticationToken password = new PasswordToken(config.getString("password"));
      String instanceName = config.getString("instanceName");
      String zookeepers = config.getString("zookeepers");
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
          config.getString("username"),
          new PasswordToken(config.getString("password")));
    }
    
    public Communication get(String commId) {
      FetchRequest fr = new FetchRequest();
      fr.addToCommunicationIds(commId);;
      try {
        FetchResult r = fetch.fetch(fr);
        return r.getCommunications().get(0);
      } catch (Exception e) {
        e.printStackTrace();
        return null;
      }
    }
  }


  /**
   * Roughly equivalent to {@link SituationSearch}
   * TODO parma integration
   */
  public static class Search {
    private String username;
    private PasswordToken password;
    private Authorizations auths;
    private int numQueryThreads;
    
//    private SimpleAccumuloConfig conf_f2t;
    private Connector conn;

//    public List<SitSearchResult> search(List<String> triageFeats, TermVec docContext) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    public List<SitSearchResult> search(List<String> triageFeats, StringTermVec docContext, ComputeIdf df) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
      /*
       * 1) use a batch scan over f2t to find plausible t's
       * 2) use a batch scan over t2f with t's from prev step, figure out how to do early stopping on this
       */
      
      /*
       * Naive Bayes vs tf-idf?
       * argmax_t p(t|f) = p(t,f) / p(f)
       * argmax_t g(t,f) = p(f|t) * idf(f)
       */
      
      // Make a batch scanner to retrieve all tokenization which contain any triageFeats
      BatchScanner bs_f2t = conn.createBatchScanner(T_f2t.toString(), auths, numQueryThreads);
      List<Range> triageFeatRows = convert(triageFeats);
      bs_f2t.setRanges(triageFeatRows);
      // Results will be in sorted order, keep running tally of score(tokenization)
      Counts.Pseudo<String> tokUuid2score = new Counts.Pseudo<>();
      GroupBy<Entry<Key, Value>, HPair<String, String>> gb = new GroupBy<>(bs_f2t.iterator(), Search::kf);
      while (gb.hasNext()) {
        // This is a list of all Tokenizations which contain a given feature (key)
        List<Entry<Key, Value>> perFeat = gb.next();
        double p = 2d / (1 + perFeat.size());
        for (Entry<Key, Value> e : perFeat) {
          String tokUuid = e.getKey().getColumnQualifier().toString();
          tokUuid2score.update(tokUuid, p);
        }
      }

      // Now we have scores for every tokenization
      // Need to add in the document tf-idf score
      
      // 1) Make a batch scanner to retrieve all the commUuids from t2c
      Map<String, String> tokUuid2commUuid = getCommIdsFor(tokUuid2score);
      
      // 2) Make a batch scanner to retrieve the words from the most promising comms, look in c2w
      Map<String, StringTermVec> commUuid2terms = getWordsForComms(tokUuid2commUuid.values());
      
      // 3) Go through each tok and re-score
      List<SitSearchResult> res = new ArrayList<>();
      for (Entry<String, Double> r : tokUuid2score.entrySet()) {
        String tokUuid = r.getKey();
        List<Feat> score = new ArrayList<>();
        SitSearchResult ss = new SitSearchResult(tokUuid, null, score);
        
        String commUuid = tokUuid2commUuid.get(tokUuid);
        StringTermVec commVec = commUuid2terms.get(commUuid);
//        IntDoubleHashMap idf = null;  // TODO
//        double tfidf = TermVec.tfidfCosineSim(docContext, commVec, idf);
        double tfidf = df.tfIdfCosineSim(docContext, commVec);
        
        score.add(new Feat("entMatch").setWeight(r.getValue()));
        score.add(new Feat("tfidf").setWeight(tfidf));
        
        res.add(ss);
      }
      
      return res;
    }
    
    /** returned map is tokUuid -> commUuid */
    private Map<String, String> getCommIdsFor(Counts.Pseudo<String> tokUuid2score) throws TableNotFoundException {
      List<Range> rows = new ArrayList<>();
      for (Entry<String, Double> x : tokUuid2score.entrySet())
        rows.add(Range.exact(x.getKey()));
      // TODO Consider filtering based on score?
      int numQueryThreads = 4;
      BatchScanner bs = conn.createBatchScanner(T_t2c.toString(), auths, numQueryThreads);
      bs.setRanges(rows);
      Map<String, String> t2c = new HashMap<>();
      for (Entry<Key, Value> e : bs) {
        String tokUuid = e.getKey().getRow().toString();
        String commUuid = e.getValue().toString();
        Object old = t2c.put(tokUuid, commUuid);
        assert old == null;
      }
      return t2c;
    }
    
    /** keys of returned map are comm uuids */
    private Map<String, StringTermVec> getWordsForComms(Iterable<String> commUuidsNonUniq) throws TableNotFoundException {
      // Collect the ids of all the comm keys which need to be retrieved in c2w
      List<Range> rows = new ArrayList<>();
      Set<String> uniq = new HashSet<>();
      for (String commUuid : commUuidsNonUniq)
        if (uniq.add(commUuid))
          rows.add(Range.exact(commUuid));
      int numQueryThreads = 4;
      BatchScanner bs = conn.createBatchScanner(T_c2w.toString(), auths, numQueryThreads);
      bs.setRanges(rows);
      Map<String, StringTermVec> c2tv = new HashMap<>();
      // Group by commUuid (row in c2w)
      Function<Entry<Key, Value>, String> keyFunction = e -> e.getKey().getRow().toString();
      GroupBy<Entry<Key, Value>, String> gb = new GroupBy<>(bs.iterator(), keyFunction);
      while (gb.hasNext()) {
        List<Entry<Key, Value>> wordsForComm = gb.next();
        String commUuid = wordsForComm.get(0).getKey().getRow().toString();
        int n = wordsForComm.size();
        StringTermVec tv = new StringTermVec();
        for (int i = 0; i < n; i++) {
          Entry<Key, Value> e = wordsForComm.get(i);
          String word = e.getKey().getColumnQualifier().toString();
          int count = decodeCount(e.getValue().get());
          tv.add(word, count);
        }
        Object old = c2tv.put(commUuid, tv);
        assert old == null;
      }
      return c2tv;
    }
    
    private static HPair<String, String> kf(Entry<Key, Value> f2t_entry) {
      String feat = f2t_entry.getKey().getRow().toString();
      String featType = f2t_entry.getKey().getColumnFamily().toString();
      return new HPair<>(feat, featType);
    }
    
    private static List<Range> convert(List<String> rows) {
      List<Range> r = new ArrayList<>(rows.size());
      for (String s : rows)
        r.add(Range.exact(s));
      return r;
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
    
    // Finds EntityMentions for query documents which just come with char offsets.
    TacQueryEntityMentionResolver findEntityMention =
        new TacQueryEntityMentionResolver("tacQuery");
    
    AccumuloCommRetrieval commRet = new AccumuloCommRetrieval(config);
    Search search = new Search();
//    TfIdf df = new TfIdf(config.getExistingFile("wordDocFreq"));
    ComputeIdf df = new ComputeIdf(config.getExistingFile("wordDocFreq"));

    List<KbpQuery> queries = TacKbp.getKbp2013SfQueries();

    // How many results per KBP query (before dedup).
    // Higher values are noticeably slower.
    int limit = config.getInt("limit", 20);

    for (KbpQuery q : queries) {
      EC.increment("kbpQuery");
      System.out.println(TIMER);
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
      
      // 2) Extract entity mention features
      // TODO Remove headwords, switch to purely a key-word based retrieval model.
      // NOTE that headwords must match the headwords extracted during the indexing phrase.
      String[] headwords = new String[] {};
      String entityName = q.name;
      String entityType = TacKbp.tacNerTypesToStanfordNerType(q.entity_type);
      TokenObservationCounts tokObs = null;
      TokenObservationCounts tokObsLc = null;
      List<String> triageFeats = IndexCommunications.getEntityMentionFeatures(entityName, headwords, entityType, tokObs, tokObsLc);
      
      // 3) Search and show results
      List<SitSearchResult> res = search.search(triageFeats, queryContext, df);
      for (SitSearchResult r : res) {
        System.out.println(r);
      }
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
    } else if (c.equals("kbpSearch")) {
      kbpSearching(config);
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
