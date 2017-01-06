package edu.jhu.hlt.ikbp.tac;

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
import edu.jhu.hlt.concrete.ingesters.conll.CoNLLX;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.AttrFeatMatch;
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
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.CountMinSketch;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;
import edu.jhu.util.OfflineBatchParseyAnnotator;
import edu.jhu.util.TokenizationIter;
import redis.clients.jedis.Jedis;

/**
 * Search outwards starting with a {@link KbpQuery} seed.
 */
public class PkbpSearching implements Serializable {
  private static final long serialVersionUID = 3891008944720961213L;
  
  public void playback(ExperimentProperties config) throws Exception {
    Log.info("playing back");
    
    // (for now) For every SitSearchResult coming from arg3 of a SEARCH,
    // detect entity heads in that tokenization and run DependencySyntaxEvents
    // run parsey first?
    List<SitSearchResult> res = new ArrayList<>();
    for (Action a : history) {
      if (a.type.equals("SEARCH")) {
        res.addAll((List<SitSearchResult>) a.arguments.get(2));
      }
    }
    Log.info("collected " + res.size() + " SitSearchResults");
    AccumuloIndex.dedupCommunications(res);
    
    // Add parsey deps if desired/needed
    File parseyCacheDir = config.getFile("parseyCacheDir", null);
    if (parseyCacheDir != null) {
      Log.info("adding parsey parses with parseyCacheDir=" + parseyCacheDir.getPath());
      List<Communication> comms = AccumuloIndex.extractCommunications(res);
      OfflineBatchParseyAnnotator p = new OfflineBatchParseyAnnotator(
          parseyCacheDir, OfflineBatchParseyAnnotator.PARSEY_SCRIPT_LAPTOP);
      Map<String, Communication> anno = new HashMap<>();
      for (Communication c : comms) {
        Communication a = p.annotate(c);
        Object old = anno.put(a.getId(), a);
        assert old == null;
      }
      Log.info("collected " + anno.size() + " parsey-annotated comms");
      for (SitSearchResult r : res) {
        Communication a = anno.get(r.getCommunicationId());
        assert a != null;
        r.setCommunication(a);
      }
      Log.info("done with parsey");
    }
    
    // Run event extraction on all of the tokenization/SitSearchResults.
    // At the same time do a mock PKB population run, filling in events
    int topK = 1_000_000;
    try (RedisSitFeatFrequency rsitf = new RedisSitFeatFrequency("localhost", 8567, topK)) {
      List<PkbpSituation> events = new ArrayList<>();
      for (SitSearchResult r : res) {
        extractAndLinkSituations(r, events, rsitf);
      }
    }
  }

