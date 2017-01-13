package edu.jhu.hlt.ikbp.tac;

import static edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat.sortAndPrune;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.AttrFeatMatch;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.ComputeIdf;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.KbpSearching;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.StringTermVec;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.TriageSearch;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SitSearchResult;
import edu.jhu.hlt.ikbp.tac.PkbpEntity.Mention;
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
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.CountMinSketch;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;
import edu.jhu.util.TokenizationIter;
import redis.clients.jedis.Jedis;

/**
 * Search outwards starting with a {@link KbpQuery} seed.
 */
public class PkbpSearching implements Serializable {
  private static final long serialVersionUID = 3891008944720961213L;

  public static final MultiTimer TIMER = AccumuloIndex.TIMER;
  
//  public void playback(ExperimentProperties config) throws Exception {
//    Log.info("playing back");
//    
//    // (for now) For every SitSearchResult coming from arg3 of a SEARCH,
//    // detect entity heads in that tokenization and run DependencySyntaxEvents
//    // run parsey first?
//    List<SitSearchResult> res = new ArrayList<>();
//    for (Action a : history) {
//      if (a.type.equals("SEARCH")) {
//        res.addAll((List<SitSearchResult>) a.arguments.get(2));
//      }
//    }
//    Log.info("collected " + res.size() + " SitSearchResults");
//    AccumuloIndex.dedupCommunications(res);
//    
//    // Add parsey deps if desired/needed
//    File parseyCacheDir = config.getFile("parseyCacheDir", null);
//    if (parseyCacheDir != null) {
//      Log.info("adding parsey parses with parseyCacheDir=" + parseyCacheDir.getPath());
//      List<Communication> comms = AccumuloIndex.extractCommunications(res);
//      OfflineBatchParseyAnnotator p = new OfflineBatchParseyAnnotator(
//          parseyCacheDir, OfflineBatchParseyAnnotator.PARSEY_SCRIPT_LAPTOP);
//      Map<String, Communication> anno = new HashMap<>();
//      for (Communication c : comms) {
//        Communication a = p.annotate(c);
//        Object old = anno.put(a.getId(), a);
//        assert old == null;
//      }
//      Log.info("collected " + anno.size() + " parsey-annotated comms");
//      for (SitSearchResult r : res) {
//        Communication a = anno.get(r.getCommunicationId());
//        assert a != null;
//        r.setCommunication(a);
//      }
//      Log.info("done with parsey");
//    }
//    
//    // Run event extraction on all of the tokenization/SitSearchResults.
//    // At the same time do a mock PKB population run, filling in events
//    int topK = 1_000_000;
//    try (RedisSitFeatFrequency rsitf = new RedisSitFeatFrequency("localhost", 8567, topK)) {
//      List<PkbpSituation> events = new ArrayList<>();
//      for (SitSearchResult r : res) {
//        extractAndLinkSituations(r, events, rsitf);
//      }
//    }
//  }

//  /**
//   * TODO Modify this so that instead of accepting a FULL LIST of all situations,
//   * 1) Do the entity linking against PKB entities
//   * 2) For every entityMention->entity link, consider situationMention->situation link s.t. entity in situation.args
//   * 
//   * @deprecated this goes up to playback, see outerLoop
//   */
//  public void extractAndLinkSituations(SitSearchResult r, List<PkbpSituation> knownSituations, RedisSitFeatFrequency rsitf) {
//    
//    // 1) Extract situation mentions and their entity arguments in the given Tokenization
//    System.out.println("considering SitSearchResult: " + r.getWordsInTokenizationWithHighlightedEntAndSit());
//    DependencyParse deps = IndexCommunications.getPreferredDependencyParse(r.getTokenization());
//    List<Integer> entities = DependencySyntaxEvents.extractEntityHeads(r.getTokenization());
//    if (entities.size() < 2) {
//      System.out.println("skipping b/c to few entities");
//      return;
//    }
//    for (int e : entities) {
//      Span es = IndexCommunications.nounPhraseExpand(e, deps);
//      String s = AccumuloIndex.words(es, r.getTokenization());
//      System.out.println("\tentity at=" + e + "\tent=" + s);
//    }
//    System.out.println();
//
//    CoNLLX.VERBOSE = false;
//    DependencySyntaxEvents.CoverArgumentsWithPredicates sitEx =
//        new DependencySyntaxEvents.CoverArgumentsWithPredicates(
//            r.getCommunication(), r.getTokenization(), deps, entities);
//
//    Map<Integer, Set<String>> sit2feat = sitEx.getSituations();
//    for (int s : sit2feat.keySet())
//      System.out.println("\tsituation at=" + s + " sit=" + r.getTokenization().getTokenList().getTokenList().get(s).getText());
//    System.out.println();
//
//
//    // 2) For each situation, try to link it to a known situation
//    // for now we are using a list of situations as our PKB over situations
//    for (Entry<Integer, Set<String>> e : sit2feat.entrySet()) {
//      // laptop: ssh -fNL 8567:test2:7379 test2b
//      // laptop: redis-cli -p 8567    # for some reason -h localhost makes it not work
//      int pred = e.getKey();
//      Set<String> sf = e.getValue();
//      System.out.println("considering situation at=" + pred
//          + " word=" + r.getTokenization().getTokenList().getTokenList().get(pred).getText());
//      if (knownSituations.isEmpty()) {
//        // Just add the first event, no questions asked
//        PkbpSituation.Mention sm = PkbpSituation.Mention.convert(pred, sitEx, rsitf::getScore);
//        knownSituations.add(new PkbpSituation(sm));
//        continue;
//      }
//
//      Map<String, Double> sf2f = rsitf.getFrequenciesM(sf);
//      PkbpSituation.Mention cur = PkbpSituation.Mention.convert(pred, sitEx, rsitf::getScore);
//
//      ArgMax<Pair<PkbpSituation, List<Feat>>> bestSit = new ArgMax<>();
//      for (int i = 0; i < knownSituations.size(); i++) {
//        List<Feat> sim = knownSituations.get(i).similarity(sf2f);
//        bestSit.offer(new Pair<>(knownSituations.get(i), sim), Feat.sum(sim));
//        
//        System.out.println("entity.mentions {");
//        for (PkbpSituation.Mention sm : knownSituations.get(i).mentions) {
//          System.out.println("  =>" + sm.showPredInContext());
//          System.out.println("      " + sortAndPrune(sm.getFeatures(), 5));
//        }
//        System.out.println("}");
//        System.out.println("cur.mention: " + cur.showPredInContext());
//        System.out.println("sim: " + sortAndPrune(sim, 5));
//        System.out.println();
//      }
//
//      System.out.println();
//      Pair<PkbpSituation, List<Feat>> best = bestSit.get();
//      System.out.println("best entity.mentions {");
//      for (PkbpSituation.Mention sm : best.get1().mentions) {
//        System.out.println("  =>" + sm.showPredInContext());
//        System.out.println("      " + sortAndPrune(sm.getFeatures(), 5));
//      }
//      System.out.println("}");
//      System.out.println("best cur.mention: " + cur.showPredInContext());
//      System.out.println("best sim: " + sortAndPrune(best.get2(), 5));
//      System.out.println("best score: " + Feat.sum(best.get2()));
//      System.out.println();
//
//      if (bestSit.getBestScore() >= 1.5) {
//        System.out.println("LINK_SITUATION");
//        best.get1().addMention(cur);
//      } else {
//        System.out.println("NEW_SITUATION");
//        knownSituations.add(new PkbpSituation(cur));
//      }
//      System.out.println();
//      System.out.println();
//    }
//    System.out.println();
//  }
  
