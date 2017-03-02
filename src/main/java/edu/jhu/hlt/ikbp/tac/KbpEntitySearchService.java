package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.thrift.TException;

import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.concrete.search.SearchCapability;
import edu.jhu.hlt.concrete.search.SearchQuery;
import edu.jhu.hlt.concrete.search.SearchResult;
import edu.jhu.hlt.concrete.search.SearchResultItem;
import edu.jhu.hlt.concrete.search.SearchService;
import edu.jhu.hlt.concrete.search.SearchType;
import edu.jhu.hlt.concrete.services.ServiceInfo;
import edu.jhu.hlt.concrete.services.ServicesException;
import edu.jhu.hlt.concrete.services.search.SearchServiceWrapper;
import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.KbpSearching;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.TriageSearch;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.TriageSearch.EMQuery;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SitSearchResult;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.MultiTimer.TB;
import edu.jhu.hlt.tutils.SerializationUtils;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.util.DiskBackedFetchWrapper;

/**
 * Promotes {@link KbpSearching} capabilities to a {@link SearchService} (concrete/thrift service)
 */
public class KbpEntitySearchService implements SearchService.Iface {
  public static final String TOOL_NAME = "naive-accumulo-entity-search";
  
  private KbpSearching wrapped;
  private AnnotationMetadata meta;
  public boolean verbose = false;
  public TimeMarker tm;
  
  public KbpEntitySearchService(KbpSearching wrapped) {
    this.wrapped = wrapped;
    this.meta = new AnnotationMetadata()
        .setTool(TOOL_NAME)
        .setTimestamp(System.currentTimeMillis()/1000);
    this.tm = new TimeMarker();
  }

  @Override
  public ServiceInfo about() throws TException {
    return new ServiceInfo()
        .setName(TOOL_NAME)
        .setVersion("0.1")
        .setDescription("search gigaword5 and wikipedia for entity mentions");
  }

  @Override
  public boolean alive() throws TException {
    return true;
  }

  @Override
  public List<SearchCapability> getCapabilities() throws ServicesException, TException {
    SearchCapability c = new SearchCapability()
        .setType(SearchType.SENTENCES)
        .setLang("eng");
    return Arrays.asList(c);
  }

  @Override
  public List<String> getCorpora() throws ServicesException, TException {
    return Arrays.asList("gigaword5", "wikipedia");
  }

  /**
   * Accepts two kinds of queries, see documentation for:
   * {@link KbpEntitySearchService#searchGivenRawFeatures(SearchQuery)}
   * {@link KbpEntitySearchService#searchGivenMentionAndComm(SearchQuery)}
   * 
   * The former is chosen over the latter if {@link SearchQuery#getTerms()}
   * is populated (set and non-empty).
   */
  @Override
  public SearchResult search(SearchQuery q) throws ServicesException, TException {
    if (verbose)
      Log.info("searching for " + StringUtils.trim(q.toString(), 120));
    if (tm.enoughTimePassed(20))
      Log.info(Describe.memoryUsage());
    if (q.isSetLabels() && q.getLabels().contains("multi"))
      return searchGivenRawFeaturesMulti(q);
    if (q.isSetTerms() && q.getTermsSize() > 0)
      return searchGivenRawFeatures(q);
    return searchGivenMentionAndComm(q);
  }
  
  /**
   * Parses features like "q0:c:context" and "queryJohn:a:PERSON-nn-Dr." and "0:hi:John"
   * where the components are <queryId> <colon> <featureType> <colon> <featureValue>
   */
  private static void addFeat(String t, Map<String, EMQuery> qs) {
    String[] ar = t.split(":", 3);
    if (ar.length != 3) {
      Log.info("WARNING: bad query term, IGNORING, must have feature type prefix: " + t);
      return;
    }

    String qid = ar[0];
    EMQuery q = qs.get(qid);
    if (q == null) {
      q = new EMQuery(qid, 1);
      qs.put(qid, q);
    }
    
    switch (ar[1].toLowerCase()) {
    case "c":      // tf/context
      q.context.add(ar[2], 1);
      break;

    case "a":      // attrFeat
      int lc = ar[2].lastIndexOf(':');
      if (lc < 0) {
        q.attrFeats.add(new Feat(ar[2], 1));
      } else {
        String f = ar[2].substring(0, lc);
        double w = Double.parseDouble(ar[2].substring(lc+1));
        q.attrFeats.add(new Feat(f, w));
      }
      break;

    default:        // triage feats
      q.triageFeats.add(ar[1] + ":" + ar[2]);
      break;
    }
  }

