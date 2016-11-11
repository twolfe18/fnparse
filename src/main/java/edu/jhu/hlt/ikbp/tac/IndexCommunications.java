package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Set;

import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.MentionArgument;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.access.FetchCommunicationService;
import edu.jhu.hlt.concrete.access.FetchRequest;
import edu.jhu.hlt.concrete.access.FetchResult;
import edu.jhu.hlt.concrete.serialization.iterators.TarGzArchiveEntryCommunicationIterator;
import edu.jhu.hlt.fnparse.features.Path.EdgeType;
import edu.jhu.hlt.fnparse.features.Path.NodeType;
import edu.jhu.hlt.fnparse.features.Path2;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.StringIntUuidIndex.StrIntUuidEntry;
import edu.jhu.hlt.scion.concrete.server.FetchCommunicationServiceImpl;
import edu.jhu.hlt.scion.core.accumulo.ConnectorFactory;
import edu.jhu.hlt.scion.core.accumulo.ScionConnector;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.EfficientUuidList;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.LabeledDirectedGraph;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.OrderStatistics;
import edu.jhu.hlt.tutils.StringInt;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.hlt.tutils.ling.DParseHeadFinder;
import edu.jhu.prim.map.IntDoubleHashMap;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.prim.util.Lambda.FnIntFloatToFloat;
import edu.jhu.prim.vector.IntFloatUnsortedVector;
import edu.jhu.prim.vector.IntIntHashVector;

/**
 * Produces an index of a given Concrete corpus. Writes everything to TSVs.
 *
 * @author travis
 */
public class IndexCommunications implements AutoCloseable {

  public static final File HOME = new File("data/concretely-annotated-gigaword/ner-indexing/2016-10-27_ngram5");
  public static final Charset UTF8 = Charset.forName("UTF-8");
  public static final HashFunction HASH = Hashing.murmur3_32();
  public static final MultiTimer TIMER = new MultiTimer();

  public static int err_head = 0;
  public static int err_ner = 0;
  public static int err_misc = 0;
  private static long n_doc = 0, n_tok = 0, n_ent = 0, n_termWrites = 0, n_termHashes = 0;

  
  
  
  /**
   * Given a bunch of query results, fetch their {@link Communication}s from scion/accumulo.
   */
  public static class CommunicationRetrieval {
//    private FetchCommunicationServiceImpl impl;
    private FetchCommunicationService.Client client;
    
