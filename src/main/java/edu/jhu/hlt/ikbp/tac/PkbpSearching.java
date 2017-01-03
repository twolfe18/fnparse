package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.TableNotFoundException;

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
import edu.jhu.hlt.ikbp.tac.TacKbp.KbpQuery;
import edu.jhu.hlt.tutils.ArgMax;
import edu.jhu.hlt.tutils.Average;
import edu.jhu.hlt.tutils.Beam;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.DoublePair;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.ChooseOne;
import edu.jhu.util.CountMinSketch;
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
    for (Action a : actions) {
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
      List<PkbpSearchingSituation> events = new ArrayList<>();
      for (SitSearchResult r : res) {
        playbackResult(r, events, rsitf);
      }
    }
  }

  public void playbackResult(SitSearchResult r, List<PkbpSearchingSituation> events, RedisSitFeatFrequency rsitf) {
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

    // laptop: ssh -fNL 8567:test2:7379 test2b
    // laptop: redis-cli -p 8567    # for some reason -h localhost makes it not work
    for (Entry<Integer, Set<String>> e : sit2feat.entrySet()) {
      int pred = e.getKey();
      Set<String> sf = e.getValue();
      System.out.println("considering situation at=" + pred
          + " word=" + r.getTokenization().getTokenList().getTokenList().get(pred).getText());
      if (events.isEmpty()) {
        // Just add the first event, no questions asked
        PkbpSearchingSitMention sm = PkbpSearchingSitMention.convert(pred, sitEx, rsitf::getScore);
        events.add(new PkbpSearchingSituation(sm));
        continue;
      }

      Map<String, Double> sf2f = rsitf.getFrequenciesM(sf);
      PkbpSearchingSitMention cur = PkbpSearchingSitMention.convert(pred, sitEx, rsitf::getScore);

      ArgMax<Pair<PkbpSearchingSituation, List<Feat>>> bestSit = new ArgMax<>();
      for (int i = 0; i < events.size(); i++) {
        List<Feat> sim = events.get(i).similarity(sf2f);
        bestSit.offer(new Pair<>(events.get(i), sim), Feat.sum(sim));
        
        System.out.println("entity.mentions {");
        for (PkbpSearchingSitMention sm : events.get(i).mentions) {
          System.out.println("  =>" + sm.showPredInContext());
          System.out.println("      " + sortAndPrune(sm.feat2score, 5));
        }
        System.out.println("}");
        System.out.println("cur.mention: " + cur.showPredInContext());
        System.out.println("sim: " + sortAndPrune(sim, 5));
        System.out.println();
      }

      System.out.println();
      Pair<PkbpSearchingSituation, List<Feat>> best = bestSit.get();
      System.out.println("best entity.mentions {");
      for (PkbpSearchingSitMention sm : best.get1().mentions) {
        System.out.println("  =>" + sm.showPredInContext());
        System.out.println("      " + sortAndPrune(sm.feat2score, 5));
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
        events.add(new PkbpSearchingSituation(cur));
      }
      System.out.println();
      System.out.println();
    }
    System.out.println();
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

  public static List<Feat> sortAndPrune(List<Feat> in, double eps) {
    List<Feat> out = new ArrayList<>();
    for (Feat f : in)
      if (Math.abs(f.weight) > eps)
        out.add(f);
//    Collections.sort(out, Feat.BY_NAME);
    return out;
  }

  public static List<Feat> sortAndPrune(List<Feat> in, int topk) {
    List<Feat> out = new ArrayList<>();
    out.addAll(in);
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
      double n = 0, d = 0.01;
      for (String s : union) {
//        System.out.println("f=" + f + " p=" + p + " feat=" + s);
        double f = frequency(s);
        double k = 10;
        double p = (k + 1) / (k + f);

        if (f < 3)
          continue;
        
        d += p;
        if (intersect.contains(s)) {
          n += p;
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
        ps.expandEntity();

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

  private transient KbpSearching search;
  private transient TacQueryEntityMentionResolver findEntityMention;
  private Random rand;

  // Controls search
  public DoublePair rewardForTemporalLocalityMuSigma = new DoublePair(2, 30); // = (reward, stddev), score += reward * Math.exp(-(diffInDays/stddev)^2)
  public double rewardForAttrFeats = 3;   // score += rewardForAttrFeats * sqrt(numAttrFeats)
  public DoublePair relatedEntitySigmoid = new DoublePair(6, 2);    // first: higher value lowers prob of keeping related entities. second: temperature of sigmoid (e.g. infinity means everything is 50/50 odds of keep)
  public DoublePair xdocCorefSigmoid = new DoublePair(3, 1);

  // Pkb
  private KbpQuery seed;
  private StringTermVec seedTermVec;
  private List<PkbpSearchingEntity> entities;
  private Set<String> knownComms;
  // TODO situations
  private List<PkbpSearching.Action> actions;

  Counts<String> ec = new Counts<>();

  public boolean verbose = false;
  public boolean verboseLinking = true;

  public PkbpSearching(KbpSearching search, KbpQuery seed, double seedWeight, Random rand) {
    Log.info("seed=" + seed);
    if (seed.sourceComm == null)
      throw new IllegalArgumentException();

    this.actions = new ArrayList<>();
    this.rand = rand;
    this.search = search;
    this.seed = seed;
    this.entities = new ArrayList<>();
    this.knownComms = new HashSet<>();
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
    canonical.attributeFeaturesR = NNPSense.extractAttributeFeatures(tokUuid, seed.sourceComm, head.split("\\s+"));

    //      String id = "seed/" + seed.id;
    String id = "seed/" + head;
    List<Feat> relevanceReasons = new ArrayList<>();
    relevanceReasons.add(new Feat("seed", seedWeight));
    PkbpSearchingEntity e = new PkbpSearchingEntity(id, canonical, relevanceReasons);
    this.entities.add(e);
    this.actions.add(new Action("NEW_ENTITY", e, canonical));
  }

  public KbpQuery getSeed() {
    return seed;
  }

  public void expandEntity() throws AccumuloException, AccumuloSecurityException, TableNotFoundException {
    AccumuloIndex.TIMER.start("expandEntity");

    // Find a SitSearchResult (essentially an EntityMention) to search off of
    Pair<PkbpSearchingEntity, SitSearchResult> er = chooseMentionToExpand();
    PkbpSearchingEntity e = er.get1();

    ec.increment("expandEnt");
    if (e.id.startsWith("seed"))
      ec.increment("expandEnt/seed");

    // Do the search
    List<SitSearchResult> res = search.entityMentionSearch(er.get2());
    actions.add(new Action("SEARCH", e, er.get2(), res));
    if (verbose)
      Log.info("found " + res.size() + " results for " + er.get2());

    for (SitSearchResult newMention : res) {
      if (verbose) {
        System.out.println("result: " + newMention.getWordsInTokenizationWithHighlightedEntAndSit());
      }

      ec.increment("expandEnt/searchResult");

      // Add mention to entity
      double lpLink = newMention.getScore();
      lpLink -= xdocCorefSigmoid.first;
      lpLink /= xdocCorefSigmoid.second;
      double pLink = 1 / (1+Math.exp(-lpLink));
      double tLink = rand.nextDouble();
      if (tLink < pLink) {
        ec.increment("expandEnt/searchResult/link");

        // Link this entity
        e.addMention(newMention);

        // Check related mentions (do this once per doc)
        if (knownComms.add(newMention.getCommunicationId())) {
          List<Pair<SitSearchResult, List<Feat>>> relevant = extractRelatedEntities(er.get1(), newMention);
          if (verbose) {
            Log.info("found " + relevant.size() + " relevant mentions in " + newMention.getCommunicationId());
          }

          int m = 30;
          if (relevant.size() > m) {
            Log.info("pruning " + relevant.size() + " mentions down to " + m);
            Collections.shuffle(relevant);
            relevant = relevant.subList(0, m);
          }

          for (Pair<SitSearchResult, List<Feat>> rel : relevant) {
            ec.increment("expandEnt/searchResult/relMention");
            linkRelatedEntity(rel.get1(), rel.get2());
          }
        } else {
          ec.increment("expandEnt/searchResult/dupComm");
        }
      }
    }

    if (verbose) {
      Log.info("event counts:");
      for (String k : ec.getKeysSorted())
        System.out.printf("%-26s % 5d\n", k, ec.getCount(k));
      System.out.println();
      System.out.println("TIMER:");
      System.out.println(AccumuloIndex.TIMER);
      System.out.println();
      Log.info("done");
    }
    AccumuloIndex.TIMER.stop("expandEntity");
  }

  private Pair<PkbpSearchingEntity, List<Feat>> linkAgainstPkb(SitSearchResult r) {
    AccumuloIndex.TIMER.start("linkAgainstPkb");
    if (r.triageFeatures == null)
      throw new RuntimeException();
    ArgMax<Pair<PkbpSearchingEntity, List<Feat>>> a = new ArgMax<>();
    TriageSearch ts = search.getTriageSearch();
    for (PkbpSearchingEntity e : entities) {
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
        Log.info("triage feats for " + e.id);
      Average triage = new Average.Uniform();
      double triageMax = 0;
      for (SitSearchResult ss : e) {
        if (ss.triageFeatures == null)
          throw new RuntimeException();

        // Note: when this is true (needed b/c of related mentions which haven't
        // been triage searched before), this may cause a large slowdown due to
        // extra queries being run. These values are cached through, so perhaps
        // it will be OK when the cache is warm.
        boolean computeFeatFreqAsNeeded = true;
        Double s = null;
        //if (r.triageFeatures.size() < 10 && ss.triageFeatures.size() < 10)
        s = ts.scoreTriageFeatureIntersectionSimilarity(ss.triageFeatures, r.triageFeatures, computeFeatFreqAsNeeded);
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
      for (SitSearchResult ss : e) {
        String nameHeadQ = ss.getEntityHeadGuess();
        List<String> attrCommQ = NNPSense.extractAttributeFeatures(null, ss.getCommunication(), nameHeadQ, nameHeadQ);
        List<String> attrTokQ = NNPSense.extractAttributeFeatures(ss.tokUuid, ss.getCommunication(), nameHeadQ, nameHeadQ);
        AttrFeatMatch afm = new AttrFeatMatch(attrCommQ, attrTokQ, r);
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
    Pair<PkbpSearchingEntity, List<Feat>> best = a.get();
    if (verbose) {
      //System.out.println("best link for " + r.getEntitySpanGuess() + " is " + best.get1().id
      //    + " with score=" + Feat.sum(best.get2()) + "\t" + best.get2());
      System.out.printf("best link for=%-24s is=%-24s score=%.3f %s\n",
          StringUtils.trim(r.getEntitySpanGuess(), 20), StringUtils.trim(best.get1().id, 20),
          Feat.sum(best.get2()), best.get2());
      System.out.println(Describe.memoryUsage());
      System.out.println();
    }
    return best;
  }

  private Pair<PkbpSearchingEntity, SitSearchResult> chooseMentionToExpand() {
    AccumuloIndex.TIMER.start("chooseMentionToExpand");
    if (verbose)
      Log.info("nPkbEntities=" + entities.size());
    ChooseOne<PkbpSearchingEntity> ce = new ChooseOne<>(rand);
    for (PkbpSearchingEntity e : entities) {
      // This score characterizes centrality to the PKB/seed
      double w = Math.max(0d, e.getRelevanceWeight());
      if (verbose)
        Log.info("considering weight=" + w + "\t" + e);
      ce.offer(e, w);
    }
    PkbpSearchingEntity e = ce.choose();
    if (verbose)
      Log.info("chose " + e);

    ec.increment("chooseMentionToExpand");
    if (e.id.startsWith("seed"))
      ec.increment("chooseMentionToExpand/seedEnt");

    ChooseOne<SitSearchResult> cr = new ChooseOne<>(rand);
    if (verbose)
      Log.info("nMentions=" + e.numMentions() + " for=" + e.id);
    for (SitSearchResult r : e) {
      // This score measures quality of link to the entity
      double w = Math.max(1e-8, r.getScore());
      if (verbose) {
        Log.info("considering weight=" + w + "\t" + r.getEntitySpanGuess());
        System.out.println(r.getWordsInTokenizationWithHighlightedEntAndSit());
      }
      cr.offer(r, w);
    }
    SitSearchResult r = cr.choose();
    if (verbose) {
      Log.info("chose " + r.getEntitySpanGuess());
      System.out.println(r.getWordsInTokenizationWithHighlightedEntAndSit());
    }
    AccumuloIndex.TIMER.stop("chooseMentionToExpand");
    return new Pair<>(e, r);
  }

  /**
   * We can do three things with a relevant entity:
   * A) link it against an entity in the PKB
   * B) create a new Entity to put in PKB
   * C) nothing (prune/ignore the mention)
   * 
   * We break this down into two steps:
   * 1) Find the best entity and decide whether or not to link to it
   * 2) Conditioned on (1) decide whether to prune or add to PKB based on centrality score
   */
  public void linkRelatedEntity(SitSearchResult r, List<Feat> relevanceReasons) {
    AccumuloIndex.TIMER.start("linkRelatedEntity");
    if (verbose)
      Log.info("starting relevanceReasons=" + relevanceReasons);
    Pair<PkbpSearchingEntity, List<Feat>> link = linkAgainstPkb(r);
    PkbpSearchingEntity e = link.get1();
    double linkingScore = Feat.sum(link.get2());
    if (linkingScore > 4) {
      // Link
      if (verbose)
        Log.info("LINKING " + r.getEntitySpanGuess() + " to " + e.id);
      e.addMention(r);
      actions.add(new Action("LINKING", e, r));
      ec.increment("linkRel/pkbEnt");
    } else {
      // Decide whether to create a new entity
      double centralityScore = Feat.sum(relevanceReasons);

      List<Feat> fNewEnt = new ArrayList<>();

      fNewEnt.add(new Feat("relevance", 0.1 * centralityScore));    // only link relevant things
      fNewEnt.add(new Feat("linkQual", -2 * Math.max(0, linkingScore)));    // if it might not be NIL, then don't make it a new entity
      fNewEnt.add(new Feat("pkbSize", -0.05 * Math.sqrt(entities.size()+1)));    // don't grow the PKB indefinitely
      fNewEnt.add(new Feat("triagePrec", 1 * (10*estimatePrecisionOfTriageFeatures(r, 0.01) - 2.0)));  // a proxy for whether this is a common entity or not

      double lpNewEnt = Feat.sum(fNewEnt);
      double pNewEnt = 1 / (1 + Math.exp(-lpNewEnt));
      double t = rand.nextDouble();

      if (verbose) {
        //Log.info("mention=" + r.getEntitySpanGuess() + "\tpNewEnt=" + pNewEnt + " lpNewEnt=" + lpNewEnt + " fNewEnt=" + fNewEnt);
        Log.info(String.format("%-38s pNewEnt=%.2f draw=%.2f lpNewEnt=%+.3f fNewEnt=%s",
            StringUtils.trim(r.getEntitySpanGuess(), 34), pNewEnt, t, lpNewEnt, fNewEnt));
      }

      if (t < pNewEnt) {
        // New entity
        if (verbose)
          Log.info("NEW ENTITY " + r.getEntitySpanGuess());
        String id = r.getEntitySpanGuess().replaceAll("\\s+", "_");
        PkbpSearchingEntity newEnt = new PkbpSearchingEntity(id, r, relevanceReasons);
        entities.add(newEnt);
//        actions.add(new Action("NEW_ENTITY", e, r));
        actions.add(new Action("NEW_ENTITY", newEnt, r, e));
        ec.increment("linkRel/newEnt");
      } else {
        // Prune this mention
        // no-op
        if (verbose)
          Log.info("PRUNING " + r.getEntitySpanGuess());
        actions.add(new Action("PRUNE", e, r));
        ec.increment("linkRel/prune");
      }
    }
    if (verbose)
      Log.info("done");
    AccumuloIndex.TIMER.stop("linkRelatedEntity");
  }

  public double estimatePrecisionOfTriageFeatures(SitSearchResult r, double tolerance) {
    AccumuloIndex.TIMER.start("estimatePrecisionOfTriageFeatures");
    if (r.triageFeatures == null)
      throw new IllegalArgumentException();

    // Sort by freq
    search.fce.sortByFreqUpperBoundAsc(r.triageFeatures);
    if (verbose)
      Log.info("triageFeats=" + r.triageFeatures);

    boolean computeIfNecessary = true;
    TriageSearch ts = search.getTriageSearch();
    List<Double> c = new ArrayList<>();
    for (int i = 0; i < r.triageFeatures.size(); i++) {
      String tf = r.triageFeatures.get(i);
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
  public List<Pair<SitSearchResult, List<Feat>>> extractRelatedEntities(PkbpSearchingEntity relevantTo, SitSearchResult newMention) {
    AccumuloIndex.TIMER.start("extractRelatedEntities");
    List<Pair<SitSearchResult, List<Feat>>> out = new ArrayList<>();
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

      String tokUuid = em.getTokens().getTokenizationId().getUuidString();
      String nerType = em.getEntityType();
      assert nerType != null;

      // Create a new SitSearchResult which could be relevant and fill in known data
      SitSearchResult rel = new SitSearchResult(tokUuid, null, new ArrayList<>());
      rel.setCommunicationId(comm.getId());
      rel.setCommunication(comm);
      rel.yhatQueryEntityNerType = nerType;
      rel.yhatQueryEntityHead = em.getTokens().getAnchorTokenIndex();
      assert rel.yhatQueryEntityHead >= 0;
      DependencyParse deps = IndexCommunications.getPreferredDependencyParse(rel.getTokenization());
      rel.yhatQueryEntitySpan = IndexCommunications.nounPhraseExpand(rel.yhatQueryEntityHead, deps);
      // NOTE: This can be multiple words with nn, e.g. "Barack Obama"
      String head = rel.getEntitySpanGuess();
      TokenObservationCounts tokObs = null;
      TokenObservationCounts tokObsLc = null;
      List<String> feats = IndexCommunications.getEntityMentionFeatures(
          head, head.split("\\s+"), em.getEntityType(), tokObs, tokObsLc);
      //em.getText(), new String[] {head}, em.getEntityType(), tokObs, tokObsLc);
      rel.triageFeatures = feats;


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
      rel.attributeFeaturesR = NNPSense.extractAttributeFeatures(tokUuid, comm, head.split("\\s+"));
      int nAttrFeat = rel.attributeFeaturesR.size();
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
}