  /**
   * Expects all features (triage, attr, context) to be prefixed by a number between 0 and N-1.
   * Performs a multi-entity search for the N entities described by the various features.
   */
  public SearchResult searchGivenRawFeaturesMulti(SearchQuery q) throws ServicesException, TException {
    
    Map<String, EMQuery> qs = new HashMap<>();
    for (String t : q.getTerms()) {
      try {
        addFeat(t, qs);
      } catch (Exception e) {
        Log.info("dropping feat=" + t + " because of " + e.getMessage());
      }
    }
    if (qs.isEmpty())
      throw new ServicesException("no features recgnoized in: " + q.toString());
    
    try {
      List<SitSearchResult> res = null;
      try (TB tb = timer().new TB("wrapped.multiEntityMentionSearch")) {
        res = wrapped.multiEntityMentionSearch(new ArrayList<>(qs.values()));
      }
      return buildResult(res, q);
    } catch (Exception e) {
      ServicesException se = new ServicesException("error during search: " + e.getMessage());
      byte[] bytes = SerializationUtils.t2bytes(e);
      se.setSerEx(bytes);
      throw se;
    }
  }

  /**
   * Pulls triage, attribute, and context features from {@link SearchQuery#getTerms()}
   * by check their prefix (e.g. "h:", "a:", and "c:" respectively).
   * 
   * triageFeatures are computed via {@link IndexCommunications#getEntityMentionFeatures(String, String[], String, TokenObservationCounts, TokenObservationCounts)}
   */
  public SearchResult searchGivenRawFeatures(SearchQuery q) throws ServicesException, TException {
    if (verbose)
      Log.info("starting, numTerms=" + q.getTermsSize());
    
    List<String> triageFeats = new ArrayList<>();
    List<Feat> attrFeats = new ArrayList<>();
    StringTermVec context = new StringTermVec();
    
    int lc;
    
    for (String t : q.getTerms()) {
      String[] ar = t.split(":", 2);
      if (ar.length != 2) {
        Log.info("WARNING: bad query term, IGNORING, must have feature type prefix: " + t);
        continue;
      }
      switch (ar[0].toLowerCase()) {
      case "c":      // tf/context
        context.add(ar[1], 1);
        break;

      case "a":      // attrFeat
        lc = ar[1].lastIndexOf(':');
        if (lc < 0) {
          attrFeats.add(new Feat(ar[1], 1));
        } else {
          String f = ar[1].substring(0, lc);
          double w = Double.parseDouble(ar[1].substring(lc+1));
          attrFeats.add(new Feat(f, w));
        }
        break;

      default:        // triage feats
        triageFeats.add(t);
        break;
      }
    }
    
    try {
      if (verbose) {
        Log.info(String.format("triage feats (%s): %s", triageFeats.size(), triageFeats));
        Log.info(String.format("attr feats (%d):   %s", attrFeats.size(), Feat.sortAndPrune(attrFeats, 0d)));
        Log.info(String.format("context (%.1f):      %s", context.getTotalCount(), wrapped.getTermFrequencies().importantTerms(context, 10)));
      }
      if (q.isSetK())
        wrapped.setMaxResults(q.getK());
      List<SitSearchResult> mentions = wrapped.entityMentionSearch(triageFeats, attrFeats, context);
      return buildResult(mentions, q);
    } catch (Exception e) {
      ServicesException se = new ServicesException("error during search: " + e.getMessage());
      byte[] bytes = SerializationUtils.t2bytes(e);
      se.setSerEx(bytes);
      throw se;
    }
  }

