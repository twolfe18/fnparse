package edu.jhu.hlt.ikbp.tac;

import static edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat.sortAndPrune;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.search.SearchQuery;
import edu.jhu.hlt.concrete.search.SearchResult;
import edu.jhu.hlt.concrete.search.SearchResultItem;
import edu.jhu.hlt.concrete.search.SearchService;
import edu.jhu.hlt.concrete.search.SearchType;
import edu.jhu.hlt.concrete.services.ServicesException;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.AttrFeatMatch;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.KbpSearching;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.TriageSearch;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SitSearchResult;
import edu.jhu.hlt.ikbp.tac.PkbpEntity.Mention;
import edu.jhu.hlt.ikbp.tac.PkbpSearching.New.PkbpNode;
import edu.jhu.hlt.ikbp.tac.TacKbp.KbpQuery;
import edu.jhu.hlt.tutils.ArgMax;
import edu.jhu.hlt.tutils.Average;
import edu.jhu.hlt.tutils.Beam;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.DoublePair;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.LabeledDirectedGraph;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.MultiTimer.TB;
import edu.jhu.hlt.tutils.SerializationUtils;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.prim.bimap.ObjectObjectBimap;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.ChooseOne;
import edu.jhu.util.CountMinSketch;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;
import edu.jhu.util.DiskBackedFetchWrapper;
import edu.jhu.util.DiskBackedSearchWrapper;
import edu.jhu.util.TokenizationIter;
import redis.clients.jedis.Jedis;

/**
 * Search outwards starting with a {@link KbpQuery} seed.
 */
public class PkbpSearching implements Serializable {
  private static final long serialVersionUID = 3891008944720961213L;

//  public static final MultiTimer TIMER = AccumuloIndex.TIMER;
  public static MultiTimer timer() {
    return AccumuloIndex.TIMER;
  }
  
  public static List<Feat> scoreSitLink(PkbpSituation sit, PkbpSituation.Mention mention) {
    assert sit.feat2score.size() > 0;
    assert mention.getFeatures().iterator().hasNext();
    List<Feat> out = new ArrayList<>();
    
    // General purpose feature overlap
    // See DependencySyntaxEvents.CoverArgumentsWithPredicates for feature extraction
    for (Feat f : mention.getFeatures()) {
      Double v = sit.feat2score.get(f.name);
      if (v != null) {
        out.add(new Feat(f.name, Math.sqrt(f.weight * v)));
      }
    }
    
    // TODO Strong negative penalty for not linking the arguments of this mention
    // to pre-existing arguments of this situation

    return out;
  }

  /**
   * Checks all situations which include at least two linked mention arguments.
   * 
   * This does NOT work like linkSituationOld, it returns a list of links with NEW and LINK links scattered throughout.
   * Therefore you should check when applying these actions, for instance, that NEW(m) is mutex with LINK(m,*),
   * whereas LINK(m,i) is not mutex with LINK(m,j).
   */
  public void linkSituationNew(
      PkbpSituation.Mention mention,
      List<EntLink> entityArgs,
      List<SitLink> outputNewSit,
      List<SitLink> outputLinkSit) {
    boolean verbose = false;
    boolean vv = false;
    assert outputLinkSit.isEmpty();
    assert outputNewSit.isEmpty();
    if (verbose) {
      Log.info("starting on:  " + mention);
      if (vv) {
        System.out.println("nEntityArgs=" + entityArgs.size());
        for (int i = 0; i < entityArgs.size(); i++)
          System.out.printf("entityArg(%d)=%s\n", i, entityArgs.get(i));
      }
    }
    
    /*
     * What would a simpler entity+situation linking model look look like?
     * 
     * ents:list[entity]
     * sits:list[situation]   where a sit has a arguments:list<entity> like result currently does
     * 
     * entity linking proceeds as it currently does
     * 
     * given a sm we know the entities via argument linking
     * for every pair of linked arg entities, lookup all situations (lets define one per ent pair for now) and decide link/prune
     */

    // TODO Switch from pairs to n-tuples, including n=1
    Set<List<String>> outputUniqCoreArgs = new HashSet<>();
    int n = entityArgs.size();
    for (int i = 0; i < n-1; i++) {
      for (int j = i+1; j < n; j++) {
        EntLink ei = entityArgs.get(i);
        EntLink ej = entityArgs.get(j);
        List<String> rKey = generateOutputKey(ei.target, ej.target);
        if (rKey == null) {
          if (vv)
            Log.info("SKIP (no key) i=" + i + " j=" + j + " rKey=" + rKey);
          continue;
        }
        if (!outputUniqCoreArgs.add(rKey)) {
          if (vv)
            Log.info("SKIP (not uniq) i=" + i + " j=" + j + " rKey=" + rKey);
          continue;
        }
        
        /*
         * TODO What is the difference between linking using
         * memb_e2s:Map<Ent, Sit*> and output2:Map<EntStrKey, Situation>?
         * the former should only be used for NEW_SITUATION actions
         * the latter should be used for LINK_SITUATION
         *
         * No, this isn't entirely correct.
         * These are two views which are do not necessarily support different needs.
         * For instance, I could do everything with just memb_e2s.
         * Then the process of showing results to users would involve iterating over
         * a e2s<~>s2e join view which could then be groupBy-ed (ei,ej).
         * Similarly, query formulation based on (ei,ej) pairs could be done in the same way.
         * OR, at this point in THIS loop, I could fail to find an intersection of these entities
         * and thus decide to create a new PkbpSituation for this mention and pair of (ei,ej).
         */
        
        // What situations contain both of these entities as core arguments?
        LL<PkbpSituation> si = memb_e2s.get(ei.target);
        LL<PkbpSituation> sj = memb_e2s.get(ej.target);
        if (vv) {
          Log.info("[LINK_SIT] i=" + i + " n=" + n + " ei=" + ei);
          Log.info("[LINK_SIT] j=" + j + " n=" + n + " ej=" + ej);
          System.out.println("[LINK_SIT] sits which ei partipates in: " + LL.toList(si));
          System.out.println("[LINK_SIT] sits which ej partipates in: " + LL.toList(sj));
        }
        Set<PkbpSituation> scommon = new HashSet<>();
        boolean anyCommon = false;
        for (LL<PkbpSituation> cur = si; cur != null; cur = cur.next)
          scommon.add(cur.item);
        for (LL<PkbpSituation> cur = sj; cur != null; cur = cur.next) {
          PkbpSituation sit = cur.item;
          if (scommon.contains(sit)) {
            List<EntLink> contingentUpon = Arrays.asList(ei, ej);
            List<Feat> score = scoreSitLink(sit, mention);
            outputLinkSit.add(new SitLink(contingentUpon, mention, sit, score, false));
            anyCommon = true;
            if (vv) {
              System.out.println("[LINK_SIT] common: " + sit);
              System.out.println("[LINK_SIT] score:  " + Feat.sum(score) + " " + sortAndPrune(score, 5));
              System.out.println();
            }
          }
        }
        
        if (!anyCommon) {
          // Consider a new situation link
          String id = "sit/" + mention.getCommTokHeadWordAndLoc();
          PkbpSituation target = new PkbpSituation(id, mention);
          target.addCoreArgument(ei.target);
          target.addCoreArgument(ej.target);
          List<EntLink> contingentUpon = Arrays.asList(ei, ej);
          List<Feat> score = new ArrayList<>();
          ComputeIdf df = search.getTermFrequencies();
          TriageSearch ts = search.getTriageSearch();
          score.addAll(interestingEntityMention(ei.source, seedTermVec, df, ts));
          score.addAll(interestingEntityMention(ej.source, seedTermVec, df, ts));
          outputNewSit.add(new SitLink(contingentUpon, mention, target, score, true));
          if (vv) {
            System.out.println("[NEW_SIT] no common situations, considering new one");
            System.out.println("[NEW_SIT] score:  " + Feat.sum(score) + " " + sortAndPrune(score, 5));
            System.out.println();
          }
        }
      }
    }
    if (verbose) {
      Log.info("done, found " + outputLinkSit.size() + " possible LINK_SITs"
          + " and " + outputNewSit.size() + " NEW_SITs "
          + " given nLinkedEntArgs=" + entityArgs.size() + " and mention=" + mention);
    }
  }
  
  /**
   * Works the same way as linkEntity, returns [newSit, bestSitLink, secondBest, ...]
   */
  public List<SitLink> linkSituationOld(PkbpSituation.Mention mention, List<EntLink> entityArgs, double defaultScoreForNewSituation) {
    
    List<PkbpSituation> possible = new ArrayList<>();
    List<EntLink> entityArgsUsed = new ArrayList<>();
    for (EntLink el : entityArgs) {
      PkbpEntity e = el.target;
      boolean used = false;
      for (LL<PkbpSituation> cur = memb_e2s.get(e); cur != null; cur = cur.next) {
        // TODO This shouldn't be the UNION of all situations which the arguments
        // appear in, it should be the set of situations which share at least two arguments
        possible.add(cur.item);
        used = true;
      }
      if (used)
        entityArgsUsed.add(el);
    }


    ArgMax<Pair<PkbpSituation, List<Feat>>> a = new ArgMax<>();
    for (PkbpSituation sit : possible) {
      List<Feat> score = scoreSitLink(sit, mention);
      double s = Feat.sum(score);
      a.offer(new Pair<>(sit, score), s);
    }
    
    List<SitLink> links = new ArrayList<>();
    
    // New
    List<Feat> newSitFeats = new ArrayList<>();
    newSitFeats.add(new Feat("intercept", defaultScoreForNewSituation));
    String id = "sit/" + mention.getCommTokHeadWordAndLoc();
    PkbpSituation newSit = new PkbpSituation(id, mention);
    links.add(new SitLink(Collections.emptyList(), mention, newSit, newSitFeats, true));
    
    if (a.numOffers() > 0) {
      List<Feat> af = a.get().get2();
      links.add(new SitLink(entityArgsUsed, mention, a.get().get1(), af, false));
      newSitFeats.add(new Feat("goodCompetingLink", Math.min(0, -Feat.sum(af))));
    }

    // Show links for debugging
    for (int i = 0; i < links.size(); i++) {
      Log.info(i + "\t" + links.get(i));
    }

    return links;
  }

  public static <R> List<R> first(int k, Iterable<R> items) {
    List<R> out = new ArrayList<>();
    for (R r : items) {
      out.add(r);
      if (out.size() == k)
        break;
    }
    return out;
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    if (config.getBoolean("newMain", true)) {
      New.main(config);
      return;
    }
    
    Random rand = config.getRandom();
    KbpSearching ks = null;
    PkbpSearching ps;

    ComputeIdf df = new ComputeIdf(config.getExistingFile("wordDocFreq"));

    String sfName = config.getString("slotFillQueries", "sf13+sf14");
    List<KbpQuery> queries = TacKbp.getKbpSfQueries(sfName);

    int maxResults = config.getInt("maxResults", 100);
    int maxDocsForMultiEntSearch = config.getInt("maxDocsForMultiEntSearch", 100_000);

    int stepsPerQuery = config.getInt("stepsPerQuery", 3);
    double seedWeight = config.getDouble("seedWeight", 30);
    Log.info("stepsPerQuery=" + stepsPerQuery + " seedWeight=" + seedWeight);

    File outputDir = config.getFile("outputDir", null);
    if (outputDir != null) {
      outputDir.mkdirs();
      Log.info("outputDir=" + outputDir.getPath());
    }
    File inputDir = config.getFile("inputDir", null);
    if (inputDir != null) {
      assert outputDir == null;
      Log.info("inputDir=" + inputDir.getPath());
    }
    
    boolean clearCachesEveryQuery = config.getBoolean("clearCachesEveryQuery", false);
    Log.info("clearCachesEveryQuery=" + clearCachesEveryQuery);
    
    boolean oneKsPerQuery = true;

    StringCountMinSketch sfCms = null;
    for (KbpQuery seed : queries) {
      Log.info("working on seed=" + seed + "\t" + Describe.memoryUsage());

      if (inputDir != null) {
        // Run anywhere with some files caching the accumulo work
        File inSearch = new File(inputDir, seed.id + ".search.jser.gz");
        if (!inSearch.isFile()) {
          Log.info("no pre-generated file: " + inSearch.getPath());
          continue;
        }
        Log.info("loading PkbpSearching from " + inSearch.getPath());
        ps = (PkbpSearching) FileUtil.deserialize(inSearch);
        
        if (ks == null || oneKsPerQuery) {
          File inKs = oneKsPerQuery
              ? new File(inputDir, "KbpSearching." + seed.id + ".jser.gz")
              : new File(inputDir, "KbpSearching.jser.gz");
          Log.info("deserializing KbpSearching from " + inKs.getPath());
          ks = (KbpSearching) FileUtil.deserialize(inKs);
        }
        ps.search = ks;

        ps.restoreCommsInInitialResultsCache();
        ps.clearSeenCommToks();

        File inQuery = new File(inputDir, seed.id + ".query.jser.gz");
        seed = (KbpQuery) FileUtil.deserialize(inQuery);
        ps.setSeed(seed, seedWeight, df);
      } else {
        // Run on the grid w/ access to accumulo
        if (ks == null) {
          File fceFile = config.getExistingFile("triageFeatureFrequencies");
          Log.info("loading triage feature frequencies (FeatureCardinalityEstimator.New) from=" + fceFile.getPath());
          FeatureCardinalityEstimator.New triageFeatureFrequencies =
              (FeatureCardinalityEstimator.New) FileUtil.deserialize(fceFile);
          TriageSearch ts = new TriageSearch(triageFeatureFrequencies, maxResults, maxDocsForMultiEntSearch);
          File fetchCacheDir = config.getOrMakeDir("fetch.cacheDir");
          String fetchHost = config.getString("fetch.host");
          int fetchPort = config.getInt("fetch.port");
          DiskBackedFetchWrapper commRet = KbpSearching.buildFetchWrapper(fetchCacheDir, fetchHost, fetchPort);
          Double minTriageScore = config.getDouble("minTriageScore", -1);
          if (minTriageScore <= 0)
            minTriageScore = null;
          ks = new KbpSearching(ts, df, minTriageScore, commRet, new HashMap<>());
        }
        if (sfCms == null) {
          // e.g. /export/projects/twolfe/sit-search/situation-feature-counts/count-min-sketch-v2/cag-cms.jser
          File sitFeatFreqCms = config.getExistingFile("sitFeatFreqCms");
          Log.info("loading sitFeatFreqCms=" + sitFeatFreqCms.getPath());
          sfCms = (StringCountMinSketch) FileUtil.deserialize(sitFeatFreqCms);
        }

        // Resolve query comm
        seed.sourceComm = ks.getCommCaching(seed.docid);
        if (seed.sourceComm == null) {
          Log.info("skipping b/c can't retrieve query comm: " + seed);
          continue;
        }

        ps = new PkbpSearching(ks, sfCms, seed, seedWeight, rand);
      }
      //ps.verbose = true;
      //ps.verboseLinking = true;
      PkbpResult searchFor = ps.queue.pop();
      ps.outerLoop(searchFor);

      // Serialize the results for later
      if (outputDir != null) {
        // PkbpSearching
        try (TB tb = timer().new TB("serialize/PkbpSearching")) {
          File outSearch = new File(outputDir, seed.id + ".search.jser.gz");
          Log.info("saving PKB to " + outSearch.getPath());
          ps.dropCommsFromInitialResultsCache();
          FileUtil.serialize(ps, outSearch);
        }

        // KbpQuery
        try (TB tb = timer().new TB("serialize/KbpQuery")) {
          File outQuery = new File(outputDir, seed.id + ".query.jser.gz");
          Log.info("saving seed/query to " + outQuery.getPath());
          FileUtil.serialize(seed, outQuery);
        }
        
        // KbpSearching
        // NOTE: Only one of these, rather than one per query.
        // It grows upon every query (stores more comms, feature freqs, etc).
        try (TB tb = timer().new TB("serialize/KbpSearching")) {
          File outKs = oneKsPerQuery
              ? new File(outputDir, "KbpSearching." + seed.id + ".jser.gz")
              : new File(outputDir, "KbpSearching.jser.gz");
          Log.info("saving KbpSearching (comm/featFreq/etc cache) to " + outKs.getPath());
          FileUtil.serialize(ks, outKs);
        }
      }

      if (clearCachesEveryQuery)
        ks.clearCaches();
    }
  }

  // Input: Seed
  private KbpQuery seed;
  private PkbpEntity seedEntity;
  private StringTermVec seedTermVec;

  // Searching
  /**
   * This holds all the cached accumulo data (possibly across queries).
   * It should be de/serialized and managed separately from any serialization of this class.
   */
  private KbpSearching search;
  private TacQueryEntityMentionResolver findEntityMention;
  private Random rand;
  /** Stores the (approximate) frequency of situation features, used for string match weighting in situation coref */
  private StringCountMinSketch sitFeatCms;

  // Controls search
//  public DoublePair rewardForTemporalLocalityMuSigma = new DoublePair(2, 30); // = (reward, stddev), score += reward * Math.exp(-(diffInDays/stddev)^2)
//  public DoublePair xdocCorefSigmoid = new DoublePair(3, 1);
  public double rewardForAttrFeats = 3;   // score += rewardForAttrFeats * sqrt(numAttrFeats)
  public DoublePair relatedEntitySigmoid = new DoublePair(6, 2);    // first: higher value lowers prob of keeping related entities. second: temperature of sigmoid (e.g. infinity means everything is 50/50 odds of keep)

  // Caching/memoization
  /** key is a KbpResult.id, values should NOT have communications set */
  private HashMap<String, List<SitSearchResult>> initialResultsCache;
  private File initialResultsDiskCacheDir = new File("/tmp/PkbpSearching-initialResults");

  /** Instances of (comm,tokenization) which have been processed once, after which should be skipped */
  Set<Pair<String, String>> seenCommToks;

  // IO
//  /** @deprecated use the simplified scenario of linking to situations */
//  LinkedHashMap<List<String>, PkbpResult> output; // keys are sorted lists of PkbpEntity ids
  Deque<PkbpResult> queue;    // TODO change element type, will want to have actions on here like "merge two situations"
  // Currently every element here is implicitly "search for situations involving these arguments and link ents/sits hanging off the results"
  
  // PKB
  List<PkbpEntity> entities;
  List<PkbpSituation> situations;
  
  // Inverted indices for objects
//  /** Keys are features which would get you most of the way (scoring-wise) towards a LINK */
//  Map<String, LL<PkbpEntity>> discF2Ent;
//  Map<String, LL<PkbpSituation>> discF2Sit;

  // Membership graph.
  // Inverse links like s2e are encoded by the
  // objects themselves (in this case s)
  Map<PkbpEntity, LL<PkbpSituation>> memb_e2s;
  
  

  static void triageFeatDebug(TriageSearch ts) {
    Log.info("checking the validity of entity triage feature frequencies and scores:");
    List<String> check = new ArrayList<>();
    check.add("pb:abdullah_abdullah");
    check.add("hi:ghani");
    check.add("pb:runner_karzai");
    check.add("h:front-runner");

    check.add("Bush");
    check.add("h:Bush");
    check.add("hi:bush");

    ts.getTriageFeatureFrequencies().sortByFreqUpperBoundAsc(check);

    for (String s : check) {
      IntPair tc = ts.getTriageFeatureFrequencies().getFrequency(s);
      double p = ts.getFeatureScore(s);
      System.out.printf("%-24s %.4f %s\n", s, p, tc);
    }
  }


  static class New {
    
    static void main(ExperimentProperties config) throws Exception {
      Log.info("starting...");
      Feat.SHOW_REASON_IN_TOSTRING = false;
      // Fetch service for retrieving communications
      String fetchHost = "localhost";
      int fetchPort = 9999;
      File fetchCacheDir = config.getOrMakeDir("fetchCacheDir", new File("data/sit-search/fetch-comms-cache/"));
      try (DiskBackedFetchWrapper commRet = KbpSearching.buildFetchWrapper(fetchCacheDir, fetchHost, fetchPort)) {
        // SearchService for basic accumulo-backed search
        String searchHost = "localhost";
        int searchPort = 8989;
        try (TTransport trans = new TFramedTransport(new TSocket(searchHost, searchPort))) {
          trans.open();
          TProtocol prot = new TCompactProtocol(trans);
          SearchService.Client kbpEntSearch = new SearchService.Client(prot);
          runSearches(config, kbpEntSearch, commRet);
        }
      }
      Log.info("done");
    }

