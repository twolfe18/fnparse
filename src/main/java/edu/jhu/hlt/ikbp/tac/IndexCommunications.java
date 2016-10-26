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

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.serialization.iterators.TarGzArchiveEntryCommunicationIterator;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.OrderStatistics;
import edu.jhu.hlt.tutils.TimeMarker;
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

  public static final File HOME = new File("data/concretely-annotated-gigaword/ner-indexing/2016-10-26");
  public static final Charset UTF8 = Charset.forName("UTF-8");
  public static final HashFunction HASH = Hashing.murmur3_32();
  public static final MultiTimer TIMER = new MultiTimer();
  
  /**
   * Given an (entityName, entityType, entityContextDoc) query,
   * finds mentions where:
   * a) there is ngram overlap between entityName and EntityMention.getText()
   * b) entityType matches
   * c) the tf-idf similarity between entityContextDoc and the doc in which the result appears.
   */
  public static class Search {
    
    public static void main(ExperimentProperties config) throws IOException {
      File nerNgrams = config.getExistingFile("nerNgrams", new File(HOME, "raw/nerNgrams.txt.gz"));
      File docVecs = config.getExistingFile("docVecs", new File(HOME, "doc/docVecs.128.txt"));
      File idf = config.getExistingFile("idf", new File(HOME, "doc/idf.txt"));
      File mentionLocs = config.getExistingFile("mentionLocs", new File(HOME, "raw/mentionLocs.txt.gz"));

      Search s = new Search(nerNgrams, docVecs, idf, mentionLocs);
      
      String context = "Barack Hussein Obama II (US Listen i/bəˈrɑːk huːˈseɪn oʊˈbɑːmə/;[1][2] born August 4 , 1961 )"
          + " is an American politician who is the 44th and current President of the United States . He is the first"
          + " African American to hold the office and the first president born outside the continental United States . "
          + "Born in Honolulu , Hawaii , Obama is a graduate of Columbia University and Harvard Law School, where he was "
          + "president of the Harvard Law Review . He was a community organizer in Chicago before earning his law degree . "
          + "He worked as a civil rights attorney and taught constitutional law at the University of Chicago Law School "
          + "between 1992 and 2004 . While serving three terms representing the 13th District in the Illinois Senate from "
          + "1997 to 2004 , he ran unsuccessfully in the Democratic primary for the United States House of Representatives "
          + "in 2000 against incumbent Bobby Rush .";
      List<Result> rr = s.search("Barack Obama", "PERSON", context);

      for (int i = 0; i < 10 && i < rr.size(); i++) {
        System.out.println(rr.get(i));
      }
      System.out.println(TIMER);
    }

    private NerNgramInvertedIndex nerNgrams;
    private TfIdf tfidf;
    private Map<String, String> emUuid2commUuid;
    private Map<String, String> emUuid2commId;
    
    public Search(File nerNgrams, File docVecs, File idf, File mentionLocs) throws IOException {
      this.nerNgrams = new NerNgramInvertedIndex(nerNgrams);
      this.tfidf = new TfIdf(docVecs, idf);
      emUuid2commUuid = new HashMap<>();
      emUuid2commId = new HashMap<>();
      Log.info("loading mention locations from " + mentionLocs.getPath());
      TIMER.start("load/mentionLocs");
      try (BufferedReader r = FileUtil.getReader(mentionLocs)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          // EntityMention UUID, Communication UUID, Communication id
          String[] ar = line.split("\t");
          assert ar.length == 3;
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
    
    public List<Result> search(String entityName, String entityType, String contextWhitespaceDelim) {
      TIMER.start("search");
      int numTermsInPack = tfidf.getMaxVecLength();
      String[] context = contextWhitespaceDelim.split("\\s+");
      TermVec contextVec = TfIdf.build(context, numTermsInPack, tfidf.idf);
      
      List<Result> mentions = nerNgrams.findMentionsMatching(entityName, entityType);
      for (Result r : mentions) {
        assert Double.isFinite(r.score);
        assert !Double.isNaN(r.score);
        r.communicationUuid = emUuid2commUuid.get(r.entityMentionUuid);
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
  public static class NerNgramInvertedIndex {
    
    public static void main(ExperimentProperties config) throws IOException {
      File input = config.getExistingFile("input", new File(HOME, "raw/nerNgrams.txt.gz"));

      NerNgramInvertedIndex n = new NerNgramInvertedIndex(input);

      for (Result x : n.findMentionsMatching("Barack Obama", "PERSON"))
        System.out.println(x);

      for (Result x : n.findMentionsMatching("OBAMA", "PERSON"))
        System.out.println(x);

      for (Result x : n.findMentionsMatching("barry", "PERSON"))
        System.out.println(x);

      for (Result x : n.findMentionsMatching("UN", "ORGANIZATION"))
        System.out.println(x);

      for (Result x : n.findMentionsMatching("United Nations", "ORGANIZATION"))
        System.out.println(x);
    }
    
    private Map<String, IntObjectHashMap<List<String>>> nerType2term2emUuids;
    private int n_mentions = 0;
    
    private List<String> get(int term, String nerType) {
      IntObjectHashMap<List<String>> m = nerType2term2emUuids.get(nerType);
      if (m == null) {
        Log.warn("unknown/unindexed nerType=" + nerType);
        return Collections.emptyList();
      }
      List<String> r = m.get(term);
      if (r == null)
        r = Collections.emptyList();
      return r;
    }
    
    private void add(int term, String nerType, String entityMentionUuid) {
      n_mentions++;
      IntObjectHashMap<List<String>> m = nerType2term2emUuids.get(nerType);
      if (m == null) {
        m = new IntObjectHashMap<>();
        nerType2term2emUuids.put(nerType, m);
      }
      List<String> r = m.get(term);
      if (r == null) {
        r = new ArrayList<>(4);
        m.put(term, r);
      }
      r.add(entityMentionUuid);
    }
    
    public NerNgramInvertedIndex(File nerNgrams) throws IOException {
      Log.info("nerNgrams=" + nerNgrams.getPath());
      TimeMarker tm = new TimeMarker();
      nerType2term2emUuids = new HashMap<>();
      TIMER.start("load/nerNgrams");
      try (BufferedReader r = FileUtil.getReader(nerNgrams)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          // term, nerType, mention id
          String[] ar = line.split("\t");
          assert ar.length == 3;
          int term = Integer.parseUnsignedInt(ar[0]);
          String nerType = ar[1];
//          if (!"PERSON".equals(nerType))
//            continue;
          String emId = ar[2];
          add(term, nerType, emId);
          
          if (tm.enoughTimePassed(2))
            Log.info(toString());
        }
      }
      TIMER.stop("load/nerNgrams");
    }
    
    @Override
    public String toString() {
      return "(NE nerTypes=" + nerType2term2emUuids.size()
        + " n_mentions=" + n_mentions
        + ")";
    }

    /**
     * Returns a list of (EntityMention UUID, score) pairs.
     *
     * @param entityName
     * @param entityType
     * @return
     */
    public List<Result> findMentionsMatching(String entityName, String entityType) {
      TIMER.start("find/nerNgrams");
      Log.info("entityName=" + entityName + " nerType=" + entityType);
      
      // 1) find ngram -> list<doc> for all ngrams in entityName
      // 2) sort the list<doc>s by their length
      // 3) score(em) = \sum_{n in ngrams(query)} I(em contains n) + tfidf(query,em.doc)
      
      // There will only be ~entityName.length ngrams, and thus EM lists
      // We could probably just intersect them first, and then only ask for tf-idf for the results
      
      
      // Find out which EntityMentions contain the query ngrams
      int ngrams = 6;
      List<String> qng = ScanCAGForMentions.ngrams(entityName, ngrams);
      int n = qng.size();
//      List<List<String>> qngContains = new ArrayList<>(n);
      Counts<String> emNgramOverlap = new Counts<>();
      for (int i = 0; i < n; i++) {
        int term = HASH.hashString(qng.get(i), UTF8).asInt();
        List<String> emsContainingTerm = get(term, entityType);
//        qngContains.add(emsContainingTerm);
        for (String em : emsContainingTerm)
          emNgramOverlap.increment(em);
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

      TIMER.stop("find/nerNgrams");
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
   * If there are 5.6M documents in gigaword (best estimate),
   * Each doc needs
   * a) 16 bytes for UUID
   * b) 128 * (4 bytes term idx + 1 byte for count)
   * = 656 bytes
   * * 5.6M = 3.6G
   * 
   * I think I estimated without pruning that it would be something like 12G?
   * 
   * The point is that 3.6G easily fits in memory and can be scaled up or down.
   */
  public static class ComputeTfIdfDocVecs {
    
    public static void main(ExperimentProperties config) throws IOException {
      int numTerms = config.getInt("numTerms", 128);
      File in = config.getExistingFile("input", new File(HOME, "raw/termDoc.txt.gz"));
      File outInvIdx = config.getFile("outputInvIdx", new File(HOME, "doc/docVecs." + numTerms + ".txt"));
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
          
          if (tm.enoughTimePassed(5)) {
            Log.info("nDoc=" + nDoc + " termDocCounts.size=" + termDocCounts.size());
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
  private BloomFilter<String> seenTerms;
  
  private BufferedWriter w_nerNgrams;   // hashedNgram, nerType, EntityMention UUID
  private BufferedWriter w_termDoc;     // count, hashedTerm, Communication UUID
  private BufferedWriter w_termHash;    // hashedTerm, term
  private BufferedWriter w_mentionLocs;   // EntityMention UUID, Communication UUUID, Communication id

  private boolean outputTfIdfTerms = false;
  
  private long n_doc = 0, n_tok = 0, n_ent = 0, n_termWrites = 0, n_termHashes = 0;
  private int err_ner = 0;

  public IndexCommunications(File nerNgrams, File termDoc, File termHash, File mentionLocs) {
    double falsePosProb = 0.001;
    int expectedInsertions = 1<<20;
    seenTerms = BloomFilter.create(Funnels.stringFunnel(UTF8), expectedInsertions, falsePosProb);
    try {
      w_nerNgrams = FileUtil.getWriter(nerNgrams);
      w_termDoc = FileUtil.getWriter(termDoc);
      w_termHash = FileUtil.getWriter(termHash);
      w_mentionLocs = FileUtil.getWriter(mentionLocs);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  public void observe(Communication c) throws IOException {
    new AddNerTypeToEntityMentions(c);
    n_doc++;
    
    // EntityMention ngrams
    int ngrams = 6;
    if (c.isSetEntityMentionSetList()) {
      for (EntityMentionSet ems : c.getEntityMentionSetList()) {
        for (EntityMention em : ems.getMentionList()) {
          
          if ("O".equals(em.getEntityType()))
            continue;
          int ttypes = em.getEntityType().split(",").length;
          if (ttypes > 1)
            continue;
          
          w_mentionLocs.write(em.getUuid().getUuidString());
          w_mentionLocs.write('\t');
          w_mentionLocs.write(c.getUuid().getUuidString());
          w_mentionLocs.write('\t');
          w_mentionLocs.write(c.getId());
          w_mentionLocs.newLine();

          n_ent++;
          for (String ngram : ScanCAGForMentions.ngrams(em.getText(), ngrams)) {
            if (!em.isSetEntityType() || em.getEntityType().indexOf('\t') >= 0) {
              err_ner++;
              continue;
            }
            int i = hash(ngram);
            w_nerNgrams.write(Integer.toUnsignedString(i));
            w_nerNgrams.write('\t');
            w_nerNgrams.write(em.getEntityType());
            w_nerNgrams.write('\t');
            w_nerNgrams.write(em.getUuid().getUuidString());
            w_nerNgrams.newLine();
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
  
  private int hash(String t) {
    n_termHashes++;
    int i = HASH.hashString(t, UTF8).asInt();
    if (!seenTerms.mightContain(t)) {
      n_termWrites++;
      seenTerms.put(t);
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
              t.add(tok.getText().toLowerCase());
            }
          }
        }
      }
    }
//    int ngrams = 5;
//    if (c.isSetEntityMentionSetList()) {
//      for (EntityMentionSet ems : c.getEntityMentionSetList()) {
//        for (EntityMention em : ems.getMentionList()) {
//          for (String ngram : ScanCAGForMentions.ngrams(em.getText(), ngrams)) {
//            t.add("EntityMention-" + em.getEntityType() + "-" + ngram);
//          }
//        }
//      }
//    }
    return t;
  }

  @Override
  public void close() throws Exception {
    w_nerNgrams.close();
    w_termDoc.close();
    w_termHash.close();
    w_mentionLocs.close();
  }
  
  @Override
  public String toString() {
    return String.format("(IC n_doc=%d n_tok=%d n_ent=%d err_ner=%d n_termHashes=%d n_termWrites=%d)",
        n_doc, n_tok, n_ent, err_ner, n_termHashes, n_termWrites);
  }
  
  public static void extractBasicData(ExperimentProperties config) throws Exception {
//    if (args.length != 2) {
//      System.err.println("please provied:");
//      System.err.println("1) an input directory containing annotated .tar.gz Communication files");
//      System.err.println("2) a directory to put output in");
//      return;
//    }
//    File commDir = new File(args[0]);
//    File outputDir = new File(args[1]);
    File commDir = config.getExistingDir("communicationsDirectory");
    File outputDir = config.getOrMakeDir("outputDirectory", new File(HOME, "raw"));

    File nerNgram = new File(outputDir, "nerNgrams.txt.gz");
    File termDoc = new File(outputDir, "termDoc.txt.gz");
    File termHash = new File(outputDir, "termHash.txt.gz");
    File mentionLocs = new File(outputDir, "mentionLocs.txt.gz");

    TimeMarker tm = new TimeMarker();
    try (IndexCommunications ic = new IndexCommunications(nerNgram, termDoc, termHash, mentionLocs)) {
      for (File f : commDir.listFiles()) {
        if (!f.getName().toLowerCase().endsWith(".tar.gz"))
          continue;
        Log.info("reading " + f.getName());
        try (InputStream is = new FileInputStream(f);
            TarGzArchiveEntryCommunicationIterator iter = new TarGzArchiveEntryCommunicationIterator(is)) {
          while (iter.hasNext()) {
            Communication c = iter.next();
            ic.observe(c);
            if (tm.enoughTimePassed(10))
              Log.info(ic + "\t" + c.getId());
          }
        }
      }
    }
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    String c = config.getString("command");
    switch (c) {
    case "extractBasicData":
      extractBasicData(config);
      break;
    case "buildDocVecs":
      ComputeTfIdfDocVecs.main(config);
      break;
    case "nerNgrams":
      NerNgramInvertedIndex.main(config);
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