    public CommunicationRetrieval(int localPort) {
//      System.setProperty("scion.accumulo.zookeepers", "r8n04.cm.cluster:2181,r8n05.cm.cluster:2181,r8n06.cm.cluster:2181");
//      System.setProperty("scion.accumulo.instanceName", "minigrid");
//      System.setProperty("scion.accumulo.user", "reader");
//      System.setProperty("scion.accumulo.password", "an accumulo reader");
//      try {
//        ConnectorFactory cf = new ConnectorFactory();
//        ScionConnector sc = cf.getConnector();
//        impl = new FetchCommunicationServiceImpl(sc);
//      } catch (Exception e) {
//        throw new RuntimeException(e);
//      }
      Log.info("talking to server at localhost:" + localPort);
      try {
        TTransport transport = new TSocket("localhost", localPort);
        transport.open();
        TProtocol protocol = new  TCompactProtocol(transport);
        client = new FetchCommunicationService.Client(protocol);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    
    public void test(String commId) {
      FetchRequest fr = new FetchRequest();
      fr.addToCommunicationIds(commId);
      Log.info(fr);
      try {
//        FetchResult res = impl.fetch(fr);
        FetchResult res = client.fetch(fr);
        if (res.getCommunicationsSize() == 0) {
          Log.info("no results!");
        } else {
          Communication c = res.getCommunications().get(0);
          Log.info("got back: " + c.getId());
          System.out.println(c.getText());
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    
    public void fetch(List<QResult> needComms) {
      
      // TODO Break up request if there are too many?
      // TODO Have option to do one-at-a-time retrieval?
      FetchRequest fr = new FetchRequest();
      for (QResult r : needComms)
        fr.addToCommunicationIds(r.feats.getCommunicationUuidString());
      
      FetchResult res = null;
      try {
//        res = impl.fetch(fr);
        res = client.fetch(fr);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      
      assert res.getCommunicationsSize() == needComms.size();
      for (int i = 0; i < needComms.size(); i++) {
        needComms.get(i).setCommunication(res.getCommunications().get(i));
      }
    }
  }
  
  /**
   * Stores everything needed to score a mention to be shown to a user.
   * Does not include all of the Communication info, which must be queried separately.
   *
   * Implicitly has an external Tokenization UUID primary key.
   * Store a Map<String, SentFeats> for all sentences in CAG which:
   * 1) contain two entities
   * 2) contains either a deprel or situation
   *
   * If we assume there are 20 of these per document, 10M docs in CAG,
   * 16 + 3 + 4*3 = 32 bytes per instance
   * 200M * 32 = 6G?
   * The 32 is not accurate since the arrays require pointers.
   *
   * If I solve the pointer issue and we count the 16 bytes for the Tokenization UUID key,
   * then 16+16+3+4*3 = 47 bytes, round up to 64 bytes
   * 200M * 64 bytes < 2^28 * 2^6 = 2^34 = 16 GB
   * 
   * If I include the pointer to TermDoc and ignore the cost of the actual vectors
   * 8+47 = 55, which is still under the 64 byte budget.
   */
  public static class SentFeats {
    // Use this to track down the Communication to show to the user
    long comm_uuid_lo, comm_uuid_hi;
    
    // These are the features which appear in this sentence/Tokenization
    char n_deprel;
    char n_situation;
    char n_entity;
    int[] features;     // contains hashed deprel, situation, and entity features
    
    // Pointer to TermDoc so that you can do tf-idf cosine sim with PKB documents
    TermVec tfidf;
    
    // TODO Have pointers to other Tokenizations in the same Communication?
    
    public SentFeats() {
      features = new int[0];
    }
    
    @Override
    public String toString() {
      java.util.UUID u = new java.util.UUID(comm_uuid_hi, comm_uuid_lo);
      return "(Sentence comm=" + u
          + " n_deprel=" + Integer.valueOf(n_deprel)
          + " n_situation=" + Integer.valueOf(n_situation)
          + " n_ent=" + Integer.valueOf(n_entity)
          + " n_words=" + (tfidf == null ? "NA" : tfidf.totalCount)
          + ")";
    }
    
    public String getCommunicationUuidString() {
      java.util.UUID u = new java.util.UUID(comm_uuid_hi, comm_uuid_lo);
      return u.toString();
    }
    
    public void setCommunicationUuid(String commUuid) {
      java.util.UUID u = java.util.UUID.fromString(commUuid);
      long old_hi = comm_uuid_hi;
      long old_lo = comm_uuid_lo;
      comm_uuid_hi = u.getMostSignificantBits();
      comm_uuid_lo = u.getLeastSignificantBits();
      assert old_hi == 0 || old_hi == comm_uuid_hi;
      assert old_lo == 0 || old_lo == comm_uuid_lo;
    }
    
    public void addDeprel(int deprelPath, int deprelArg) {
      int[] nf = new int[features.length + 2];
      nf[0] = deprelPath;
      nf[1] = deprelArg;
      System.arraycopy(features, 0, nf, 2, features.length);
      features = nf;
      n_deprel++;
    }
    
    public void addEntity(int entityFeat) {
      int[] nf = new int[features.length + 1];
      System.arraycopy(features, 0, nf, 0, features.length);
      nf[nf.length-1] = entityFeat;
      features = nf;
      n_entity++;
    }
    
    public int[] getDeprels() {
      int[] d = new int[n_deprel];
      System.arraycopy(features, 0, d, 0, n_deprel);
      return d;
    }
    
    public int[] getEntities() {
      int[] d = new int[n_entity];
      System.arraycopy(features, 2*n_deprel + n_situation, d, 0, n_entity);
      return d;
    }
  }
  
  public static class QResult {
    public final String tokUuid;
    public final SentFeats feats;
    public final double score;

    // Can be set later, not stored in memory
    private Communication comm;

    public QResult(String tokUuid, SentFeats feats, double score) {
      this.tokUuid = tokUuid;
      this.feats = feats;
      this.score = score;
    }
    
    public Communication setCommunication(Communication c) {
      Communication old = comm;
      comm = c;
      return old;
    }
    
    public Communication getCommunication() {
      return comm;
    }
    
    @Override
    public String toString() {
      return String.format("(QResult score=%.3f tok=%s has_comm=%s feats=%s)", score, tokUuid, comm!=null, feats);
    }
    
    public static final Comparator<QResult> BY_SCORE_DESC = new Comparator<QResult>() {
      @Override
      public int compare(QResult o1, QResult o2) {
        if (o1.score > o2.score)
          return -1;
        if (o1.score < o2.score)
          return +1;
        return 0;
      }
    };
  }
  
  /**
   * Retrieve situations (FN/PB frames as well as universal schema dependency relations).
   */
  public static class SituationSearch {
    
    /**
     * This is user/session-specific data, comes and goes, not index data.
     */
    static class State {
      List<TermVec> pkbDocs = new ArrayList<>();
      Set<String> pkbTokUuid = new HashSet<>();

      List<StringInt> pkbRel = new ArrayList<>();     // e.g. "appos</PERSON/appos</PERSON/appos>/PERSON/appos>"
      List<StringInt> pkbEnt = new ArrayList<>();     // e.g. "Barack_Obama"
      List<StringInt> pkbFrame = new ArrayList<>();   // e.g. "Commerce_buy"

      List<StringInt> seedRel = new ArrayList<>();    // e.g. "appos</PERSON/appos</PERSON/appos>/PERSON/appos>"
      List<StringInt> seedEnt = new ArrayList<>();    // e.g. "Barack_Obama"
      List<StringInt> seedFrame = new ArrayList<>();  // e.g. "Commerce_buy"
      
      // TODO Have PKB/seed slots, which is a relation + argument position + entity
      // TODO Include list of queries and responses?
    }

    // PKB, seeds, etc.
    private transient State state;
    
    // Contains ~200M entries? ~20GB?
    private Map<String, SentFeats> tok2feats;  // keys are Tokenization UUID
    
    // These let you look up Tokenizations given some features
    // which may appear in a seed or a query.
    private StringIntUuidIndex deprel;      // dependency path string, hash("arg0/entityWord"), Tokenization UUID+
    private StringIntUuidIndex situation;   // SituationMention kind, hash("role/entityWord"), Tokenization UUID+
    private StringIntUuidIndex entity;      // NER type, hash(entity word), Tokenization UUID+
    
    private Counts<String> ec;
    private MultiTimer tm;

    /**
     * @param tokUuid2commUuid has lines like: <tokenizationUuid> <tab> <communicationUuid>
     */
    public SituationSearch(File tokUuid2commUuid, TfIdf docVecs) throws IOException {
      
      ec = new Counts<>();
      tm = new MultiTimer();
      
      // TODO Populate [deprel, situation, entity] => Tokenization UUID maps
      tm.start("load/PERSON");
      entity = new StringIntUuidIndex();
      entity.putIntLines("PERSON", new File(HOME, "ner_feats/nerFeats.PERSON.txt"));
      tm.stop("load/PERSON");
      
      // NOTE: Currently this is very fine grain, e.g.
      // key = ("appos>/chairman/prep>/of/pobj>", hash("ARG1/ORGANIZATION/NBC"))
      // The datastructure lets me iterate over all int values, so I could search by deprel.
      tm.start("load/deprel");
      deprel = new StringIntUuidIndex();
      deprel.addStringIntLines(new File(HOME, "sit_feats/index_deprel/deprels.txt"));
      tm.stop("load/deprel");
      
      // Populate SentFeats
      tm.start("load/feats/deprel");
      tok2feats = new HashMap<>();
      for (StrIntUuidEntry x : deprel) {
        SentFeats f = tok2feats.get(x.uuid);
        if (f == null) {
          f = new SentFeats();
          tok2feats.put(x.uuid, f);
          ec.increment("tokenization");
        }
        int hs = ReversableHashWriter.onewayHash(x.string);
        f.addDeprel(hs, x.integer);
        ec.increment("feat/deprel");
      }
      tm.stop("load/feats/deprel");
      tm.start("load/feats/entity");
      for (StrIntUuidEntry x : entity) {
        SentFeats f = tok2feats.get(x.uuid);
        if (f == null) {
          f = new SentFeats();
          tok2feats.put(x.uuid, f);
          ec.increment("tokenization");
        }
        f.addEntity(x.integer);
        ec.increment("feat/entity");
      }
      tm.stop("load/feats/entity");
      // TODO situations
      
      
      // Read in the Communication UUID for every Tokenization UUID in the input
      Log.info("we know about " + tok2feats.size() + " Tokenizations,"
          + " looking for the docVecs for them in " + tokUuid2commUuid.getPath());
      tm.start("load/docVec/link");
      try (BufferedReader r = FileUtil.getReader(tokUuid2commUuid)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] ar = line.split("\t");
          if (ar.length == 0) {
            Log.info("why is there a line with a single tab in: " + tokUuid2commUuid.getPath());
            continue;
          }
          assert ar.length == 2;
          String tokUuid = ar[0];
          SentFeats f = tok2feats.get(tokUuid);
          if (f != null) {
            f.setCommunicationUuid(ar[1]);
            f.tfidf = docVecs.get(ar[1]);
            assert f.tfidf != null;
            ec.increment("docVec");
          } else {
            ec.increment("skip/docVec");
          }
        }
      }
      tm.stop("load/docVec/link");
      
      state = new State();
      Log.info("done setup, " + ec);
    }
    
    public void clearState() {
      state = new State();
    }
    
    public void seed(String s) {
      // TODO detect variables like "X" and "Y"
      // TODO Parse s and/or run semafor/etc
      // TODO extract deprel, situation, entity
      // TODO Add to state
//      throw new RuntimeException("implement me");
      
      Log.info("WARNING: assuming this is a deprel: " + s);
      state.seedRel.add(h(s));
    }
    
    public void addToPkb(QResult response) {
      Log.info("response: " + response);
      
      SentFeats f = response.feats;
      
      assert f.tfidf != null;
      state.pkbDocs.add(f.tfidf);

      for (int e : f.getEntities())
        state.pkbEnt.add(new StringInt("NA", e));
      
      // TODO entities
      
      for (int d : f.getDeprels())
        state.pkbRel.add(new StringInt("NA", d));
      
      state.pkbTokUuid.add(response.tokUuid);
    }
    
    public List<QResult> query(String entityName, String entityType, int limit) {
      tm.start("query");
      Log.info("entityName=" + entityName + " entityType=" + entityType);

      // Lookup mentions for the query entity
      int entityNameHash = ReversableHashWriter.onewayHash("h:" + entityName);
      List<String> toks = entity.get(entityType, entityNameHash);
      Log.info("entity results initial: " + toks);
      
      // Re-score mentions based on seeds/PKB
      List<QResult> scored = new ArrayList<>();
      for (String t : toks) {
        if (state.pkbTokUuid.contains(t)) {
          Log.info("skipping tok=" + t + " because it is already in the PKB");
          continue;
        }
        
        QResult r = score(t);
        scored.add(r);
      }
      Collections.sort(scored, QResult.BY_SCORE_DESC);
      
      if (limit > 0 && scored.size() > limit)
        scored = scored.subList(0, limit);

      tm.stop("query");
      return scored;
    }
    
    /**
     * Score a retrieved sentence/tokenization against seed and PKB items.
     */
    private QResult score(String tokUuid) {
      tm.start("score");
      
      /*
       * entMentions WILL be Tokenization UUIDs.
       * Go through each of them and boost their score if they:
       * 1) contain a deprel which is a seed
       * 2) contain an entity type/sim which is a seed
       * 3) contain an entity in the PKB
       * 4) etc for seed/PKB situations
       */
      
      SentFeats f = tok2feats.get(tokUuid);

      Log.info("f.tok=" + tokUuid);
      Log.info("f.comm=" + f.getCommunicationUuidString());
      
      double scoreTfidf = 0;
      double scorePkbDoc = 0;
      double maxTfidf = 0;
      for (TermVec d : state.pkbDocs) {
        double t = TermVec.tfidf(f.tfidf, d);
        scoreTfidf += t;
        if (f.tfidf == d)
          scorePkbDoc += 1;
        if (t > maxTfidf)
          maxTfidf = t;
      }
      double z = Math.sqrt(state.pkbDocs.size() + 1);
      scoreTfidf /= z;
      scorePkbDoc /= z;
      
      Log.info("maxTfIdf=" + maxTfidf);
      Log.info("pkbDocs.size=" + state.pkbDocs.size());
      Log.info("scoreTfidf=" + scoreTfidf);
      Log.info("scorePkbdoc=" + scorePkbDoc);
      
      // TODO Currently we measure a hard "this entity/deprel/situation/etc
      // showed up in the result and the PKB/seed", but we should generalize
      // this to be similarity of the embeddings.
      
      int[] deprel = f.getDeprels();
      int[] ent = f.getEntities();

      double scorePkbDeprel = dot(deprel, state.pkbRel);
      double scorePkbEnt = dot(ent, state.pkbEnt);
      
      double scoreSeedDeprel = 0.1 * dot(deprel, state.seedRel);
      double scoreSeedEnt = 0.1 * dot(ent, state.seedEnt);
      
      Log.info("f.deprels=" + Arrays.toString(deprel));
      Log.info("scorePkbDeprel=" + scorePkbDeprel);
      Log.info("scoreSeedDeprel=" + scoreSeedDeprel);

      Log.info("f.entities=" + Arrays.toString(ent));
      Log.info("scorePkbEnt=" + scorePkbEnt);
      Log.info("scoreSeedEnt=" + scoreSeedEnt);

      System.out.println();
      
      double score = scoreTfidf + scorePkbDeprel
          + scorePkbDeprel + scoreSeedDeprel
          + scorePkbEnt + scoreSeedEnt;
          
      tm.stop("score");
      return new QResult(tokUuid, f, score);
    }
    
    private static double dot(int[] feats, List<StringInt> pkbFeats) {
      double score = 0;
      for (StringInt f : pkbFeats)
        for (int i = 0; i < feats.length; i++)
          if (feats[i] == f.integer)
            score += 1;
      return score /= Math.sqrt(pkbFeats.size() + 1);
    }
    
    public static void main(ExperimentProperties config) throws IOException {
      
      File tokUuid2commUuid = config.getExistingFile("tok2comm", new File(HOME, "tokUuid2commUuid.txt"));

      File docVecs = config.getExistingFile("docVecs", new File(HOME, "doc/docVecs.128.txt"));
      File idf = config.getExistingFile("idf", new File(HOME, "doc/idf.txt"));
      TfIdf docs = new TfIdf(docVecs, idf);

      SituationSearch ss = new SituationSearch(tokUuid2commUuid, docs);

//      ss.seed("conj>/leaders/prep>/of/pobj>");    // This appears to be a pruned relation in the small data case
      ss.seed("conj>");

      int responseLim = 20;
      List<QResult> results = ss.query("Barack_Obama", "PERSON", responseLim);
      for (QResult r : results)
        System.out.println(r);
      
      
      // Lets suppose that the user like the second response
      // Add it to the PKB and see if we can get the PKB/tfidf features to fire
      QResult userLiked = results.get(1);
      ss.addToPkb(userLiked);
      
      // Lets just re-do the same query
      List<QResult> results2 = ss.query("Barack_Obama", "PERSON", responseLim);
      for (QResult r : results2)
        System.out.println(r);
      
      Log.info("done\n" + ss.tm);
    }
  }

  private static StringInt h(String s) {
    int h = ReversableHashWriter.onewayHash(s);
    return new StringInt(s, h);
  }
  
  
  /**
   * After pruning, run this to do the conversion:
   * (deprel, arg0, arg1, location) => 2 instances of (deprel, hash(argpos, argval), Tokenization UUID)
   * NOTE: Output is and UNSORTED version of input for {@link StringIntUuidIndex}.
   * Also outputs a argHash file mapping strings like "ARG0/DATE/July" to their hashed values, e.g. sit_feats/index_deprel/hashedArgs.txt.gz
   */
  public static class HashDeprels {
    /**
     * @see IndexSituations#writeDepRels(Communication, Map) for line format
     */
    public static void main(ExperimentProperties config) throws IOException {
      File input = config.getExistingFile("input");
      File output = config.getFile("output"); // may want to be /dev/stdout so you can pipe into sort
      File alph = config.getFile("argHash");
      
      try (BufferedWriter w = FileUtil.getWriter(output);
          BufferedReader r = FileUtil.getReader(input);
          ReversableHashWriter a = new ReversableHashWriter(alph);) {
        
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] ar = line.split("\t");
          assert ar.length == 10;
          
          String deprel = ar[0];
          String role0 = ar[1];
          String type0 = ar[2];
          String head0 = ar[3];
          String role1 = ar[4];
          String type1 = ar[5];
          String head1 = ar[6];
          String tokUuid = ar[7];
//          String commUuid = ar[8];
//          String commId = ar[9];
          
          int h0 = a.hash(role0 + "/" + type0 + "/" + head0);
          int h1 = a.hash(role1 + "/" + type1 + "/" + head1);
          
          w.write(deprel);
          w.write('\t');
          w.write(Integer.toUnsignedString(h0));
          w.write('\t');
          w.write(tokUuid);
          w.newLine();
          
          w.write(deprel);
          w.write('\t');
          w.write(Integer.toUnsignedString(h1));
          w.write('\t');
          w.write(tokUuid);
          w.newLine();
        }
      }
    }
  }
  
  
  /**
   * Reads every SituationMention and writes to sit_feats/situations.txt.gz and sit_feats/deprels.txt.gz
   * 
   * (frame, role, arg NER type, arg head word/phrase, Tokenization UUID, Communication UUID, Communication ID)
   * No SituationMention UUID since they won't exist for dependency path relations.
   *
   * Currently (2016-11-08) setup for
   * edu.jhu.hlt.framenet.semafor.parsing.JHUParserDriver via Semafor V2.1 4.10.3-SNAPSHOT
   * /export/projects/fferraro/cag-4.6.10/processing/from-marcc/20161012-083257/gigaword-merged
   * $FNPARSE/data/concretely-annotated-gigaword/sample-with-semafor-nov08
   */
  public static class IndexSituations implements AutoCloseable {

    public static void main(ExperimentProperties config) throws Exception {
      List<File> inputCommTgzs = config.getFileGlob("communicationArchives");
      File outputDir = config.getOrMakeDir("outputDirectory", new File(HOME, "sit_feats"));
      String nerTool = config.getString("nerTool", "Stanford CoreNLP");
      
      boolean disableSits = config.getBoolean("disableSits", false);
      boolean disableRels = config.getBoolean("disableRels", false);
      Log.info("disable sit=" + disableSits + " rel=" + disableRels);
      
      // This includes the two headwords on either end of the path.
      int maxWordsOnDeprelPath = config.getInt("maxWordsOnDeprelPath", 5);

      try (IndexSituations is = new IndexSituations(nerTool,
          maxWordsOnDeprelPath,
          disableSits ? null : new File(outputDir, "situation.txt.gz"),
          disableRels ? null : new File(outputDir, "deprels.txt.gz"))) {
        for (File f : inputCommTgzs)
          is.observe(f);
      }
    }
    
    private String nerTool;
    private Set<String> deprelEntityTypeEnpoints;
    private int maxWordsOnDeprelPath;
    
    // Both of these have the same format:
    // frame, role, arg NER type, arg head word/phrase, Tokenization UUID, Communication UUID, Communication ID
    private BufferedWriter w_situations;
    private BufferedWriter w_deprel;

    // Misc
    private TimeMarker tm;
    private Counts<String> ec;
    
    public IndexSituations(String nerTool, int maxWordsOnDeprelPath, File f_situations, File f_deprel) {
      Log.info("nerTool=" + nerTool);
      Log.info("maxWordsOnDeprelPath=" + maxWordsOnDeprelPath);
      this.nerTool = nerTool;
      this.maxWordsOnDeprelPath = maxWordsOnDeprelPath;
      this.tm =  new TimeMarker();
      this.ec = new Counts<>();
      if (f_situations != null) {
        Log.info("writing situations to " + f_situations.getPath());
        try {
          w_situations = FileUtil.getWriter(f_situations);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      if (f_deprel != null) {
        Log.info("writing deprels to " + f_deprel.getPath());
        try {
          w_deprel = FileUtil.getWriter(f_deprel);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      
      this.deprelEntityTypeEnpoints = new HashSet<>();
      this.deprelEntityTypeEnpoints.addAll(Arrays.asList(
          "PERSON",
          "LOCATION",
          "ORGANIZATION",
          "DATE",
          "MISC"));
//      415620 PERSON
//      238344 LOCATION
//      228197 ORGANIZATION
//      201512 NUMBER
//      144665 DATE
//       61295 MISC
//       45475 DURATION
//       32155 ORDINAL
//       12266 TIME
//        1541 SET
    }
    
    public void observe(File commTgzArchive) throws IOException {
      ec.increment("files");
      Log.info("reading from " + commTgzArchive.getPath());
      try (InputStream is = new FileInputStream(commTgzArchive);
          TarGzArchiveEntryCommunicationIterator iter = new TarGzArchiveEntryCommunicationIterator(is)) {
        while (iter.hasNext()) {
          Communication c = iter.next();
          observe(c);
        }
      }
    }
    
    public void observe(Communication c) throws IOException {
      ec.increment("communications");
      Map<String, Tokenization> tmap = AddNerTypeToEntityMentions.buildTokzIndex(c);
      writeSituations(c, tmap);
      writeDepRels(c, tmap);
      if (tm.enoughTimePassed(10))
        Log.info(c.getId() + "\t" + ec + "\t" + Describe.memoryUsage());
    }
    
    private void writeDepRels(Communication comm, Map<String, Tokenization> tmap) throws IOException {
      new AddNerTypeToEntityMentions(comm, tmap);
      List<EntityMention> mentions = getEntityMentions(comm);
      
      // Go over every pair of mentions in the same sentence, and output
      // a deprel fact if there is a relatively short path that connects
      // the two entities.
      Map<String, List<EntityMention>> bySent = groupBySentence(mentions);
      for (List<EntityMention> ms : bySent.values()) {
        
        int n = ms.size();
        for (int i = 0; i < n-1; i++) {
          for (int j = i+1; j < n; j++) {
            EntityMention a0 = ms.get(i);
            EntityMention a1 = ms.get(j);

            if (overlap(a0.getTokens(), a1.getTokens()))
              continue;
            
            String a0Type = AddNerTypeToEntityMentions.getNerType(a0.getTokens(), tmap, nerTool);
            String a1Type = AddNerTypeToEntityMentions.getNerType(a1.getTokens(), tmap, nerTool);
            if (!deprelEntityTypeEnpoints.contains(a0Type))
              continue;
            if (!deprelEntityTypeEnpoints.contains(a1Type))
              continue;
            
            Pair<String, Boolean> d = deprelBetween(
                a0.getTokens(), a1.getTokens(), tmap, maxWordsOnDeprelPath);
            if (d == null)
              continue;

            boolean takeNnCompounts = true;
            boolean allowFailures = true;
            String a0Head = headword(a0.getTokens(), tmap, takeNnCompounts, allowFailures);
            String a1Head = headword(a1.getTokens(), tmap, takeNnCompounts, allowFailures);
            if (a0Head == null || a1Head == null) {
              ec.increment("err/headfinder");
              continue;
            }

            assert a0Head.split("\\s+").length == 1;
            assert a1Head.split("\\s+").length == 1;
            String tokId = a0.getTokens().getTokenizationId().getUuidString();
            assert tokId.equals(a1.getTokens().getTokenizationId().getUuidString());

            w_deprel.write(d.get1()
                + "\t" + (d.get2() ? "ARG1" : "ARG0")
                + "\t" + a0Type
                + "\t" + a0Head
                + "\t" + (d.get2() ? "ARG0" : "ARG1")
                + "\t" + a1Type
                + "\t" + a1Head
                + "\t" + tokId
                + "\t" + comm.getUuid().getUuidString()
                + "\t" + comm.getId());
            w_deprel.newLine();

//            // TODO NO: This is what we want to end up with, but only AFTER pruning based on different info
//            // Can I convert this to the following format?
//            // dependency path string, hash("arg0/entityWord"), Tokenization UUID+
//            int a0h = ReversableHashWriter.onewayHash("arg0/" + a0Type + "/" + a0Head);
//            int a1h = ReversableHashWriter.onewayHash("arg1/" + a1Type + "/" + a1Head);
//            w_deprel.write(d.get1()
//                + "\t" + a0h
//                + "\t" + tokId
//                + "\t" + comm.getUuid().getUuidString()
//                + "\t" + comm.getId());
//            w_deprel.newLine();
          }
        }
      }
    }
    
    public static boolean overlap(TokenRefSequence a, TokenRefSequence b) {
      BitSet x = new BitSet();
      for (int i : a.getTokenIndexList())
        x.set(i);
      for (int i : b.getTokenIndexList())
        if (x.get(i))
          return true;
      return false;
    }
    
    private static Pair<String, Boolean> deprelBetween(
        TokenRefSequence a, TokenRefSequence b, Map<String, Tokenization> tmap, int maxWordsOnDeprelPath) {
      
      assert a.getTokenizationId().getUuidString().equals(b.getTokenizationId().getUuidString());
      Tokenization t = tmap.get(a.getTokenizationId().getUuidString());
      List<Token> toks = t.getTokenList().getTokenList();
      
      int n = toks.size();
      DependencyParse d = getPreferredDependencyParse(t);
      MultiAlphabet alph = new MultiAlphabet();
      LabeledDirectedGraph graph =
          LabeledDirectedGraph.fromConcrete(d, n, alph);
      DParseHeadFinder hf = new DParseHeadFinder();
      hf.ignoreEdge(alph.dep("prep"));
      hf.ignoreEdge(alph.dep("poss"));
      hf.ignoreEdge(alph.dep("possessive"));

      List<Integer> ts;
      int first, last;
      int ha, hb;

      try {
        ts = a.getTokenIndexList();
        first = ts.get(0);
        last = ts.get(ts.size()-1);
        ha = hf.head(graph, first, last);

        ts = b.getTokenIndexList();
        first = ts.get(0);
        last = ts.get(ts.size()-1);
        hb = hf.head(graph, first, last);
      } catch (RuntimeException e) {
        err_head++;
        Log.info("head finding error: " + e.getMessage());
        return null;
      }

      boolean flip = false;
      if (ha > hb) {
        flip = true;
        int temp = ha;
        ha = hb;
        hb = temp;
      }
      
      boolean allowMultipleHeads = true;
      edu.jhu.hlt.fnparse.datatypes.DependencyParse fndp =
          new edu.jhu.hlt.fnparse.datatypes.DependencyParse(graph, alph, 0, n, allowMultipleHeads);
      edu.jhu.hlt.fnparse.datatypes.Sentence sent =
          edu.jhu.hlt.fnparse.datatypes.Sentence.convertFromConcrete("ds", "id", t);
      Path2 path = new Path2(ha, hb, fndp, sent);
      
      if (path.connected() && path.getNumWordsOnPath() <= maxWordsOnDeprelPath) {
        boolean includeEndpoints = false;
        String p = path.getPath(NodeType.WORD_NER_BACKOFF, EdgeType.DEP, includeEndpoints);
//        System.out.println("flip:     " + flip);
//        System.out.println("path:     " + p);
//        System.out.println("entries:  " + path.getEntries());
//        System.out.println("sentence: " + Arrays.toString(sent.getWords()));
        return new Pair<>(p, flip);
      }
      return null;
    }
    
    private static Map<String, List<EntityMention>> groupBySentence(List<EntityMention> mentions) {
      Map<String, List<EntityMention>> m = new HashMap<>();
      for (EntityMention em : mentions) {
        String key = em.getTokens().getTokenizationId().getUuidString();
        List<EntityMention> l = m.get(key);
        if (l == null) {
          l = new ArrayList<>();
          m.put(key, l);
        }
        l.add(em);
      }
      return m;
    }

    private void writeSituations(Communication c, Map<String, Tokenization> tmap) throws IOException {
      if (c.isSetSituationMentionSetList()) {
        for (SituationMentionSet sms : c.getSituationMentionSetList()) {
          if (sms.isSetMentionList()) {
            for (SituationMention sm : sms.getMentionList()) {

              TokenRefSequence sitMention = sm.getTokens();
              assert tmap.get(sitMention.getTokenizationId().getUuidString()) != null;

              for (MentionArgument a : sm.getArgumentList()) {

                // Do not output if argument doens't contain a named entity
                TokenRefSequence argMention = a.getTokens();
                String nerType = AddNerTypeToEntityMentions.getNerType(argMention, tmap, nerTool);
                if ("O".equals(nerType)) {
                  ec.increment("skip/argsuments/O");
                  continue;
                }
                int ttypes = nerType.split(",").length;
                if (ttypes > 1) {
                  ec.increment("skip/argsuments/complexType");
                  continue;
                }

                boolean takeNnCompounts = true;
                boolean allowFailures = true;
                String argHead = headword(argMention, tmap, takeNnCompounts, allowFailures);
                if (argHead == null) {
                  ec.increment("err/headfinder");
                  continue;
                }
                //                      if (argHead.length() < 3 && !"a".equalsIgnoreCase(argHead) && !argHead.matches("\\d+") && !argHead.matches("[A-Z]+") && !"Count".equals(a.getRole()) && argMention.getTokenIndexListSize() > 1) {
                //                        Log.info("why? " + argHead);
                //                        argHead = headword(argMention, tmap, allowFailures);
                //                      }
                assert argHead.split("\\s+").length == 1;
                String tokId = argMention.getTokenizationId().getUuidString();
                assert tokId.equals(sitMention.getTokenizationId().getUuidString());

                w_situations.write(sm.getSituationKind()
                    + "\t" + a.getRole()
                    + "\t" + nerType
                    + "\t" + argHead
                    + "\t" + tokId
                    + "\t" + c.getUuid().getUuidString()
                    + "\t" + c.getId());
                w_situations.newLine();
                ec.increment("arguments");
              }
            }
          }
        }
      }
    }

    @Override
    public void close() throws Exception {
      Log.info("closing, " + ec);
      if (w_situations != null)
        w_situations.close();
      if (w_deprel != null)
        w_deprel.close();
    }
  }
  
  /**
   * Given an (entityName, entityType, entityContextDoc) query,
   * finds mentions where:
   * a) there is ngram overlap between entityName and EntityMention.getText()
   * b) entityType matches
   * c) the tf-idf similarity between entityContextDoc and the doc in which the result appears.
   */
  public static class EntitySearch {
    
    public static EntitySearch build(ExperimentProperties config) throws IOException {
      File nerFeatures = config.getExistingDir("nerFeatureDir", new File(HOME, "ner_feats"));
      File docVecs = config.getExistingFile("docVecs", new File(HOME, "doc/docVecs.128.txt"));
      File idf = config.getExistingFile("idf", new File(HOME, "doc/idf.txt"));
      File mentionLocs = config.getExistingFile("mentionLocs", new File(HOME, "raw/mentionLocs.txt.gz"));
      File tokObs = config.getExistingFile("tokenObs", new File(HOME, "tokenObs.jser.gz"));
      File tokObsLc = config.getExistingFile("tokenObsLower", new File(HOME, "tokenObs.lower.jser.gz"));
      EntitySearch s = new EntitySearch(nerFeatures, docVecs, idf, mentionLocs, tokObs, tokObsLc);
      return s;
    }
    
    public static void main(ExperimentProperties config) throws IOException {
      EfficientUuidList.simpleTest();
      EntitySearch s = build(config);
      String context = "Barack Hussein Obama II (US Listen i/bəˈrɑːk huːˈseɪn oʊˈbɑːmə/;[1][2] born August 4 , 1961 )"
          + " is an American politician who is the 44th and current President of the United States . He is the first"
          + " African American to hold the office and the first president born outside the continental United States . "
          + "Born in Honolulu , Hawaii , Obama is a graduate of Columbia University and Harvard Law School, where he was "
          + "president of the Harvard Law Review . He was a community organizer in Chicago before earning his law degree . "
          + "He worked as a civil rights attorney and taught constitutional law at the University of Chicago Law School "
          + "between 1992 and 2004 . While serving three terms representing the 13th District in the Illinois Senate from "
          + "1997 to 2004 , he ran unsuccessfully in the Democratic primary for the United States House of Representatives "
          + "in 2000 against incumbent Bobby Rush .";
      List<Result> rr = s.search("Barack Obama", "PERSON", new String[] {"Obama"}, context);
      for (int i = 0; i < 10 && i < rr.size(); i++) {
        System.out.println(rr.get(i));
      }
      System.out.println(TIMER);
    }

    private NerFeatureInvertedIndex nerFeatures;
    private TfIdf tfidf;
    private Map<String, String> emUuid2commUuid;
    private Map<String, String> emUuid2commId;
    private TokenObservationCounts tokObs, tokObsLc;
    
    public EntitySearch(File nerFeaturesDir, File docVecs, File idf, File mentionLocs, File tokObs, File tokObsLc) throws IOException {
      this.tfidf = new TfIdf(docVecs, idf);
      this.tokObs = (TokenObservationCounts) FileUtil.deserialize(tokObs);
      this.tokObsLc = (TokenObservationCounts) FileUtil.deserialize(tokObsLc);
      loadNerFeatures(nerFeaturesDir);
      emUuid2commUuid = new HashMap<>();
      emUuid2commId = new HashMap<>();
      Log.info("loading mention locations from " + mentionLocs.getPath());
      TIMER.start("load/mentionLocs");
      try (BufferedReader r = FileUtil.getReader(mentionLocs)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          // EntityMention UUID, Communication UUID, Communication id, entityType, hasHead?, numTokens, numChars
          String[] ar = line.split("\t");
          assert ar.length == 7;
          String emUuid = ar[0];
          String commUuid = ar[1];
          String commId = ar[2];
          Object old1 = emUuid2commUuid.put(emUuid, commUuid);
          Object old2 = emUuid2commId.put(emUuid, commId);
          assert old1 == old2 && old2 == null : "old1=" + old1 + " old2=" + old2 + " line=" + line;
        }
      }
      TIMER.stop("load/mentionLocs");
      Log.info("indexed the location of " + emUuid2commUuid.size() + " mentions");
    }
    
    public void loadNerFeatures(File nerFeaturesDir) throws IOException {
//      this.nerFeatures = new NerFeatureInvertedIndex(nerFeatures);
      if (!nerFeaturesDir.isDirectory())
        throw new IllegalArgumentException("must be a dir: " + nerFeaturesDir.getPath());
      List<Pair<String, File>> sources = new ArrayList<>();
      for (File f : nerFeaturesDir.listFiles()) {
        String[] a = f.getName().split("\\.");
        if (a.length >= 3 && a[0].equals("nerFeats")) {
          String nerType = a[1];
          sources.add(new Pair<>(nerType, f));
        }
      }
      if (sources.isEmpty())
        err_misc++;
      Log.info("sources: " + sources);
      this.nerFeatures = new NerFeatureInvertedIndex(sources);
    }
    
    public List<Result> search(String entityName, String entityType, String[] headwords, String contextWhitespaceDelim) {
      TIMER.start("search");
      int numTermsInPack = tfidf.getMaxVecLength();
      String[] context = contextWhitespaceDelim.split("\\s+");
      TermVec contextVec = TfIdf.build(context, numTermsInPack, tfidf.idf);
      
      List<Result> mentions = nerFeatures.findMentionsMatching(entityName, entityType, headwords, tokObs, tokObsLc);
      for (Result r : mentions) {
        assert Double.isFinite(r.score);
        assert !Double.isNaN(r.score);
        r.communicationUuid = emUuid2commUuid.get(r.entityMentionUuid);
        r.communicationId = emUuid2commId.get(r.entityMentionUuid);
        r.debug("queryNgramOverlap", r.score);
        assert r.communicationUuid != null : "no comm uuid for em uuid: " + r.entityMentionUuid;
        double scoreContext = tfidf.tfidf(contextVec, r.communicationUuid);
        double scoreMention = Math.min(1, r.score / entityName.length());
        r.score = scoreContext * scoreMention;
        r.debug("scoreContext", scoreContext);
        r.debug("scoreMention", scoreMention);
        assert Double.isFinite(r.score);
        assert !Double.isNaN(r.score);
      }
      
      Collections.sort(mentions, Result.BY_SCORE_DESC);
      TIMER.stop("search");
      return mentions;
    }
  }

  public static class Result {
    String entityMentionUuid;
    String communicationUuid;
    double score;
    
    // Optional
    String communicationId;

    String queryEntityName;
    String queryEntityType;
    
    Map<String, String> debugValues;
    public String debug(String key) {
      return debugValues.get(key);
    }
    public String debug(String key, Object value) {
      if (debugValues == null)
        debugValues = new HashMap<>();
      return debugValues.put(key, String.valueOf(value));
    }

    @Override
    public String toString() {
      String s = "EntityMention:" + entityMentionUuid
          + " Communication:" + communicationUuid
          + " score:" + score
          + " query:" + queryEntityName + "/" + queryEntityType;
      if (debugValues != null) {
        s += " " + debugValues;
      }
      return s;
    }
    
    public static final Comparator<Result> BY_SCORE_DESC = new Comparator<Result>() {
      @Override
      public int compare(Result o1, Result o2) {
        if (o1.score > o2.score)
          return -1;
        if (o1.score < o2.score)
          return +1;
        return 0;
      }
    };
  }

  /**
   * Runs after (ngram, nerType, EntityMention UUID) table has been built.
   * Given a query string and nerType, this breaks the string up into ngrams,
   * and finds all EntityMentions which match the type and contain ngrams from
   * the query string.
   * 
   * Strategies for sub-selecting ngrams to index (slightly diff than sharding which is not intelligble)
   * 1) only index NYT
   * 2) only index 2005-2007
   * 3) only index PERSON mentions
   */
  public static class NerFeatureInvertedIndex extends StringIntUuidIndex {
    private static final long serialVersionUID = -8638109659685198036L;

    public static void main(ExperimentProperties config) throws IOException {
      File input = config.getExistingFile("input", new File(HOME, "raw/nerFeatures.txt.gz"));

//      NerFeatureInvertedIndex n = new NerFeatureInvertedIndex(input);
      NerFeatureInvertedIndex n = new NerFeatureInvertedIndex(Arrays.asList(new Pair<>("PERSON", input)));

      for (Result x : n.findMentionsMatching("Barack Obama", "PERSON", new String[] {"Obama"}))
        System.out.println(x);

      for (Result x : n.findMentionsMatching("OBAMA", "PERSON", new String[] {"OBAMA"}))
        System.out.println(x);

      for (Result x : n.findMentionsMatching("barry", "PERSON", new String[] {"barry"}))
        System.out.println(x);

      for (Result x : n.findMentionsMatching("UN", "ORGANIZATION", new String[] {"UN"}))
        System.out.println(x);

      for (Result x : n.findMentionsMatching("United Nations", "ORGANIZATION", new String[] {"Nations"}))
        System.out.println(x);
    }
    
    
    public NerFeatureInvertedIndex(List<Pair<String, File>> featuresByNerType) throws IOException {
      super();
      for (Pair<String, File> x : featuresByNerType) {
        this.putIntLines(x.get1(), x.get2());
      }
    }
    
    @Override
    public String toString() {
      return "(NerFeatures mentions:" + valuesPerString
        + " totalMentions=" + n_values
        + ")";
    }

    public List<Result> findMentionsMatching(String entityName, String entityType, String[] headwords) {
      err_misc++;
      return findMentionsMatching(entityName, entityType, headwords, null, null);
    }

    /**
     * Returns a list of (EntityMention UUID, score) pairs.
     */
    public List<Result> findMentionsMatching(String entityName, String entityType, String[] headwords,
        TokenObservationCounts tokeObs, TokenObservationCounts tokenObsLc) {
      TIMER.start("find/nerFeatures");
      Log.info("entityName=" + entityName + " nerType=" + entityType);
      
      // Find out which EntityMentions contain the query ngrams
      List<String> features = features(entityName, headwords, entityType, tokeObs, tokenObsLc);
      int n = features.size();
      Counts<String> emNgramOverlap = new Counts<>();
      for (int i = 0; i < n; i++) {
        int term = HASH.hashString(features.get(i), UTF8).asInt();
        int weight = featureWeight(features.get(i));
        List<String> emsContainingTerm = get(entityType, term);
        for (String em : emsContainingTerm) {
//          emNgramOverlap.increment(em);
          emNgramOverlap.update(em, weight);
        }
      }
      
      List<Result> rr = new ArrayList<>();
      for (String em : emNgramOverlap.getKeysSortedByCount(true)) {
        Result r = new Result();
        r.queryEntityName = entityName;
        r.queryEntityType = entityType;
        r.entityMentionUuid = em;
        r.communicationUuid = null;
        r.score = emNgramOverlap.getCount(em);  // TODO length normalization, tf-idf weights
        rr.add(r);
      }

      TIMER.stop("find/nerFeatures");
      return rr;
    }
  }
  
  public static class TermVec {
    int totalCount = -1;        // sum(counts)
    int totalCountNoAppox = -1; // sum(counts) if counts didn't have to truncate
    int[] terms;
    short[] counts;
    
    public TermVec(int size) {
      terms = new int[size];
      counts = new short[size];
    }
    
    public void computeTotalCount() {
      totalCount = 0;
      for (int i = 0; i < counts.length; i++)
        totalCount += counts[i];
    }
    
    public int numTerms() {
      return terms.length;
    }
    
    public double tfLowerBound(int index) {
      assert totalCount > 0 : "totalCount=" + totalCount + " index=" + index + " length=" + counts.length;
      double tf = counts[index];
      return tf / totalCount;
    }
    
    void set(int index, int term, int count) {
      if (count > Short.MAX_VALUE)
        throw new IllegalArgumentException("count too big: " + count);
      if (count < 1)
        throw new IllegalArgumentException("count too small: " + count);
      terms[index] = term;
      counts[index] = (short) count;
    }

    public static double tfidf(TermVec x, TermVec y) {
      TIMER.start("tfidf");

      // a = query.tf * idf
      int sizeGuess = y.numTerms() + x.numTerms();
      double missingEntry = 0;
      IntDoubleHashMap a = new IntDoubleHashMap(sizeGuess, missingEntry);
      for (int i = 0; i < y.numTerms(); i++) {
        double s = y.tfLowerBound(i);
        a.put(y.terms[i], s);
      }
      
      // a *= comm.tf, reduceSum
      double s = 0;
      for (int i = 0; i < x.numTerms(); i++) {
        double pre = a.getWithDefault(x.terms[i], 0);
        assert Double.isFinite(pre);
        assert !Double.isNaN(pre);
        s += pre * x.tfLowerBound(i);
        assert Double.isFinite(s);
        assert !Double.isNaN(s);
      }
      
      TIMER.stop("tfidf");
      return s;
    }
  }

  /**
   * Stores the tf-idf vecs for a bunch of docs which you can query against.
   * The representation stored here can be generated by {@link ComputeTfIdfDocVecs}.
   */
  public static class TfIdf {
    private Map<String, TermVec> comm2vec;
    private IntDoubleHashMap idf;
    private int vecMaxLen = -1;
    
    public TfIdf(File docVecs, File idf) throws IOException {
      loadIdf(idf);
      loadDocVecs(docVecs);
    }
    
    public TermVec get(String commUuid) {
      return comm2vec.get(commUuid);
    }

    /**
     * @param docVecs should have lines like: <commUuid> (<tab> <hashedWord>:<count>)+
     */
    public void loadDocVecs(File docVecs) throws IOException {
      Log.info("source=" + docVecs.getPath());
      TIMER.start("load/docVecs");
      comm2vec = new HashMap<>();
      try (BufferedReader r = FileUtil.getReader(docVecs)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] ar = line.split("\t");
          assert ar.length > 2;
          TermVec tv = new TermVec(ar.length - 2);
          String commUuid = ar[0];
          tv.totalCountNoAppox = Integer.parseInt(ar[1]);
          for (int i = 2; i < ar.length; i++) {
            String[] kv = ar[i].split(":");
            assert kv.length == 2;
            int term = Integer.parseUnsignedInt(kv[0]);
            int count = Integer.parseInt(kv[1]);
            tv.set(i-2, term, count);
          }
          tv.computeTotalCount();
          Object old = comm2vec.put(commUuid, tv);
          assert old == null : "commUuid is not unique? " + commUuid;
          if (tv.numTerms() > vecMaxLen)
            vecMaxLen = tv.numTerms();
        }
      }
      TIMER.stop("load/docVecs");
    }
    
    /**
     * @param idf should have lines like: <hashedWord> <tab> <idf>
     */
    public void loadIdf(File idf) throws IOException {
      TIMER.start("load/idf");
      Log.info("source=" + idf.getPath());
      int sizeGuess = 1024;
      double missingEntry = 0;
      this.idf = new IntDoubleHashMap(sizeGuess, missingEntry);
      try (BufferedReader r = FileUtil.getReader(idf)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] ar = line.split("\t");
          assert ar.length == 2;
          int term = Integer.parseUnsignedInt(ar[0]);
          double idfTerm = Double.parseDouble(ar[1]);
          this.idf.put(term, idfTerm);
        }
      }
      TIMER.stop("load/idf");
    }
    
    public int getMaxVecLength() {
      return vecMaxLen;
    }
    
    public double tfidf(TermVec query, String commUuid) {
      TermVec comm = comm2vec.get(commUuid);
      return TermVec.tfidf(query, comm);
    }
    
    public static TermVec build(String[] document, int numTermsInPack, IntDoubleHashMap idf) {
      TIMER.start("build/termVec");

      // Count words
      int sizeGuess = document.length;
      IntFloatUnsortedVector tf = new IntFloatUnsortedVector(sizeGuess);
      for (String t : document) {
        int term = HASH.hashString(t, UTF8).asInt();
        tf.add(term, 1);
      }

      // Compute tf*idf entries
      List<Pair<IntPair, Double>> doc = new ArrayList<>(tf.getUsed());
      tf.forEach(e -> {
        int term = e.index();
        float tfTerm = e.get();
        double idfTerm = idf.getWithDefault(term, 0);
        doc.add(new Pair<>(new IntPair(term, (int) tfTerm), tfTerm * idfTerm));
      });
      Collections.sort(doc, ComputeTfIdfDocVecs.byVal);
      
      // Truncate and pack (term, freq) into vec (discarding idf info)
      int n = Math.min(numTermsInPack, doc.size());
      TermVec tv = new TermVec(n);
      for (int i = 0; i < n; i++) {
        Pair<IntPair, Double> x = doc.get(i);
        IntPair termFreq = x.get1();
        tv.set(i, termFreq.first, termFreq.second);
      }
      tv.totalCountNoAppox = document.length;
      tv.computeTotalCount();
      
      TIMER.stop("build/termVec");
      return tv;
    }
  }
  