    static void runSearches(ExperimentProperties config,
        SearchService.Client kbpEntSearch,
        DiskBackedFetchWrapper commRet) throws Exception {

      // e.g. /export/projects/twolfe/sit-search/situation-feature-counts/count-min-sketch-v2/cag-cms.jser
      File sitFeatFreqCms = config.getExistingFile("sitFeatFreqCms");
      Log.info("loading sitFeatFreqCms=" + sitFeatFreqCms.getPath());
      StringCountMinSketch sitFeatFreq = (StringCountMinSketch) FileUtil.deserialize(sitFeatFreqCms);

      ComputeIdf df = new ComputeIdf(config.getExistingFile("wordDocFreq"));

      // Triage feature frequencies and scores
      File fceFile = config.getExistingFile("triageFeatureFrequencies");
      Log.info("loading triage feature frequencies (FeatureCardinalityEstimator.New) from=" + fceFile.getPath());
      FeatureCardinalityEstimator.New triageFeatureFreq =
          (FeatureCardinalityEstimator.New) FileUtil.deserialize(fceFile);
      TriageSearch ts = new TriageSearch(triageFeatureFreq);
      triageFeatDebug(ts);

      
      List<KbpQuery> queries = new ArrayList<>();
//      queries.add(DarmstadtExample.getQuery());
      queries.addAll(TacKbp.getKbp2013SfQueries());
//      queries.addAll(TacKbp.getKbp2014SfQueries());
      Collections.shuffle(queries, new Random(9001 + 1));
      
      System.out.println();
      for (KbpQuery q : queries)
        System.out.println("[order of queries] " + q.id);
      System.out.println();

      for (KbpQuery q : queries) {

        // Resolve the query's Communication
        if (q.sourceComm == null) {
          Log.info("retrieving query comm...");
          q.sourceComm = commRet.fetch(q.docid);
          if (q.sourceComm == null) {
            Log.info("skipping b/c no comm: " + q);
            continue;
          }
        }

        runOneSearch(q, config, kbpEntSearch, commRet, sitFeatFreq, df, ts);
      }
    }

    static void runOneSearch(KbpQuery q,
        ExperimentProperties config,
        SearchService.Iface kbpEntSearch,
        DiskBackedFetchWrapper commRet,
        StringCountMinSketch sitFeatFreq,
        ComputeIdf df,
        TriageSearch ts) throws Exception {

      boolean debug = true;
      
      File evalOutputDir = config.getFile("evalOutputDir", null);
      if (evalOutputDir != null) {
        Log.info("using evalOutputDir=" + evalOutputDir.getPath());
        evalOutputDir.mkdirs();
      }
      
      File debugOutputDir = config.getFile("debugOutputDir", null);
      if (debugOutputDir != null) {
        Log.info("using debugOutputDir=" + debugOutputDir.getPath());
        debugOutputDir.mkdirs();
      }
      
      File searchServiceCacheDir = config.getExistingDir("searchServiceCacheDir", null);
      if (searchServiceCacheDir != null) {
        searchServiceCacheDir = new File(searchServiceCacheDir, q.id);
        searchServiceCacheDir.mkdirs();
        Log.info("caching SearchService in cacheDir=" + searchServiceCacheDir.getPath());
        boolean saveResults = true;
        boolean compression = false;
        @SuppressWarnings("resource") // There is nothing to close
        DiskBackedSearchWrapper w = new DiskBackedSearchWrapper(kbpEntSearch, null, searchServiceCacheDir, saveResults, compression);
        w.debug = true;
        kbpEntSearch = w;
      }

      Log.info("working on " + q);
      New search = new New(kbpEntSearch, sitFeatFreq, config.getRandom());

      TacQueryEntityMentionResolver emFinder = new TacQueryEntityMentionResolver("tacQuery");

      // Add the query as a seed
      double seedWeight = config.getDouble("seedWeight", 10);
      PkbpEntity.Mention seed = PkbpEntity.Mention.convert(q, emFinder, df, ts);
      boolean createEnt = true;
      search.addSeed(seed, seedWeight, createEnt);
      if (debug) {
        Log.info("seed entity:");
        List<PkbpNode> ents = search.findIntersection(FeatureNames.ENTITY);
        showEntitiesN(first(10, ents), 10);
      }

      // Search for mentions of this seed, create EMs out of them
      int maxResults = 80;
//      int maxResults = 320;
      PkbpNode seedEntNode = search.findIntersection(FeatureNames.SEED, FeatureNames.ENTITY).get(0);
      PkbpEntity seedEnt = (PkbpEntity) seedEntNode.obj;
      List<PkbpNode> seedEntMentions = search.searchForMentionsOf(seedEnt, maxResults, commRet::fetch, df, ts, null);
      if (debug) {
        Log.info("[main] mentions after initial query:");
        showMentionsN(seedEntMentions);
      }

      // Link the EMs to create Es
      if (debug)
        Log.info("[main] initial mentions (hopefully to one entity):");
      double scoreNoOpInitial = 2;
      search.batchEntityLinking(seedEntMentions, df, ts, scoreNoOpInitial);
      if (debug) {
        List<PkbpNode> ents = search.findIntersection(FeatureNames.ENTITY);
        showEntitiesN(ents, 10);
      }

      // Extract SMs and their arguments
      Log.info("[main] extracting situations related to initial mentions:");
      List<PA> relatedSits = new ArrayList<>(); 
      for (PkbpNode emMention : seedEntMentions)
        search.extractSituationsAndArgument(emMention, relatedSits, df, ts);
      if (debug) {
        Log.info("situations mentions:");
        showMentionsM(PA.sitMentions(relatedSits));
        Log.info("arguments of those situations:");
        showMentionsM(PA.otherArgs(relatedSits));
      }

      // Link the arguments
      Log.info("[main] linking arguments of related situations...");
      List<PkbpEntity.Mention> args = PA.otherArgs(relatedSits);
      assert disjoint(node2mentions(seedEntMentions), args);
      List<PkbpNode> argNodes = search.addNodes(args, FeatureNames.ENTITY_MENTION);
      double scoreNoOp = 1;
      List<PkbpNode> newEntNodes = search.batchEntityLinking(argNodes, df, ts, scoreNoOp, true);
      addToRelevanceReasons(newEntNodes, "foundWithSeed", 2);
      if (debug) {
        Log.info("new entities:");
        showEntitiesN(newEntNodes, 10);
      }


//        // Link SMs to create Ss
//        Log.info("[main] linking situations...");
//        List<PkbpSituation.Mention> sits = PA.sitMentions(relatedSits);
//        search.addNodes(sits, FeatureNames.SITUATION_MENTION);
//        search.batchSituationLinking(relatedSits);
//        if (debug) {
//          search.showSituations();
//        }
      
      // Show relevant entities
      List<Pair<PkbpEntity, Double>> rel = search.relevantEntities();
      showRelevantEntities(rel, df);


      // Search for SEED+X mentions
      List<MultiEntityMention> mems = new ArrayList<>();
      int nMemIter = maxResults / 4;
      Counts<String> reasonsForMemFilter = new Counts<>();
      Set<String> memPredsSeen = new HashSet<>();
      List<String> searchedForEntityIds = new ArrayList<>();
      searchedForEntityIds.add(seedEnt.id);
      for (int i = 0; i < nMemIter; i++) {
        PkbpEntity searchFor = search.chooseEntityToSearchFor(searchedForEntityIds);
        if (searchFor == null) {
          Log.info("nothing to search for");
          break;
        }
//        searchFor.getRelevanceReasons().add(new Feat("searched", -10));
        searchedForEntityIds.add(searchFor.id);
        List<PkbpEntity> entPair = Arrays.asList(seedEnt, searchFor);
        Log.info("[main] at i=" + i + " of nMemIter=" + nMemIter + " searching for:\t" + Describe.memoryUsage());
        showEntitiesE(entPair, 5);
        List<MultiEntityMention> res = search.searchForSituationsInvolving(
            entPair, maxResults, commRet::fetch, df, ts, reasonsForMemFilter, null);

        for (MultiEntityMention mem : res) {
          if (memPredsSeen.add(mem.pred.getCommTokHeadWordAndLoc())) {
            mems.add(mem);
          } else {
            Log.info("found MEM more than once: ");
            showMultiEntityMention(mem);
          }
        }

        showMultiEntityMention(res, "(" + seedEnt.id + ", " + searchFor.id + ")");
        System.out.println();
        System.out.println("sum(MEM filtering causes): " + reasonsForMemFilter);
        System.out.println();
        

        // Add MEM links to PKB
        // Update our notion of relevant entities in light of these links
        addMemAsSupportForEntityRelevance(res, seedEnt, df);
        // Show results
        showRelevantEntities(search.relevantEntities(), df);
        showMEMsForRelevantEntities(search.relevantEntities(), mems, seedEnt.getMention(0), df);
      }
      
//      // Link MEMs SitMentions and create new Situations
//      Log.info("[main] linking MultiEntityMention situation mentions...");
//      List<PA> entPairSitMentions = MultiEntityMention.getPAs(mems, seedEnt);
//      showMentionsM(PA.sitMentions(entPairSitMentions));
//      // TODO This isn't even needed, I'm going to use parma to dedup WITHIN a SEED+X/corePartipants
//      search.batchSituationLinking(entPairSitMentions);
//      if (debug) {
//        search.showSituations();
//      }

      // Output all sits and sitMentions for evaluation
      if (evalOutputDir != null) {
        try (TB tb = timer().new TB("evalRedundancy")) {
          File f = new File(evalOutputDir, q.id);
          if (!f.isDirectory())
            f.mkdirs();
          Log.info("[main] writing out evaluation/HIT data to " + f.getPath());
          EvalRedundancy ev = new EvalRedundancy(q, mems, f, search.rand);
          int numPos = 30;
          int numNeg = 30;
          ev.generateExamples(numPos, numNeg);
          try {
            ev.writeOutExamples();
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      
      }

        // Stuff like (predicate, seedEnt, otherEnt)
      if (debugOutputDir != null) {
        File a = new File(debugOutputDir, q.id);
        if (!a.isDirectory()) a.mkdirs();
        writeMEMStats(mems, new File(a, "triples.txt"), df);
        writeSourceSentence(mems, new File(a, "source-sentences.txt.gz"));
      }

      System.out.println();
      System.out.println("TIMER:\t" + Describe.memoryUsage());
      System.out.println(timer());
      System.out.println();
      
      search.clear();
    }
    
    static void writeMEMStats(List<MultiEntityMention> mems, File dest, ComputeIdf df) throws IOException {
      try (BufferedWriter w = FileUtil.getWriter(dest)) {
        for (MultiEntityMention mem : mems) {
          StringBuilder line = new StringBuilder();
          line.append(mem.pred.getHeadWord());
          for (int i = 0; i < mem.query.length; i++) {
            line.append('\t');
            PkbpEntity.Mention canonical = mem.query[i].guessCanonicalMention(df);
            String cs = canonical.getSpanString();
            assert cs.indexOf('\t') < 0;
            line.append(cs);
          }
          w.write(line.toString());
          w.newLine();
        }
      }
    }
    
    static void writeSourceSentence(List<MultiEntityMention> mems, File dest) throws IOException {
      Set<String> seen = new HashSet<>();
      try (BufferedWriter w = FileUtil.getWriter(dest)) {
        for (MultiEntityMention mem : mems) {
          String t = mem.pred.getCommTokIdShort();
          if (seen.add(t)) {
            w.write(t);
            for (Token tok : mem.pred.getTokenization().getTokenList().getTokenList()) {
              w.write(' ');
              w.write(tok.getText());
            }
            w.newLine();
          }
        }
      }
    }
    
    public static void addToRelevanceReasons(List<PkbpNode> entities, String why, double amount) {
      for (PkbpNode n : entities)
        ((PkbpEntity) n.obj).getRelevanceReasons().add(new Feat(why, amount));
    }
    
    public List<Pair<PkbpEntity, Double>> relevantEntities() {
      return relevantEntities(Collections.emptyList());
    }
    public List<Pair<PkbpEntity, Double>> relevantEntities(Collection<String> ignoreEntityIds) {
      List<Pair<PkbpEntity, Double>> entsOk = new ArrayList<>();
      List<PkbpNode> ents = findIntersection(FeatureNames.ENTITY);
      for (PkbpNode n : ents) {
        PkbpEntity e = (PkbpEntity) n.obj;
        if (ignoreEntityIds.contains(e.id))
          continue;
        double score = e.getRelevanceWeight();
        entsOk.add(new Pair<>(e, score));
      }
      Collections.sort(entsOk, new Comparator<Pair<PkbpEntity, Double>>() {
        @Override
        public int compare(Pair<PkbpEntity, Double> o1, Pair<PkbpEntity, Double> o2) {
          double s1 = o1.get2();
          double s2 = o2.get2();
          if (s1 > s2)
            return -1;
          if (s1 < s2)
            return +1;
          return 0;
        }
      });
      return entsOk;
    }
    
    public PkbpEntity chooseEntityToSearchFor(Collection<String> ignoreEntityIds) {
      ChooseOne<PkbpEntity> c = new ChooseOne<>(rand);
      List<Pair<PkbpEntity, Double>> entsOk = relevantEntities(ignoreEntityIds);
      double minScore = 0;
      for (Pair<PkbpEntity, Double> ee : entsOk)
        minScore = Math.min(minScore, ee.get2());
      for (Pair<PkbpEntity, Double> ee : entsOk)
        c.offer(ee.get1(), ee.get2() - minScore);
      if (c.numOffers() == 0)
        return null;
      return c.choose();
    }
    
    public static List<PkbpMention> node2mentions(Iterable<PkbpNode> nodes) {
      List<PkbpMention> m = new ArrayList<>();
      for (PkbpNode n : nodes)
        m.add((PkbpMention) n.obj);
      return m;
    }
    
    public static boolean disjoint(Iterable<? extends PkbpMention> a, Iterable<? extends PkbpMention> b) {
      Set<String> seen = new HashSet<>();
      for (PkbpMention m : a)
        seen.add(m.getCommTokIdShort() + "/" + m.head);
      for (PkbpMention m : b)
        if (!seen.add(m.getCommTokIdShort() + "/" + m.head))
          return false;
      return true;
    }
    
    // New way of doing things...
    // The point is that I want a graph data structure where it makes sense to build it
    // in any way you choose (e.g. maybe I only want to do xdoc entity coref, shouldn't
    // need to wrap entities in crap to make this happen).
    static class PkbpNode {
      public final int id;
      public final Object obj;   // PkbpEntity, PkbSituation, PkbEntity.Mention, PkbpSituation.Mention
      private List<Feat> feats;
      //    Map<String, Integer> relatedObjs;
      //    List<Feat> properties;
      public PkbpNode(int id, Object obj) {
        this.id = id;
        this.obj = obj;
      }
      public void addFeat(Feat f) {
        if (feats == null)
          feats = new ArrayList<>();
        feats.add(f);
      }
    }
    
//    private KbpSearching search;
    private SearchService.Iface kbpEntitySearchService;
    private StringCountMinSketch sitFeatCounts;
    private Random rand;

    private List<PkbpNode> nodes;
    // Features are how we do triage on nodes for retrieval
    // I knew this when it came to searching CAG for entities, and now I should apply it to the PKB as well!
    private Map<String, IntArrayList> feat2nodes;    // values are indices into nodes
    // Features: hold off on defining these, they only need to support the operations you actually implement!
    // "ent & headwordsInclude("John")"
    // "sit & participantsInlucde(ent(42))"
    //  Map<String, LL<IntPair>> feat2edges;    // don't worry about this until you have to
    
    
    public void clear() {
      Log.info("clearing " + nodes.size() + " nodes and " + feat2nodes.size() + " feat entries");
      nodes.clear();
      feat2nodes.clear();
    }

    // NOTE: This is distinct from the feature names of IndexCommunications.getEntityMentionFeatures
    // as well as the prefixes used by KbpEntitySearchService
    static class FeatureNames {
      public static final String SEED = "q";
      public static final String ENTITY_MENTION = "em";
      public static final String ENTITY = "e";
      public static final String SITUATION_MENTION = "sm";
      public static final String SITUATION = "s";

      /**
       * feat2nodes can be used to encode linking edges using this function to
       * generate keys. So the nodes point to by "e/42" are the PkbpEntities
       * which are likely links for PkbpEntity.Mention with id 42.
       */
      public static String entity(PkbpNode mention) {
        return "e/" + mention.id;
      }

      public static String argOf(PkbpNode sitMention) {
        return "argOf/" + sitMention.id;
      }
      
      public static List<String> occurredOn(PkbpMention m) {
        GigawordId gw = new GigawordId(m.getCommunicationId());
        if (!gw.isValid())
          return Collections.emptyList();
        List<String> fs = new ArrayList<>();
        for (int offset = -2; offset <= 2; offset++) {
          LocalDate d = gw.toDate().plusDays(offset);
          fs.add("tClose/" + d.getYear() + "-" + d.getMonthValue() + "-" + d.getDayOfMonth());
//          int day = gw.day + offset;
//          if (day < 1 || day > 31)
//            throw new RuntimeException("implement me");
//          fs.add("tClose/" + gw.year + "-" + gw.month + "-" + day);
        }
        return fs;
      }
      
      public static String sitLemma(PkbpSituation.Mention m) {
        return "sitLemma/" + m.getHeadLemma();
      }
      
      public static String entHeadLocation(PkbpEntity.Mention em) {
        return "entHead/" + em.getCommTokIdShort() + "/" + em.head;
      }
    }

    // I want to be able to play with lots of manipulations of the PKB graph.
    // But to do this I need to ensure I have all the data needed to play locally.
    // To do this I should run code which does one search off of a SF query entity mention and puts these mentions in a graph.
    // The graph is serialized and we can ensure certain properties like all of the comms have been retrieved

    public New(SearchService.Iface kbpEntitySearchService, StringCountMinSketch sitFeatCounts, Random rand) {
      this.kbpEntitySearchService = kbpEntitySearchService;
      this.sitFeatCounts = sitFeatCounts;
      this.rand = rand;
      this.nodes = new ArrayList<>();
      this.feat2nodes = new HashMap<>();
    }
    
    public void showSituations() {
      
      // link(em, e)    -- we have a good way to do this
      // arg(em, sm)    -- given, observable from syntax
      // link(sm, s)
      // arg(e,s) := arg(em,sm) & link(em,e) & link(sm,s)
      
      PkbpNode seedEnt = findIntersection(FeatureNames.ENTITY, FeatureNames.SEED).get(0);
      Log.info("seedEnt: " + seedEnt.obj);
      List<PkbpNode> sits = findIntersection(FeatureNames.SITUATION, FeatureNames.argOf(seedEnt));
      Log.info("numSits=" + sits.size());
      for (PkbpNode s : sits) {
        Log.info(s.obj);
      }
      System.out.println();
    }
    
    public static void addMemAsSupportForEntityRelevance(List<MultiEntityMention> mems, PkbpEntity seed, ComputeIdf df) {
      Log.info("[main] adding to relevance for " + mems.size() + " MEMs");
      Counts<String> entId2nLinks = new Counts<>();
      Map<String, PkbpEntity> entId2ent = new HashMap<>();
      Counts<String> c = new Counts<>();
      for (MultiEntityMention mem : mems) {
        addMemAsSupportForEntityRelevance(mem, seed, entId2nLinks, entId2ent, c);
      }
      System.out.println("upon completion:");
      System.out.println(c);
      System.out.println();
      for (Entry<String, Integer> eid : entId2nLinks.entrySet()) {
        PkbpEntity e = entId2ent.get(eid.getKey());
        double sqrtNumMentions = Math.sqrt(eid.getValue());
        e.getRelevanceReasons().add(new Feat("sqrtNumMentions", sqrtNumMentions));
        
        List<Feat> localSimPred = extractFromMEMsMatchingEntity(e.id, mems, m -> {
          
          // Factor 1: How sure we are that this is the same entity
          StringTermVec sL = seed.getMention(0).getContextLocal();
          StringTermVec mL = m.pred.getContextLocal();
          double tfidf = df.tfIdfCosineSim(sL, mL);
          
          // Factor 2: How sure are we of the entity link to this entity in this MEM
          EntLink link = m.findLinkTo(e);
          double pLink = link.estimateProb();

          // Factor 3: whether this predicate is any good
          String pred = m.pred.getHeadWord();
          double pidf = df.idf(pred);
          
          // Factor 4: proximity to the predicate
          boolean includeEndpoints = false;
          int[] spath = m.pred.shortestPath(link.source.head, includeEndpoints);
          int pathLen = spath == null ? 30 : spath.length;
          double ppath = 3 / (2d + pathLen);

          return new Feat(pred, (-1 + (1 + tfidf) * (1 + pidf + ppath)) * pLink);
        });
        e.getRelevanceReasons().add(new Feat("predIdfSimAvg", Feat.avg(localSimPred)));
        e.getRelevanceReasons().add(new Feat("predIdfSimMax", 0.5 * Feat.max(localSimPred)));
        
      }
    }
    private static void addMemAsSupportForEntityRelevance(MultiEntityMention mem, PkbpEntity seedMention, Counts<String> entId2nLinks, Map<String, PkbpEntity> entId2ent, Counts<String> c) {
      if (!mem.allQueryEntitiesMentioned()) {
        c.increment("notAllQueriesLinked");
        return;
      }
      if (!mem.allMentionsUniq()) {
        c.increment("notAllMentionsUniq");
        return;
      }
      
      int n = mem.alignedMentions.length;
      for (int i = 0; i < n; i++) {
        c.increment("boosted");
        PkbpEntity e = mem.query[i];
        e.addMention(mem.alignedMentions[i]);
        entId2nLinks.increment(e.id);
        if (!entId2ent.containsKey(e.id))
          entId2ent.put(e.id, e);
      }
    }
    
    public static void showRelevantEntities(List<Pair<PkbpEntity, Double>> ents, ComputeIdf df) {
      Log.info("[main] there are " + ents.size() + " relevant entities:");
      for (Pair<PkbpEntity, Double> x : ents) {
        String ss = String.format("%.2f", x.get2());
        PkbpEntity e = x.get1();
        PkbpEntity.Mention c = e.guessCanonicalMention(df);
        int w = 50;
        System.out.printf("%6s nMention=%d %-20s %s\n",
            ss, e.numMentions(), StringUtils.trimPretty(e.id, 20), c.getContextAroundHead(w, w, true));
      }
      System.out.println();
      Log.info("here are the reasons for the " + ents.size() + " relevant entities:");
      for (Pair<PkbpEntity, Double> x : ents) {
        PkbpEntity e = x.get1();
        List<Feat> r = e.getRelevanceReasons();
        System.out.printf("%-32s%s\n", StringUtils.trimPretty(e.id, 32), Feat.sortByMag(r, 15));
      }
      System.out.println();
    }
    
    /**
     * Filters to only look at valid MEMs which contain the given entity (id),
     * and runs the given extractor, accumulating all values into the returned list.
     * Similar to {@link New#addMemAsSupportForEntityRelevance(List, PkbpEntity, ComputeIdf)},
     * this will skip invalid MEMs where all args haven't been linked.
     */
    private static <T> List<T> extractFromMEMsMatchingEntity(String relEntityId, List<MultiEntityMention> mems, Function<MultiEntityMention, T> extractor) {
      List<T> f = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (MultiEntityMention mem : mems) {
        if (!mem.allQueryEntitiesMentioned())
          continue;
        if (!mem.allMentionsUniq())
          continue;
        boolean rel = false;
        for (int i = 0; !rel && i < mem.query.length; i++)
          rel |= relEntityId.equals(mem.query[i].id);
        if (rel && seen.add(mem.pred.getCommTokHeadWordAndLoc())) {
          T t = extractor.apply(mem);
          f.add(t);
        }
      }
      return f;
    }

    public static void showMEMsForRelevantEntities(List<Pair<PkbpEntity, Double>> ents, List<MultiEntityMention> res, PkbpEntity.Mention seedMention, ComputeIdf df) {
      Log.info("here are the PREDICATE words and their idf for MEMs linked to the " + ents.size() + " relevant entities:");
      for (Pair<PkbpEntity, Double> x : ents) {
        PkbpEntity e = x.get1();
        List<Feat> r = extractFromMEMsMatchingEntity(e.id, res, m -> {
          String pred = m.pred.getHeadWord();
          return new Feat(pred, df.idf(pred));
        });
        System.out.printf("%-32s%s\n", StringUtils.trimPretty(e.id, 32), r, 15);
      }
      System.out.println();
      Log.info("here are the LOCAL tfidf similarities for MEMs linked to the " + ents.size() + " relevant entities:");
      for (Pair<PkbpEntity, Double> x : ents) {
        PkbpEntity e = x.get1();
        List<Feat> r = extractFromMEMsMatchingEntity(e.id, res, m -> {
          StringTermVec a = e.getContextLocal();
          StringTermVec b = m.pred.getContextLocal();
          return new Feat("simLocal", df.tfIdfCosineSim(a, b));
        });
        System.out.printf("%-32s%s\n", StringUtils.trimPretty(e.id, 32), r, 15);
      }
      System.out.println();
      Log.info("here are the DOC tfidf similarities for MEMs linked to the " + ents.size() + " relevant entities:");
      for (Pair<PkbpEntity, Double> x : ents) {
        PkbpEntity e = x.get1();
        List<Feat> r = extractFromMEMsMatchingEntity(e.id, res, m -> {
          StringTermVec a = e.getContextDoc();
          StringTermVec b = m.pred.getContextDoc();
          return new Feat("simDoc", df.tfIdfCosineSim(a, b));
        });
        System.out.printf("%-32s%s\n", StringUtils.trimPretty(e.id, 32), r, 15);
      }
      System.out.println();
      Log.info("here are the link probs for the " + ents.size() + " relevant entities:");
      for (Pair<PkbpEntity, Double> x : ents) {
        PkbpEntity e = x.get1();
        List<Feat> r = extractFromMEMsMatchingEntity(e.id, res, m -> {
          EntLink l = m.findLinkTo(e);
          double p = l.estimateProb();
          return new Feat(l.source.getHeadWord(), p);
        });
        System.out.printf("%-32s%s\n", StringUtils.trimPretty(e.id, 32), r, 15);
      }
      System.out.println();
    }
    
    public static void showEntitiesE(List<PkbpEntity> ents, int maxMentionsPerEnt) {
      for (PkbpEntity e : ents) {
        System.out.printf("entity(?): %s\n", e);
        System.out.println("\ttriageFeats: " + e.getTriageFeatures());
        System.out.println("\tcommonTriageFeats: " + e.getCommonTriageFeatures());
        System.out.println("\tattrFeats: " + Feat.sortAndPrune(e.getAttrFeatures(), 0d));
        System.out.println("\tcommonAttrFeats: " + e.getCommonAttrFeats());
        int w = 60;
        int n = e.numMentions();
        for (int i = 0; i < Math.min(n, maxMentionsPerEnt); i++) {
          PkbpMention m = e.getMention(i);
          System.out.printf("\t%-32s %s\n", StringUtils.trimPretty(m.getCommTokIdShort(), 32), m.getContextAroundHead(w, w, true));
        }
        if (n > maxMentionsPerEnt)
          System.out.println("\t... and " + (n-maxMentionsPerEnt) + " more mentions");

        System.out.println();
      }
    }
    public static void showEntitiesN(List<PkbpNode> ents, int maxMentionsPerEnt) {
      for (PkbpNode node : ents) {
        PkbpEntity e = (PkbpEntity) node.obj;
        System.out.printf("entity(%d): %s\n", node.id, e);
        System.out.println("\ttriageFeats: " + e.getTriageFeatures());
        System.out.println("\tcommonTriageFeats: " + e.getCommonTriageFeatures());
        System.out.println("\tattrFeats: " + Feat.sortAndPrune(e.getAttrFeatures(), 0d));
        System.out.println("\tcommonAttrFeats: " + e.getCommonAttrFeats());
        int w = 60;
        int n = e.numMentions();
        for (int i = 0; i < Math.min(n, maxMentionsPerEnt); i++) {
          PkbpMention m = e.getMention(i);
          System.out.printf("\t%-32s %s\n", StringUtils.trimPretty(m.getCommTokIdShort(), 32), m.getContextAroundHead(w, w, true));
        }
        if (n > maxMentionsPerEnt)
          System.out.println("\t... and " + (n-maxMentionsPerEnt) + " more mentions");

        System.out.println();
      }
    }
    
    public static void showMentionsM(List<? extends PkbpMention> mentions) {
      int w = 60;
      for (PkbpMention m : mentions) {
        System.out.printf("%-32s %s\n", StringUtils.trimPretty(m.getCommTokIdShort(), 32), m.getContextAroundHead(w, w, true));
      }
      System.out.println();
    }

    public static void showMentionsN(List<PkbpNode> mentions) {
      int w = 60;
      for (PkbpNode n : mentions) {
        PkbpMention m = (PkbpMention) n.obj;
        System.out.printf("id=%d\t%-32s %s\n", n.id, StringUtils.trimPretty(m.getCommTokIdShort(), 32), m.getContextAroundHead(w, w, true));
      }
      System.out.println();
    }
    
    public static void showMultiEntityMention(List<MultiEntityMention> mems, String desc) {

      // Track some stats
      Set<String> preds = new HashSet<>();
      Set<String> args = new HashSet<>();
      Set<String> comms = new HashSet<>();
      Set<String> toks = new HashSet<>();

      for (MultiEntityMention mem : mems) {
        showMultiEntityMention(mem);
        System.out.println();
        
        preds.add(mem.pred.getCommTokHeadWordAndLoc());
        for (int i = 0; i < mem.alignedMentions.length; i++) {
          if (mem.alignedMentions[i] != null)
            args.add(mem.getMention(i).getCommTokHeadWordAndLoc());
        }
        comms.add(mem.pred.getCommunicationId());
        toks.add(mem.pred.getCommTokIdShort());
      }
      Log.info(desc + " preds=" + preds.size() + " args=" + args.size() + " comms=" + comms.size() + " toks=" + toks.size());
    }
    public static void showMultiEntityMention(MultiEntityMention mem) {
//      System.out.println("MultiEntityMention in " + mem.pred.getCommTokIdShort());
//      System.out.printf("pred:   %s\n", mem.pred.getContextAroundHead(60, 60, true));
//      for (int i = 0; i < mem.alignedMentions.length; i++) {
//        if (mem.alignedMentions[i] == null)
//          System.out.printf("arg(%d): %s\n", i, "null");
//        else
//          System.out.printf("arg(%d): %s\n", i, mem.alignedMentions[i].getContextAroundHead(60, 60, true));
//      }
      for (String s : showMultiEntityMention2(mem))
        System.out.println(s);
    }
    public static List<String> showMultiEntityMention2(MultiEntityMention mem) {
      List<String> l = new ArrayList<>();
      int w = 60;
      l.add("MultiEntityMention in " + mem.pred.getCommTokIdShort());
      l.add(String.format("pred:  %-18s       %s", "", mem.pred.getContextAroundHead(w, w, true)));
      for (int i = 0; i < mem.alignedMentions.length; i++) {
        String m = mem.alignedMentions[i] == null ? "null" : mem.getMention(i).getContextAroundHead(w, w, true);
        String e = StringUtils.trim(mem.query[i].id, 18);
        l.add(String.format("arg(%d): e:%-18s  m:%s", i, e, m));
      }
      return l;
    }

    public List<Pair<PkbpNode, Double>> findUnion(String... features) {
      return findUnion(Arrays.asList(features));
    }
    public List<Pair<PkbpNode, Double>> findUnion(List<String> features) {
      Map<Integer, Pair<PkbpNode, Double>> m = new HashMap<>();
      for (String f : features) {
        IntArrayList ids = feat2nodes.get(f);
        if (ids == null)
          continue;
        for (int i = 0; i < ids.size(); i++) {
          int k = ids.get(i);
          Pair<PkbpNode, Double> p = m.get(k);
          if (p == null) {
            m.put(k, new Pair<>(nodes.get(k), 0d));
          } else {
            m.put(k, new Pair<>(p.get1(), p.get2()+1));
          }
        }
      }
      return new ArrayList<>(m.values());
    }
    
    public List<PkbpNode> findIntersection(String... features) {
      if (features.length == 0)
        throw new IllegalArgumentException();
      IntArrayList l = feat2nodes.get(features[0]);
      for (int i = 1; i < features.length && l != null; i++) {
        IntArrayList r = feat2nodes.get(features[i]);
        l = intersect(l, r);
      }
      if (l == null)
        return Collections.emptyList();
      List<PkbpNode> r = new ArrayList<>();
      for (int i = 0; i < l.size(); i++)
        r.add(nodes.get(l.get(i)));
      return r;
    }
    
    // null means empty list
    private static IntArrayList intersect(IntArrayList a, IntArrayList b) {
      // TODO fast impl for when a,b are sorted
      if (a == null || b == null)
        return null;
      if (a.size() > b.size())
        return intersect(b, a);
      Set<Integer> as = new HashSet<>();
      int na = a.size();
      for (int i = 0; i < na; i++)
        as.add(a.get(i));
      IntArrayList c = new IntArrayList();
      int nb = b.size();
      for (int i = 0; i < nb; i++) {
        int v = b.get(i);
        if (as.contains(v))
          c.add(v);
      }
      return c;
    }

    public List<PkbpNode> addNodes(List<?> sms, String... features) {
      List<PkbpNode> added = new ArrayList<>();
      for (Object sm : sms) {
        PkbpNode n = new PkbpNode(nodes.size(), sm);
        for (String feat : features)
          n.addFeat(new Feat(feat, 1));
        addNode(n);
        added.add(n);
      }
      return added;
    }

    public void addNode(PkbpNode n) {
      assert n.id == nodes.size();
      nodes.add(n);
      for (Feat f : n.feats) {
        IntArrayList l = feat2nodes.get(f.name);
        if (l == null) {
          l = new IntArrayList();
          feat2nodes.put(f.name, l);
        }
        l.add(n.id);
      }
    }
    
    public void addSeed(PkbpEntity.Mention seed, double weight, boolean createEntity) {
      int id = nodes.size();
      PkbpNode node = new PkbpNode(id, seed);
      node.addFeat(new Feat(FeatureNames.SEED, 1));
      node.addFeat(new Feat(FeatureNames.ENTITY_MENTION, 1));
      addNode(node);

      if (createEntity) {
        PkbpEntity e = new PkbpEntity("seedEnt/" + node.id, seed, new ArrayList<>());
        e.getRelevanceReasons().add(new Feat("seed", weight));
        PkbpNode en = new PkbpNode(nodes.size(), e);
        en.addFeat(new Feat(FeatureNames.SEED, 1));
        en.addFeat(new Feat(FeatureNames.ENTITY, 1));
        addNode(en);
      }
    }
    
    public static class MultiEntityMention {
      // Input
      PkbpEntity[] query;
      // Output (after search)
//      PkbpEntity.Mention[] alignedMentions;   // co-indexed with query
      EntLink[] alignedMentions;   // co-indexed with query
      PkbpSituation.Mention pred;   // pred which unifies all alignedMentions
      
      public MultiEntityMention(List<PkbpEntity> query, PkbpSituation.Mention pred) {
        int n = query.size();
        this.query = new PkbpEntity[n];
        for (int i = 0; i < n; i++)
          this.query[i] = query.get(i);
//        this.alignedMentions = new PkbpEntity.Mention[n];
        this.alignedMentions = new EntLink[n];
        this.pred = pred;
      }
      
      public EntLink findLinkTo(PkbpEntity e) {
        EntLink link = null;
        for (int i = 0; i < query.length; i++) {
          assert (query[i] == e) == (query[i].id.equals(e.id));
          if (query[i] == e) {
            assert link == null;
            link = alignedMentions[i];
          }
        }
        return link;
      }
      
      public PkbpEntity.Mention getMention(int i) {
        if (alignedMentions[i] == null)
          return null;
        return alignedMentions[i].source;
      }
      
      public boolean allQueryEntitiesMentioned() {
        for (int i = 0; i < alignedMentions.length; i++)
          if (alignedMentions[i] == null)
            return false;
        return true;
      }
      
      public boolean allMentionsUniq() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < alignedMentions.length; i++) {
          EntLink l = alignedMentions[i];
          if (l != null && !seen.add(l.source.getCommTokHeadWordAndLoc()))
            return false;
        }
        return true;
      }

      public static List<PkbpSituation.Mention> getPredicates(Iterable<MultiEntityMention> mems) {
        List<PkbpSituation.Mention> sm = new ArrayList<>();
        for (MultiEntityMention mem : mems)
          sm.add(mem.pred);
        return sm;
      }
      
      public static List<PA> getPAs(List<MultiEntityMention> mems, PkbpEntity commonArg0) {
        Log.info("converting " + mems.size() + " MultiEntityMentions to PA, looking for arg0=" + commonArg0);
        List<PA> pa = new ArrayList<>();
        for (MultiEntityMention mem : mems) {
          PA p = PA.convert(mem, commonArg0);
          if (p != null)
            pa.add(p);
        }
        Log.info("converted " + pa.size() + " of " + mems.size() + " MultiEntityMentions");
        return pa;
      }
    }
    