  /*
   * TODO Modify this so that instead of accepting a FULL LIST of all situations,
   * 1) Do the entity linking against PKB entities
   * 2) For every entityMention->entity link, consider situationMention->situation link s.t. entity in situation.args
   */
  public void extractAndLinkSituations(SitSearchResult r, List<PkbpSituation> knownSituations, RedisSitFeatFrequency rsitf) {
    
    // 1) Extract situation mentions and their entity arguments in the given Tokenization
    System.out.println("considering SitSearchResult: " + r.getWordsInTokenizationWithHighlightedEntAndSit());
    DependencyParse deps = IndexCommunications.getPreferredDependencyParse(r.getTokenization());
    List<Integer> entities = DependencySyntaxEvents.extractEntityHeads(r.getTokenization());
    if (entities.size() < 2) {
      System.out.println("skipping b/c to few entities");
      return;
    }
    for (int e : entities) {
      Span es = IndexCommunications.nounPhraseExpand(e, deps);
      String s = AccumuloIndex.words(es, r.getTokenization());
      System.out.println("\tentity at=" + e + "\tent=" + s);
    }
    System.out.println();

    CoNLLX.VERBOSE = false;
    DependencySyntaxEvents.CoverArgumentsWithPredicates sitEx =
        new DependencySyntaxEvents.CoverArgumentsWithPredicates(
            r.getCommunication(), r.getTokenization(), deps, entities);

    Map<Integer, Set<String>> sit2feat = sitEx.getSituations();
    for (int s : sit2feat.keySet())
      System.out.println("\tsituation at=" + s + " sit=" + r.getTokenization().getTokenList().getTokenList().get(s).getText());
    System.out.println();


    // 2) For each situation, try to link it to a known situation
    // for now we are using a list of situations as our PKB over situations
    for (Entry<Integer, Set<String>> e : sit2feat.entrySet()) {
      // laptop: ssh -fNL 8567:test2:7379 test2b
      // laptop: redis-cli -p 8567    # for some reason -h localhost makes it not work
      int pred = e.getKey();
      Set<String> sf = e.getValue();
      System.out.println("considering situation at=" + pred
          + " word=" + r.getTokenization().getTokenList().getTokenList().get(pred).getText());
      if (knownSituations.isEmpty()) {
        // Just add the first event, no questions asked
        PkbpSituation.Mention sm = PkbpSituation.Mention.convert(pred, sitEx, rsitf::getScore);
        knownSituations.add(new PkbpSituation(sm));
        continue;
      }

      Map<String, Double> sf2f = rsitf.getFrequenciesM(sf);
      PkbpSituation.Mention cur = PkbpSituation.Mention.convert(pred, sitEx, rsitf::getScore);

      ArgMax<Pair<PkbpSituation, List<Feat>>> bestSit = new ArgMax<>();
      for (int i = 0; i < knownSituations.size(); i++) {
        List<Feat> sim = knownSituations.get(i).similarity(sf2f);
        bestSit.offer(new Pair<>(knownSituations.get(i), sim), Feat.sum(sim));
        
        System.out.println("entity.mentions {");
        for (PkbpSituation.Mention sm : knownSituations.get(i).mentions) {
          System.out.println("  =>" + sm.showPredInContext());
          System.out.println("      " + sortAndPrune(sm.getFeatures(), 5));
        }
        System.out.println("}");
        System.out.println("cur.mention: " + cur.showPredInContext());
        System.out.println("sim: " + sortAndPrune(sim, 5));
        System.out.println();
      }

      System.out.println();
      Pair<PkbpSituation, List<Feat>> best = bestSit.get();
      System.out.println("best entity.mentions {");
      for (PkbpSituation.Mention sm : best.get1().mentions) {
        System.out.println("  =>" + sm.showPredInContext());
        System.out.println("      " + sortAndPrune(sm.getFeatures(), 5));
      }
      System.out.println("}");
      System.out.println("best cur.mention: " + cur.showPredInContext());
      System.out.println("best sim: " + sortAndPrune(best.get2(), 5));
      System.out.println("best score: " + Feat.sum(best.get2()));
      System.out.println();

      if (bestSit.getBestScore() >= 1.5) {
        System.out.println("LINK_SITUATION");
        best.get1().addMention(cur);
      } else {
        System.out.println("NEW_SITUATION");
        knownSituations.add(new PkbpSituation(cur));
      }
      System.out.println();
      System.out.println();
    }
    System.out.println();
  }
  
  public List<Feat> scoreSitLink(PkbpSituation sit, PkbpSituation.Mention mention) {
    assert sit.feat2score.size() > 0;
    assert mention.getFeatures().iterator().hasNext();
    List<Feat> out = new ArrayList<>();
    for (Feat f : mention.getFeatures()) {
      Double v = sit.feat2score.get(f.name);
      if (v != null) {
        out.add(new Feat(f.name, Math.sqrt(f.weight * v)));
      }
    }
    return out;
  }
  