  /**
   * Runs after (count, hashedTerm, comm uuid) table is built.
   * 
   * If there are 9.9M documents in gigaword (best estimate),
   * Each doc needs
   * a) 16 bytes for UUID
   * b) 128 * (4 bytes term idx + 1 byte for count)
   * = 656 bytes
   * * 9.9M docs = 6.3GB
   * 
   * I think I estimated without pruning that it would be something like 12G?
   * 
   * The point is that 6.3G easily fits in memory and can be scaled up or down.
   */
  public static class ComputeTfIdfDocVecs {
    
    public static void main(ExperimentProperties config) throws IOException {
      int numTerms = config.getInt("numTerms", 128);
      File in = config.getExistingFile("input", new File(HOME, "raw/termDoc.txt.gz"));
      File outInvIdx = config.getFile("outputDocVecs", new File(HOME, "doc/docVecs." + numTerms + ".txt"));
      File outIdfs = config.getFile("outputIdf", new File(HOME, "doc/idf.txt"));
      ComputeTfIdfDocVecs c = new ComputeTfIdfDocVecs(in);
      c.writeoutIdfs(outIdfs);
      c.pack(numTerms, outInvIdx);
    }
    
    private IntIntHashVector termDocCounts; // keys are hashed words
    private int nDoc;
    private double logN;
    private File termDoc;
      