    /**
     * Given a few entities, formulate a query which returns passages in which
     * at least two (ideally all) of them are mentioned. For these passages,
     * link them to the given entities and find the predicate that unifies as
     * many as possible.
     */
    public List<MultiEntityMention> searchForSituationsInvolving(
        List<PkbpEntity> entities,
        int maxResults,
        Function<String, Communication> commRet,
        ComputeIdf df,
        TriageSearch ts,
        Counts<String> reasonsForFiltering,
        Set<String> ignoreCommToks) {
      
      Set<String> eids = new HashSet<>();
      for (PkbpEntity e : entities)
        if (!eids.add(e.id))
          throw new IllegalArgumentException("duplicate entity id=" + e.id);
      
      boolean debug = true;
      
      if (ignoreCommToks != null)
        throw new RuntimeException("implement me");
      
      SearchQuery query = new SearchQuery();
      query.addToLabels("multi");
      query.setK(maxResults);
      query.setType(SearchType.SENTENCES);
      query.setTerms(new ArrayList<>());

      for (int i = 0; i < entities.size(); i++) {
        PkbpEntity e = entities.get(i);
//        String qid = String.valueOf(i);
        String qid = e.id;

        // Triage features
        for (Feat tf : e.getTriageFeatures())
          query.addToTerms(qid + ":" + tf.name);

        // Attr features
        for (Feat af : e.getAttrFeatures())
          query.addToTerms(String.format("%s:a:%s:%.3f", qid, af.name, af.weight));

        // Context
        for (String ct : df.importantTerms(e.getContextDoc(), 100))
          query.addToTerms(qid + ":c:" + ct);
      }
      
      if (debug)
        Log.info("issuing query: " + query);
      SearchResult res = null;
      try (TB tb = timer().new TB("kbpEntitySearchService")) {
        res = kbpEntitySearchService.search(query);
      } catch (ServicesException e) {
        Exception ee = (Exception) SerializationUtils.bytes2t(e.getSerEx());
        Log.info("cause:");
        ee.printStackTrace();
        Log.info("returning empty list");
        return Collections.emptyList();
      } catch (TException e1) {
        e1.printStackTrace();
        Log.info("returning empty list");
        return Collections.emptyList();
      }

      if (debug) {
        Log.info("SearchService retured " + res.getSearchResultItemsSize() + " results");
        Log.info("about to fetch comms and extract MultiEntityMentions from the results");
      }

      List<MultiEntityMention> mems = new ArrayList<>();
      TimeMarker tm = new TimeMarker();
      int nres = 0;
      Set<String> comms = new HashSet<>();
      for (SearchResultItem r : res.getSearchResultItems()) {
        Communication comm = commRet.apply(r.getCommunicationId());
        Tokenization toks = IndexCommunications.findTok(r.getSentenceId().getUuidString(), comm);
        DependencyParse deps = IndexCommunications.getPreferredDependencyParse(toks);
        
        nres++;
        comms.add(comm.getId());
        if (tm.enoughTimePassed(2)) {
          Log.info("found " + mems.size() + " MEMs in " + nres + " results and " + comms.size() + " comms so far");
        }
        
        int maxToks = 200;
        if (toks.getTokenList().getTokenListSize() > maxToks) {
          // There are some really long sentences breaking stuff
          // I mean ones with >4000 words.
          reasonsForFiltering.increment("sentenceLongerThan" + maxToks + "Tokens");
          continue;
        }
        
        // Search for entities and situations
        DependencySyntaxEvents.CoverArgumentsWithPredicates dse;
        try (TB tb = timer().new TB("extractMEMs")) {
          List<Integer> args = DependencySyntaxEvents.extractEntityHeads(toks);
          dse = new DependencySyntaxEvents.CoverArgumentsWithPredicates(comm, toks, deps, args, df);
          if (dse.situation2args.size() == 0)
            reasonsForFiltering.increment("noDepSynPreds");
        }
        try (TB tb = timer().new TB("linkMEMs")) {
          // Line up the extracted args with the searched for entities
          for (int pred : dse.situation2args.keySet()) {
            MultiEntityMention mem = resolveArguments(
                pred, dse, deps, toks, comm, entities, df, ts, sitFeatCounts, reasonsForFiltering);
            if (mem != null)
              mems.add(mem);
          }
        }
      }
      Log.info("[filter] given " + res.getSearchResultItemsSize() + " results, extracted " + mems.size() + " MultiEntityMentions");

      return mems;
    }
    
