package edu.jhu.hlt.ikbp.tac;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

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
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.KbpSearching;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SitSearchResult;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.hlt.tutils.hash.Hash;

/**
 * Promotes KbpSearching capabilities to a SearchService (concrete/thrift service)
 */
public class KbpEntitySearchService implements SearchService.Iface {
  public static final String TOOL_NAME = "naive-accumulo-entity-search";
  
  private KbpSearching wrapped;
  private AnnotationMetadata meta;
  public boolean verbose = false;
  
  public KbpEntitySearchService(KbpSearching wrapped) {
    this.wrapped = wrapped;
    this.meta = new AnnotationMetadata()
        .setTool(TOOL_NAME)
        .setTimestamp(System.currentTimeMillis()/1000);
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

  @Override
  public SearchResult search(SearchQuery q) throws ServicesException, TException {
    if (verbose)
      Log.info("searching for " + StringUtils.trim(q.toString(), 120));
    
    // Get the communication which the mention is in
    Communication comm;
    if (q.isSetCommunication()) {
      // If you provide a communication, we will use it
      comm = q.getCommunication();
    } else if (q.isSetCommunicationId()) {
      // Otherwise we make an attempt to look it up in our database
      comm = wrapped.getCommCaching(q.getCommunicationId());
      if (comm == null)
        throw new ServicesException("can't find comm with id=" + q.getCommunicationId());
    } else {
      throw new ServicesException("you must provide either a Communication or communicationId");
    }

    // Find the mention
    TokenRefSequence trs;
    if (q.isSetTokens()) {
      // Preferred method: take the TRS
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

    PkbpEntity.Mention query = new PkbpEntity.Mention(head, span, nerType, toks, deps, comm);
    if (verbose)
      Log.info("query: " + query);
    try {
      List<SitSearchResult> mentions = wrapped.entityMentionSearch(query);
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
              .setAnchorTokenIndex(m.yhatQueryEntityHead);
          if (m.yhatQueryEntitySpan == null) {
            DependencyParse d = IndexCommunications.getPreferredDependencyParse(m.getTokenization());
            m.yhatQueryEntitySpan = IndexCommunications.nounPhraseExpand(m.yhatQueryEntityHead, d);
          }
          t.setTokenIndexList(new ArrayList<>());
          for (int i = m.yhatQueryEntitySpan.start; i < m.yhatQueryEntitySpan.end; i++)
            t.addToTokenIndexList(i);
          x.setTokens(t);
        }
        res.addToSearchResultItems(x);
      }
      
      return res;
    } catch (Exception e) {
      throw new ServicesException(e.getMessage());
    }
  }
  
  /**
   * Start up a server
   */
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    int port = config.getInt("port", 9999);
    Log.info("using port=" + port);
    KbpSearching s = new KbpSearching(config, new HashMap<>());
    KbpEntitySearchService ss = new KbpEntitySearchService(s);
    ss.verbose = config.getBoolean("verbose", false);
    try (SearchServiceWrapper sss = new SearchServiceWrapper(ss, port)) {
      sss.run();
    }
  }
}