  /** works the same way as linkEntity, returns [newSit, bestSitLink, secondBest, ...] */
  public List<SitLink> linkSituation(PkbpSituation.Mention mention, List<EntLink> entityArgs, double defaultScoreForNewSituation) {
    
    List<PkbpSituation> possible = new ArrayList<>();
    List<EntLink> entityArgsUsed = new ArrayList<>();
//    for (PkbpEntity e : entityArgs) {
    for (EntLink el : entityArgs) {
      PkbpEntity e = el.target;
      boolean used = false;
      for (LL<PkbpSituation> cur = memb_e2s.get(e); cur != null; cur = cur.next) {
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
    newSitFeats.add(new Feat("goodCompetingLink", Math.min(0, -Feat.sum(a.get().get2()))));
    PkbpSituation newSit = new PkbpSituation(mention);
    links.add(new SitLink(Collections.emptyList(), mention, newSit, newSitFeats, true));
    
    if (a.numOffers() == 0) {
      links.add(new SitLink(entityArgsUsed, mention, a.get().get1(), a.get().get2(), false));
    }

    // Show links for debugging
    for (int i = 0; i < links.size(); i++) {
      Log.info(i + "\t" + links.get(i));
    }

    return links;
  }


  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);

    // Playback a result which was computed and then serialized
    File playbackPkbpFile = config.getFile("playbackPkbpFile", null);
    if (playbackPkbpFile != null) {
      Log.info("deserializing playbackPkbpFile=" + playbackPkbpFile);
      PkbpSearching ps = (PkbpSearching) FileUtil.deserialize(playbackPkbpFile);
      ps.playback(config);
      Log.info("done");
      return;
    }
    
    Random rand = config.getRandom();
    KbpSearching ks = new KbpSearching(config);

    String sfName = config.getString("slotFillQueries", "sf13+sf14");
    List<KbpQuery> queries = TacKbp.getKbpSfQueries(sfName);

    int stepsPerQuery = config.getInt("stepsPerQuery", 12);
    double seedWeight = config.getDouble("seedWeight", 30);
    Log.info("stepsPerQuery=" + stepsPerQuery + " seedWeight=" + seedWeight);

    File outputDir = config.getFile("outputDir", null);
    if (outputDir != null) {
      outputDir.mkdirs();
      Log.info("outputDirs=" + outputDir.getPath());
    }

    for (KbpQuery seed : queries) {

      // Resolve query comm
      seed.sourceComm = ks.getCommCaching(seed.docid);
      if (seed.sourceComm == null) {
        Log.info("skipping b/c can't retrieve query comm: " + seed);
        continue;
      }

      PkbpSearching ps = new PkbpSearching(ks, seed, seedWeight, rand);
      ps.verbose = true;
      for (int i = 0; i < stepsPerQuery; i++) {
        Log.info("step=" + (i+1));
//        ps.expandEntity();
        ps.outerLoop();

        System.out.println();
        System.out.println("TIMER:");
        System.out.println(AccumuloIndex.TIMER);

        // Serialize the results for later
        if (outputDir != null) {
          File out = new File(outputDir, seed.id + "_kbpStep" + (i+1) + ".jser.gz");
          Log.info("saving PKB to " + out.getPath());
          FileUtil.serialize(ps, out);
        }
      }
      System.out.println();

      ks.clearCommCache();
    }
  }

  /** SEARCH, LINK, PRUNE, or NEW_ENTITY */
  static class Action implements Serializable {
    private static final long serialVersionUID = 1057136776248973712L;

    String type;
    List<Object> arguments;

    public Action(String type, Object... args) {
      this.type = type;
      this.arguments = Arrays.asList(args);
    }
  }

  // Input: Seed
  private KbpQuery seed;
  private StringTermVec seedTermVec;

  // Searching
  private transient KbpSearching search;
  private transient TacQueryEntityMentionResolver findEntityMention;
  private Random rand;

  // Controls search
  public DoublePair rewardForTemporalLocalityMuSigma = new DoublePair(2, 30); // = (reward, stddev), score += reward * Math.exp(-(diffInDays/stddev)^2)
  public double rewardForAttrFeats = 3;   // score += rewardForAttrFeats * sqrt(numAttrFeats)
  public DoublePair relatedEntitySigmoid = new DoublePair(6, 2);    // first: higher value lowers prob of keeping related entities. second: temperature of sigmoid (e.g. infinity means everything is 50/50 odds of keep)
  public DoublePair xdocCorefSigmoid = new DoublePair(3, 1);

//  // OLD PKB
//  /** @deprecated */
//  private List<PkbpEntity> entities;
//  /** @deprecated */
//  private Set<String> knownComms;
  
  // NEW PKB
  /*
   * To mimic the hyper-graph used in Uberts:
   * every node must have an id
   * edges are encoded via adjacency maps
   * edges should be added in one place and never/carefully removed
   */

  Set<Pair<String, String>> seenCommToks;

  // IO
  List<PkbpResult> output;
  Deque<PkbpResult> queue;    // TODO change element type, will want to have actions on here like "merge two situations"
  // Currently every element here is implicitly "search for situations involving these arguments and link ents/sits hanging off the results"
  
  StringCountMinSketch sitFeatCms;

  // Inverted indices for objects
//  /** Keys are features which would get you most of the way (scoring-wise) towards a LINK */
//  Map<String, LL<PkbpEntity>> discF2Ent;
//  Map<String, LL<PkbpSituation>> discF2Sit;


  // Membership graph.
  // Inverse links like s2e, r2e, and r2s are encoded by the
  // objects themselves (in this case s, r, and r respectively)
  Map<PkbpEntity, LL<PkbpSituation>> memb_e2s;
  Map<PkbpEntity, LL<PkbpResult>> memb_e2r;
  Map<PkbpSituation, LL<PkbpResult>> memb_s2r;
  

  // History (for debugging/running offline)
  // TODO needed?
  private List<Action> history;