    public static MultiEntityMention resolveArguments(
        int pred,
        DependencySyntaxEvents.CoverArgumentsWithPredicates dse,
        DependencyParse deps,
        Tokenization toks,
        Communication comm,
        List<PkbpEntity> entities,
        ComputeIdf df,
        TriageSearch ts,
        StringCountMinSketch sitFeatFreq,
        Counts<String> reasonsForFiltering) {

      double alignmentThreshold = 1;
      boolean debug = false;
      
      // (for debug) How many chars to show on either side of pred/arg heads
      int w = 50;

      BitSet arBS = dse.situation2args.get(pred);
      int[] ars = DependencySyntaxEvents.getSetIndicesInSortedOrder(arBS);
      if (ars.length < 2) {
        reasonsForFiltering.increment("only" + ars.length + "Args");
        if (debug) {
          Log.info("returning early since there are only " + ars.length + " arguments");
          System.out.println();
        }
        return null;
      }
      PkbpSituation.Mention sit = new PkbpSituation.Mention(pred, ars, deps, toks, comm);
      try (TB t = timer().new TB("measureSitFeatFreq")) {
        for (String f : dse.situation2features.get(pred)) {
          int c = sitFeatFreq.apply(f, false);
          double k = 10;
          double p = (k+1) / (k+c);
          sit.addFeature(f, p);
        }
      }
      MultiEntityMention mem = new MultiEntityMention(entities, sit);

      if (debug) {
        Log.info("aligning arguments for: " + sit.getContextAroundHead(w, w, true));
      }

      // Build each mention
      PkbpEntity.Mention[] argMentions = new PkbpEntity.Mention[ars.length];
      for (int i = 0; i < ars.length; i++) {
        Span span = IndexCommunications.nounPhraseExpand(ars[i], deps);
        String nerType = IndexCommunications.nerType(ars[i], toks);
        argMentions[i] = new PkbpEntity.Mention(ars[i], span, nerType, toks, deps, comm);
        if (debug) {
          Log.info("arg(" + i + "): " + argMentions[i].getContextAroundHead(w, w, true));
        }
      }

      if (debug)
        System.out.println();
      
      /*
       * TODO Fill out the alignment matrix in best-first order
       * Enforce "at most one ent/mention"
       * Penalize repeated ent-mentions (e.g. former constraint doesn't prohibit one ent covering all args)
       */
      
      /*
       * TODO Consider linking against all entities in the PKB.
       * I've honed it to the point where we will return a Tokenization with the N searched for entities,
       * but it may contain predicates which don't cover all the query entities.
       * These predicates, from what I've seen, are a good source of information.
       * A simple test is to check the score at which they link to the PKB,
       * we could treat these mentions just like we do the single-seed-entity mentions in the initial query
       * and link all the args hanging off them.
       * 
       * I think this gets much easier when I implement triage features for the PKB...
       * which will alleviate any problems of scalability.
       */

      // TODO Best first ordering
      // Match mentions and entities, argmax_{mention} \forall entities
      for (int i = 0; i < mem.query.length; i++) {
        PkbpEntity e = mem.query[i];
//        ArgMax<PkbpEntity.Mention> a = new ArgMax<>();
        ArgMax<EntLink> a = new ArgMax<>();
        for (int j = 0; j < argMentions.length; j++) {
          EntLink el;
          try (TB tb = timer().new TB("resolveArguments/scoreEntLink")) {
            el = scoreEntLink(e, argMentions[j], df, ts);
          }
//          a.offer(argMentions[j], Feat.sum(el.score));
          a.offer(el, Feat.sum(el.score));

          if (debug) {
            try (TB tb = timer().new TB("resolveArguments/scoreEntLink/debug")) {
              Log.info("ent:     " + e);
              Log.info("mention: " + argMentions[j].getContextAroundHead(w, w, true));
              Log.info("score:   " + Feat.showScore(el.score, 120));
              System.out.println();
            }
          }
        }
        if (a.getBestScore() >= alignmentThreshold) {
          mem.alignedMentions[i] = a.get();
        } else {
          reasonsForFiltering.increment("noLinkForOneOf" + argMentions.length + "Mentions");
          return null;  // Look ahead to "link all query entities" constraint
        }
      }
      
      // Check that all the queried entities have been linked
//      for (int i = 0; i < mem.alignedMentions.length; i++)
//        if (mem.alignedMentions[i] == null)
//          return null;
      
      // TODO Check that all linked arguments are uniq

      if (debug)
        System.out.println();
      reasonsForFiltering.increment("kept");
      return mem;
    }

    /**
     * Run triage+attrFeat on the given mention
     * @return a list of EM nodes which were added.
     */
    public List<PkbpNode> searchForMentionsOf(
        PkbpEntity entity,
        int maxResults,
        Function<String, Communication> commRet,
        ComputeIdf df,
        TriageSearch ts,
        Set<String> ignoreCommToks) {
      
      Log.info("searching for " + entity);
      showMentionsM(first(10, entity));
      
      if (ignoreCommToks != null)
        throw new RuntimeException("implement me");

      List<PkbpNode> added = new ArrayList<>();
      SearchQuery query = new SearchQuery();
      query.setK(maxResults);
      query.setType(SearchType.SENTENCES);
      query.setTerms(new ArrayList<>());

      // Triage features
      for (Feat tf : entity.getTriageFeatures())
        query.addToTerms(tf.name);

      // Attr features
      for (Feat af : entity.getAttrFeatures())
        query.addToTerms(String.format("a:%s:%.3f", af.name, af.weight));

      // Context
      StringTermVec c = entity.getContextDoc();
      List<String> contextTerms = df.importantTerms(c, 100);
      for (String ct : contextTerms)
        query.addToTerms("c:" + ct);

      try {
        Log.info("issuing query: " + query);
        SearchResult res = kbpEntitySearchService.search(query);
        Log.info("got back " + res.getSearchResultItemsSize() + " results for " + entity + ", retrieving comms...");
        for (SearchResultItem r : res.getSearchResultItems()) {
          // TODO Get comm from results instead
          // https://gitlab.hltcoe.jhu.edu/concrete/concrete/merge_requests/54
          Communication comm = commRet.apply(r.getCommunicationId());
          PkbpEntity.Mention m = PkbpEntity.Mention.convert(r, comm, ts);
          PkbpNode n = new PkbpNode(nodes.size(), m);
          n.addFeat(new Feat(FeatureNames.ENTITY_MENTION, 1));
          addNode(n);
          added.add(n);
        }
        return added;
      } catch (ServicesException e) {
        Log.info("cause:");
        Exception cause = (Exception) SerializationUtils.bytes2t(e.getSerEx());
        cause.printStackTrace();
        System.out.println();
        throw new RuntimeException(e);
      } catch (TException e) {
        throw new RuntimeException(e);
      }
    }
    
    public StringTermVec getSeedTermVec() {
      PkbpNode seedNode = findIntersection(FeatureNames.SEED, FeatureNames.ENTITY).get(0);
      PkbpEntity seedEnt = (PkbpEntity) seedNode.obj;
      StringTermVec seedTermVec = seedEnt.getContextDoc();
      return seedTermVec;
    }

    /**
     * Go through all the entity mentions and either link, promote, or prune them.
     * @return entity nodes which were created in the process
     */
    public List<PkbpNode> batchEntityLinking(List<PkbpNode> mentions, ComputeIdf df, TriageSearch ts, double scoreNoOp) {
      return batchEntityLinking(mentions, df, ts, scoreNoOp, false);
    }
    public List<PkbpNode> batchEntityLinking(List<PkbpNode> mentions, ComputeIdf df, TriageSearch ts, double scoreNoOp, boolean verbose) {
      StringTermVec seedTermVec = getSeedTermVec();
      List<PkbpNode> entNodesNew = new ArrayList<>();
      for (PkbpNode mentionNode : mentions) {
        try (TB tb = timer().new TB("batchEntityLinkingOne")) {
          EntLink l = batchEntityLinkingOne(mentionNode, seedTermVec, df, ts, scoreNoOp, verbose);
          if (l == null)
            continue;
          if (l.newEntity)
            entNodesNew.add(l.targetNode);
        }
      }
      return entNodesNew;
    }

    public EntLink batchEntityLinkingOne(PkbpNode mentionNode, StringTermVec seedTermVec, ComputeIdf df, TriageSearch ts, double scoreNoOp, boolean verbose) {
      List<PkbpNode> entNodesExisting = findIntersection(FeatureNames.ENTITY);
      int numBestEntLinks = 1;
      
      int w = 60;

      PkbpEntity.Mention mention = (PkbpEntity.Mention) mentionNode.obj;
      if (verbose) {
        System.out.println("linking: " + mention);
        System.out.println("linking: " + mention.getContextAroundHead(w, w, true));
        System.out.println("linking: triage: " + Feat.sortAndPrune(mention.getTriageFeatures(), 0d));
        System.out.println("linking: attr:   " + Feat.sortAndPrune(mention.getAttrFeatures(), 0d));
        //          NNPSense.EXTRACT_ATTR_FEAT_VERBOSE = true;
        //          NNPSense.extractAttributeFeaturesNewAndImproved(mention.tokUuid, mention.getCommunication(), mention.getHeadString());
        //          NNPSense.EXTRACT_ATTR_FEAT_VERBOSE = false;
        System.out.println();
      }

      Beam<EntLink> links = new Beam.BeamN<>(numBestEntLinks);
      // Existing entities
      for (PkbpNode en : entNodesExisting) {
        PkbpEntity e = (PkbpEntity) en.obj;
        EntLink l = scoreEntLink(e, mention, df, ts);
        l.targetNode = en;
        links.push(l, Feat.sum(l.score));
        if (verbose) {
          System.out.println("epush: " + l.target);
          System.out.println("epush: triage:    " + Feat.sortAndPrune(l.target.getTriageFeatures(), 0d));
          System.out.println("epush: attr:      " + Feat.sortAndPrune(l.target.getAttrFeatures(), 0d));
          System.out.println("epush: attrMatch: " + Feat.sortAndPrune(Feat.cosineSim(mention.getAttrFeatures(), l.target.getAttrFeatures()).get2(), 0d));
          System.out.println("epush: score: " + Feat.showScore(l.score, 200));
          System.out.println();
        }
      }
      // New entity
      {
        List<Feat> interesting = interestingEntityMention(mention, seedTermVec, df, ts);
        double interestingScore = Feat.sum(interesting);
        String newEntId = String.format("ent%03d/%s", nodes.size(), mention.getSpanString().replaceAll("\\s+", "_"));
        PkbpEntity newEnt = new PkbpEntity(newEntId, mention, interesting);
        EntLink newEntLink = new EntLink(mention, newEnt, interesting, true);
        links.push(newEntLink, interestingScore);
        if (verbose) {
          System.out.println("epush: " + newEntLink.target);
          System.out.println("epush: score: " + Feat.showScore(interesting, 200));
          System.out.println();
        }
      }
      // No op
      EntLink noOp = new EntLink(mention, null, null, false);
      links.push(noOp, scoreNoOp);

      EntLink l = links.pop();
      if (l == noOp)
        return null;
      if (verbose) {
        System.out.println("epop: " + l.target);
        if (l.newEntity) {
          int n = l.target.numMentions();
          if (n > 1) {
            for (int i = 0; i < n; i++) {
              System.out.println("epop: prevMention: " + l.target.getMention(i).getContextAroundHead(w, w, true));
            }
          }
        }
        System.out.println();
      }
      l.sourceNode = mentionNode;

      if (l.newEntity) {
        PkbpNode newEntNode = new PkbpNode(nodes.size(), l.target);
        newEntNode.addFeat(new Feat(FeatureNames.ENTITY, 1));
        newEntNode.addFeat(new Feat(FeatureNames.entity(l.sourceNode)));
        addNode(newEntNode);
        l.targetNode = newEntNode;
      } else {
        assert l.targetNode != null;
        l.target.addMention(l);
      }

      // Update feature index
      String key = FeatureNames.entity(mentionNode);
      IntArrayList parents = feat2nodes.get(key);
      if (parents == null) {
        parents = new IntArrayList();
        feat2nodes.put(key, parents);
      }
      parents.add(l.targetNode.id);

      if (verbose)
        System.out.println();
      return l;
    }
    
    static class PA {
      PkbpNode arg0Node;
      // TODO I don't think I actually use this PkbpNode, rather than a plain PkbpEntity or PkbpEntity.Mention
      // Or, at least I can do a lot without knowing it, like sit linking
      // Include another field which is the non-node type for arg0
      PkbpEntity.Mention arg0;

      PkbpSituation.Mention sitMention;
      List<PkbpEntity.Mention> otherArgs;

      public PA(PkbpNode arg0, PkbpSituation.Mention sitMention) {
        this.arg0Node = arg0;
        if (arg0Node != null)
          this.arg0 = (PkbpEntity.Mention) arg0Node.obj;
        this.sitMention = sitMention;
        this.otherArgs = new ArrayList<>();
      }
      
      public static PA convert(MultiEntityMention mem, PkbpEntity arg0Ent) {
        PA pa = new PA(null, mem.pred);
        
        // Search for arg0
        int indexOfArg0Ent = -1;
        for (int i = 0; i < mem.query.length; i++) {
          if (mem.query[i].id.equals(arg0Ent.id)) {
            assert indexOfArg0Ent < 0;
            indexOfArg0Ent = i;
          }
        }
        if (indexOfArg0Ent < 0) {
          // Didn't find arg0Ent in this MEM
          return null;
        }

        // Search for the metion of arg0
        pa.arg0Node = null; // take note
//        pa.arg0 = mem.alignedMentions[indexOfArg0Ent];
        pa.arg0 = mem.alignedMentions[indexOfArg0Ent].source;
        for (int i = 0 ; i < mem.query.length; i++) {
          if (mem.alignedMentions[i] != null && i != indexOfArg0Ent) {
//            pa.otherArgs.add(mem.alignedMentions[i]);
            pa.otherArgs.add(mem.alignedMentions[i].source);
          }
        }
        return pa;
      }

      public static List<PkbpSituation.Mention> sitMentions(List<PA> pas) {
        List<PkbpSituation.Mention> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PA pa : pas) {
          String key = pa.sitMention.getCommTokIdShort() + "/" + pa.sitMention.head;
          if (seen.add(key))
            out.add(pa.sitMention);
        }
        return out;
      }
      
      public static List<PkbpEntity.Mention> otherArgs(List<PA> pas) {
        List<PkbpEntity.Mention> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PA pa : pas) {
          for (PkbpEntity.Mention m : pa.otherArgs) {
            String key = m.getCommTokIdShort() + "/" + m.head;
            if (seen.add(key))
              out.add(m);
          }
        }
        return out;
      }
    }
    
    /**
     * @param outputSitAndArgs contains values of (sitMention, entMentionsWithArgArgsOfThisSitMention)
     */
    public void extractSituationsAndArgument(PkbpNode emNode, List<PA> output, ComputeIdf df, TriageSearch ts) {
      timer().start("extractSituationsAndArgument");
      PkbpEntity.Mention em = (PkbpEntity.Mention) emNode.obj;
      Communication c = em.getCommunication();
      Tokenization t = em.getTokenization();
      DependencyParse deps = IndexCommunications.getPreferredDependencyParse(t);
      List<Integer> entHeads = DependencySyntaxEvents.extractEntityHeads(t);
      if (!entHeads.contains(em.head))
        entHeads.add(em.head);

      DependencySyntaxEvents.CoverArgumentsWithPredicates se = null;
      try {
        se = new DependencySyntaxEvents.CoverArgumentsWithPredicates(c, t, deps, entHeads, df);
      } catch (Exception e) {
        e.printStackTrace();
        return;
      }

      for (Entry<Integer, Set<String>> sm : se.getSituations().entrySet()) {
        int pred = sm.getKey();
        BitSet argsBS = se.situation2args.get(pred);
        int[] args = DependencySyntaxEvents.getSetIndicesInSortedOrder(argsBS);
        
        // Only take situations where the searched for entity plays a role
        if (!argsBS.get(em.head))
          continue;
        
        // Only take situations where there is at least one other participant
        if (args.length < 2)
          continue;
        
        // New SituationMention
        Set<String> smFeats = se.situation2features.get(pred);
        PkbpSituation.Mention sitMention = new PkbpSituation.Mention(pred, args, deps, t, c);
        for (String feat : smFeats) {
          int freq = sitFeatCounts.apply(feat, false);
          double k = 10;
          double score = (k + 1) / (k + freq);
          sitMention.addFeature(feat, score);
        }
//        outputSitMentions.add(sitMention);
        PA pa = new PA(emNode, sitMention);
        
        // New ArgumentMentions (if this situation involves a given EntityMention as an argument)
        for (int argHead : args) {
          if (argHead == em.head) {
            continue;
          }
          
          Span argSpan = IndexCommunications.nounPhraseExpand(argHead, deps);
          String nerType = IndexCommunications.nerType(argHead, t);
          PkbpEntity.Mention argEm = new PkbpEntity.Mention(argHead, argSpan, nerType, t, deps, c);
          argEm.scoreTriageFeatures(ts);
//          outputArgumentEntMentions.add(argEm);
          pa.otherArgs.add(argEm);
        }
        output.add(pa);
      }
      timer().stop("extractSituationsAndArgument");
    }
    
    
    public List<SitLink> batchSituationLinking(List<PA> sitMentions) {
      List<SitLink> links = new ArrayList<>();
      for (PA s : sitMentions) {
        SitLink sl = singleSituationLink(s);
        if (sl != null)
          links.add(sl);
      }
      return links;
    }
    
    // TODO Refactor this so that it has a side-effect free version
    public SitLink singleSituationLink(PA pa) {
      boolean debug = true;
      
      // (for debug) How many chars to show around heads
      int w = 60;
      
      if (debug) {
        System.out.println("linking:  " + pa.sitMention.getContextAroundHead(w, w, true));
        System.out.println("features: " + pa.sitMention.getFeatures());
//        for (Feat f : pa.sitMention.getFeatures()) {
//          System.out.println("feat: " + f);
//        }
        System.out.println();
      }

      // Look up triage set of Situations by indexed feature
      List<String> triageSitFeats = extractSituationMentionTriageFeatures(pa);
      List<Pair<PkbpNode, Double>> triage = findUnion(triageSitFeats);
      if (debug) {
        System.out.println("triageSitFeats="+ triageSitFeats);
        System.out.println("nTriageSitutaions=" + triage.size());
      }

      Beam<SitLink> beam = new Beam.BeamN<>(5);
      // Existing situations
      for (Pair<PkbpNode, Double> sit : triage) {
        PkbpSituation s = (PkbpSituation) sit.get1().obj;
        List<Feat> feats = scoreSitLink(s, pa.sitMention);
        SitLink sl = new SitLink(Collections.emptyList(), pa.sitMention, s, feats, false);
        sl.targetNode = sit.get1();
        beam.push(sl, Feat.sum(feats));
        if (debug) {
          System.out.println("spush: target=" + sl.target);
          for (Feat f : first(4, feats))
          System.out.println("spush: feat=" + f);
          System.out.println("spush: score=" + Feat.sum(feats));
          System.out.println();
        }
      }
      // New situation
      String id = String.format("sit/%s/%s", pa.sitMention.getCommTokIdShort(), pa.sitMention.getHeadWordAndPosition());
      PkbpSituation newSit = new PkbpSituation(id, pa.sitMention);
      List<Feat> newSitScore = new ArrayList<>();
      newSitScore.add(new Feat("intercept", 0.5));
      SitLink slNew = new SitLink(Collections.emptyList(), pa.sitMention, newSit, newSitScore, true);
      beam.push(slNew, Feat.sum(newSitScore));
      if (debug) {
        System.out.println("spush: " + slNew.target);
        for (Feat f : first(4, newSitScore))
        System.out.println("spush: feat=" + f);
        System.out.println("spush: score=" + Feat.sum(newSitScore));
        System.out.println();
      }
      
      SitLink sl = beam.pop();
      if (debug) {
        System.out.println("spop: new=" + sl.newSituation + "\ttarget=" + sl.target);
        if (!sl.newSituation) {
          for (PkbpMention sm : sl.target.mentions)
            System.out.println("spop: prevMention: " + sm.getContextAroundHead(w, w, true));
        }
        System.out.println();
      }
      if (sl.newSituation) {
        PkbpNode n = new PkbpNode(nodes.size(), sl.target);
        n.addFeat(new Feat(FeatureNames.SITUATION, 1));
        addNode(n);
        sl.targetNode = n;
      } else {
        assert sl.targetNode != null;
      }
      for (String ts : triageSitFeats) {
        IntArrayList sits = feat2nodes.get(ts);
        if (sits == null) {
          sits = new IntArrayList();
          feat2nodes.put(ts, sits);
        }
        sits.add(sl.targetNode.id);
      }
      if (debug) {
        System.out.println();
      }
      return sl;
    }
    
    public static List<String> extractSituationMentionTriageFeatures(PA pa) {
      // Get a triage set of potential situations by feature
      List<String> triageSitFeats = new ArrayList<>();
      // year-month-nearByDay
      triageSitFeats.addAll(FeatureNames.occurredOn(pa.sitMention));
      // lemma
      triageSitFeats.add(FeatureNames.sitLemma(pa.sitMention));

      // arg-entityId
      // TODO I want the PbkNodes for otherArgs
      
      // For now we'll have each entity be indexed by a feature of where
//      triageSitFeats.add(FeatureNames.entHeadLocation((PkbpEntity.Mention) pa.arg0Node.obj));
      triageSitFeats.add(FeatureNames.entHeadLocation(pa.arg0));
      for (PkbpEntity.Mention a : pa.otherArgs)
        triageSitFeats.add(FeatureNames.entHeadLocation(a));
      
      return triageSitFeats;
    }
    