  /**
   * Accepts queries of the form (EntityMention, Communication) where an
   * EntityMention can be a TokenRefSequence or a string which is searched for
   * in the Communication.
   * 
   * TODO Make this method a SearchQuery re-writer to convert and use searchGivenRawFeatures
   */
  public SearchResult searchGivenMentionAndComm(SearchQuery q) throws ServicesException, TException {
    /* SETUP FOR QUERY *******************************************************/
    // Get the communication which the mention is in
    Communication comm;
    if (q.isSetCommunication()) {
      // If you provide a communication, we will use it
      comm = q.getCommunication();
      if (verbose)
        Log.info("using provided comm id=" + comm.getId() + " uuid=" + comm.getUuid().getUuidString());
    } else if (q.isSetCommunicationId()) {
      if (verbose)
        Log.info("searching for comm by id=" + q.getCommunicationId());
      // Otherwise we make an attempt to look it up in our database
      try {
        if (q.isSetK())
          wrapped.setMaxResults(q.getK());
        comm = wrapped.getCommCaching(q.getCommunicationId());
      } catch (Exception e) {
        comm = null;
        e.printStackTrace();
      }
      if (comm == null) {
        Log.info("throwing b/c no comm");
        throw new ServicesException("can't find comm with id=" + q.getCommunicationId());
      }
      if (verbose)
        Log.info("looking up comm by id=" + comm.getId() + "\tfound uuid=" + comm.getUuid().getUuidString());
    } else {
      throw new ServicesException("you must provide either a Communication or communicationId");
    }

    // Find the mention
    TokenRefSequence trs;
    if (q.isSetTokens()) {
      // Preferred method: take the TRS
      if (verbose)
        Log.info("using provident TokenRefSequence");
      trs = q.getTokens();
    } else if (q.isSetName()) {
      // If you provide a name, we will look for it
      if (comm.getEntityMentionSetListSize() == 0)
        throw new ServicesException("comm must have EntityMentions if you don't provide a TokenRefSequence");
      String[] headwords = q.getName().split("\\s+");
      TokenObservationCounts tokObs = null;
      TokenObservationCounts tokObsLc = null;
      String nerType = null;
      List<String> feats = IndexCommunications.getEntityMentionFeatures(q.getName(), headwords, nerType, tokObs, tokObsLc);
      if (verbose)
        Log.info("searching for EntityMention in comm.uuid=" + comm.getUuid().getUuidString() + " using feats=" + feats);
      new AddNerTypeToEntityMentions(comm);
      EntityMention em = IndexCommunications.bestGuessAtQueryMention(null, comm, feats, verbose);
      if (em == null)
        throw new ServicesException("could not find EntityMention for " + q.getName());
      trs = em.getTokens();
    } else {
      throw new ServicesException("you must provide a TokenRefSequence or a name");
    }

    Tokenization toks = IndexCommunications.findTok(trs.getTokenizationId().getUuidString(), comm);
    DependencyParse deps = IndexCommunications.getPreferredDependencyParse(toks);
    
    if (verbose)
      Log.info("comm=" + comm.getId() + " tok=" + toks.getUuid().getUuidString());
    
    int head;
    if (trs.isSetAnchorTokenIndex()) {
      head = trs.getAnchorTokenIndex();
    } else {
      // TODO We are just taking the last token
      head = trs.getTokenIndexList().get(trs.getTokenIndexListSize()-1);
    }
    Span span = Span.getSpan(trs, false);
    
    String nerType;
    TokenTagging ner = IndexCommunications.getPreferredNerTags(toks);
    if (ner == null)
      nerType = null;
    else
      nerType = ner.getTaggedTokenList().get(head).getTag();

    /* PERFORM THE QUERY ******************************************************/
    try {
      PkbpEntity.Mention query = new PkbpEntity.Mention(head, span, nerType, toks, deps, comm, null);
      if (verbose)
        Log.info("query: " + query);
      List<SitSearchResult> mentions = wrapped.entityMentionSearch(query);
      return buildResult(mentions, q);
    } catch (Exception e) {
      throw new ServicesException(e.getMessage());
    }
  }
  
  private SearchResult buildResult(List<SitSearchResult> mentions, SearchQuery q) {
    if (verbose)
      Log.info("retrieved " + mentions.size() + " results");
    SearchResult res = new SearchResult();
    res.setLang("eng");
    res.setMetadata(meta);
    res.setSearchQuery(q);

    long hi = Hash.sha256(meta.toString());
    long lo = Hash.sha256(q.toString());
    java.util.UUID uuid = new java.util.UUID(hi, lo);
    res.setUuid(new UUID(uuid.toString()));

    res.setSearchResultItems(new ArrayList<>());
    for (SitSearchResult m : mentions) {
      SearchResultItem x = new SearchResultItem()
          .setCommunicationId(m.getCommunicationId())
          .setScore(m.getScore())
          .setSentenceId(new UUID(m.tokUuid));
      if (m.yhatQueryEntityHead >= 0) {
        TokenRefSequence t = new TokenRefSequence()
            .setTokenizationId(new UUID(m.tokUuid))
            .setAnchorTokenIndex(m.yhatQueryEntityHead);
        if (m.yhatQueryEntitySpan == null) {
          DependencyParse d = IndexCommunications.getPreferredDependencyParse(m.getTokenization());
          m.yhatQueryEntitySpan = IndexCommunications.nounPhraseExpand(m.yhatQueryEntityHead, d);
        }
        t.setTokenIndexList(new ArrayList<>());
        for (int i = m.yhatQueryEntitySpan.start; i < m.yhatQueryEntitySpan.end; i++)
          t.addToTokenIndexList(i);
        x.setTokens(t);
        
        // TODO Add Communications to the results when this passes:
        // https://gitlab.hltcoe.jhu.edu/concrete/concrete/merge_requests/54
      }
      res.addToSearchResultItems(x);
    }

    if (verbose) {
      if (tm.enoughTimePassed(30)) {
        System.out.println();
        System.out.println("TIMER:");
        System.out.println(timer());
        System.out.println();
      }
      Log.info("returning " + res.getSearchResultItemsSize() + " results");
    }

    return res;
  }
  