  public List<Feat> scoreSitLink(PkbpSituation sit, PkbpSituation.Mention mention) {
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
          PkbpSituation target = new PkbpSituation(mention);
          target.addCoreArgument(ei.target);
          target.addCoreArgument(ej.target);
          List<EntLink> contingentUpon = Arrays.asList(ei, ej);
          List<Feat> score = new ArrayList<>();
          score.addAll(interestingEntityMention(ei.source));
          score.addAll(interestingEntityMention(ej.source));
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
    PkbpSituation newSit = new PkbpSituation(mention);
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


  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);

//    // Playback a result which was computed and then serialized
//    File playbackPkbpFile = config.getFile("playbackPkbpFile", null);
//    if (playbackPkbpFile != null) {
//      Log.info("deserializing playbackPkbpFile=" + playbackPkbpFile);
//      PkbpSearching ps = (PkbpSearching) FileUtil.deserialize(playbackPkbpFile);
//      ps.playback(config);
//      Log.info("done");
//      return;
//    }
    
    Random rand = config.getRandom();
    KbpSearching ks = null;
    PkbpSearching ps;

    String sfName = config.getString("slotFillQueries", "sf13+sf14");
    List<KbpQuery> queries = TacKbp.getKbpSfQueries(sfName);

    int maxResultsPerSearch = config.getInt("maxResultsPerQuery", 300);
    double maxToksPruningSafetyRatio = config.getDouble("maxToksPruningSafetyRatio", 2d);