//    static class SitRedunancyEvalInst {
//      MultiEntityMention mem;   // SEED+X and PkbpSituation.Mention
//      SitLink sitCluster;       // source is mem.pred and target is a PkbpSituation
//    }
    
    static class EvalRedundancy {
      private KbpQuery query;
      private List<MultiEntityMention> sitMentions;
      private File outputDir;
      private Random rand;

      private Map<String, ReservoirSample<IntPair>> examples;
      private int numPos, numNeg;

      public EvalRedundancy(KbpQuery query, List<MultiEntityMention> sitMentions, File outputDir, Random rand) {
        if (!outputDir.isDirectory())
          throw new IllegalArgumentException("not a dir: " + outputDir.getPath());
        this.query = query;
        this.sitMentions = sitMentions;
        this.outputDir = outputDir;
        this.rand = rand;
        this.numPos = -1;
        this.numNeg = -1;
        
        // Check for duplicate MEMs (currently by predicate location)
        Set<String> seen = new HashSet<>();
        for (MultiEntityMention mem : sitMentions)
          if (!seen.add(mem.pred.getCommTokHeadWordAndLoc()))
            throw new IllegalArgumentException("duplicate predicate: " + mem.pred.getCommTokHeadWordAndLoc());
      }
      
      /*
       * Where are we going to get the SitMention pairs from?
       * Previously there was one list which was being de-duplicated, just run parma on all-pairs.
       * Now that we have a notion of splitting by core participants, we have an initial/top-level/constraint clustering.
       * I could still present the results as (KbpQuery, List<QResultCluster>)
       * 
       * Regardless of what code I use to display it, that answers the question:
       * We take the union of all SitMentions in the second stage of SEED+X queries, run parma within each entPair cluster of events
       * The negatives are implicit from all pairs over the union of SEED+X results.
       * And these are negatives "with some teeth", as in they are all plausible on some level: they all involve the seed entity
       * 
       * Implementation note: EvaluateRedundancy takes Pair<KbpQuery, List<SitSearchResult>>
       * I will need to make KbpQuery really correspond to a SEED+X query, since EvaluateRedundancy contains code for running dedup on the List<SitSearchResult>
       * 
       * TODO EvaluateRedunancy doesn't include code to call parma, so would it be easier/better to just do it
       * straight off of PkbpSituation.Mentions?
       * The whole ParmaVw => ConcreteMentionFeatureExtractor => VwWrapper pipeline is garbage.
       * The only tough-ish part is re-training parma models with CreateParmaTrainingData.GenStr
       * 
       * Backwards chaining:
       * WANT:
       *  1) dedup:Function<List<PA>, List<List<PA>>>       -- will involve refactors and re-training parma
       *  2) showHitPairs: Function<List<List<PA>>, IO>     -- just do it
       */
      
//      public static List<String> writeExample(SitRedunancyEvalInst a, SitRedunancyEvalInst b) {
//        boolean yhat = a.sitCluster.target == b.sitCluster.target;
//      }

      /**
       * One HTML file per pair, at the top list the 4 diff methods's decision on this pair
       */
      public void writeOutExamples() throws IOException {
        if (examples == null)
          throw new IllegalStateException();
        
        // 1) Write out one HTML file per pair (for turker evaluation)
        File byInstanceDir = new File(outputDir, "byInstance");
        if (!byInstanceDir.isDirectory())
          byInstanceDir.mkdirs();
        Map<IntPair, LL<String>> ex2yhat = new HashMap<>();
        for (String yhat : examples.keySet())
          for (IntPair ex : examples.get(yhat))
            ex2yhat.put(ex, new LL<>(yhat, ex2yhat.get(ex)));
        for (IntPair ij : ex2yhat.keySet()) {
          List<String> yhats = LL.toList(ex2yhat.get(ij));
          MultiEntityMention a = sitMentions.get(ij.first);
          MultiEntityMention b = sitMentions.get(ij.second);
          File f = new File(byInstanceDir, getId(a, b).replaceAll("/", "_") + ".html");
          writeoutOne(a, b, f, yhats);
        }
        
        // 2) Write out many examples per group
        File byLabelDir = new File(outputDir, "byLabel");
        if (!byLabelDir.isDirectory())
          byLabelDir.mkdirs();
        for (Entry<String, ReservoirSample<IntPair>> e : examples.entrySet()) {
          List<String> yhats = Arrays.asList(e.getKey());
          File f = new File(byLabelDir, e.getKey().replaceAll("/", "_") + ".html");
          try (BufferedWriter w = FileUtil.getWriter(f)) {
            for (IntPair ij : e.getValue()) {
              MultiEntityMention a = sitMentions.get(ij.first);
              MultiEntityMention b = sitMentions.get(ij.second);

              w.write("<h2>" + a.pred.getCommTokHeadWordAndLoc() + "</h2>");
              w.newLine();
              w.write("<pre>");
              w.newLine();
              for (String s : showMultiEntityMention2(a)) {
                w.write(s);
                w.newLine();
              }
              w.write("</pre><br/>");
              w.newLine();

              w.write("<h2>" + b.pred.getCommTokHeadWordAndLoc() + "</h2>");
              w.newLine();
              w.write("<pre>");
              w.newLine();
              for (String s : showMultiEntityMention2(b)) {
                w.write(s);
                w.newLine();
              }
              w.write("</pre><br/>");
              w.newLine();

              for (String line : writeoutOneHelper(a, b, yhats)) {
                w.write(line);
                w.newLine();
              }
              w.write("<br/><br/>");
              w.newLine();
            }
          }
        }
      }
      
      private static void writeoutOne(MultiEntityMention a, MultiEntityMention b, File f, List<String> yhats) throws IOException {
        try (BufferedWriter w = FileUtil.getWriter(f)) {
          for (String line : writeoutOneHelper(a, b, yhats)) {
            w.write(line);
            w.newLine();
          }
        }
      }
      
      private static List<String> writeoutOneHelper(MultiEntityMention a, MultiEntityMention b, List<String> yhats) {
        List<String> l = new ArrayList<>();
        
        l.add("<h2>" + StringUtils.join(", ", yhats) + "</h2>");

        l.add("<center>");
        l.add("<table border=\"1\" cellpadding=\"10\" width=\"80%\">");
        l.add("<col width=\"50%\"><col width=\"50%\">");

        l.add("<tr>");

        l.add("<td valign=top>");
//        l.add(left.getWordsInTokenizationWithHighlightedEntAndSit(false));
        l.add(a.pred.getContextAroundHead(true));
        l.add("</td>");

        l.add("<td valign=top>");
//        l.add(right.getWordsInTokenizationWithHighlightedEntAndSit(false));
        l.add(b.pred.getContextAroundHead(true));
        l.add("</td>");

        l.add("</tr>");

        l.add("</table>");
        l.add("</center>");


        return l;
      }
      
      /** [participants, lemma, participants&lemma, participants&parma] * [pos, neg] */
      public void generateExamples(int numPos, int numNeg) {
        this.numPos = numPos;
        this.numNeg = numNeg;
        this.examples = new HashMap<>();
        
        // Check that there is a bijection between indices and MEM ids
        ObjectObjectBimap<IntPair, String> b = new ObjectObjectBimap<>();

        int n = sitMentions.size();
        for (int i = 0; i < n-1; i++) {
          for (int j = i+1; j < n; j++) {
            add(i, j, "participants");
            add(i, j, "lemma");
            add(i, j, "participants", "lemma");
            add(i, j, "participants", "parma");   // this is fast due to short-circuiting
            
            String id = getId(sitMentions.get(i), sitMentions.get(j));
            try {
              b.put(new IntPair(i,j), id);
            } catch (Exception e) {
              Log.info("WARNING: i=" + i + " j=" + j + " id=" + id);
              throw new RuntimeException("there appear to be duplicate MEMs");
            }
          }
        }
      }
      
      private void add(int i, int j, String... methods) {
        if (i >= j)
          throw new IllegalArgumentException("i=" + i + " j=" + j);
        MultiEntityMention a = sitMentions.get(i);
        MultiEntityMention b = sitMentions.get(j);
        boolean yhat = true;
        for (int k = 0; k < methods.length && yhat; k++)
          yhat &= coref(methods[k], a, b);
        String key = StringUtils.join("&", methods) + "/" + (yhat ? "pos" : "neg");
        ReservoirSample<IntPair> r = examples.get(key);
        if (r == null) {
          int k = yhat ? numPos : numNeg;
          r = new ReservoirSample<>(k, rand);
          examples.put(key, r);
        }
        r.add(new IntPair(i, j));
      }
      
      public boolean coref(String method, MultiEntityMention sitA, MultiEntityMention sitB) {
        switch (method.toLowerCase()) {
        case "lemma":
          return lemmaMatch(sitA, sitB);
        case "parma":
          return parmaMatch(sitA, sitB);
        case "participants":
          return participantsMatch(sitA, sitB);
        default:
          throw new RuntimeException("unknown method: " + method);
        }
      }

      public boolean participantsMatch(MultiEntityMention sitA, MultiEntityMention sitB) {
        if (sitA.query.length == 0 || sitB.query.length == 0)
          throw new IllegalArgumentException();
        
        // TODO?
        // Not only must they agree on arguments, but also on what predicate they're talking about
        // ...this is not well defined, since these could be arbitrary predicates in diff sentences, are we adding lemma match?

        boolean same = sitA.query.length == sitB.query.length;
        for (int i = 0; i < sitA.query.length && same; i++)
          same &= sitA.query[i].id.equals(sitB.query[i].id);
        return same;
      }
      
      public boolean lemmaMatch(MultiEntityMention sitA, MultiEntityMention sitB) {
        return sitA.pred.getHeadLemma().equalsIgnoreCase(sitB.pred.getHeadLemma());
      }
      
      public boolean parmaMatch(MultiEntityMention sitA, MultiEntityMention sitB) {
//        throw new RuntimeException("implement me");
        Log.info("WARNING: Flipping a coin but should be calling PARMA!");
        return rand.nextBoolean();
      }
      
      public String getId(MultiEntityMention sitA, MultiEntityMention sitB) {
        return sitA.pred.getCommTokHeadWordAndLoc() + "_" + sitB.pred.getCommTokHeadWordAndLoc();
      }
    }
    
  } // END NEW CLASS


  // Debugging
  Counts<String> ec = new Counts<>();
  public boolean verbose = false;
  public boolean verboseLinking = false;


  public PkbpSearching(KbpSearching search, StringCountMinSketch sitFeatCms, KbpQuery seed, double seedWeight, Random rand) {
    Log.info("seed=" + seed);
    if (seed.sourceComm == null)
      throw new IllegalArgumentException();
    if (sitFeatCms == null)
      throw new IllegalArgumentException();
    getTacEMFinder();
    this.sitFeatCms = sitFeatCms;
    this.initialResultsCache = new HashMap<>();
    this.seenCommToks = new HashSet<>();
    this.memb_e2s = new HashMap<>();
    this.rand = rand;
    this.queue = new ArrayDeque<>();
    
    this.entities = new ArrayList<>();
    this.situations = new ArrayList<>();
    
    this.search = search;
    ComputeIdf df = null;   // TODO
    setSeed(seed, seedWeight, df);
  }
  
  public void clearSeenCommToks() {
    this.seenCommToks.clear();
  }
  
  private ComputeIdf getTermFrequencies() {
    return search.getTermFrequencies();
  }
  
  private TacQueryEntityMentionResolver getTacEMFinder() {
    if (findEntityMention == null)
      findEntityMention = new TacQueryEntityMentionResolver("tacQuery");
    return findEntityMention;
  }
  
  public void setSeed(KbpQuery seed, double seedWeight, ComputeIdf df) {
    // TODO Use PkbpEntity.mention.convert(KbpQuery)
    Log.info("seedWeight=" + seedWeight + " seed=" + seed);
    this.seed = seed;
    boolean normalizeNumbers = false;
    this.seedTermVec = new StringTermVec(seed.sourceComm, normalizeNumbers);
    if (seed.entityMention == null) {
      boolean addEmToCommIfMissing = true;
      getTacEMFinder().resolve(seed, addEmToCommIfMissing);
    }
    assert seed.entityMention != null;
    assert seed.entityMention.isSetText();
    assert seed.entity_type != null;

    String tokUuid = seed.entityMention.getTokens().getTokenizationId().getUuidString();
    SitSearchResult canonical = new SitSearchResult(tokUuid, null, Collections.emptyList());
    canonical.setCommunicationId(seed.docid);
    canonical.setCommunication(seed.sourceComm);
    canonical.yhatQueryEntityNerType = seed.entity_type;

    Map<String, Tokenization> tokMap = new HashMap<>();
    for (Tokenization tok : new TokenizationIter(seed.sourceComm)) {
      Object old = tokMap.put(tok.getUuid().getUuidString(), tok);
      assert old == null;
    }

    // sets head token, needs triage feats and comm
    String mentionText = seed.entityMention.getText();
    String[] headwords = mentionText.split("\\s+");
    String nerType = seed.entity_type;
    TokenObservationCounts tokObs = null;
    TokenObservationCounts tokObsLc = null;
    canonical.triageFeatures = IndexCommunications.getEntityMentionFeatures(
          mentionText, headwords, nerType, tokObs, tokObsLc);
    AccumuloIndex.findEntitiesAndSituations(canonical, getTermFrequencies(), false);

    DependencyParse deps = IndexCommunications.getPreferredDependencyParse(canonical.getTokenization());
    canonical.yhatQueryEntitySpan = IndexCommunications.nounPhraseExpand(canonical.yhatQueryEntityHead, deps);

    List<Feat> relevanceReasons = new ArrayList<>();
    relevanceReasons.add(new Feat("seed", seedWeight));

    PkbpEntity.Mention canonical2 = new PkbpEntity.Mention(canonical);
    assert canonical2.getCommunication() != null;
    canonical2.getContextDoc();
//    canonical2.getAttrCommFeatures();
//    canonical2.getAttrTokFeatures();
    canonical2.getAttrFeatures();
    assert canonical2.triageFeatures != null;
    
    Pair<List<EntLink>, List<SitLink>> p = proposeLinks(canonical2, df);
    // New entity for the searched-for mention
    Set<String> exUniq = new HashSet<>();   // proposeLinks doesn't de-duplicate links
    EntLink best = null;
    for (EntLink el : p.get1()) {
      if (el.source.head != canonical2.head)
        continue;
      if (!exUniq.add(linkStr(el)))
        continue;
      Log.info("setup EntLink: " + el);
      if (el.source.equals(canonical2)) {
        assert best == null : "multiple new links";
        best = el;
      }
    }
    if (best == null) {
      System.err.flush();
      System.out.flush();
      throw new RuntimeException("fail to link " + canonical2);
    }
    PkbpResult r0 = new PkbpResult("seed/" + seed.id, true);
    best.target.addMention(best);
    r0.addArgument(best.target, null);
    this.seedEntity = best.target;

    this.seenCommToks.add(new Pair<>(
        canonical2.getCommunication().getId(),
        canonical2.getTokenization().getUuid().getUuidString()));
    this.queue.add(r0);
  }

  private static String linkStr(EntLink el) {
    return "el/" + el.source.getCommTokIdShort() + "/" + el.source.head + "/" + el.target.id;
  }
  private static String linkStr(SitLink el) {
    String tid = StringUtils.join("-", el.target.getHeads());
    return "sl/" + el.source.getCommTokIdShort() + "/" + el.source.head + "/" + tid;
  }
  
  private Communication getComm(String id) {
    return search.getCommCaching(id);
  }
  
  /**
   * Do this before serializing this instance so as to de-duplicate
   * {@link Communication}s which are pointed to by many results.
   */
  private void dropCommsFromInitialResultsCache() {
    Log.info("dropping comms...");
    for (List<SitSearchResult> mentions : initialResultsCache.values())
      for (SitSearchResult ss : mentions)
        ss.setCommunication(null);
    Log.info("done");
  }
  private void restoreCommsInInitialResultsCache() {
    Log.info("starting...");
    for (List<SitSearchResult> mentions : initialResultsCache.values()) {
      for (SitSearchResult ss : mentions) {
        if (ss.getCommunication() != null)
          continue;
        Communication c = getComm(ss.getCommunicationId());
        ss.setCommunication(c);
      }
    }
    Log.info("done");
  }
  
  /**
   * @deprecated
   * TODO Need to add attr feats anyway
   */
  private List<SitSearchResult> initialSearch(PkbpResult searchFor) throws Exception {
    List<SitSearchResult> mentions = null;
    if (initialResultsCache != null && (mentions = initialResultsCache.get(searchFor.id)) != null) {
      return mentions;
    }

    // Build a view of all the entities in this result
    TriageSearch ts = search.getTriageSearch();
    StringTermVec docContext = new StringTermVec();
    Set<String> triageFeats = new HashSet<>();
//    List<Feat> attrComm = new ArrayList<>();
//    List<Feat> attrTok = new ArrayList<>();
    int nm = 0;
    for (PkbpEntity e : searchFor.getArguments()) {
      for (PkbpEntity.Mention m : e) {
        nm++;
        docContext.add(m.getContextDoc());
        for (Feat ft : m.triageFeatures)
          triageFeats.add(ft.name);
//        attrComm.addAll(m.getAttrCommFeatures());
//        attrTok.addAll(m.getAttrTokFeatures());
      }
    }
    assert nm > 0;

    // Perform triage search
    mentions = ts.search(new ArrayList<>(triageFeats), docContext, getTermFrequencies(), null);
    Log.info("triage search returned " + mentions.size() + " mentions for " + searchFor);

    // Resolve the communications
    Log.info("resolving communications for results");
    for (SitSearchResult ss : mentions) {
      Communication c = getComm(ss.getCommunicationId());
      assert c != null;
      ss.setCommunication(c);
    }
    
    // Store in cache
    if (initialResultsCache != null) {
      initialResultsCache.put(searchFor.id, mentions);
    }
    
    return mentions;
  }

  /**
   * 1) choose a tuple of entities to search for (one of which will be the seed entity)
   * 2) for each resulting tokenization: extract entMentions and sitMentions
   * 3) for each resulting tok, entMention: try to link to ent, possible create new entity if entMention looks good
   * 4) for each resulting tok, sitMention: try to link to a sit if it shares a resolved entity & sitFeatScores are good
   * 5) go back and find any sitMentions which link to 2+ situations, consider linking them
   * 6) go back and find any entMentions which link to 2+ entities, consider linking them
   */
  public void outerLoop(PkbpResult searchFor) throws Exception {
    boolean verbose = true;
    
    if (verbose) {
      Log.info("starting, searchFor=" + searchFor);
      int maxEntities = 10;
      int maxMentionsPerEnt = 4;
      showEntities(maxEntities, maxMentionsPerEnt);
    }
    
    ComputeIdf df = null;   // TODO
    
    // Build a view of all the entities in this result
    StringTermVec docContext = new StringTermVec();
    Set<String> triageFeats = new HashSet<>();
//    List<Feat> attrComm = new ArrayList<>();
//    List<Feat> attrTok = new ArrayList<>();
    List<Feat> attr = new ArrayList<>();
    for (PkbpEntity e : searchFor.getArguments()) {
      for (PkbpEntity.Mention m : e) {
        docContext.add(m.getContextDoc());
        for (Feat ft : m.triageFeatures)
          triageFeats.add(ft.name);
//        attrComm.addAll(m.getAttrCommFeatures());
//        attrTok.addAll(m.getAttrTokFeatures());
      }
      attr.addAll(e.getAttrFeatures());
    }
    
    // NOTE: This can be memoized!
    // Perform the initial search (triage features and attribute feature reranking)
    List<SitSearchResult> mentions = initialSearch(searchFor);
    
    // Highlight the searched for entity mention
    {
    List<SitSearchResult> pruned = new ArrayList<>();
    for (SitSearchResult ss : mentions) {
      if (AccumuloIndex.findEntitiesAndSituations(ss, getTermFrequencies(), false))
        pruned.add(ss);
    }
    Log.info("[filter] lost " + (mentions.size()-pruned.size()) + " mentions due to query head finding failure");
    mentions = pruned;
    }

    // Compute and score attribute features
//    boolean dedup = true;
//    List<String> attrCommQ = Feat.demote(attrComm, dedup);
//    List<String> attrTokQ = Feat.demote(attrTok, dedup);
    if (verbose)
      Log.info("doing attribute feature reranking, attr=" + attr);
//    AccumuloIndex.attrFeatureReranking(attrCommQ, attrTokQ, mentions);
    AccumuloIndex.attrFeatureReranking(attr, mentions);

    // Scan the results
    Map<PkbpEntity.Mention, LL<PkbpEntity>> entDups = new HashMap<>();
    Map<PkbpSituation.Mention, LL<PkbpSituation>> sitDups = new HashMap<>();
    for (SitSearchResult s : mentions) {
      PkbpEntity.Mention em = new PkbpEntity.Mention(s);
      TokenTagging ner = IndexCommunications.getPreferredNerTags(em.getTokenization());
      em.nerType = ner.getTaggedTokenList().get(em.head).getTag();
      Pair<List<EntLink>, List<SitLink>> x = proposeLinks(em, df);
      
      // Execute the linking operations.
      // TODO I believe this is OK since proposeLinks returns only one EntLink per entMention,
      // but in general the safest way to do this is to choose links according to the SitLinks
      // (and their contingentUpon:EntLinks), and then only add un-problematic EntLinks which
      // are not associated with a SitLink.
      Object old;
      Set<String> exUniq = new HashSet<>();
      Map<Integer, Object> headToken2linkTarget = new HashMap<>();  // ensures that we aren't linking a mention to two different things
      List<EntLink> exEL = new ArrayList<>();
      List<SitLink> exSL = new ArrayList<>();
      // This needs to come first since it populates memb_e2s
      for (SitLink sl : x.get2()) {
        if (!exUniq.add(linkStr(sl))) {
          ec.increment("dup/SitLink");
          ec.increment("dup/SitLink/new=" + sl.newSituation);
        } else {
          ec.increment("apply/SitLink");
          ec.increment("apply/SitLink/new=" + sl.newSituation);
          if (verbose)
            Log.info("ADDING: " + sl);
          sl.target.addMention(sl.source);
          if (sl.newSituation)
            situations.add(sl.target);
          exSL.add(sl);
          // relations can have a headword which is an entity headword
          // i.e. it is not gauranteed to be disjoint with all other mentions.
          //old = headToken2linkTarget.put(sl.source.head, sl.target);
          //assert old == null : "sl.source=" + sl.source + " old=" + old;
          for (EntLink el : sl.contingentUpon) {
            if (!exUniq.add(linkStr(el))) {
              ec.increment("dup/EntLink");
              ec.increment("dup/EntLink/new=" + el.newEntity);
              ec.increment("dup/EntLink/contingent");
            } else {
              ec.increment("apply/EntLink");
              ec.increment("apply/EntLink/new=" + el.newEntity);
              ec.increment("apply/EntLink/contingent");
              if (verbose)
                Log.info("ADDING: " + el);
              el.target.addMention(el);
              memb_e2s.put(el.target, new LL<>(sl.target, memb_e2s.get(el.target)));
              if (el.newEntity)
                entities.add(el.target);
              exEL.add(el);
              old = headToken2linkTarget.put(el.source.head, el.target);
              assert old == null : "el.source=" + el.source + " old=" + old;
            }
          }
        }
      }
      for (EntLink el : x.get1()) {
        if (!exUniq.add(linkStr(el))) {
          ec.increment("dup/EntLink");
          ec.increment("dup/EntLink/new=" + el.newEntity);
        } else {
          ec.increment("apply/EntLink");
          ec.increment("apply/EntLink/new=" + el.newEntity);
          if (verbose)
            Log.info("ADDING: " + el);
          el.target.addMention(el);
          if (el.newEntity)
            entities.add(el.target);
          exEL.add(el);
          old = headToken2linkTarget.put(el.source.head, el.target);
          assert old == null : "el.source=" + el.source + " old=" + old;
        }
      }
      
      // Update duplicate index
      for (EntLink el : exEL)
        entDups.put(el.source, new LL<>(el.target, entDups.get(el.source)));
      for (SitLink sl : exSL)
        sitDups.put(sl.source, new LL<>(sl.target, sitDups.get(sl.source)));
      
      
//      // By nature of linking to PkbpEntities and PkbpSituations, we are already adding SitSearchResults/PkbpMentions to the PkbpResults
//      // The only question is whether we should create a *NEW* PkbpResult.
//      // We do this every time we have a pair of entities which we haven't seen before
//      timer().start("linkResults");
//      int ne = exEL.size();
//      Log.info("trying to link to PkbpResults based on pairs from nEntLink=" + ne);
//      for (int i = 0; i < ne-1; i++) {
//        for (int j = i+1; j < ne; j++) {
//          
//          PkbpEntity ei = exEL.get(i).target;
//          PkbpEntity ej = exEL.get(j).target;
//          List<String> rKey = generateOutputKey(ei, ej);
//          
//          PkbpResult r = output.get(rKey);
//          if (r == null) {
//            String id = String.format("r%03d:%s+%s", output.size(), rKey.get(0), rKey.get(1));
//            boolean containsSeed = ei.id.equals("seed") || ej.id.equals("seed");
//            r = new PkbpResult(id, containsSeed);
//            r.addArgument(ei, memb_e2r);
//            r.addArgument(ej, memb_e2r);
//            Log.info("NEW RESULT, queing search for: " + r);
//            output.put(rKey, r);
//            queue.add(r);
//          } else {
//            Log.info("LINK RESULT, already exists: " + r);
//          }
//        }
//      }
//      timer().stop("linkResults");

      System.out.println();
      System.out.println("TIMER:");
      System.out.println(timer());
      System.out.println();

      System.out.println();
      System.out.println("COUNTS:");
      System.out.println(ec);
      System.out.println();

      System.out.println();
//      showResultsOld();
      int maxSit = 5;
      int maxSitMentions = 5;
      showResultsNew(maxSit, maxSitMentions);
      System.out.println();
    }
    
    // For mentions which appear in more than one ent/sit, consider merging them
    timer().start("dedupEnts");
    for (Entry<Mention, LL<PkbpEntity>> e : entDups.entrySet()) {
      List<PkbpEntity> ents = e.getValue().toList();
      if (ents.size() > 1)
        considerMergingEntities(ents);
    }
    timer().stop("dedupEnts");
    timer().start("dedupSits");
    for (Entry<PkbpSituation.Mention, LL<PkbpSituation>> e : sitDups.entrySet()) {
      List<PkbpSituation> sits = e.getValue().toList();
      if (sits.size() > 1)
        considerMergingSituations(sits);
    }
    timer().stop("dedupSits");

    Log.info("done");
  }
  
  private static List<String> generateOutputKey(PkbpEntity ei, PkbpEntity ej) {
    if (ei == ej)
      return null;
    List<String> rKey = new ArrayList<>();
    if (ei.id.compareTo(ej.id) < 0) {
      rKey.add(ei.id);
      rKey.add(ej.id);
    } else {
      rKey.add(ej.id);
      rKey.add(ei.id);
    }
    return rKey;
  }
  
  public void showEntities(int maxEntities, int maxMentionsPerEnt) {
    Log.info("showing " + entities.size() + " entities:");
    int ei = 0;
    for (PkbpEntity e : entities) {
      System.out.printf("entity(%d): %s\n", ei++, e);
      int n = e.numMentions();
      for (int i = 0; i < Math.min(n, maxMentionsPerEnt); i++)
        System.out.println(e.getMention(i));
      if (n > maxMentionsPerEnt)
        System.out.println("... and " + (n-maxMentionsPerEnt) + " more mentions");
      System.out.println();
      if (ei == maxEntities) {
        System.out.println("... and " + (entities.size()-maxEntities) + " more entities");
        System.out.println();
        break;
      }
    }
    System.out.println();
  }
  
  public void showResultsNew(int maxSits, int maxSitMentions) {
    Log.info("e.size=" + entities.size()
        + " e2s.size=" + memb_e2s.size()
        + " situations.size=" + situations.size());
    Log.info("seedEntity: " + seedEntity);

    // Iterate over all tuples of core arguments, where the seed is at least one of them.
    // For now I'll just consider pairs.
//    for (PkbpEntity e : this.memb_e2s.keySet()) {
//      List<String> rKey = generateOutputKey(seedEntity, e);
//      lskfds
//    }
    
    // Group the situations which the seed entity participates in
    // by their core arguments.
    List<PkbpSituation> seedEntSits = LL.toList(memb_e2s.get(seedEntity));
    Log.info("seedEntSits.size=" + seedEntSits.size());
    Map<List<String>, List<PkbpSituation>> byCoreArgs = new HashMap<>();
    for (PkbpSituation sit : seedEntSits) {
      assert sit.coreArguments != null && sit.coreArguments.size() == 2;
      List<String> key = generateOutputKey(sit.coreArguments.get(0), sit.coreArguments.get(1));
      if (key == null)
        continue;
      List<PkbpSituation> sits = byCoreArgs.get(key);
      if (sits == null) {
        sits = new ArrayList<>();
        byCoreArgs.put(key, sits);
      }
      sits.add(sit);
    }
    
    // Show each core argument group as a cluster.
    // You can think of each of these clusters as being the situations associated with a pair of entities.
    // TODO Sort these pairs by some salience metric.
    // TODO Limit the number of mentions/sit and sit/coreArgSet
    int ns = 0;
    for (List<String> key : byCoreArgs.keySet()) {
      List<PkbpSituation> sits = byCoreArgs.get(key);
      System.out.println(key);
      int i = 0;
      for (PkbpSituation sit : sits) {
        System.out.printf("sit(%d): %s\n", i++, sit);
        int nsm = 0;
        for (PkbpSituation.Mention sm : sit.mentions) {
          System.out.println("\t" + sm);
          nsm++;
          if (nsm >= maxSitMentions) {
            System.out.println("\tand " + (sit.mentions.size()-nsm) + " more mentions");
            break;
          }
        }
      }
      System.out.println();
      ns++;
      if (ns >= maxSits) {
        System.out.println("and " + (byCoreArgs.size()-ns) + " more situations");
        System.out.println();
      }
    }
  }