  public static MultiTimer timer() {
    return AccumuloIndex.TIMER;
  }
  
  /**
   * Start up a server
   */
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    // Set the index tables that we're working with
    String tnsKey = "tableNamespace";
    if (config.containsKey(tnsKey))
      AccumuloIndex.TABLE_NAMESPACE = config.getString(tnsKey);
    Log.info("using tableNamespace=" + AccumuloIndex.TABLE_NAMESPACE);
    
    int port = config.getInt("port", 9999);
    Log.info("using port=" + port);
    File fceFile = config.getExistingFile("triageFeatureFrequencies");
    Log.info("loading triage feature frequencies (FeatureCardinalityEstimator.New) from=" + fceFile.getPath());
    FeatureCardinalityEstimator.New triageFeatureFrequencies =
        (FeatureCardinalityEstimator.New) FileUtil.deserialize(fceFile);
    ComputeIdf df = new ComputeIdf(config.getExistingFile("wordDocFreq"));
    int maxResults = config.getInt("maxResults", 100);
    int maxDocsForMultiEntSearch = config.getInt("maxDocsForMultiEntSearch", 100_000);
    boolean idfWeighting = config.getBoolean("idfWeighting", true);
    
    TriageSearch ts;
    String accInstance = config.getString("accumulo.instance", null);
    if (accInstance != null) {
      String accZks = config.getString("accumulo.zookeepers");
      String accUser = config.getString("accumulo.user");
      PasswordToken accPw = new PasswordToken(config.getString("accumulo.password"));
      int nThreads = 4;
      boolean batchC2W = true;
      ts = new TriageSearch(accInstance, accZks, accUser, accPw, triageFeatureFrequencies, idfWeighting,
          nThreads, maxResults, maxDocsForMultiEntSearch, TriageSearch.DEFAULT_TRIAGE_FEAT_NB_PRIOR, batchC2W);
    } else {
      Log.info("using default accumulo configuration");
      ts = new TriageSearch(triageFeatureFrequencies, maxResults, maxDocsForMultiEntSearch, idfWeighting);
    }
    
    PkbpSearching.triageFeatDebug(ts);

    DiskBackedFetchWrapper commRet = null;
    String fetchHost = config.getString("fetch.host", null);
    if (fetchHost == null) {
      Log.info("not using a fetch service");
    } else {
      int fetchPort = config.getInt("fetch.port");
      File fetchCacheDir = config.getOrMakeDir("fetch.cacheDir");
      commRet = KbpSearching.buildFetchWrapper(fetchCacheDir, fetchHost, fetchPort);
      commRet.disableCache = !config.getBoolean("fetch.caching", false);
      Log.info("fetch.caching=" + (!commRet.disableCache));
    }
    
    Double minTriageScore = config.getDouble("minTriageScore", -1);
    if (minTriageScore <= 0)
      minTriageScore = null;
    
    boolean retrieveComms = config.getBoolean("retrieveComms", false);
    Log.info("retrieveComms=" + retrieveComms);

    // Make non-null if you want caching, which you probably don't for a long living process.
    // Need to come up with an eviction policy to make that work.
    Map<String, Communication> commRetCache = null;
    try (KbpSearching s = new KbpSearching(ts, df, minTriageScore, retrieveComms, commRet, commRetCache)) {
      KbpEntitySearchService ss = new KbpEntitySearchService(s);
      ss.verbose = config.getBoolean("verbose", false);
      try (SearchServiceWrapper sss = new SearchServiceWrapper(ss, port)) {
        Log.info("setup done, accepting queries...");
        sss.run();
      }
    }
  }
}