    int stepsPerQuery = config.getInt("stepsPerQuery", 3);
    double seedWeight = config.getDouble("seedWeight", 30);
    Log.info("stepsPerQuery=" + stepsPerQuery + " seedWeight=" + seedWeight);
    
    File fceFile = config.getExistingFile("featureCardinalityEstimator");
    Log.info("loading FeatureCardinalityEstimator.New from=" + fceFile.getPath());
    FeatureCardinalityEstimator.New fce =
        (FeatureCardinalityEstimator.New) FileUtil.deserialize(fceFile);

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
        
//        // Temporary fix
//        ps.termFreq = new ComputeIdf(config.getExistingFile("wordDocFreq")); // e.g. data/idf/word-df.small.tsv

        ps.restoreCommsInInitialResultsCache();
        ps.clearSeenCommToks();

        File inQuery = new File(inputDir, seed.id + ".query.jser.gz");
        seed = (KbpQuery) FileUtil.deserialize(inQuery);
        ps.setSeed(seed, seedWeight);
      } else {
        // Run on the grid w/ access to accumulo
        if (ks == null) {
          ks = new KbpSearching(config, fce, maxResultsPerSearch, maxToksPruningSafetyRatio, new HashMap<>());
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
        TIMER.start("serialize/PkbpSearching");
        File outSearch = new File(outputDir, seed.id + ".search.jser.gz");
        Log.info("saving PKB to " + outSearch.getPath());
        ps.dropCommsFromInitialResultsCache();
        FileUtil.serialize(ps, outSearch);
        TIMER.stop("serialize/PkbpSearching");

        // KbpQuery
        TIMER.start("serialize/KbpQuery");
        File outQuery = new File(outputDir, seed.id + ".query.jser.gz");
        Log.info("saving seed/query to " + outQuery.getPath());
        FileUtil.serialize(seed, outQuery);
        TIMER.stop("serialize/KbpQuery");
        
        // KbpSearching
        // NOTE: Only one of these, rather than one per query.
        // It grows upon every query (stores more comms, feature freqs, etc).
        TIMER.start("serialize/KbpSearching");
        File outKs = oneKsPerQuery
            ? new File(outputDir, "KbpSearching." + seed.id + ".jser.gz")
            : new File(outputDir, "KbpSearching.jser.gz");
        Log.info("saving KbpSearching (comm/featFreq/etc cache) to " + outKs.getPath());
        FileUtil.serialize(ks, outKs);
        TIMER.stop("serialize/KbpSearching");
      }

      if (clearCachesEveryQuery)
        ks.clearCaches();
    }
  }