//  public void showResultsOld() {
//    Log.info("nResult=" + output.size());
//    for (Entry<List<String>, PkbpResult> e : output.entrySet()) {
//      System.out.println("key: " + e.getKey());
//      showResult(e.getValue(), false, true);
//      System.out.println();
//    }
//    System.out.println();
//  }

  public void showResult(PkbpResult r, boolean showArgsAndMentions, boolean showSitAndMentions) {
    System.out.println(r.toString());
    int i = 0;
    System.out.println("there are " + r.getArguments().size() + " args covering " + r.getEntityMentions().size() + " mentions");
    if (showArgsAndMentions) {
      for (PkbpEntity e : r.getArguments()) {
        System.out.printf("arg(%d): %s\n", i, e);
        for (PkbpEntity.Mention em : e) {
          System.out.printf("\t%s\n", em.getWordsInTokenizationWithHighlightedEntAndSit());
        }
        i++;
      }
    }
    System.out.println("there are " + r.getSituations().size() + " situations covering " + r.getSituationMentions().size() + " mentions");
    if (showSitAndMentions) {
      i = 0;
      for (PkbpSituation s : r.getSituations()) {
        System.out.printf("sit(%d): %s\n", i, s);
        for (PkbpSituation.Mention sm : s) {
          System.out.printf("\t%s\n", sm.showPredInContext());
        }
        i++;
      }
    }
  }

  public void considerMergingSituations(List<PkbpSituation> sits) {
    assert sits.size() > 1;
    throw new RuntimeException("implement me");
  }
  
  public void considerMergingEntities(List<PkbpEntity> ents) {
    assert ents.size() > 1;
    throw new RuntimeException("implement me");
  }
  
  static class HashEqLink {
    private String getCharacteristicStr() {
      if (this instanceof EntLink)
        return linkStr((EntLink) this);
      else if (this instanceof SitLink)
        return linkStr((SitLink) this);
      else throw new RuntimeException();
    }
    @Override
    public int hashCode() {
      return getCharacteristicStr().hashCode();
    }
    @Override
    public boolean equals(Object other) {
       if (other instanceof HashEqLink) {
         HashEqLink e = (HashEqLink) other;
         return getCharacteristicStr().equals(e.getCharacteristicStr());
       }
       return false;
    }
  }
  
  static class EntLink extends HashEqLink {
    PkbpEntity.Mention source;
    PkbpEntity target;
    List<Feat> score;
    boolean newEntity;
    
    PkbpNode sourceNode;
    PkbpNode targetNode;

    public EntLink(PkbpEntity.Mention source, PkbpEntity target, List<Feat> score, boolean newEntity) {
      this.source = source;
      this.target = target;
      this.score = score;
      this.newEntity = newEntity;
    }
    
    public double estimateProb() {
      double s = Feat.sum(score);
//      return 1 / (1 + Math.exp(-s/4));
      s = -2 + s/2;
      return 1 / (1 + Math.exp(-s));
    }
    
    @Override
    public String toString() {
      return String.format("(EntLink score=%.2f b/c %s source=%s target=%s)",
          Feat.sum(score), sortAndPrune(score, 5), source, target);
    }
  }

  static class SitLink extends HashEqLink {
    List<EntLink> contingentUpon;
    PkbpSituation.Mention source;
    PkbpSituation target;
    List<Feat> score;
    boolean newSituation;
    
    PkbpNode sourceNode;
    PkbpNode targetNode;

    public SitLink(List<EntLink> contingentUpon, PkbpSituation.Mention source,
        PkbpSituation target, List<Feat> score, boolean newSituation) {
      this.contingentUpon = contingentUpon;
      this.source = source;
      this.target = target;
      this.score = score;
      this.newSituation = newSituation;
    }
    
    @Override
    public String toString() {
      return String.format("(SitLink %s => %s b/c %s nContingentUpon=%d)",
          source, target, sortAndPrune(score, 4), contingentUpon.size());
    }
  }
  
  /**
   * Does not actually modify the PKB in any way, just returns a
   * list of potential changes.
   * 
   * Returned list contains one link per entMention/sitMention near the given mention.
   *
   * Things which are NOT handled by this method:
   * - link deduplication: we might for instance have two identical EntLinks
   * - updating the data structures like memb_e2s (everything within a SitLink/EntLink is fully built)
   *
   * @param searchResult
   * @param outputEntityLinks
   * @param outputSituationLinks
   */
  public Pair<List<EntLink>, List<SitLink>> proposeLinks(PkbpEntity.Mention searchResult, ComputeIdf df) {
    timer().start("proposeLinks");
    
    boolean verbose = false;

    // Higher numbers mean less linking
    // TODO Include threshold for PRUNE
    double defaultScoreOfCreatingNewEntity = -1;
//    double defaultNewSitScore = -1;

    List<EntLink> elinks = new ArrayList<>();
    List<SitLink> slinks = new ArrayList<>();
    Pair<List<EntLink>, List<SitLink>> links = new Pair<>(elinks, slinks);
    
    Communication c = searchResult.getCommunication();
    Tokenization t = searchResult.getTokenization();
    if (verbose)
      Log.info("processing " + searchResult);// + "\tc=" + c.getId() + " t=" + t.getUuid().getUuidString().substring(t.getUuid().getUuidString().length()-5));
    
    if (!seenCommToks.add(new Pair<>(c.getId(), t.getUuid().getUuidString()))) {
      // We've already processed this sentence
      if (verbose)
        Log.info("we've processed this comm+tok before, returning early");
      timer().stop("proposeLinks");
      return links;
    }
    
    DependencyParse deps = searchResult.getDeps();
    TokenTagging ner = IndexCommunications.getPreferredNerTags(t);
    
    // Extract arguments/entities
    List<Integer> entHeads = DependencySyntaxEvents.extractEntityHeads(t);
    if (!entHeads.contains(searchResult.head)) {
      if (verbose) {
        Log.info("search result head was not extracted as an argument by DependencySyntaxEvents"
            + " (nFound=" + entHeads.size() + "), adding it anyway");
        for (int h : entHeads)
          System.out.println("  found:  " + new PkbpMention(h, t, deps, c));
        System.out.println("  adding: " + searchResult);
      }
      entHeads.add(searchResult.head);
      Collections.sort(entHeads);
    }
    //Log.info("found " + entHeads.size() + " heads");
    // Extract situations
    DependencySyntaxEvents.CoverArgumentsWithPredicates se =
        new DependencySyntaxEvents.CoverArgumentsWithPredicates(c, t, deps, entHeads, df);

    // Link args/ents
    if (verbose)
      Log.info("linking " + entHeads.size() + " entMentions/args to PKB");
    // Map from entity head to the one and only link which this mention can go to
    Map<Integer, EntLink> entLinks = new HashMap<>();
    for (int entHead : entHeads) {
      Span span = IndexCommunications.nounPhraseExpand(entHead, deps);
      String nerType = ner.getTaggedTokenList().get(entHead).getTag();
      PkbpEntity.Mention m = new PkbpEntity.Mention(entHead, span, nerType, t, deps, c);
      List<EntLink> el = linkEntityMention(m, defaultScoreOfCreatingNewEntity);
      assert el.size() > 0;
      assert el.get(0).newEntity;
      for (int i = 1; i < el.size(); i++)
        assert !el.get(i).newEntity;

      if (el.size() == 1) {
        if (verbose)
          Log.info("NEW ENTITY (no choice): " + el.get(0));
        entLinks.put(entHead, el.get(0));
      } else if (Feat.sum(el.get(1).score) > 1.5) {
        if (verbose)
          Log.info("LINK ENTITY: " + el.get(1));
        entLinks.put(entHead, el.get(1));
      } else {
        // Maybe keep based on how interesting this mention is
        List<Feat> fKeep = interestingEntityMention(el.get(0).source, seedTermVec, search.getTermFrequencies(), search.getTriageSearch());
        double pKeep = 1 / (1 + Math.exp(-Feat.sum(fKeep)));
        double d = rand.nextDouble();
        if (verbose) {
          System.out.println("[maybe new] pKeep=" + pKeep + " draw=" + d
              + "\t" + sortAndPrune(fKeep, 5) + "\t" + el.get(0).source);
        }
        if (d < pKeep) {
          if (verbose)
            Log.info("NEW ENTITY (looks interesting): " + el.get(0));
          entLinks.put(entHead, el.get(0));
        } else {
          if (verbose)
            Log.info("PRUNE ENTITY (not interesting): " + el.get(0));
        }
      }
    }
    elinks.addAll(entLinks.values());
    if (verbose)
      System.out.println();
    

    // By this point, entity linking is done
    // And situation linking must respect the entity linking decisions

    
    // Link sits
    if (verbose)
      Log.info("linking " + se.getSituations().size() + " sitMentions to PKB");
    timer().start("linkingSituations");
    for (Entry<Integer, Set<String>> s : se.getSituations().entrySet()) {
      int pred = s.getKey();
      BitSet bsArgs = se.getArguments().get(pred);
      int[] args = DependencySyntaxEvents.getSetIndicesInSortedOrder(bsArgs);
      List<EntLink> entityArgs = new ArrayList<>();
      for (int a : args) {
        EntLink e = entLinks.get(a);
        if (e != null)
          entityArgs.add(e);
      }
      PkbpSituation.Mention sm = new PkbpSituation.Mention(s.getKey(), args, deps, t, c);
      for (String f : se.situation2features.get(pred)) {
        int freq = sitFeatCms.apply(f, false);
        double p = search.getTriageSearch().getFeatureScore(freq, freq);
        sm.addFeature(new Feat(f, p));
      }
      if (verbose) {
        Log.info("args=" + Arrays.toString(args) + " yields nEntityArgs=" + entityArgs.size() + " sm=" + sm);
      }

      // Generate candidate SitLinks
      List<SitLink> newSits = new ArrayList<>();
      List<SitLink> linkSits = new ArrayList<>();
      linkSituationNew(sm, entityArgs, newSits, linkSits);

      // Try LINK_SIT first, and allow n>=0 through
      Counts<Integer> linksPerPred = new Counts<>();
      for (SitLink sl : linkSits) {
        if (Feat.sum(sl.score) > 2.0) {
          slinks.add(sl);
          elinks.addAll(sl.contingentUpon);
          linksPerPred.increment(sl.source.head);
        }
      }
      // If [preds with] n=0, then consider which NEW_SIT action might be best
      Map<Integer, ArgMax<SitLink>> bestNewLinks = new HashMap<>();
      for (SitLink nl : newSits) {
        int prd = nl.source.getPred();
        assert prd == pred : "i'm confused, prd=" + prd + " pred=" + pred;
        int n = linksPerPred.getCount(pred);
        if (n == 0) {
          ArgMax<SitLink> a = bestNewLinks.get(pred);
          if (a == null) {
            a = new ArgMax<>();
            bestNewLinks.put(pred, a);
          }
          a.offer(nl, Feat.sum(nl.score));
        }
      }
      // Take the best NEW_SIT per predicate
      for (Entry<Integer, ArgMax<SitLink>> bnl : bestNewLinks.entrySet()) {
        SitLink sl = bnl.getValue().get();
        boolean ex = Feat.sum(sl.score) > 2.0;
        if (verbose)
          Log.info("considering NEW_SIT " + sl + " at pred=" + bnl.getKey() + " execute=" + ex);
        if (ex) {
          // Note these don't have core arguments yet
          slinks.add(sl);
          elinks.addAll(sl.contingentUpon);
        }
      }
    }
    timer().stop("linkingSituations");
    if (verbose) {
      System.out.println();
      Log.info("done, returning " + links.get1().size() + " ent links and " + links.get2().size() + " sit links");
      System.out.println();
    }
    timer().stop("proposeLinks");
    return links;
  }
  
  

  public KbpQuery getSeed() {
    return seed;
  }