  // Debugging
  Counts<String> ec = new Counts<>();
  public boolean verbose = false;
  public boolean verboseLinking = true;


  public PkbpSearching(KbpSearching search, KbpQuery seed, double seedWeight, Random rand) {
    Log.info("seed=" + seed);
    if (seed.sourceComm == null)
      throw new IllegalArgumentException();

    this.seenCommToks = new HashSet<>();

    this.memb_e2s = new HashMap<>();
    this.memb_e2r = new HashMap<>();
    this.memb_s2r = new HashMap<>();

    this.history = new ArrayList<>();
    this.rand = rand;
    this.search = search;
    this.seed = seed;
//    this.entities = new ArrayList<>();
//    this.knownComms = new HashSet<>();
    this.seedTermVec = new StringTermVec(seed.sourceComm);
    this.findEntityMention = new TacQueryEntityMentionResolver("tacQuery");
    boolean addEmToCommIfMissing = true;
    findEntityMention.resolve(seed, addEmToCommIfMissing);
    assert seed.entityMention != null;
    assert seed.entity_type != null;

    String tokUuid = seed.entityMention.getTokens().getTokenizationId().getUuidString();
    SitSearchResult canonical = new SitSearchResult(tokUuid, null, Collections.emptyList());
    canonical.setCommunicationId(seed.docid);
    canonical.setCommunication(seed.sourceComm);
    canonical.yhatQueryEntityNerType = seed.entity_type;

    TokenObservationCounts tokObs = null;
    TokenObservationCounts tokObsLc = null;
    Map<String, Tokenization> tokMap = new HashMap<>();
    for (Tokenization tok : new TokenizationIter(seed.sourceComm)) {
      Object old = tokMap.put(tok.getUuid().getUuidString(), tok);
      assert old == null;
    }
    EntityMention em = seed.entityMention;
    boolean takeNnCompounts = true;
    boolean allowFailures = true;
    String headEM = IndexCommunications.headword(em.getTokens(), tokMap, takeNnCompounts, allowFailures);
    canonical.triageFeatures = IndexCommunications.getEntityMentionFeatures(
        em.getText(), headEM.split("\\s+"), em.getEntityType(), tokObs, tokObsLc);

    // sets head token, needs triage feats and comm
    AccumuloIndex.findEntitiesAndSituations(canonical, search.df, false);

    DependencyParse deps = IndexCommunications.getPreferredDependencyParse(canonical.getTokenization());
    canonical.yhatQueryEntitySpan = IndexCommunications.nounPhraseExpand(canonical.yhatQueryEntityHead, deps);

    String head = canonical.getEntitySpanGuess();
//    canonical.attributeFeaturesR = NNPSense.extractAttributeFeatures(tokUuid, seed.sourceComm, head.split("\\s+"));

    //      String id = "seed/" + seed.id;
//    String id = "seed/" + head;
    List<Feat> relevanceReasons = new ArrayList<>();
    relevanceReasons.add(new Feat("seed", seedWeight));

    PkbpEntity.Mention canonical2 = new PkbpEntity.Mention(canonical);
    canonical2.context = new StringTermVec(seed.sourceComm);
    canonical2.attrCommFeatures = Feat.promote(1, NNPSense.extractAttributeFeatures(null, seed.sourceComm, head.split("\\s+")));
    canonical2.attrTokFeatures = Feat.promote(1, NNPSense.extractAttributeFeatures(tokUuid, seed.sourceComm, head.split("\\s+")));

//    PkbpEntity e = new PkbpEntity(id, canonical2, relevanceReasons);
//    this.entities.add(e);
//    this.history.add(new Action("NEW_ENTITY", e, canonical));
    
    this.queue = new ArrayDeque<>();
//    PkbpResult r = new PkbpResult();
//    r.addArgument(e, memb_e2r);
//    this.queue.addLast(r);
//
//    this.memb_e2s.put(e, null);
    
    Pair<List<EntLink>, List<SitLink>> p = proposeLinks(canonical2);
    // New entity for the searched-for mention
    EntLink best = null;
    for (EntLink el : p.get1()) {
      if (el.source == canonical2) {
        assert best == null;
        best = el;
      }
    }
    PkbpResult r0 = new PkbpResult();
    best.target.addMention(best.source);
    r0.addArgument(best.target, memb_e2r);
  }
  