    // For pack, track order stats of approx quality l2(approx(tfidf))/l2(tfidf)
    // TODO This stores O(n) memory, may break on big datasets
    private OrderStatistics<Double> approxQualOrdStats = new OrderStatistics<>();
    
    public ComputeTfIdfDocVecs(File termDoc) throws IOException {
      this.termDoc = termDoc;
      this.termDocCounts = new IntIntHashVector();
      this.nDoc = 0;
      TimeMarker tm = new TimeMarker();
      String prevId = null;
      TIMER.start("load/termVec/raw");
      try (BufferedReader r = FileUtil.getReader(termDoc)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          // count, term, doc
          String[] ar = line.split("\t");
          assert ar.length == 3;
//          int count = Integer.parseInt(ar[0]);
          int term = Integer.parseUnsignedInt(ar[1]);
          String uuid = ar[2];
          if (!uuid.equals(prevId)) {
            nDoc++;
            prevId = uuid;
          }
          termDocCounts.add(term, 1);
          
          if (tm.enoughTimePassed(10)) {
            Log.info("nDoc=" + nDoc + " termDocCounts.size=" + termDocCounts.size()
                + "\t" + Describe.memoryUsage());
          }
        }
      }
      this.logN = Math.log(nDoc);
      TIMER.stop("load/termVec/raw");
    }
    
