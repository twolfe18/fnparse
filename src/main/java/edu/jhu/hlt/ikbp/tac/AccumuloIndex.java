package edu.jhu.hlt.ikbp.tac;

import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.ClientConfiguration;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.mapreduce.AccumuloInputFormat;
import org.apache.accumulo.core.client.mapreduce.AccumuloOutputFormat;
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

import com.esotericsoftware.minlog.Log;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumulo;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloConfig;
import edu.jhu.hlt.fnparse.rl.full.GroupBy;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.TermVec;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.prim.tuple.Pair;
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
 * NOTE: t2f and f2t contain the SAME DATA but are permutations of the key fields.
 * featType in [entity, deprel, situation] and feat values are things like "h:John" and "X<nsubj<loves>dobj>PER"
 * 
 * TODO create c2f_idf etc tables
 * 
 *
 * @author travis
 */
public class AccumuloIndex {

  public final static byte[] NA = new byte[0];
  public final static String TABLE_NAMESPACE = "twolfe-cag1-index";
  public final static Text T_f2t = new Text(TABLE_NAMESPACE + "_f2t");
  public final static Text T_t2f = new Text(TABLE_NAMESPACE + "_t2f");
  public final static Text T_t2c = new Text(TABLE_NAMESPACE + "_t2c");
  public final static Text T_c2w = new Text(TABLE_NAMESPACE + "_c2w");

  public static byte[] encodeCount(int count) {
    if (count <= 0)
      throw new IllegalArgumentException("count=" + count + " must be >0");
    int lim = Byte.MAX_VALUE;
    byte b = (byte) (count > lim ? lim : count);
    return new byte[] {b};
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
          CharSequence c = comm.getId();
          byte[] cb = comm.getId().getBytes();
          
          // c2w
          Counts<String> terms = IndexCommunications.terms2(comm);
          for (Entry<String, Integer> t : terms.entrySet()) {
            String w = t.getKey();
            int cn = t.getValue();
            Mutation m = new Mutation(c);
            m.put(NA, w.getBytes(), encodeCount(cn));
            ctx.write(T_c2w, m);
          }
          
          
          // t2c
          for (Tokenization tok : new TokenizationIter(comm)) {
            CharSequence t = tok.getUuid().getUuidString();
            Mutation m = new Mutation(t);
            m.put(NA, NA, cb);
            ctx.write(T_t2c, m);
          }
          
          // f2t and t2f
          // TODO

        } catch (Exception e) {
          e.printStackTrace();
        }
        
      }
    }
  }
  
  public static class BuildIndex {
    public static void main(ExperimentProperties config) throws ConfigurationException, IOException, InterruptedException, ClassNotFoundException {
      Job job = Job.getInstance();
      job.setJobName(config.getString("jobname", "buildAccIndex"));
      ClientConfiguration cc = new ClientConfiguration()
          .withInstance(config.getString("instanceName"))
          .withZkHosts(config.getString("zookeepers"));
      AccumuloInputFormat.setZooKeeperInstance(job, cc);
      AccumuloInputFormat.setInputTableName(job, config.getString("sourceTable", "simple_accumulo_dev"));
      AccumuloOutputFormat.setBatchWriterOptions(job, new BatchWriterConfig());
      job.setMapperClass(Mapper.class); // identity mapper
      job.setReducerClass(BuildIndexReducer.class);
      job.submit();
      job.monitorAndPrintJob();
    }

  }

  public static class Search {
    private String username;
    private PasswordToken password;
    private Authorizations auths;
    private int numQueryThreads;
    
    private SimpleAccumuloConfig conf_f2t;


    public void search(List<String> triageFeats, TermVec docContext) throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
      // NerFeatureInvertedIndex: do tok retrieval based on tf-idf of feature, so you need to know how many toks a feat appears in
      
      // SituationSearch.score: currently just measures overlap between tok+doc+feats and PKB+seeds, no real tuning

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
      Connector conn_f2t = conf_f2t.connect(username, password);
      BatchScanner bs_f2t = conn_f2t.createBatchScanner("f2t", auths, numQueryThreads);
      List<Range> triageFeatRows = convert(triageFeats);
      bs_f2t.setRanges(triageFeatRows);
      // Results will be in sorted order, keep running tally of score(tokenization)
      Counts.Pseudo<String> tokUuuid2score = new Counts.Pseudo<>();
      GroupBy<Entry<Key, Value>, Pair<String, String>> gb = new GroupBy<>(bs_f2t.iterator(), Search::kf);
      while (gb.hasNext()) {
        List<Entry<Key, Value>> perFeat = gb.next();
        double p = 2d / (1 + perFeat.size());
        for (Entry<Key, Value> e : perFeat) {
          String tokUuid = e.getKey().getColumnQualifier().toString();
          tokUuuid2score.update(tokUuid, p);
        }
      }
      // Now we have scores for every tokenization
      // Need to add in the document tf-idf score
    }
    
    private static Pair<String, String> kf(Entry<Key, Value> f2t_entry) {
      return null;
    }
    
    private static List<Range> convert(List<String> rows) {
      throw new RuntimeException("implement me");
    }
  }
  
  
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    String c = config.getString("command");
    if (c.equalsIgnoreCase("buildIndex")) {
      BuildIndex.main(config);
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
