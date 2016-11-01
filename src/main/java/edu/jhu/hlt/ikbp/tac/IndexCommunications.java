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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.serialization.iterators.TarGzArchiveEntryCommunicationIterator;
import edu.jhu.hlt.fnparse.util.Describe;
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
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.hlt.tutils.ling.DParseHeadFinder;
import edu.jhu.prim.map.IntDoubleHashMap;
import edu.jhu.prim.map.IntObjectHashMap;
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

  public static int err_ner = 0;
  public static int err_misc = 0;
  
  /**
   * Given an (entityName, entityType, entityContextDoc) query,
   * finds mentions where:
   * a) there is ngram overlap between entityName and EntityMention.getText()
   * b) entityType matches
   * c) the tf-idf similarity between entityContextDoc and the doc in which the result appears.
   */
  public static class Search {
    
    public static Search build(ExperimentProperties config) throws IOException {
      File nerFeatures = config.getExistingDir("nerFeatureDir", new File(HOME, "ner_feats"));
      File docVecs = config.getExistingFile("docVecs", new File(HOME, "doc/docVecs.128.txt"));
      File idf = config.getExistingFile("idf", new File(HOME, "doc/idf.txt"));
      File mentionLocs = config.getExistingFile("mentionLocs", new File(HOME, "raw/mentionLocs.txt.gz"));
      File tokObs = config.getExistingFile("tokenObs", new File(HOME, "tokenObs.jser.gz"));
      File tokObsLc = config.getExistingFile("tokenObsLower", new File(HOME, "tokenObs.lower.jser.gz"));
      Search s = new Search(nerFeatures, docVecs, idf, mentionLocs, tokObs, tokObsLc);
      return s;
    }
    
    public static void main(ExperimentProperties config) throws IOException {
      EfficientUuidList.simpleTest();
      Search s = build(config);
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
    
    public Search(File nerFeaturesDir, File docVecs, File idf, File mentionLocs, File tokObs, File tokObsLc) throws IOException {
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
  public static class NerFeatureInvertedIndex {
    
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
    
    
    private Map<String, IntObjectHashMap<EfficientUuidList>> nerType2term2uuids;
    private Counts<String> mentionsByType = new Counts<>();
    private int n_mentions = 0;

    public NerFeatureInvertedIndex(List<Pair<String, File>> featuresByNerType) throws IOException {
      TimeMarker tm = new TimeMarker();
      nerType2term2uuids = new HashMap<>();
      TIMER.start("load/nerFeatures");
      for (Pair<String, File> t : featuresByNerType) {
        String nerType = t.get1();
        Log.info("reading " + nerType + " features from " + t.get2());
        TIMER.start("load/nerFeatures/" + nerType);
        IntObjectHashMap<EfficientUuidList> m = new IntObjectHashMap<>();
        try (BufferedReader r = FileUtil.getReader(t.get2())) {
          for (String line = r.readLine(); line != null; line = r.readLine()) {
            // term, comm_uuid+
            String[] ar = line.split("\t");
            assert ar.length >= 2;
            int term = Integer.parseUnsignedInt(ar[0]);
            EfficientUuidList uuids = new EfficientUuidList(ar.length-1);
            for (int i = 1; i < ar.length; i++)
              uuids.add(ar[i]);
            Object old = m.put(term, uuids);
            assert old == null;
            n_mentions++;
            
            if (tm.enoughTimePassed(5)) {
              Log.info("n_mentions=" + n_mentions + " f=" + t.get2()
                + " c=" + m.size() + "\t" + Describe.memoryUsage());
            }
          }
        }
        mentionsByType.update(nerType, m.size());
        nerType2term2uuids.put(nerType, m);
        TIMER.stop("load/nerFeatures/" + nerType);
      }
      Log.info("done, loaded: " + mentionsByType);
      TIMER.stop("load/nerFeatures");
    }

    private List<String> get(int term, String nerType) {
      IntObjectHashMap<EfficientUuidList> t2m = nerType2term2uuids.get(nerType);
      if (t2m == null) {
        err_misc++;
        return Collections.emptyList();
      }
      EfficientUuidList mentions = t2m.get(term);
      if (mentions == null) {
        return Collections.emptyList();
      }
      int n = mentions.size();
      List<String> l = new ArrayList<>(n);
      for (int i = 0; i < n; i++)
        l.add(mentions.getString(i));
      return l;
    }
    
    @Override
    public String toString() {
      return "(NerFeatures mentions:" + mentionsByType
        + " totalMentions=" + n_mentions
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
        List<String> emsContainingTerm = get(term, entityType);
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
      TIMER.start("tfidf");
      TermVec comm = comm2vec.get(commUuid);

      // a = query.tf * idf
      int sizeGuess = query.numTerms() + comm.numTerms();
      double missingEntry = 0;
      IntDoubleHashMap a = new IntDoubleHashMap(sizeGuess, missingEntry);
      for (int i = 0; i < query.numTerms(); i++) {
        double x = query.tfLowerBound(i);
        a.put(query.terms[i], x);
      }
      
      // a *= comm.tf, reduceSum
      double s = 0;
      for (int i = 0; i < comm.numTerms(); i++) {
        double pre = a.getWithDefault(comm.terms[i], 0);
        assert Double.isFinite(pre);
        assert !Double.isNaN(pre);
        s += pre * comm.tfLowerBound(i);
        assert Double.isFinite(s);
        assert !Double.isNaN(s);
      }
      
      TIMER.stop("tfidf");
      return s;
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
  
  

  // Given a word, if it's not in this approx-set, then you definitely haven't seen it
  private WrittenOut seenTerms;
  
  // For determining the correct prefix length based on counts
  private TokenObservationCounts tokObs;
  private TokenObservationCounts tokObsLc;
  
  private BufferedWriter w_nerFeatures;   // hashedFeature, nerType, EntityMention UUID
  private BufferedWriter w_termDoc;       // count, hashedTerm, Communication UUID
  private BufferedWriter w_termHash;      // hashedTerm, term
  private BufferedWriter w_mentionLocs;   // EntityMention UUID, Communication UUID, Communication id, entityType, hasHead?, numTokens, numChars

  private boolean outputTfIdfTerms = false;
  
  private long n_doc = 0, n_tok = 0, n_ent = 0, n_termWrites = 0, n_termHashes = 0;
  
  // Indexes the Tokenizations of the Communication currently being observed
  private Map<String, Tokenization> tokMap;

  public IndexCommunications(TokenObservationCounts tokObs, TokenObservationCounts tokObsLc, File nerFeatures, File termDoc, File termHash, File mentionLocs) {
    this.tokObs = tokObs;
    this.tokObsLc = tokObsLc;
    seenTerms = new WrittenOut(1<<14, 1<<22);
    tokMap = new HashMap<>();
    try {
      w_nerFeatures = FileUtil.getWriter(nerFeatures);
      w_termDoc = FileUtil.getWriter(termDoc);
      w_termHash = FileUtil.getWriter(termHash);
      w_mentionLocs = FileUtil.getWriter(mentionLocs);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  private static String headword(TokenRefSequence trs, Map<String, Tokenization> tokMap) {
    Tokenization t = tokMap.get(trs.getTokenizationId().getUuidString());
    if (t == null)
      return "";
    
    List<Token> toks = t.getTokenList().getTokenList();
    if (trs.isSetAnchorTokenIndex())
      return toks.get(trs.getAnchorTokenIndex()).getText();
    
    // Fall back on a dependency parse
    int n = toks.size();
    DependencyParse d = getPreferredDependencyParse(t);
    LabeledDirectedGraph graph =
        LabeledDirectedGraph.fromConcrete(d, n, new MultiAlphabet());
    DParseHeadFinder hf = new DParseHeadFinder();
    int h = hf.head(graph, 0, n-1);
    return toks.get(h).getText();
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
  
  public void observe(Communication c) throws IOException {
    buildTokMap(c);
    new AddNerTypeToEntityMentions(c);
    n_doc++;
    
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
          
          String head = headword(em.getTokens(), tokMap);
          List<String> feats = features(em.getText(), new String[] {head}, em.getEntityType(),
              tokObs, tokObsLc);
          for (String f : feats) {
            int i = hash(f);
            w_nerFeatures.write(Integer.toUnsignedString(i));
            w_nerFeatures.write('\t');
            w_nerFeatures.write(em.getEntityType());
            w_nerFeatures.write('\t');
            w_nerFeatures.write(em.getUuid().getUuidString());
            w_nerFeatures.newLine();
          }
        }
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
        int i = hash(t);
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
  
  static class WrittenOut {
    Counts<String> common, recent;
    int commonLim, recentLim;
    
    public WrittenOut(int recentLim, int commonLim) {
      this.recentLim = recentLim;
      this.commonLim = commonLim;
      this.recent = new Counts<>();
      this.common = new Counts<>();
    }
    
    public boolean add(String t) {
      if (common.getCount(t) >= 1)
        return false;
      if (recent.getCount(t) >= 1)
        return false;
      
      recent.increment(t);
      
      // COMPACT
      if (recent.numNonZero() > recentLim) {
        // Merge recent into common
        for (Entry<String, Integer> tt : recent.entrySet())
          common.update(tt.getKey(), tt.getValue());
        recent.clear();
        
        // Trim common
        if (common.numNonZero() > commonLim) {
          Counts<String> c = new Counts<>(commonLim);
          for (String x : common.getKeysSortedByCount(true)) {
            int cc = Math.max(1, common.getCount(x) - 100);
            c.update(x, cc);
            if (c.numNonZero() == commonLim)
              break;
          }
          common = c;
        }
      }
      return true;
    }
  }
  
  private int hash(String t) {
    n_termHashes++;
    int i = HASH.hashString(t, UTF8).asInt();
    if (seenTerms.add(t)) {
      n_termWrites++;
      try {
        w_termHash.write(Integer.toUnsignedString(i));
        w_termHash.write('\t');
        w_termHash.write(t);
        w_termHash.newLine();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return i;
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
    w_termHash.close();
    w_mentionLocs.close();
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
    
    Log.info("loading TokenObservationCounts from " + tokObsFile.getPath() + "\t" + Describe.memoryUsage());
    TokenObservationCounts t = (TokenObservationCounts) FileUtil.deserialize(tokObsFile);
    Log.info("loading TokenObservationCounts from " + tokObsLowerFile.getPath() + "\t" + Describe.memoryUsage());
    TokenObservationCounts tl = (TokenObservationCounts) FileUtil.deserialize(tokObsLowerFile);
    
    File nerFeatures = new File(outputDir, "nerFeatures.txt.gz");
    File termDoc = new File(outputDir, "termDoc.txt.gz");
    File termHash = new File(outputDir, "termHash.approx.txt.gz");
    File mentionLocs = new File(outputDir, "mentionLocs.txt.gz");

    TimeMarker tm = new TimeMarker();
    try (IndexCommunications ic = new IndexCommunications(t, tl, nerFeatures, termDoc, termHash, mentionLocs)) {
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
    case "search":
      Search.main(config);
      break;
    default:
      Log.info("unknown command: " + c);
      break;
    }
    
    Log.info("done");
  }
}