    public double idf(int term) {
      int df = termDocCounts.get(term) + 1;
      return this.logN - Math.log(df);
    }
    
    public void writeoutIdfs(File idf) throws IOException {
      Log.info("output=" + idf.getPath());
      try (BufferedWriter w = FileUtil.getWriter(idf)) {
        termDocCounts.forEach(iie -> {
          int term = iie.index();
          try {
            w.write(Integer.toUnsignedString(term) + "\t" + idf(term));
            w.newLine();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
      }
    }

    /**
     * Writes out lines of the form:
     * <commUuid> <tab> <numTermsInComm> (<tab> <term>:<freq>)*
     * 
     * @param numTerms
     * @param prunedTermDoc
     * @throws IOException
     */
    public void pack(int numTerms, File prunedTermDoc) throws IOException {
      TIMER.start("pack");
      Log.info("numTerms=" + numTerms + " output=" + prunedTermDoc);
      TimeMarker tm = new TimeMarker();
      int n = 0;
      // ((term, count), tf*idf)
      List<Pair<IntPair, Double>> curTerms = new ArrayList<>();
      String curId = null;
      try (BufferedReader r = FileUtil.getReader(termDoc);
          BufferedWriter w = FileUtil.getWriter(prunedTermDoc)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] toks = line.split("\t");
          assert toks.length == 3;
          int count = Integer.parseInt(toks[0]);
          int term = Integer.parseUnsignedInt(toks[1]);
          String id = toks[2];
          
          if (!id.equals(curId)) {
            if (curId != null)
              packHelper(numTerms, w, curTerms, curId);
            curId = id;
            curTerms.clear();
          }

          IntPair key = new IntPair(term, count);
          curTerms.add(new Pair<>(key, count * idf(term)));
          
          n++;
          if (tm.enoughTimePassed(10)) {
            Log.info("processed " + n + " documents\t" + Describe.memoryUsage());
          }
        }
        if (curId != null)
          packHelper(numTerms, w, curTerms, curId);
      }
      Log.info("done, approximation quality = l2(approx(tfidf)) / l2(tfidf), 1 is perfect:");
      List<Double> r = Arrays.asList(0.0, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5);
      List<Double> p = approxQualOrdStats.getOrders(r);
      for (int i = 0; i < r.size(); i++) {
        System.out.printf("\t%.1f%%-percentile approximation quality is %.3f\n",
            100*r.get(i), p.get(i));
      }
      TIMER.stop("pack");
    }
    
//    /** 
//     * @param doc should have values of ((term, count), tf*idf)
//     */
//    public static void packTerms(List<Pair<IntPair, Double>> doc, int termsInPack) {
//      // Sort by tf*idf
//      Collections.sort(doc, byVal);
//
//    }

    private void packHelper(int numTerms, BufferedWriter w, List<Pair<IntPair, Double>> doc, String id) throws IOException {
      TIMER.start("pack/vec");
      // Sort by tf*idf
      Collections.sort(doc, byVal);
      // Count all terms
      int z = 0;
      for (Pair<IntPair, Double> e : doc)
        z += e.get1().second;
      // Prune
      List<Pair<IntPair, Double>> approx;
      if (doc.size() > numTerms) {
        approx = doc.subList(0, numTerms);
      } else {
        approx = doc;
      }
      // Output
      w.write(id);
      w.write('\t');
      w.write(Integer.toString(z));
      for (Pair<IntPair, Double> e : approx) {
        w.write('\t');
        w.write(Integer.toUnsignedString(e.get1().first));
        w.write(':');
        w.write(Integer.toString(e.get1().second));
      }
      w.newLine();
      
      // Compute approximation error
      double a = l2(approx);
      double b = l2(doc);
      approxQualOrdStats.add(a / b);
      TIMER.stop("pack/vec");
    }
    private double l2(List<Pair<IntPair, Double>> vec) {
      double ss = 0;
      for (Pair<IntPair, Double> x : vec) {
        double s = x.get2();
        ss += s * s;
      }
      return Math.sqrt(ss);
    }
    private static final Comparator<Pair<IntPair, Double>> byVal = new Comparator<Pair<IntPair, Double>>() {
      @Override
      public int compare(Pair<IntPair, Double> o1, Pair<IntPair, Double> o2) {
        if (o1.get2() > o2.get2())
          return -1;
        if (o1.get2() < o2.get2())
          return +1;
        return 0;
      }
    };
  }
  
  