//  public void expandEntity() throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
//    AccumuloIndex.TIMER.start("expandEntity");
//
//    // Find a SitSearchResult (essentially an EntityMention) to search off of
//    Pair<PkbpEntity, PkbpEntity.Mention> er = chooseMentionToExpand();
//    PkbpEntity e = er.get1();
//
//    ec.increment("expandEnt");
//    if (e.id.startsWith("seed"))
//      ec.increment("expandEnt/seed");
//
//    // Do the search
//    List<SitSearchResult> res = search.entityMentionSearch(er.get2());
//    history.add(new Action("SEARCH", e, er.get2(), res));
//    if (verbose)
//      Log.info("found " + res.size() + " results for " + er.get2());
//
//    for (SitSearchResult newMention : res) {
//      if (verbose) {
//        System.out.println("result: " + newMention.getWordsInTokenizationWithHighlightedEntAndSit());
//      }
//
//      ec.increment("expandEnt/searchResult");
//
//      // Add mention to entity
//      double lpLink = newMention.getScore();
//      lpLink -= xdocCorefSigmoid.first;
//      lpLink /= xdocCorefSigmoid.second;
//      double pLink = 1 / (1+Math.exp(-lpLink));
//      double tLink = rand.nextDouble();
//      if (tLink < pLink) {
//        ec.increment("expandEnt/searchResult/link");
//
//        // Link this entity
//        PkbpEntity.Mention newMention2 = new PkbpEntity.Mention(newMention);
//        e.addMention(newMention2);
//
//        // Check related mentions (do this once per doc)
//        if (knownComms.add(newMention.getCommunicationId())) {
//          List<Pair<PkbpEntity.Mention, List<Feat>>> relevant = extractRelatedEntities(er.get1(), newMention);
//          if (verbose) {
//            Log.info("found " + relevant.size() + " relevant mentions in " + newMention.getCommunicationId());
//          }
//
//          int m = 30;
//          if (relevant.size() > m) {
//            Log.info("pruning " + relevant.size() + " mentions down to " + m);
//            Collections.shuffle(relevant);
//            relevant = relevant.subList(0, m);
//          }
//
//          for (Pair<PkbpEntity.Mention, List<Feat>> rel : relevant) {
//            ec.increment("expandEnt/searchResult/relMention");
//            linkRelatedEntity(rel.get1(), rel.get2());
//          }
//        } else {
//          ec.increment("expandEnt/searchResult/dupComm");
//        }
//      }
//    }
//
//    if (verbose) {
//      Log.info("event counts:");
//      for (String k : ec.getKeysSorted())
//        System.out.printf("%-26s % 5d\n", k, ec.getCount(k));
//      System.out.println();
//      System.out.println("TIMER:");
//      System.out.println(AccumuloIndex.TIMER);
//      System.out.println();
//      Log.info("done");
//    }
//    AccumuloIndex.TIMER.stop("expandEntity");
//  }
  
  public static EntLink scoreEntLink(PkbpEntity e, PkbpEntity.Mention r, ComputeIdf df, TriageSearch ts) {
    double prior = 1;
    return scoreEntLink(e, r, df, ts, prior);
  }
  public static EntLink scoreEntLink(PkbpEntity e, PkbpEntity.Mention r, ComputeIdf df, TriageSearch ts, double prior) {
    if (prior < 0 || Double.isInfinite(prior) || Double.isNaN(prior))
      throw new IllegalArgumentException("prior=" + prior);

    boolean verboseLinking = false;

    List<Feat> fs = new ArrayList<>();
    //fs.add(new Feat("intercept", -1));

    // TODO Products of features below
    
    if (!e.containsMentionWithNer(r.getHeadNer())) {
      fs.add(new Feat("nerMismatch/" + r.getHeadNer(), -2.5));
    }

    // Cosine similarity at document level
    if (verboseLinking)
      Log.info("cosine sim for " + e.id);
    StringTermVec eCtxDoc = e.getContextDoc();
    StringTermVec rCtxDoc = r.getContextDoc();
    double tfidfCosineDoc = df.tfIdfCosineSim(eCtxDoc, rCtxDoc);
    if (tfidfCosineDoc > 0.95) {
      if (verboseLinking)
        Log.info("tfidf cosine doc, adjusting down " + tfidfCosineDoc + " => 0.75");
      tfidfCosineDoc = 0.75;   // same doc
    }
    fs.add(new Feat("tfidfCosineDoc", 0.25 * (tfidfCosineDoc - 0.5)));
    
    
    StringTermVec eCtxLocal = e.getContextLocal();
    StringTermVec rCtxLocal = r.getContextLocal();
    double tfidfCosineLocal = df.tfIdfCosineSim(eCtxLocal, rCtxLocal);
    fs.add(new Feat("tfidfCosineLocal", 0.5 * (tfidfCosineLocal - 0.5)));


    // Average triage feature similarity
    if (verboseLinking)
      Log.info("triage feats for " + e.id + " which has " + e.numMentions() + " mentions");
    Average triage = new Average.Uniform();
    double triageMax = 0;
    for (PkbpEntity.Mention ss : e) {
      if (ss.triageFeatures == null)
        throw new RuntimeException();

//      List<String> ssTf = Feat.demote(ss.triageFeatures, false);
//      List<String> rTf = Feat.demote(r.triageFeatures, false);
//      double s = ts.scoreTriageFeatureIntersectionSimilarity(ssTf, rTf, verboseLinking);
//      Pair<Double, List<Feat>> sAlt = Feat.cosineSim(ss.triageFeatures, r.triageFeatures);
//      System.out.printf("[triage debug] overlap=%.3f cosine=%.3f intersect=%s\n", s, sAlt.get1(), Feat.sortByMag(sAlt.get2(), 9999));
      Pair<Double, List<Feat>> sAlt = Feat.cosineSim(ss.triageFeatures, r.triageFeatures);
      double s = sAlt.get1();

      if (verboseLinking) {
        System.out.println("ss.triageFeats: " + ss.triageFeatures);
        System.out.println("r.triageFeats:  " + r.triageFeatures);
        System.out.println("overlap:        " + Feat.sortByMag(sAlt.get2(), 9999));
        System.out.println("score:          " + s);
        System.out.println();
      }
      triage.add(s);
      if (s > triageMax)
        triageMax = s;
    }
    fs.add(new Feat("triageAvgTfidfDoc", 20 * (0.1 + tfidfCosineDoc) * triage.getAverage()));
    fs.add(new Feat("triageMaxTfidfDoc", 10 * (0.1 + tfidfCosineDoc) * triageMax));
    fs.add(new Feat("triageAvgTfidfLocal", 20 * (0.1 + tfidfCosineLocal) * triage.getAverage()));
    fs.add(new Feat("triageMaxTfidfLocal", 10 * (0.1 + tfidfCosineLocal) * triageMax));

    // Attribute Features
    if (verboseLinking)
      Log.info("attr feats for " + e.id);
    double attrFeatScore = 0;
    ArgMax<PkbpEntity.Mention> attrFeatMax = new ArgMax<>();
    for (PkbpEntity.Mention ss : e) {
      List<Feat> x = ss.getAttrFeatures();
      List<Feat> y = r.getAttrFeatures();
      Pair<Double, List<Feat>> m = Feat.cosineSim(x, y);
      attrFeatScore += m.get1();
      attrFeatMax.offer(ss, m.get1());
      assert !Double.isNaN(attrFeatScore);
      assert Double.isFinite(attrFeatScore);
    }
    assert e.numMentions() > 0;
    double attrFeatAvg = attrFeatScore / e.numMentions();
    double attrFeatSqrt = Math.sqrt(attrFeatScore+1)-1;
    fs.add(new Feat("attrSqrtTriageAvg", 4 * (0.1 + triage.getAverage()) * attrFeatSqrt));
    fs.add(new Feat("attrMaxTriageAvg",  4 * (0.1 + triage.getAverage()) * attrFeatMax.getBestScore()));
    fs.add(new Feat("attrAvgTriageAvg", 40 * (0.1 + triage.getAverage()) * attrFeatAvg));
    fs.add(new Feat("attrSqrtTriageMax", 2 * (0.1 + triageMax) * attrFeatSqrt));
    fs.add(new Feat("attrMaxTriageMax",  2 * (0.1 + triageMax) * attrFeatMax.getBestScore()));
    fs.add(new Feat("attrAvgTriageMax", 20 * (0.1 + triageMax) * attrFeatAvg));

    if (prior != 1) {
      for (Feat f : fs)
        f.rescale("prior", prior);
    }

    double score = Feat.sum(fs);

    if (verboseLinking) {
      System.out.println("mention:  " + r.getWordsInTokenizationWithHighlightedEntAndSit());
      System.out.println("entity:   " + e);
      System.out.println("score:    " + score);
      System.out.println("features: " + fs);
      System.out.println();
    }
    return new EntLink(r, e, fs, false);
  }

  /**
   * Returns [newEntity, bestLink, secondBestLink, ...]
   * 
   * Current implementation only considers one best link, so the
   * length of the returned list is either 1 (empty PKB) or 2 [newEnt,bestLink].
   * 
   * @deprecated
   * TODO use scoreLink
   */
  private List<EntLink> linkEntityMention(PkbpEntity.Mention r, double defaultScoreOfCreatingNewEntity) {
    if (verboseLinking)
      Log.info("working on " + r);
    timer().start("linkEntityMention");
    if (r.triageFeatures == null)
      throw new IllegalArgumentException();

    if (verboseLinking) {
      Log.info("linking against " + entities.size() + " entities in PKB");
    }
    ArgMax<Pair<PkbpEntity, List<Feat>>> a = new ArgMax<>();
    TriageSearch ts = search.getTriageSearch();
    for (PkbpEntity e : entities) {
      List<Feat> fs = new ArrayList<>();
      //fs.add(new Feat("intercept", -1));

      // TODO Products of features below

      // Cosine similarity
      if (verboseLinking)
        Log.info("cosine sim for " + e.id);
      StringTermVec eDocVec = e.getContextDoc();
      StringTermVec rDocVec = r.getContextDoc();
      double tfidfCosine = getTermFrequencies().tfIdfCosineSim(eDocVec, rDocVec);
      if (tfidfCosine > 0.95) {
        if (verboseLinking)
          Log.info("tfidf cosine same doc, adjusting down " + tfidfCosine + " => 0.75");
        tfidfCosine = 0.75;   // same doc
      }
      fs.add(new Feat("tfidfCosine", 0.25 * (tfidfCosine - 0.5)));

      // Average triage feature similarity
      if (verboseLinking)
        Log.info("triage feats for " + e.id + " which has " + e.numMentions() + " mentions");
      Average triage = new Average.Uniform();
      double triageMax = 0;
      for (PkbpEntity.Mention ss : e) {
        if (ss.triageFeatures == null)
          throw new RuntimeException();

        List<String> ssTf = Feat.demote(ss.triageFeatures, false);
        List<String> rTf = Feat.demote(r.triageFeatures, false);
        double s = ts.scoreTriageFeatureIntersectionSimilarity(ssTf, rTf, verboseLinking);
        if (verboseLinking) {
          System.out.println("ss.triageFeats: " + ss.triageFeatures);
          System.out.println("r.triageFeats:  " + r.triageFeatures);
          System.out.println("score:          " + s);
          System.out.println();
        }
        triage.add(s);
        if (s > triageMax)
          triageMax = s;
      }
      fs.add(new Feat("triageFeatsAvg", 6 * triage.getAverage()));
      fs.add(new Feat("triageFeatsMax", 3 * triageMax));
      fs.add(new Feat("triageFeatsAvgTfIdf", 40 * (0.1 + tfidfCosine) * triage.getAverage()));
      fs.add(new Feat("triageFeatsMaxTfIdf", 20 * (0.1 + tfidfCosine) * triageMax));

      // Attribute Features
      if (verboseLinking)
        Log.info("attr feats for " + e.id);
      double attrFeatScore = 0;
      for (PkbpEntity.Mention ss : e) {
        String nameHeadQ = ss.getSpanString();
        List<String> attrCommQ = NNPSense.extractAttributeFeatures(null, ss.getCommunication(), nameHeadQ, nameHeadQ);
        List<String> attrTokQ = NNPSense.extractAttributeFeatures(ss.tokUuid, ss.getCommunication(), nameHeadQ, nameHeadQ);
        AttrFeatMatch afm = new AttrFeatMatch(attrCommQ, attrTokQ, r.getSpanString(), r.getTokenization(), r.getCommunication());
        attrFeatScore += Feat.avg(afm.getFeatures());
      }
      fs.add(new Feat("attrFeat", Math.sqrt(attrFeatScore+1)-1));

      double score = Feat.sum(fs);
      if (verboseLinking) {
        System.out.println("mention:  " + r.getWordsInTokenizationWithHighlightedEntAndSit());
        System.out.println("entity:   " + e);
        System.out.println("score:    " + score);
        System.out.println("features: " + fs);
        System.out.println();
      }
      a.offer(new Pair<>(e, fs), score);
    }
    Pair<PkbpEntity, List<Feat>> best = a.get();
    if (verbose) {
      if (best == null) {
        System.out.println("best link NONE!");
      } else {
        System.out.printf("best link for=%-24s is=%-24s score=%.3f %s\n",
            StringUtils.trim(r.getSpanString(), 20), StringUtils.trim(best.get1().id, 20),
            Feat.sum(best.get2()), best.get2());
      }
      System.out.println(Describe.memoryUsage());
      System.out.println();
    }

    List<EntLink> el = new ArrayList<>();

    // New entity
    List<Feat> newEntFeat = new ArrayList<>();
    newEntFeat.add(new Feat("intercept", defaultScoreOfCreatingNewEntity));
    String id = r.getSpanString().replaceAll("\\s+", "_");
    PkbpEntity newEnt = new PkbpEntity(id, r, newEntFeat);
    el.add(new EntLink(r, newEnt, newEntFeat, true));
    
    // Best link
    if (best != null) {
      List<Feat> ls = best.get2();
      if (verbose)
        System.out.println("best ls.sum=" + Feat.sum(ls) + " ls=" + ls);
      el.add(new EntLink(r, best.get1(), ls, false));
      newEntFeat.add(new Feat("competingLinkIsGood", Math.min(0, -Feat.sum(ls))));
    }
    
    // Show links for debugging
    if (verbose) {
      for (int i = 0; i < el.size(); i++)
        System.out.println(i + "\t" + el.get(i));
      System.out.println();
    }

    timer().stop("linkEntityMention");
    return el;
  }

  public static List<Feat> interestingEntityMention(PkbpEntity.Mention m, StringTermVec seedTermVec, ComputeIdf df, TriageSearch ts) {
    List<Feat> fs = new ArrayList<>();

    String ner = m.getHeadNer();
    double nerW = nerTypeExploreLogProb(ner, 1);
    fs.add(new Feat("ner=" + ner, nerW));

    double tol = 0.01;
    double prec = estimatePrecisionOfTriageFeatures(m, ts, tol);
    double freq = 1/prec;
    double k = 10;
    double p = (k + 1) / (k + freq);
    fs.add(new Feat("wordFreq", p));

    double tfidf = df.tfIdfCosineSim(seedTermVec, m.getContextDoc());
    fs.add(new Feat("tfidfWithSeed", tfidf));

    return fs;
  }