//  /** SEARCH, LINK, PRUNE, or NEW_ENTITY */
//  static class Action implements Serializable {
//    private static final long serialVersionUID = 1057136776248973712L;
//
//    String type;
//    List<Object> arguments;
//
//    public Action(String type, Object... args) {
//      this.type = type;
//      this.arguments = Arrays.asList(args);
//    }
//  }

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
    setSeed(seed, seedWeight);
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
  
  public void setSeed(KbpQuery seed, double seedWeight) {
    Log.info("seedWeight=" + seedWeight + " seed=" + seed);
    this.seed = seed;
    this.seedTermVec = new StringTermVec(seed.sourceComm);
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
    canonical2.getContext();
    canonical2.getAttrCommFeatures();
    canonical2.getAttrTokFeatures();
    assert canonical2.triageFeatures != null;
    
    Pair<List<EntLink>, List<SitLink>> p = proposeLinks(canonical2);
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
    best.target.addMention(best.source);
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
  
  private File getInitialResultsDiskCache(PkbpResult searchFor) {
    return new File(initialResultsDiskCacheDir, searchFor.id + ".jser");
  }

  private List<SitSearchResult> initialSearch(PkbpResult searchFor) throws Exception {
    List<SitSearchResult> mentions = null;
    if (initialResultsCache != null && (mentions = initialResultsCache.get(searchFor.id)) != null) {
      return mentions;
    }

    // Build a view of all the entities in this result
    TriageSearch ts = search.getTriageSearch();
    StringTermVec docContext = new StringTermVec();
    Set<String> triageFeats = new HashSet<>();
    List<Feat> attrComm = new ArrayList<>();
    List<Feat> attrTok = new ArrayList<>();
    int nm = 0;
    for (PkbpEntity e : searchFor.getArguments()) {
      for (PkbpEntity.Mention m : e) {
        nm++;
        docContext.add(m.getContext());
        for (Feat ft : m.triageFeatures)
          triageFeats.add(ft.name);
        attrComm.addAll(m.getAttrCommFeatures());
        attrTok.addAll(m.getAttrTokFeatures());
      }
    }
    assert nm > 0;

    // Perform triage search
    mentions = ts.search(new ArrayList<>(triageFeats), docContext, getTermFrequencies());
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
    
    // Build a view of all the entities in this result
    StringTermVec docContext = new StringTermVec();
    Set<String> triageFeats = new HashSet<>();
    List<Feat> attrComm = new ArrayList<>();
    List<Feat> attrTok = new ArrayList<>();
    for (PkbpEntity e : searchFor.getArguments()) {
      for (PkbpEntity.Mention m : e) {
        docContext.add(m.getContext());
        for (Feat ft : m.triageFeatures)
          triageFeats.add(ft.name);
        attrComm.addAll(m.getAttrCommFeatures());
        attrTok.addAll(m.getAttrTokFeatures());
      }
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
    boolean dedup = true;
    List<String> attrCommQ = Feat.demote(attrComm, dedup);
    List<String> attrTokQ = Feat.demote(attrTok, dedup);
    if (verbose)
      Log.info("doing attribute feature reranking, attrTokQ=" + attrTokQ);
    AccumuloIndex.attrFeatureReranking(attrCommQ, attrTokQ, mentions);

    // Scan the results
    Map<PkbpEntity.Mention, LL<PkbpEntity>> entDups = new HashMap<>();
    Map<PkbpSituation.Mention, LL<PkbpSituation>> sitDups = new HashMap<>();
    for (SitSearchResult s : mentions) {
      PkbpEntity.Mention em = new PkbpEntity.Mention(s);
      TokenTagging ner = IndexCommunications.getPreferredNerTags(em.getTokenization());
      em.nerType = ner.getTaggedTokenList().get(em.head).getTag();
      Pair<List<EntLink>, List<SitLink>> x = proposeLinks(em);
      
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
              el.target.addMention(el.source);
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
          el.target.addMention(el.source);
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
//      TIMER.start("linkResults");
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
//      TIMER.stop("linkResults");

      System.out.println();
      System.out.println("TIMER:");
      System.out.println(TIMER);
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
    TIMER.start("dedupEnts");
    for (Entry<Mention, LL<PkbpEntity>> e : entDups.entrySet()) {
      List<PkbpEntity> ents = e.getValue().toList();
      if (ents.size() > 1)
        considerMergingEntities(ents);
    }
    TIMER.stop("dedupEnts");
    TIMER.start("dedupSits");
    for (Entry<PkbpSituation.Mention, LL<PkbpSituation>> e : sitDups.entrySet()) {
      List<PkbpSituation> sits = e.getValue().toList();
      if (sits.size() > 1)
        considerMergingSituations(sits);
    }
    TIMER.stop("dedupSits");

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

    public EntLink(PkbpEntity.Mention source, PkbpEntity target, List<Feat> score, boolean newEntity) {
      this.source = source;
      this.target = target;
      this.score = score;
      this.newEntity = newEntity;
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
  public Pair<List<EntLink>, List<SitLink>> proposeLinks(PkbpEntity.Mention searchResult) {
    TIMER.start("proposeLinks");
    
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
      TIMER.stop("proposeLinks");
      return links;
    }
    
    if (searchResult.deps == null)
      searchResult.deps = IndexCommunications.getPreferredDependencyParse(t);
    DependencyParse deps = searchResult.deps;

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
        new DependencySyntaxEvents.CoverArgumentsWithPredicates(c, t, deps, entHeads);

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
        List<Feat> fKeep = interestingEntityMention(el.get(0).source);
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
    TIMER.start("linkingSituations");
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
    TIMER.stop("linkingSituations");
    if (verbose) {
      System.out.println();
      Log.info("done, returning " + links.get1().size() + " ent links and " + links.get2().size() + " sit links");
      System.out.println();
    }
    TIMER.stop("proposeLinks");
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

  /**
   * Returns [newEntity, bestLink, secondBestLink, ...]
   * 
   * Current implementation only considers one best link, so the
   * length of the returned list is either 1 (empty PKB) or 2 [newEnt,bestLink].
   */
  private List<EntLink> linkEntityMention(PkbpEntity.Mention r, double defaultScoreOfCreatingNewEntity) {
    if (verboseLinking)
      Log.info("working on " + r);
    TIMER.start("linkEntityMention");
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
      StringTermVec eDocVec = e.getDocVec();
      StringTermVec rDocVec = new StringTermVec(r.getCommunication());
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
        String nameHeadQ = ss.getEntityHeadGuess();
        List<String> attrCommQ = NNPSense.extractAttributeFeatures(null, ss.getCommunication(), nameHeadQ, nameHeadQ);
        List<String> attrTokQ = NNPSense.extractAttributeFeatures(ss.tokUuid, ss.getCommunication(), nameHeadQ, nameHeadQ);
        AttrFeatMatch afm = new AttrFeatMatch(attrCommQ, attrTokQ, r.getEntityHeadGuess(), r.getTokenization(), r.getCommunication());
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
            StringUtils.trim(r.getEntitySpanGuess(), 20), StringUtils.trim(best.get1().id, 20),
            Feat.sum(best.get2()), best.get2());
      }
      System.out.println(Describe.memoryUsage());
      System.out.println();
    }

    List<EntLink> el = new ArrayList<>();

    // New entity
    List<Feat> newEntFeat = new ArrayList<>();
    newEntFeat.add(new Feat("intercept", defaultScoreOfCreatingNewEntity));
    String id = r.getEntitySpanGuess().replaceAll("\\s+", "_");
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

    TIMER.stop("linkEntityMention");
    return el;
  }

  public List<Feat> interestingEntityMention(PkbpEntity.Mention m) {
    List<Feat> fs = new ArrayList<>();

    String ner = m.getHeadNer();
    double nerW = nerTypeExploreLogProb(ner, 1d);
    fs.add(new Feat("ner=" + ner, nerW));

    double tol = 0.01;
    double prec = estimatePrecisionOfTriageFeatures(m, tol);
    double freq = 1/prec;
    double k = 10;
    double p = (k + 1) / (k + freq);
    fs.add(new Feat("wordFreq", p));

    double tfidf = getTermFrequencies().tfIdfCosineSim(seedTermVec, m.getContext());
    fs.add(new Feat("tfidfWithSeed", 5 * tfidf));

    return fs;
  }

//  private Pair<PkbpEntity, PkbpEntity.Mention> chooseMentionToExpand() {
//    AccumuloIndex.TIMER.start("chooseMentionToExpand");
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

  public double estimatePrecisionOfTriageFeatures(PkbpEntity.Mention r, double tolerance) {
    TIMER.start("estimatePrecisionOfTriageFeatures");
    if (r.triageFeatures == null)
      throw new IllegalArgumentException();

    // Sort by freq
    search.triageFeatureCardinalityEstimator.sortByFreqUpperBoundAsc(r.triageFeatures, f -> f.name);
    if (verbose)
      Log.info("triageFeats=" + r.triageFeatures);

    TriageSearch ts = search.getTriageSearch();
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
      Log.info("p=" + p + " c=" + c + " ent=" + r.getEntitySpanGuess() + " feats=" + r.triageFeatures);
    TIMER.stop("estimatePrecisionOfTriageFeatures");
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
    TIMER.start("extractRelatedEntities");
    List<Pair<PkbpEntity.Mention, List<Feat>>> out = new ArrayList<>();
    ec.increment("exRel");

    // Compute tf-idf similarity between this comm and the seed
    Communication comm = newMention.getCommunication();
    StringTermVec commVec = new StringTermVec(comm);
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
      int nAttrFeat = rel.getAttrTokFeatures().size();
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
            nerType, rel.getEntityHeadGuess(), relatedEntitySigmoid, t, d, d<t, relevanceReasons));
      }
      ec.increment("exRel/mention");
      if (d < t) {
        ec.increment("exRel/mention/kept");
        out.add(new Pair<>(rel, relevanceReasons));
      }
    }
    TIMER.stop("extractRelatedEntities");
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

  private double nerTypeExploreLogProb(String nerType, double stdDev) {
    switch (nerType) {
    case "PER":
    case "PERSON":
      return 0.5 * stdDev;
    case "GPE":
    case "ORG":
    case "ORGANIZATION":
      return 0.0 * stdDev;
    case "MISC":
    case "LOC":
    case "LOCATION":
      return -1.0 * stdDev;
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