  /**
   * 1) choose a tuple of entities to search for (one of which will be the seed entity)
   * 2) for each resulting tokenization: extract entMentions and sitMentions
   * 3) for each resulting tok, entMention: try to link to ent, possible create new entity if entMention looks good
   * 4) for each resulting tok, sitMention: try to link to a sit if it shares a resolved entity & sitFeatScores are good
   * 5) go back and find any sitMentions which link to 2+ situations, consider linking them
   * 6) go back and find any entMentions which link to 2+ entities, consider linking them
   */
  public void outerLoop() throws Exception {
    
    if (queue.isEmpty()) {
      Log.info("there are no items in the queue, done");
      return;
    }
    
    // This is a tuple of entities which we haven't searched for yet
    // where at least one entity is the seed entity
    PkbpResult searchFor = queue.pop();
    
    // Build a view of all the entities in this result
    TriageSearch ts = search.getTriageSearch();
    StringTermVec docContext = new StringTermVec();
    Set<String> triageFeats = new HashSet<>();
    List<Feat> attrComm = new ArrayList<>();
    List<Feat> attrTok = new ArrayList<>();
    for (PkbpEntity e : searchFor.getArguments()) {
      for (PkbpEntity.Mention m : e) {
        docContext.add(m.context);
        for (Feat ft : m.triageFeatures)
          triageFeats.add(ft.name);
        
        attrComm.addAll(m.attrCommFeatures);
        attrTok.addAll(m.attrTokFeatures);
      }
    }

    // Perform triage search
    List<SitSearchResult> mentions = ts.search(new ArrayList<>(triageFeats), docContext, search.df);
    Log.info("triage search returned " + mentions.size() + " mentions for " + searchFor);

    // Resolve the communications
    Log.info("resolving communications for results");
    for (SitSearchResult ss : mentions) {
      Communication c = search.getCommCaching(ss.getCommunicationId());
      assert c != null;
      ss.setCommunication(c);
    }
    
    // Highlight the searched for entity mention
    {
    List<SitSearchResult> pruned = new ArrayList<>();
    for (SitSearchResult ss : mentions) {
      boolean verbose = false;
      if (AccumuloIndex.findEntitiesAndSituations(ss, search.df, verbose))
        pruned.add(ss);
    }
    Log.info("[filter] lost " + (mentions.size()-pruned.size()) + " mentions due to query head finding failure");
    mentions = pruned;
    }

    // Compute and score attribute features
    boolean dedup = true;
    List<String> attrCommQ = Feat.demote(attrComm, dedup);
    List<String> attrTokQ = Feat.demote(attrTok, dedup);
    Log.info("doing attribute feature reranking, attrTokQ=" + attrTokQ);
    AccumuloIndex.attrFeatureReranking(attrCommQ, attrTokQ, mentions);

    // Scan the results
    Map<PkbpEntity.Mention, LL<PkbpEntity>> entDups = new HashMap<>();
    Map<PkbpSituation.Mention, LL<PkbpSituation>> sitDups = new HashMap<>();
    for (SitSearchResult s : mentions) {
      PkbpEntity.Mention em = new PkbpEntity.Mention(s);
      Pair<List<EntLink>, List<SitLink>> x = proposeLinks(em);
      
      // Execute the linking operations
      List<EntLink> exEL = new ArrayList<>();
      List<SitLink> exSL = new ArrayList<>();
      for (EntLink el : x.get1()) {
        el.target.addMention(el.source);
        exEL.add(el);
      }
      for (SitLink sl : x.get2()) {
        sl.target.addMention(sl.source);
        exSL.add(sl);
        for (EntLink el : sl.contingentUpon) {
          el.target.addMention(el.source);
          exEL.add(el);
        }
      }
      
      // Update duplicate index
      for (EntLink el : exEL)
        entDups.put(el.source, new LL<>(el.target, entDups.get(el.source)));
      for (SitLink sl : exSL)
        sitDups.put(sl.source, new LL<>(sl.target, sitDups.get(sl.source)));
    }
    
    // For mentions which appear in more than one ent/sit, consider merging them
    for (Entry<Mention, LL<PkbpEntity>> e : entDups.entrySet()) {
      List<PkbpEntity> ents = e.getValue().toList();
      if (ents.size() > 1)
        considerMergingEntities(ents);
    }
    for (Entry<PkbpSituation.Mention, LL<PkbpSituation>> e : sitDups.entrySet()) {
      List<PkbpSituation> sits = e.getValue().toList();
      if (sits.size() > 1)
        considerMergingSituations(sits);
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
  
  static class EntLink {
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
      return String.format("(EL %s => %s b/c %s)",
          source, target, sortAndPrune(score, 5));
    }
  }
  
  static class SitLink {
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
      return String.format("(SL %s => %s b/c %s nContingentUpon=%d)",
          source, target, sortAndPrune(score, 4), contingentUpon.size());
    }
  }
  