  // Writes out a file containing (hashedTerm, term) pairs for every
  // term used in any output file.
  private ReversableHashWriter termHash;    // hashedTerm, term
  
  // For determining the correct prefix length based on counts
  private TokenObservationCounts tokObs;
  private TokenObservationCounts tokObsLc;
  
  private BufferedWriter w_nerFeatures;   // hashedFeature, nerType, EntityMention UUID, Tokenization UUID
  private BufferedWriter w_termDoc;       // count, hashedTerm, Communication UUID
  private BufferedWriter w_mentionLocs;   // EntityMention UUID, Communication UUID, Communication id, entityType, hasHead?, numTokens, numChars
  private BufferedWriter w_tok2comm;      // EntityMention UUID, Tokenization UUID, Communication UUID, Communication ID

  private boolean outputTfIdfTerms = false;
  
  // Indexes the Tokenizations of the Communication currently being observed
  private Map<String, Tokenization> tokMap;

  public IndexCommunications(
      TokenObservationCounts tokObs, TokenObservationCounts tokObsLc,
      File nerFeatures, File termDoc, File termHash, File mentionLocs, File tok2comm) {
    this.tokObs = tokObs;
    this.tokObsLc = tokObsLc;
    tokMap = new HashMap<>();
    try {
      w_nerFeatures = FileUtil.getWriter(nerFeatures);
      w_termDoc = FileUtil.getWriter(termDoc);
      w_mentionLocs = FileUtil.getWriter(mentionLocs);
      w_tok2comm = FileUtil.getWriter(tok2comm);
      this.termHash = new ReversableHashWriter(termHash, 1<<14, 1<<22);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  private static String headword(TokenRefSequence trs, Map<String, Tokenization> tokMap, boolean takeNnCompounds, boolean allowFailures) {
    Tokenization t = tokMap.get(trs.getTokenizationId().getUuidString());
    if (t == null) {
      if (!allowFailures)
        throw new RuntimeException("can't find tokenization! " + trs.getTokenizationId().getUuidString());
      return null;
    }
    
    List<Token> toks = t.getTokenList().getTokenList();
    if (!takeNnCompounds) {
      if (trs.isSetAnchorTokenIndex())
        return toks.get(trs.getAnchorTokenIndex()).getText();
      if (trs.getTokenIndexListSize() == 1)
        return toks.get(trs.getTokenIndexList().get(0)).getText();
    }
    
    // Fall back on a dependency parse
    int n = toks.size();
    DependencyParse d = getPreferredDependencyParse(t);
    MultiAlphabet a = new MultiAlphabet();
    LabeledDirectedGraph graph =
        LabeledDirectedGraph.fromConcrete(d, n, a);
    DParseHeadFinder hf = new DParseHeadFinder();
    hf.ignoreEdge(a.dep("prep"));
    hf.ignoreEdge(a.dep("poss"));
    hf.ignoreEdge(a.dep("possessive"));
    try {

      List<Integer> ts = trs.getTokenIndexList();
      int first = ts.get(0);
      int last = ts.get(ts.size()-1);
      
      if (takeNnCompounds) {
        int nnEdgeType = a.dep("nn");
        int[] hs = hf.headWithNn(graph, first, last, nnEdgeType);
        if (hs == null) {
          err_head++;
          if (!allowFailures)
            throw new RuntimeException();
          return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hs.length; i++) {
          if (i > 0) sb.append('_');
          assert hs[i] >= 0;
          sb.append(toks.get(hs[i]).getText());
        }
        return sb.toString();
      } else {
        int h = hf.head(graph, first, last);
        return toks.get(h).getText();
      }

    } catch (RuntimeException e) {
      if (allowFailures)
        return null;
      System.err.println(d);
      throw e;
    }
  }
  
  private static DependencyParse getPreferredDependencyParse(Tokenization toks) {
    for (DependencyParse dp : toks.getDependencyParseList()) {
      if ("parsey".equalsIgnoreCase(dp.getMetadata().getTool()))
        return dp;
    }
    for (DependencyParse dp : toks.getDependencyParseList()) {
      if ("Stanford CoreNLP basic".equalsIgnoreCase(dp.getMetadata().getTool()))
        return dp;
    }
    return null;
  }
  
  private void buildTokMap(Communication c) {
    tokMap.clear();
    if (c.isSetSectionList()) {
      for (Section sect : c.getSectionList()) {
        if (sect.isSetSentenceList()) {
          for (Sentence sent : sect.getSentenceList()) {
            Tokenization t = sent.getTokenization();
            Object old = tokMap.put(t.getUuid().getUuidString(), t);
            assert old == null;
          }
        }
      }
    }
  }
  
  private static List<String> prefixGrams(String mentionText,
      TokenObservationCounts tokObs, TokenObservationCounts tokObsLc) {
    boolean verbose = false;
    String[] toks = mentionText
        .replaceAll("-", " ")
        .replaceAll("\\d", "0")
        .replaceAll("[^A-Za-z0 ]", "")
        .split("\\s+");
    String[] toksLc = new String[toks.length];
    for (int i = 0; i < toks.length; i++)
      toksLc[i] = toks[i].toLowerCase();
    
    if (verbose) {
      System.out.println(mentionText);
      System.out.println(Arrays.toString(toks));
    }
    if (toks.length == 0) {
      err_misc++;
      return Collections.emptyList();
    }

    List<String> f = new ArrayList<>();
    
    // unigram prefixes
    for (int i = 0; i < toks.length; i++) {
      String p = tokObs.getPrefixOccuringAtLeast(toks[i], 10);
      String pi = tokObsLc.getPrefixOccuringAtLeast(toksLc[i], 10);
      f.add("p:" + p);
      f.add("pi:" + pi);
    }
    
    // bigram prefixes
    String B = "BBBB";
    String A = "AAAA";
    int lim = 10;
    boolean lc = true;
    TokenObservationCounts c = lc ? tokObsLc : tokObs;
    String[] tk = lc ? toksLc : toks;
    for (int i = -1; i < toks.length; i++) {
      String w, ww;
      if (i < 0) {
        w = B;
        ww = c.getPrefixOccuringAtLeast(tk[i+1], lim);
      } else if (i == toks.length-1) {
        w = c.getPrefixOccuringAtLeast(tk[i], lim);
        ww = A;
      } else {
        w = c.getPrefixOccuringAtLeast(tk[i], lim);
        ww = c.getPrefixOccuringAtLeast(tk[i+1], lim);
      }
      f.add("pb:" + w + "_" + ww);
    }

    if (verbose) {
      System.out.println(f);
      System.out.println();
    }

    return f;
  }
  
  public static List<String> features(String mentionText, String[] headwords, String nerType,
      TokenObservationCounts tokObs, TokenObservationCounts tokObsLc) {
    // ngrams
    List<String> features = new ArrayList<>();
    features.addAll(prefixGrams(mentionText, tokObs, tokObsLc));
    // headword
    for (int i = 0; i < headwords.length; i++) {
      if (headwords[i] == null)
        continue;
      String h = headwords[i];
      String hi = headwords[i].toLowerCase();
      String hp = tokObs.getPrefixOccuringAtLeast(headwords[i], 5);
//      String hip = tokObsLc.getPrefixOccuringAtLeast(headwords[i].toLowerCase(), 5);
      features.add("h:" + h);
      features.add("hi:" + hi);
      features.add("hp:" + hp);
//      features.add("hip:" + hip);
    }
    return features;
  }
  public static int featureWeight(String feature) {
    if (feature.startsWith("h:"))
      return 30;
    if (feature.startsWith("hi:"))
      return 20;
    if (feature.startsWith("hp:"))
      return 16;
    if (feature.startsWith("hip:"))
      return 8;
    if (feature.startsWith("p:"))
      return 2;
    if (feature.startsWith("pi:"))
      return 1;
    if (feature.startsWith("pb:"))
      return 5;
    err_misc++;
    return 1;
  }
  
  public static List<EntityMention> getEntityMentions(Communication c) {
    List<EntityMention> mentions = new ArrayList<>();
    if (c.isSetEntityMentionSetList()) {
      for (EntityMentionSet ems : c.getEntityMentionSetList()) {
        for (EntityMention em : ems.getMentionList()) {

          if (!em.isSetEntityType() || em.getEntityType().indexOf('\t') >= 0) {
            err_ner++;
            continue;
          }
          
          if ("O".equals(em.getEntityType()))
            continue;
          int ttypes = em.getEntityType().split(",").length;
          if (ttypes > 1)
            continue;

          n_ent++;
          mentions.add(em);
        }
      }
    }
    return mentions;
  }
  
  public void observe(Communication c) throws IOException {
    buildTokMap(c);
    new AddNerTypeToEntityMentions(c);
    n_doc++;

    for (EntityMention em : getEntityMentions(c)) {

      // EntityMention UUID, Communication UUID, Communication id, entityType, hasHead?, numTokens, numChars
      w_mentionLocs.write(em.getUuid().getUuidString());
      w_mentionLocs.write('\t');
      w_mentionLocs.write(c.getUuid().getUuidString());
      w_mentionLocs.write('\t');
      w_mentionLocs.write(c.getId());
      w_mentionLocs.write('\t');
      w_mentionLocs.write(String.valueOf(em.getEntityType()));
      w_mentionLocs.write('\t');
      w_mentionLocs.write(String.valueOf(em.getTokens().isSetAnchorTokenIndex()));
      w_mentionLocs.write('\t');
      w_mentionLocs.write(String.valueOf(em.getTokens().getTokenIndexListSize()));
      w_mentionLocs.write('\t');
      w_mentionLocs.write(String.valueOf(em.getText().length()));
      w_mentionLocs.newLine();
      
      // EntityMention UUID, Tokenization UUID, Communication UUID, Communication ID
      w_tok2comm.write(em.getUuid().getUuidString());
      w_tok2comm.write('\t');
      w_tok2comm.write(em.getTokens().getTokenizationId().getUuidString());
      w_tok2comm.write('\t');
      w_tok2comm.write(c.getUuid().getUuidString());
      w_tok2comm.write('\t');
      w_tok2comm.write(c.getId());
      w_tok2comm.newLine();

      boolean takeNnCompounts = true;
      boolean allowFailures = true;
      String head = headword(em.getTokens(), tokMap, takeNnCompounts, allowFailures);
      List<String> feats = features(em.getText(), new String[] {head}, em.getEntityType(),
          tokObs, tokObsLc);
      for (String f : feats) {
        int i = termHash.hash(f);
        // hash(word), nerType, EntityMention UUID, Tokenization UUID
        w_nerFeatures.write(Integer.toUnsignedString(i));
        w_nerFeatures.write('\t');
        w_nerFeatures.write(em.getEntityType());
        w_nerFeatures.write('\t');
        w_nerFeatures.write(em.getUuid().getUuidString());
        w_nerFeatures.write('\t');
        w_nerFeatures.write(em.getTokens().getTokenizationId().getUuidString());
        w_nerFeatures.newLine();
      }
    }
    
    // Terms
    List<String> terms = terms(c);
    if (outputTfIdfTerms) {

      // need to read-in IDF table
      throw new RuntimeException("implement me");

    } else {
      
      IntFloatUnsortedVector tf = new IntFloatUnsortedVector(terms.size());
      for (String t : terms) {
        n_tok++;
        int i = termHash.hash(t);
        tf.add(i, 1);
      }
      // apply calls compact
      tf.apply(new FnIntFloatToFloat() {
        @Override public float call(int arg0, float arg1) {
          try {
            // count, word, doc
            w_termDoc.write(Integer.toUnsignedString((int) arg1));
            w_termDoc.write('\t');
            w_termDoc.write(Integer.toUnsignedString(arg0));
            w_termDoc.write('\t');
            w_termDoc.write(c.getUuid().getUuidString());
            w_termDoc.newLine();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          return arg1;
        }
      });
    }
  }
  
  private static List<String> terms(Communication c) {
    List<String> t = new ArrayList<>(256);
    if (c.isSetSectionList()) {
      for (Section section : c.getSectionList()) {
        if (section.isSetSentenceList()) {
          for (Sentence sentence : section.getSentenceList()) {
            for (Token tok : sentence.getTokenization().getTokenList().getTokenList()) {
              String w = tok.getText()
                  .replaceAll("\\d", "0");
              t.add(w);
            }
          }
        }
      }
    }
    return t;
  }

  @Override
  public void close() throws Exception {
    w_nerFeatures.close();
    w_termDoc.close();
    w_mentionLocs.close();
    w_tok2comm.close();
    termHash.close();
  }
  
  @Override
  public String toString() {
    return String.format("(IC n_doc=%d n_tok=%d n_ent=%d err_ner=%d n_termHashes=%d n_termWrites=%d)",
        n_doc, n_tok, n_ent, err_ner, n_termHashes, n_termWrites);
  }
  
  public static void mainTokenObs(ExperimentProperties config) throws IOException {
    List<File> inputCommTgzs = config.getFileGlob("communicationArchives");
    File output = config.getFile("outputTokenObs", new File(HOME, "tokenObs.jser.gz"));
    File outputLower = config.getFile("outputTokenObsLower", new File(HOME, "tokenObs.lower.jser.gz"));
    TokenObservationCounts t = new TokenObservationCounts();
    TokenObservationCounts tLower = new TokenObservationCounts();
    TokenObservationCounts b = null;
    TokenObservationCounts bLower = null;
    TokenObservationCounts.trainOnCommunications(inputCommTgzs, t, tLower, b, bLower);
    FileUtil.serialize(t, output);
    FileUtil.serialize(tLower, outputLower);
  }
  
  public static void extractBasicData(ExperimentProperties config) throws Exception {
//    File commDir = config.getExistingDir("communicationsDirectory", new File(HOME, "../source-docs"));
    List<File> inputCommTgzs = config.getFileGlob("communicationArchives");
    File outputDir = config.getOrMakeDir("outputDirectory", new File(HOME, "raw"));
    File tokObsFile = config.getExistingFile("tokenObs", new File(HOME, "tokenObs.jser.gz"));
    File tokObsLowerFile = config.getExistingFile("tokenObsLower", new File(HOME, "tokenObs.lower.jser.gz"));
    
    Log.info("provided " + inputCommTgzs.size() + " communication archive files to scan");
    
    Log.info("loading TokenObservationCounts from " + tokObsFile.getPath() + "\t" + Describe.memoryUsage());
    TokenObservationCounts t = (TokenObservationCounts) FileUtil.deserialize(tokObsFile);
    Log.info("loading TokenObservationCounts from " + tokObsLowerFile.getPath() + "\t" + Describe.memoryUsage());
    TokenObservationCounts tl = (TokenObservationCounts) FileUtil.deserialize(tokObsLowerFile);
    
    File nerFeatures = new File(outputDir, "nerFeatures.txt.gz");
    File termDoc = new File(outputDir, "termDoc.txt.gz");
    File termHash = new File(outputDir, "termHash.approx.txt.gz");
    File mentionLocs = new File(outputDir, "mentionLocs.txt.gz");
    File tok2comm = new File(outputDir, "emTokCommUuidId.txt.gz");

    TimeMarker tm = new TimeMarker();
    try (IndexCommunications ic = new IndexCommunications(t, tl,
        nerFeatures, termDoc, termHash, mentionLocs, tok2comm)) {
      for (File f : inputCommTgzs) {
        Log.info("reading " + f.getName());
        try (InputStream is = new FileInputStream(f);
            TarGzArchiveEntryCommunicationIterator iter = new TarGzArchiveEntryCommunicationIterator(is)) {
          while (iter.hasNext()) {
            Communication c = iter.next();
            ic.observe(c);
            if (tm.enoughTimePassed(10))
              Log.info(ic + "\t" + c.getId() + "\t" + Describe.memoryUsage());
          }
        }
      }
    }
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    Log.info("starting, args: " + Arrays.toString(args));
    String c = config.getString("command");
    switch (c) {
    case "tokenObs":
      mainTokenObs(config);
      break;
    case "extractBasicData":
      extractBasicData(config);
      break;
    case "buildDocVecs":
      ComputeTfIdfDocVecs.main(config);
      break;
    case "nerFeatures":
      NerFeatureInvertedIndex.main(config);
      break;
    case "entitySearch":
      EntitySearch.main(config);
      break;
    case "indexSituations":
      IndexSituations.main(config);
      break;
    case "hashDeprels":
      HashDeprels.main(config);
      break;
    case "situationSearch":
      SituationSearch.main(config);
      break;
    case "testAccumulo":
      int localPort = config.getInt("port", 8585);
      CommunicationRetrieval cr = new CommunicationRetrieval(localPort);
      cr.test("NYT_ENG_20090901.0206");
      cr.test("ef93e366-79cc-b8e5-c816-8627a2e25887");
      break;
    default:
      Log.info("unknown command: " + c);
      break;
    }
    
    Log.info("done");
  }
}