//  private Pair<PkbpEntity, PkbpEntity.Mention> chooseMentionToExpand() {
//    AccumuloIndex.timer().start("chooseMentionToExpand");
//    if (verbose)
//      Log.info("nPkbEntities=" + entities.size());
//    ChooseOne<PkbpEntity> ce = new ChooseOne<>(rand);
//    for (PkbpEntity e : entities) {
//      // This score characterizes centrality to the PKB/seed
//      double w = Math.max(0d, e.getRelevanceWeight());
//      if (verbose)
//        Log.info("considering weight=" + w + "\t" + e);
//      ce.offer(e, w);
//    }
//    PkbpEntity e = ce.choose();
//    if (verbose)
//      Log.info("chose " + e);
//
//    ec.increment("chooseMentionToExpand");
//    if (e.id.startsWith("seed"))
//      ec.increment("chooseMentionToExpand/seedEnt");
//
//    ChooseOne<PkbpEntity.Mention> cr = new ChooseOne<>(rand);
//    if (verbose)
//      Log.info("nMentions=" + e.numMentions() + " for=" + e.id);
//    for (PkbpEntity.Mention r : e) {
//      // This score measures quality of link to the entity
////      double w = Math.max(1e-8, r.getScore());
//      double w = 0;
//      if (w == 0)
//        throw new RuntimeException("impelment me");
//      if (verbose) {
//        Log.info("considering weight=" + w + "\t" + r.getEntitySpanGuess());
//        System.out.println(r.getWordsInTokenizationWithHighlightedEntAndSit());
//      }
//      cr.offer(r, w);
//    }
//    PkbpEntity.Mention r = cr.choose();
//    if (verbose) {
//      Log.info("chose " + r.getEntitySpanGuess());
//      System.out.println(r.getWordsInTokenizationWithHighlightedEntAndSit());
//    }
//    AccumuloIndex.TIMER.stop("chooseMentionToExpand");
//    return new Pair<>(e, r);
//  }

//  /**
//   * We can do three things with a relevant entity:
//   * A) link it against an entity in the PKB
//   * B) create a new Entity to put in PKB
//   * C) nothing (prune/ignore the mention)
//   * 
//   * We break this down into two steps:
//   * 1) Find the best entity and decide whether or not to link to it
//   * 2) Conditioned on (1) decide whether to prune or add to PKB based on centrality score
//   */
////  public void linkRelatedEntity(SitSearchResult r, List<Feat> relevanceReasons) {
//  public void linkRelatedEntity(PkbpEntity.Mention r, List<Feat> relevanceReasons) {
//    AccumuloIndex.TIMER.start("linkRelatedEntity");
//    if (verbose)
//      Log.info("starting relevanceReasons=" + relevanceReasons);
//    Pair<PkbpEntity, List<Feat>> link = linkEntityMentionToPkb(r);
//    PkbpEntity e = link.get1();
//    double linkingScore = Feat.sum(link.get2());
//    if (linkingScore > 4) {
//      // Link
//      if (verbose)
//        Log.info("LINKING " + r.getEntitySpanGuess() + " to " + e.id);
//      e.addMention(r);
//      history.add(new Action("LINKING", e, r));
//      ec.increment("linkRel/pkbEnt");
//    } else {
//      // Decide whether to create a new entity
//      double centralityScore = Feat.sum(relevanceReasons);
//
//      List<Feat> fNewEnt = new ArrayList<>();
//
//      fNewEnt.add(new Feat("relevance", 0.1 * centralityScore));    // only link relevant things
//      fNewEnt.add(new Feat("linkQual", -2 * Math.max(0, linkingScore)));    // if it might not be NIL, then don't make it a new entity
//      fNewEnt.add(new Feat("pkbSize", -0.05 * Math.sqrt(entity2situation.size()+1)));    // don't grow the PKB indefinitely
//      fNewEnt.add(new Feat("triagePrec", 1 * (10*estimatePrecisionOfTriageFeatures(r, 0.01) - 2.0)));  // a proxy for whether this is a common entity or not
//
//      double lpNewEnt = Feat.sum(fNewEnt);
//      double pNewEnt = 1 / (1 + Math.exp(-lpNewEnt));
//      double t = rand.nextDouble();
//
//      if (verbose) {
//        //Log.info("mention=" + r.getEntitySpanGuess() + "\tpNewEnt=" + pNewEnt + " lpNewEnt=" + lpNewEnt + " fNewEnt=" + fNewEnt);
//        Log.info(String.format("%-38s pNewEnt=%.2f draw=%.2f lpNewEnt=%+.3f fNewEnt=%s",
//            StringUtils.trim(r.getEntitySpanGuess(), 34), pNewEnt, t, lpNewEnt, fNewEnt));
//      }
//
//      if (t < pNewEnt) {
//        // New entity
//        if (verbose)
//          Log.info("NEW ENTITY " + r.getEntitySpanGuess());
//        String id = r.getEntitySpanGuess().replaceAll("\\s+", "_");
//        PkbpEntity newEnt = new PkbpEntity(id, r, relevanceReasons);
//        entities.add(newEnt);
//        history.add(new Action("NEW_ENTITY", newEnt, r, e));
//        ec.increment("linkRel/newEnt");
//      } else {
//        // Prune this mention
//        // no-op
//        if (verbose)
//          Log.info("PRUNING " + r.getEntitySpanGuess());
//        history.add(new Action("PRUNE", e, r));
//        ec.increment("linkRel/prune");
//      }
//    }
//    if (verbose)
//      Log.info("done");
//    AccumuloIndex.TIMER.stop("linkRelatedEntity");
//  }

  public static double estimatePrecisionOfTriageFeatures(PkbpEntity.Mention r, TriageSearch ts, double tolerance) {
    boolean verbose = false;
    timer().start("estimatePrecisionOfTriageFeatures");
    if (r.triageFeatures == null)
      throw new IllegalArgumentException();

    // Sort by freq
    ts.getTriageFeatureFrequencies().sortByFreqUpperBoundAsc(r.triageFeatures, Feat::getName);
    if (verbose)
      Log.info("triageFeats=" + r.triageFeatures);

//    TriageSearch ts = search.getTriageSearch();
    List<Double> c = new ArrayList<>();
    for (int i = 0; i < r.triageFeatures.size(); i++) {
      String tf = r.triageFeatures.get(i).name;
      double p = ts.getFeatureScore(tf);
      c.add(p);

//      // Exit early based on estimate of mass remaining
//      int remaining = r.triageFeatures.size()-(i+1);
//      if (p*remaining < tolerance) {
//        Log.info("breaking1 tol=" + tolerance + " p=" + p + " remaining=" + remaining + " i=" + i);
//        break;
//      }
//      if (i >= 8) {
//        Log.info("breaking2 i=" + i);
//        break;
//      }
    }

    // Bias towards most specific features
    Collections.sort(c);
    Collections.reverse(c);
    //Average a = new Average.Exponential(0.5);
    double p = 0;
    //for (int i = 0; i < 4 && i < c.size(); i++) {
    for (int i = 0; i < c.size(); i++) {
      //a.add(c.get(i));
      p += c.get(i);
    }
    //double p = a.getAverage();
    if (verbose)
      Log.info("p=" + p + " c=" + c + " ent=" + r.getSpanString() + " feats=" + r.triageFeatures);
    timer().stop("estimatePrecisionOfTriageFeatures");
    return p;
  }

  /**
   * Looks in the communication around newMention for other entities which might be relevant
   * to the given entity in the context of the seed query.
   * 
   * Each returned pair is a relevant entity and a list of reasons why it is relevant.
   * 
   * This method doesn't modify the PKB/state of this instance.
   */
  public List<Pair<PkbpEntity.Mention, List<Feat>>> extractRelatedEntities(PkbpEntity relevantTo, SitSearchResult newMention) {
    timer().start("extractRelatedEntities");
    List<Pair<PkbpEntity.Mention, List<Feat>>> out = new ArrayList<>();
    ec.increment("exRel");

    // Compute tf-idf similarity between this comm and the seed
    Communication comm = newMention.getCommunication();
    boolean normalizeNumbers = false;
    StringTermVec commVec = new StringTermVec(comm, normalizeNumbers);
    double tfidfWithSeed = getTermFrequencies().tfIdfCosineSim(seedTermVec, commVec);

    // Index tokenizations by their id
    Map<String, Tokenization> tokMap = new HashMap<>();
    for (Tokenization tok : new TokenizationIter(comm)) {
      Object old = tokMap.put(tok.getUuid().getUuidString(), tok);
      assert old == null;
    }
    // Determine NER types
    new AddNerTypeToEntityMentions(comm);
    // See if each EntityMention is relevant
    for (EntityMention em : IndexCommunications.getEntityMentions(comm)) {

      String nerType = em.getEntityType();
      assert nerType != null;

      String tokUuid = em.getTokens().getTokenizationId().getUuidString();
      Tokenization toks = tokMap.get(tokUuid);
      assert toks != null;
      
      DependencyParse deps = IndexCommunications.getPreferredDependencyParse(toks);

      // Create a new SitSearchResult which could be relevant and fill in known data
      int headIdx = em.getTokens().getAnchorTokenIndex();
      assert headIdx >= 0;
      Span span = IndexCommunications.nounPhraseExpand(headIdx, deps);
      PkbpEntity.Mention rel = new PkbpEntity.Mention(headIdx, span, nerType, toks, deps, comm);


      List<Feat> relevanceReasons = new ArrayList<>();

      // Long mentions are costly to do search on...
      int nt = em.getTokens().getTokenIndexList().size();
      relevanceReasons.add(new Feat("mentionTooLong", -Math.sqrt(nt+1)));

      // To be relevant, the new mention must indeed be coreferent with the entity searched for
      relevanceReasons.add(new Feat("searchQuality", Math.sqrt(newMention.getScore())));

      // Certain NER types are more or less interesting
      relevanceReasons.add(new Feat("nerType", nerTypeExploreLogProb(nerType, relatedEntitySigmoid.second)));

      // Nominal mentions are dis-preferred due to difficulty in doing xdoc coref for them, etc
      if (!hasNNP(em.getTokens(), tokMap))
        relevanceReasons.add(new Feat("noNNP", -3));
      if (hasV(em.getTokens(), tokMap))
        relevanceReasons.add(new Feat("hasV", -2));

      // Having attribute features like "PERSON-nn-Dr." is good, discriminating features help coref
//      rel.attrCommFeatures = Feat.promote(1, NNPSense.extractAttributeFeatures(null, comm, head.split("\\s+")));
//      rel.attrTokFeatures = Feat.promote(1, NNPSense.extractAttributeFeatures(tokUuid, comm, head.split("\\s+")));
//      int nAttrFeat = rel.getAttrTokFeatures().size();
      int nAttrFeat = rel.getAttrFeatures().size();
      relevanceReasons.add(new Feat("nAttrFeat", (Math.sqrt(nAttrFeat+1d)-1) * rewardForAttrFeats));

      // Reward for this comm looking like the seed
      relevanceReasons.add(new Feat("tfidfWithSeed", 8 * (tfidfWithSeed - 0.5)));

      // TODO Add this back
      //      GigawordId sourceGW = new GigawordId(measureRelevanceAgainst.getCommunicationId());
      //      GigawordId targetGW = new GigawordId(r.getCommunicationId());
      //        // It is good if this mention appears in the same sentence as a known mention
      //        if (!tokUuid.equals(r.getTokenization().getUuid().getUuidString()))
      //          relevanceReasons.add(new Feat("notSameSent", -5));
      //
      //        // It is good if this story came out at a time near a known mention/story
      //        Integer days = GigawordId.daysBetween(sourceGW, targetGW);
      //        if (days != null) {
      //          days = Math.abs(days);
      //          double tl = days / rewardForTemporalLocalityMuSigma.second;
      //          assert tl >= 0 : "tl=" + tl;
      //          tl = rewardForTemporalLocalityMuSigma.first * Math.exp(-(tl*tl));
      //          assert tl >= 0 : "tl=" + tl;
      //          relevanceReasons.add(new Feat("temporalLocality", tl));
      //        }

      // Decide to keep or not
      double lp = Feat.sum(relevanceReasons) - relatedEntitySigmoid.first;
      lp /= relatedEntitySigmoid.second;
      double t = 1 / (1+Math.exp(-lp));
      double d = rand.nextDouble();
      if (verbose) {
        Log.info(String.format(
            "keep related entity? ner=%s head=%s relatedEntitySigmoidOffset=%s thresh=%.3f draw=%.3f keep=%s reasons=%s",
            nerType, rel.getSpanString(), relatedEntitySigmoid, t, d, d<t, relevanceReasons));
      }
      ec.increment("exRel/mention");
      if (d < t) {
        ec.increment("exRel/mention/kept");
        out.add(new Pair<>(rel, relevanceReasons));
      }
    }
    timer().stop("extractRelatedEntities");
    return out;
  }

  private boolean hasNNP(TokenRefSequence trs, Map<String, Tokenization> tokMap) {
    Tokenization toks = tokMap.get(trs.getTokenizationId().getUuidString());
    TokenTagging pos = IndexCommunications.getPreferredPosTags(toks);
    for (int t : trs.getTokenIndexList()) {
      String p = pos.getTaggedTokenList().get(t).getTag();
      if (p.equalsIgnoreCase("NNP"))
        return true;
    }
    return false;
  }

  private boolean hasV(TokenRefSequence trs, Map<String, Tokenization> tokMap) {
    Tokenization toks = tokMap.get(trs.getTokenizationId().getUuidString());
    TokenTagging pos = IndexCommunications.getPreferredPosTags(toks);
    for (int t : trs.getTokenIndexList()) {
      String p = pos.getTaggedTokenList().get(t).getTag();
      if (p.toUpperCase().startsWith("V"))
        return true;
    }
    return false;
  }

  private static double nerTypeExploreLogProb(String nerType, double stdDev) {
    switch (nerType) {
    case "PER":
    case "PERSON":
      return 0.5 * stdDev;
    case "GPE":
    case "ORG":
    case "ORGANIZATION":
      return 0.25 * stdDev;
    case "LOC":
    case "LOCATION":
      return 0.25 * stdDev;
    case "MISC":
      return 0.1 * stdDev;
    default:
      return -4.0 * stdDev;
    }
  }

  public static String showWithTag(Tokenization t, Span mark, String tag) {
    return showWithTag(t, mark, "<" + tag + ">", "</" + tag + ">");
  }

  public static String showWithTag(Tokenization t, Span mark, String openTag, String closeTag) {
    StringBuilder sb = new StringBuilder();
    int n = t.getTokenList().getTokenListSize();
    for (int i = 0; i < n; i++) {
      String w = t.getTokenList().getTokenList().get(i).getText();
      if (i > 0)
        sb.append(' ');
      if (i == mark.start)
        sb.append(openTag);
      sb.append(w);
      if (i == mark.end-1)
        sb.append(closeTag);
    }
    return sb.toString();
  }
  

  /**
   * TODO optional memoization
   * @deprecated use {@link CountMinSketch} generated by {@link DependencySyntaxEvents}
   */
  static class RedisSitFeatFrequency implements AutoCloseable {
    private Jedis j;
    public Counts<String> ec;

    public Beam<String> topK;
    private Map<String, Integer> topKTable;

    /**
     * @param topK maintain this many of the most frequent features in an LRU cache. Set to <=0 to disable
     */
    public RedisSitFeatFrequency(String host, int port, int topK) {
      ec = new Counts<>();
      j = new Jedis(host, port);
      j.connect();
      Log.info("connected via jedis host=" + host + " port=" + port);
      
      if (topK > 0) {
        this.topK = Beam.getMostEfficientImpl(topK);
        this.topKTable = new HashMap<>();
      }
    }
    
    private void store(String feature, int freq) {
      if (topK.push(feature, freq)) {
        ec.increment("cache/clear");
        topKTable.clear();
      }
    }
    
    private Integer freqMemo(String feature) {
      assert topK != null;
      if (topKTable.isEmpty()) {
        ec.increment("cache/build");
        Iterator<Beam.Item<String>> iter = topK.itemIterator();
        while (iter.hasNext()) {
          Beam.Item<String> i = iter.next();
          Object old = topKTable.put(i.getItem(), (int) i.getScore());
          assert old == null;
        }
      }
      return topKTable.get(feature);
    }

    public int frequency(String feature) {
      if (topK != null) {
        Integer v = freqMemo(feature);
        if (v != null) {
          ec.increment("cache/hit");
          return v;
        }
        ec.increment("cache/miss");
      }

      String c = j.get(feature);
      if (c == null) {
//        System.out.println("unknown feature: " + feature);
        ec.increment("freq/fail");
        return 0;
      }
      ec.increment("freq/succ");
      int v = Integer.parseUnsignedInt(c);
      if (topK != null && v > 3)
        store(feature, v);
      return v;
    }

    @Override
    public void close() throws Exception {
      Log.info("closing");
      j.close();
    }
    
    private static List<String> toSortedList(Iterable<String> i) {
      List<String> l = new ArrayList<>();
      for (String s : i)
        l.add(s);
      Collections.sort(l);
      return l;
    }

    public List<Feat> getFrequenciesL(Iterable<String> feats) {
      Set<String> seen = new HashSet<>();
      List<Feat> l = new ArrayList<>();
      for (String feat : feats) {
        if (seen.add(feat)) {
          double f = frequency(feat);
          double k = 10;
          double p = (k + 1) / (k + f);
          l.add(new Feat(feat, p));
        }
      }
      return l;
    }

    public Map<String, Double> getFrequenciesM(Iterable<String> feats) {
      Map<String, Double> m = new HashMap<>();
      for (Feat f : getFrequenciesL(feats)) {
        Object old = m.put(f.name, f.weight);
        assert old == null;
      }
      return m;
    }
    
    public double getScore(String s) {
      double f = frequency(s);
      double k = 10;
      double p = (k + 1) / (k + f);
      return p;
    }
    
    public List<Feat> similarity(Iterable<String> sitFeat, Iterable<String> mentionFeat) {
      ec.increment("sim");

//      Set<String> union = new HashSet<>();
//      Set<String> intersect = new HashSet<>();
//      for (String a : sitFeat) {
//        union.add(a);
//        intersect.add(a);
//      }
      
      Set<String> l = new HashSet<>();
      for (String s : sitFeat) l.add(s);
      
      Set<String> r = new HashSet<>();
      for (String s : mentionFeat) r.add(s);
      
      Set<String> intersect = new HashSet<>();
      intersect.addAll(l);
      intersect.retainAll(r);
      
      Set<String> union = new HashSet<>();
      union.addAll(l);
      union.addAll(r);
      
      List<Feat> feats = new ArrayList<>();
      List<Feat> i = new ArrayList<>();
//      double n = 0, d = 0.01;
      for (String s : union) {
//        System.out.println("f=" + f + " p=" + p + " feat=" + s);
        double f = frequency(s);
        double k = 10;
        double p = (k + 1) / (k + f);

        if (f < 3)
          continue;
        
//        d += p;
        if (intersect.contains(s)) {
//          n += p;
          i.add(new Feat(s, p));
          feats.add(new Feat("intersect/" + s, p));
        } else {
          feats.add(new Feat("union/" + s, -p));
        }
      }
//      double x = n/d;
      
//      System.out.println();
//      System.out.println("sitFeat:     " + toSortedList(sitFeat));
//      System.out.println("mentionFeat: " + toSortedList(mentionFeat));
////      System.out.println("union:       " + toSortedList(union));
//      System.out.println("intersect:   " + i);//toSortedList(intersect));
//      System.out.println("n:           " + n);
//      System.out.println("d:           " + d);
//      System.out.println("x:           " + x);
//      System.out.println();

      return feats;
    }
  }

  
  /**
   * @deprecated use the {@link RedisSitFeatFrequency} version of this function
   */
  public static double similarity(Set<String> sitFeat, Set<String> sitMentionFeats) {
    Set<String> i = new HashSet<>();
    i.addAll(sitMentionFeats);
    i.retainAll(sitFeat);
    // TODO Weight by how common these features are
    Set<String> u = new HashSet<>();
    u.addAll(sitFeat);
    u.addAll(sitMentionFeats);
    return i.size() / (u.size()+1d);
  }
}