  /**
   * Does not actually modify the PKB in any way, just returns a
   * list of potential changes.
   * 
   * Returned list contains one link per entMention/sitMention near the given mention.
   *
   * @param searchResult
   * @param outputEntityLinks
   * @param outputSituationLinks
   */
  public Pair<List<EntLink>, List<SitLink>> proposeLinks(PkbpEntity.Mention searchResult) {

    // Higher numbers mean less linking
    // TODO Include threshold for PRUNE
    double defaultScoreOfCreatingNewEntity = 2;
    double defaultNewSitScore = 2;

    Pair<List<EntLink>, List<SitLink>> links = new Pair<>(new ArrayList<>(), new ArrayList<>());
    
    Communication c = searchResult.getCommunication();
    Tokenization t = searchResult.getTokenization();
    Log.info("processing " + searchResult + "\tc=" + c.getId() + " t=" + t.getUuid().getUuidString().substring(t.getUuid().getUuidString().length()-5));
    
    if (!seenCommToks.add(new Pair<>(c.getId(), t.getUuid().getUuidString()))) {
      // We've already processed this sentence
      Log.info("we've processed this comm+tok before, returning early");
      return links;
    }
    
    if (searchResult.deps == null)
      searchResult.deps = IndexCommunications.getPreferredDependencyParse(t);
    DependencyParse deps = searchResult.deps;
    
    // Extract arguments/entities
    List<Integer> entHeads = DependencySyntaxEvents.extractEntityHeads(t);
    Log.info("found " + entHeads.size() + " heads");
    // Extract situations
    DependencySyntaxEvents.CoverArgumentsWithPredicates se =
        new DependencySyntaxEvents.CoverArgumentsWithPredicates(c, t, deps, entHeads);

    // Link args
    Log.info("linking args...");
    Map<Integer, EntLink> entLinks = new HashMap<>();
    for (int entHead : entHeads) {
      PkbpEntity.Mention m = new PkbpEntity.Mention(entHead, t, deps, c);
      List<EntLink> el = linkEntityMention(m, defaultScoreOfCreatingNewEntity);
      assert el.size() > 0;
      assert el.get(0).newEntity;
      if (el.size() == 1 || Feat.sum(el.get(0).score) > 0) {
        // New
        entLinks.put(entHead, el.get(0));
      } else {
        // Link
        entLinks.put(entHead, el.get(1));
      }
    }
    
    // Link sits
    Log.info("linking sits...");
    for (Entry<Integer, Set<String>> s : se.getSituations().entrySet()) {
      BitSet bsArgs = se.getArguments().get(s.getKey());
      int[] args = DependencySyntaxEvents.bs2a(bsArgs);
      List<EntLink> entityArgs = new ArrayList<>();
      for (int a : args) {
        EntLink e = entLinks.get(a);
        if (e != null)
          entityArgs.add(e);
      }
      PkbpSituation.Mention sm = new PkbpSituation.Mention(s.getKey(), args, deps, t, c);
      Log.info("found " + entLinks + " links to support linking the sitMention " + sm);
      List<SitLink> sl = linkSituation(sm, entityArgs, defaultNewSitScore);
      if (sl.size() == 1 || Feat.sum(sl.get(0).score) > 0) {
        // New
        links.get2().add(sl.get(0));
      } else {
        // Link
        links.get2().add(sl.get(1));
      }
    }
    
    
    /*
     * Not clear how to do this!
     * A result is essentially a tuple of entities with some situations hanging off,
     * so the question becomes whether any of the entities or situations created/linked
     * here introduce any new keys (entity tuples) or values (situations).
     * 
     * TODO I can linear scan to see if there are any new entity tuples, but ... this is crappy...
     * if an entity tuple is the key, how to efficiently check?
     * induce an ordering over elements of a set (in this case a set of entities),
     * then the key is a sorted list of elements (ints/indices)
     * 
     * Ignoring computational efficiency for a moment,
     * If mentions may belong to n>=0 entities/situations,
     * we can apply the same logic to ent/sits belonging to n>=0 results.
     * This just means that we need a ent->LL<result> and sit->LL<result> index
     * 
     * => Solved:
     * Whenever you call addMention/addEntity/addSituation, you are forced
     * to provide an inverted adjacency list so coherence is always gauranteed.
     */
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

//  private Pair<PkbpEntity, List<Feat>> linkEntityMentionToPkb(PkbpEntity.Mention r) {
  /**
   * Returns [newEntity, bestLink, secondBestLink, ...]
   * 
   * Current implementation only considers one best link, so the
   * length of the returned list is either 1 (empty PKB) or 2 [newEnt,bestLink].
   */
  private List<EntLink> linkEntityMention(PkbpEntity.Mention r, double defaultScoreOfCreatingNewEntity) {
    Log.info("working on " + r);
    AccumuloIndex.TIMER.start("linkAgainstPkb");
    if (r.triageFeatures == null) {
      Log.info("computing triage feats");

      if (r.deps == null)
        r.deps = IndexCommunications.getPreferredDependencyParse(r.toks);

      assert r.head >= 0;
      if (r.span == null)
        r.span = IndexCommunications.nounPhraseExpand(r.head, r.deps);

      //assert r.nerType != null;
      TokenObservationCounts tokObs = null;
      TokenObservationCounts tokObsLc = null;
      String mentionText = r.getEntitySpanGuess();
      String[] headwords = mentionText.split("\\s+");
      r.triageFeatures = Feat.promote(1, IndexCommunications.getEntityMentionFeatures(
          mentionText, headwords, r.nerType, tokObs, tokObsLc));
    }
    Log.info("linking against " + memb_e2s.size() + " entities in PKB");
    ArgMax<Pair<PkbpEntity, List<Feat>>> a = new ArgMax<>();
    TriageSearch ts = search.getTriageSearch();
    for (PkbpEntity e : memb_e2s.keySet()) {
      List<Feat> fs = new ArrayList<>();
      //fs.add(new Feat("intercept", -1));

      // TODO Products of features below

      // Cosine similarity
      if (verboseLinking)
        Log.info("cosine sim for " + e.id);
      StringTermVec eDocVec = e.getDocVec();
      StringTermVec rDocVec = new StringTermVec(r.getCommunication());
      double tfidfCosine = search.df.tfIdfCosineSim(eDocVec, rDocVec);
      if (tfidfCosine > 0.95) {
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

        // Note: when this is true (needed b/c of related mentions which haven't
        // been triage searched before), this may cause a large slowdown due to
        // extra queries being run. These values are cached through, so perhaps
        // it will be OK when the cache is warm.
        boolean computeFeatFreqAsNeeded = true;
        Double s = null;
        List<String> ssTf = Feat.demote(ss.triageFeatures, false);
        List<String> rTf = Feat.demote(r.triageFeatures, false);
//        s = ts.scoreTriageFeatureIntersectionSimilarity(ss.triageFeatures, r.triageFeatures, computeFeatFreqAsNeeded);
        s = ts.scoreTriageFeatureIntersectionSimilarity(ssTf, rTf, computeFeatFreqAsNeeded);
        if (verboseLinking) {
          System.out.println("ss.triageFeats: " + ss.triageFeatures);
          System.out.println("r.triageFeats:  " + r.triageFeatures);
          System.out.println("score:          " + s);
          System.out.println();
        }
        if (s == null)
          s = 0d;
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
    AccumuloIndex.TIMER.stop("linkAgainstPkb");
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
//    return best;

    List<EntLink> el = new ArrayList<>();

    // New entity
    List<Feat> score = new ArrayList<>();
    score.add(new Feat("intercept", defaultScoreOfCreatingNewEntity));
    score.add(new Feat("competingLinkIsGood", Math.min(0, -Feat.sum(best.get2()))));
    PkbpEntity newEnt = new PkbpEntity("id", r, score);
    el.add(new EntLink(r, newEnt, score, true));
    
    // Best link
    el.add(new EntLink(r, best.get1(), best.get2(), false));
    
    // Show links for debugging
    for (int i = 0; i < el.size(); i++) {
      Log.info(i + "\t" + el.get(i));
    }

    return el;
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
    AccumuloIndex.TIMER.start("estimatePrecisionOfTriageFeatures");
    if (r.triageFeatures == null)
      throw new IllegalArgumentException();

    // Sort by freq
    search.fce.sortByFreqUpperBoundAsc(r.triageFeatures, f -> f.name);
    if (verbose)
      Log.info("triageFeats=" + r.triageFeatures);

    boolean computeIfNecessary = true;
    TriageSearch ts = search.getTriageSearch();
    List<Double> c = new ArrayList<>();
    for (int i = 0; i < r.triageFeatures.size(); i++) {
      String tf = r.triageFeatures.get(i).name;
      double p = ts.getFeatureScore(tf, computeIfNecessary);
      c.add(p);

      // Exit early based on estimate of mass remaining
      int remaining = r.triageFeatures.size()-(i+1);
      if (p*remaining < tolerance) {
        Log.info("breaking1 tol=" + tolerance + " p=" + p + " remaining=" + remaining + " i=" + i);
        break;
      }
      if (i >= 8) {
        Log.info("breaking2 i=" + i);
        break;
      }
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
    AccumuloIndex.TIMER.stop("estimatePrecisionOfTriageFeatures");
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
//  public List<Pair<SitSearchResult, List<Feat>>> extractRelatedEntities(PkbpEntity relevantTo, SitSearchResult newMention) {
  public List<Pair<PkbpEntity.Mention, List<Feat>>> extractRelatedEntities(PkbpEntity relevantTo, SitSearchResult newMention) {
    AccumuloIndex.TIMER.start("extractRelatedEntities");
//    List<Pair<SitSearchResult, List<Feat>>> out = new ArrayList<>();
    List<Pair<PkbpEntity.Mention, List<Feat>>> out = new ArrayList<>();
    ec.increment("exRel");

    // Compute tf-idf similarity between this comm and the seed
    Communication comm = newMention.getCommunication();
    StringTermVec commVec = new StringTermVec(comm);
    double tfidfWithSeed = search.df.tfIdfCosineSim(seedTermVec, commVec);

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
//      SitSearchResult rel = new SitSearchResult(tokUuid, null, new ArrayList<>());
      int headIdx = em.getTokens().getAnchorTokenIndex();
      PkbpEntity.Mention rel = new PkbpEntity.Mention(headIdx, toks, deps, comm);
      rel.nerType = nerType;
//      rel.yhatQueryEntityHead = em.getTokens().getAnchorTokenIndex();
//      assert rel.yhatQueryEntityHead >= 0;
      assert rel.head >= 0;
      rel.span = IndexCommunications.nounPhraseExpand(rel.head, deps);
      // NOTE: This can be multiple words with nn, e.g. "Barack Obama"
      String head = rel.getEntitySpanGuess();
      TokenObservationCounts tokObs = null;
      TokenObservationCounts tokObsLc = null;
      List<String> feats = IndexCommunications.getEntityMentionFeatures(
          head, head.split("\\s+"), em.getEntityType(), tokObs, tokObsLc);
      //em.getText(), new String[] {head}, em.getEntityType(), tokObs, tokObsLc);
      rel.triageFeatures = Feat.promote(1, feats);


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
      rel.attrCommFeatures = Feat.promote(1, NNPSense.extractAttributeFeatures(null, comm, head.split("\\s+")));
      rel.attrTokFeatures = Feat.promote(1, NNPSense.extractAttributeFeatures(tokUuid, comm, head.split("\\s+")));
      int nAttrFeat = rel.attrTokFeatures.size();
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
            nerType, head, relatedEntitySigmoid, t, d, d<t, relevanceReasons));
      }
      ec.increment("exRel/mention");
      if (d < t) {
        ec.increment("exRel/mention/kept");
        out.add(new Pair<>(rel, relevanceReasons));
      }
    }
    AccumuloIndex.TIMER.stop("extractRelatedEntities");
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
  
  public static List<Feat> sortAndPrune(Map<String, Double> in, double eps) {
    List<Feat> l = new ArrayList<>();
    for (Entry<String,  Double> e : in.entrySet()) {
      l.add(new Feat(e.getKey(), e.getValue()));
    }
    return sortAndPrune(l, eps);
  }

  public static List<Feat> sortAndPrune(Map<String, Double> in, int topk) {
    List<Feat> l = new ArrayList<>();
    for (Entry<String,  Double> e : in.entrySet()) {
      l.add(new Feat(e.getKey(), e.getValue()));
    }
    return sortAndPrune(l, topk);
  }

  public static List<Feat> sortAndPrune(Iterable<Feat> in, double eps) {
    List<Feat> out = new ArrayList<>();
    for (Feat f : in)
      if (Math.abs(f.weight) > eps)
        out.add(f);
//    Collections.sort(out, Feat.BY_NAME);
    return out;
  }

  public static List<Feat> sortAndPrune(Iterable<Feat> in, int topk) {
    List<Feat> out = new ArrayList<>();
    for (Feat f : in)
      out.add(f);
    Collections.sort(out, Feat.BY_SCORE_DESC);
    while (out.size() > topk)
      out.remove(out.size()-1);
//    Collections.sort(out, Feat.BY_NAME);
    return out;
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
