package edu.jhu.hlt.ikbp.tac;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.search.SearchResultItem;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.TriageSearch;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SitSearchResult;
import edu.jhu.hlt.ikbp.tac.PkbpSearching.EntLink;
import edu.jhu.hlt.ikbp.tac.TacKbp.KbpQuery;
import edu.jhu.hlt.tutils.ArgMax;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.util.TokenizationIter;

/**
 * To a first approximation: a list of mentions with an id.
 *
 * @author travis
 */
class PkbpEntity implements Serializable, Iterable<PkbpEntity.Mention> {
  private static final long serialVersionUID = 6258694570076400637L;
  
  public static class Mention extends PkbpMention implements Serializable {
    private static final long serialVersionUID = 7827905162665248704L;

    // head is the head token
    Span span;  // should contain the head
    String nerType;
    List<Feat> triageFeatures;
//    private List<Feat> attrCommFeatures;
//    private List<Feat> attrTokFeatures;
    private List<Feat> attrFeatures;

    public static PkbpEntity.Mention convert(KbpQuery seed, TacQueryEntityMentionResolver emFinder, ComputeIdf df, TriageSearch ts) {
      if (seed.sourceComm == null)
        throw new IllegalArgumentException("KbpQuery must have comm");
      if (seed.entityMention == null) {
        boolean addEmToCommIfMissing = true;
        emFinder.resolve(seed, addEmToCommIfMissing);
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
      AccumuloIndex.findEntitiesAndSituations(canonical, df, false);

//      DependencyParse deps = IndexCommunications.getPreferredDependencyParse(canonical.getTokenization());
//      canonical.yhatQueryEntitySpan = IndexCommunications.nounPhraseExpand(canonical.yhatQueryEntityHead, deps);
      // Take the span from the query
      // Sometimes noun phrase expand doesn't do exactly what you want, e.g. "Public <head>Library</head> of Science" => "Public Library"
//      EntityMention em = TacQueryEntityMentionResolver.find(seed, 3d);
//      boolean addEmToCommIfMissing = true;
//      TacQueryEntityMentionResolver t = new TacQueryEntityMentionResolver("tacEmFinder");
//      t.resolve(seed, addEmToCommIfMissing);
      EntityMention em = seed.entityMention;
      if (em == null)
        throw new RuntimeException("could not find " + seed + " in " + canonical.getCommunicationId());
      if (!em.getTokens().getTokenizationId().getUuidString().equals(canonical.tokUuid))
        throw new RuntimeException();
      canonical.yhatQueryEntitySpan = IndexCommunications.MturkCorefHit.convert(em.getTokens());

      PkbpEntity.Mention canonical2 = new PkbpEntity.Mention(canonical);
      assert canonical2.span == canonical.yhatQueryEntitySpan;
      assert canonical2.getCommunication() != null;
      canonical2.getContextDoc();
      canonical2.getAttrFeatures();
      assert canonical2.triageFeatures != null;
      canonical2.nerType = seed.entity_type;
      canonical2.scoreTriageFeatures(ts);
      
      return canonical2;
    }
    
    public static Mention convert(SearchResultItem r, Communication c, TriageSearch ts) {
      if (c == null)
        throw new IllegalArgumentException();
      String tokUuid = r.getSentenceId().getUuidString();
      Tokenization toks = IndexCommunications.findTok(tokUuid, c);
      DependencyParse deps = IndexCommunications.getPreferredDependencyParse(toks);
      int head = r.getTokens().getAnchorTokenIndex();
      Span span = IndexCommunications.nounPhraseExpand(head, deps);
      TokenTagging ner = IndexCommunications.getPreferredNerTags(toks);
      String nerType = ner.getTaggedTokenList().get(head).getTag();
      Mention m = new Mention(head, span, nerType, toks, deps, c);
      m.scoreTriageFeatures(ts);
      return m;
    }

    public Mention(SitSearchResult ss) {
      this(ss.yhatQueryEntityHead,
          ss.yhatQueryEntitySpan,
          ss.yhatQueryEntityNerType,
          ss.getTokenization(),
          IndexCommunications.getPreferredDependencyParse(ss.getTokenization()),
          ss.getCommunication());
    }

    public Mention(int head, Span span, String nerType, Tokenization toks, DependencyParse deps, Communication comm) {
      super(head, toks, deps, comm);
      this.span = span;
      this.nerType = nerType;
      String mentionText = getSpanString();
      String[] headwords = mentionText.split("\\s+");
      TokenObservationCounts tokObs = null;
      TokenObservationCounts tokObsLc = null;
      triageFeatures = Feat.promote(1, IndexCommunications.getEntityMentionFeatures(
          mentionText, headwords, nerType, tokObs, tokObsLc));
    }

    public static Mention build(int head, Tokenization toks, Communication comm) {
      DependencyParse deps = IndexCommunications.getPreferredDependencyParse(toks);
      Span span = IndexCommunications.nounPhraseExpand(head, deps);
      TokenTagging ner = IndexCommunications.getPreferredNerTags(toks);
      String nerType = ner.getTaggedTokenList().get(head).getTag();
      return new Mention(head, span, nerType, toks, deps, comm);
    }
    
    public void scoreTriageFeatures(TriageSearch ts) {
      for (Feat f : triageFeatures)
        f.weight = ts.getFeatureScore(f.name);
    }

    @Override
    public String toString() {
      String t = tokUuid.substring(0, 3);
      t += "..";
      t += tokUuid.substring(tokUuid.length()-4);
      String nTf = triageFeatures == null ? "null" : "" + triageFeatures.size();
//      String nAf = "(c=" + (attrCommFeatures == null ? "null" : attrCommFeatures.size());
//      nAf += ",t=" + (attrTokFeatures == null ? "null" : attrTokFeatures.size()) + ")";
      String nAf = attrFeatures == null ? "null" : String.valueOf(attrFeatures.size());
      return "(EM h=" + getHeadWord() + "@" + head
          + " neType=" + nerType
          + " s=" + Span.safeShortString(span)
          + " nTf=" + nTf
          + " nAf=" + nAf
          + " t=" + t
          + ")";
    }
    
    public List<Feat> getTriageFeatures() {
      if (triageFeatures == null) {
//        IndexCommunications.getEntityMentionFeatures(mentionText, headwords, nerType, tokObs, tokObsLc)
        throw new RuntimeException("implement me");
      }
      return triageFeatures;
    }
    
//    public List<Feat> getAttrCommFeatures() {
//      if (attrCommFeatures == null) {
//        attrCommFeatures = Feat.promote(1,
//            NNPSense.extractAttributeFeatures(null, getCommunication(), getEntitySpanGuess().split("\\s+")));
//      }
//      return attrCommFeatures;
//    }
//    
//    public List<Feat> getAttrTokFeatures() {
//      if (attrTokFeatures == null) {
//        attrTokFeatures = Feat.promote(1,
//            NNPSense.extractAttributeFeatures(tokUuid, getCommunication(), getEntitySpanGuess().split("\\s+")));
//      }
//      return attrTokFeatures;
//    }
    
    public String getHeadNer() {
      TokenTagging ner = IndexCommunications.getPreferredNerTags(getTokenization());
      TaggedToken n = ner.getTaggedTokenList().get(head);
      assert n.getTokenIndex() == head;
      return n.getTag();
    }
    
    public List<String> getNNPWordsInSpan() {
      List<String> l = new ArrayList<>();
      TokenTagging pos = IndexCommunications.getPreferredPosTags(getTokenization());
      for (int i = span.start; i < span.end; i++) {
        String p = pos.getTaggedTokenList().get(i).getTag();
        if (p.toUpperCase().startsWith("NNP")) {
          l.add(getTokenization().getTokenList().getTokenList().get(i).getText());
        }
      }
      return l;
    }

    public List<Feat> getAttrFeatures() {
      if (attrFeatures == null) {
        attrFeatures = NNPSense.extractAttributeFeaturesNewAndImproved(
            tokUuid,
            getCommunication(),
            getHeadNer(),
            getNNPWordsInSpan());
//            getEntitySpanGuess().split("\\s+"));
      }
      return attrFeatures;
    }
    
    public String getSpanString() {
      StringBuilder sb = new StringBuilder();
      for (int i = span.start; i < span.end; i++) {
        if (sb.length() > 0)
          sb.append(' ');
        sb.append(toks.getTokenList().getTokenList().get(i).getText());
      }
      return sb.toString();
    }
    
    public String getWordsInTokenizationWithHighlightedEntAndSit() {
      return getWordsInTokenizationWithHighlightedEntAndSit(true);
    }

    public String getWordsInTokenizationWithHighlightedEntAndSit(boolean includeCommTokId) {
      StringBuilder sb = new StringBuilder();
      if (includeCommTokId) {
        sb.append(getCommTokIdShort());
        sb.append(':');
      }
      if (head < 0)
        sb.append(" noEnt");
      List<Token> toks = this.toks.getTokenList().getTokenList();
      for (Token t : toks) {
        sb.append(' ');
        if (t.getTokenIndex() == span.start)
          sb.append("[ENT]");
        sb.append(t.getText());
        if (t.getTokenIndex() == span.end-1)
          sb.append("[/ENT]");
      }
      return sb.toString();
    }

    /** returns "commId/tokUuidSuf" */
    public String getCommTokIdShort() {
      return getCommunicationId() + "/" + tokUuid.substring(tokUuid.length()-4, tokUuid.length());
    }
  }

  public final String id;

//  private List<Mention> mentions;
  // The first link is from source->this link, and its link features are effectively the "relevance features"
  private List<EntLink> mentions;

//  /** Reasons why this entity is central to the PKB/seed */
//  List<Feat> relevantReasons;
  
  private transient StringTermVec memoContextDoc;
  private transient StringTermVec memoContextLocal;
  private transient List<Feat> memoAttrFeat;
  private transient List<Feat> memoTriageFeat;

  public PkbpEntity(String id, Mention canonical, List<Feat> relevanceReasons) {
    if (id == null)
      throw new IllegalArgumentException();
    if (canonical == null)
      throw new IllegalArgumentException();
    this.id = id;
//    this.relevantReasons = relevanceReasons;
    this.mentions = new ArrayList<>();
    addMention(new EntLink(canonical, this, relevanceReasons, true));
    //Log.info(this);
  }
  
  public boolean containsMentionWithNer(String ner) {
    for (EntLink l : mentions)
      if (ner.equalsIgnoreCase(l.source.getHeadNer()))
        return true;
    return false;
  }
  
  @Override
  public int hashCode() {
    return id.hashCode();
  }
  
  @Override
  public boolean equals(Object other) {
    if (other instanceof PkbpEntity) {
      PkbpEntity e = (PkbpEntity) other;
      return id.equals(e.id);
    }
    return false;
  }
  
  public String getCanonicalHeadString() {
    if (mentions.isEmpty())
      return "NA";
    return mentions.get(0).source.getHeadWord();
  }
  
//  public void addMention(Mention mention) {
  public void addMention(EntLink mention) {
    if (mention.source.head < 0)
      throw new IllegalArgumentException();
    if (mention.source.span == null)
      throw new IllegalArgumentException();
    this.memoContextDoc = null;
    this.memoContextLocal = null;
    this.memoAttrFeat = null;
    this.memoTriageFeat = null;
    this.mentions.add(mention);
  }

  public Mention guessCanonicalMention(ComputeIdf df) {
    // TODO Try out a few ways of doing this
    // For now: longest mention + tfidf with centroid
//    StringTermVec center = getDocVec();
    ArgMax<Mention> a = new ArgMax<>();
//    for (Mention m : this) {
//      double s = df.tfIdfCosineSim(center, m.getContext());
//      a.offer(m, m.span.width() + s/10d);
    for (EntLink l : mentions) {
      double w = 2 + Math.sqrt(1d+l.source.span.width());
      w *= Feat.sum(l.score);
      if (w > 0)
        a.offer(l.source, w);
    }
    if (a.numOffers() == 0)
      return mentions.get(0).source;
    return a.get();
  }
  
  public Mention getMention(int i) {
    return mentions.get(i).source;
  }
  
  public EntLink getLink(int i) {
    return mentions.get(i);
  }
  
  public List<Feat> getRelevanceReasons() {
    return mentions.get(0).score;
  }

  public double getRelevanceWeight() {
//    return Feat.sum(relevantReasons);
//    return Feat.sum(mentions.get(0).score);
    return Feat.sum(getRelevanceReasons());
  }

  @Override
  public String toString() {
    return "(PkbpEntity id=" + id
        + " nMentions=" + mentions.size()
        + ")";
  }
  
  public List<Feat> getAttrFeatures() {
    if (this.memoAttrFeat == null) {
      int n = mentions.size();
      List<Feat> fs = new ArrayList<>();
      for (int i = 0; i < n; i++)
        fs = Feat.vecadd(fs, mentions.get(i).source.getAttrFeatures());
      this.memoAttrFeat = fs;
    }
    return this.memoAttrFeat;
  }

  public List<Feat> getCommonAttrFeats() {
    Counts<String> c = new Counts<>();
    for (PkbpEntity.Mention m : this)
      for (Feat f : m.getAttrFeatures())
        c.increment(f.name);
    Map<String, Feat> m = Feat.index(this.getAttrFeatures());
    List<Feat> r = new ArrayList<>();
    for (String f : c.countIsAtLeast(2)) {
      assert m.get(f) != null;
      r.add(m.get(f));
    }
    Collections.sort(r, Feat.BY_SCORE_DESC);
    return r;
  }
  
  public List<Feat> getTriageFeatures() {
    if (this.memoTriageFeat == null) {
      int n = mentions.size();
      List<Feat> fs = new ArrayList<>();
      for (int i = 0; i < n; i++)
        fs = Feat.vecadd(fs, mentions.get(i).source.getTriageFeatures());
      this.memoTriageFeat = fs;
    }
    return this.memoTriageFeat;
  }
  
  public List<Feat> getCommonTriageFeatures() {
    Counts<String> c = new Counts<>();
    for (PkbpEntity.Mention m : this)
      for (Feat f : m.getTriageFeatures())
        c.increment(f.name);
    Map<String, Feat> m = Feat.index(this.getTriageFeatures());
    List<Feat> r = new ArrayList<>();
    for (String f : c.countIsAtLeast(2)) {
      assert m.get(f) != null;
      r.add(m.get(f));
    }
    Collections.sort(r, Feat.BY_SCORE_DESC);
    return r;
  }

  public StringTermVec getContextDoc() {
    if (this.memoContextDoc == null) {
      this.memoContextDoc = new StringTermVec();
      Set<String> seen = new HashSet<>();
      for (Mention s : this)
        if (seen.add(s.getCommunicationId()))
          this.memoContextDoc.add(s.getContextDoc());
    }
    return this.memoContextDoc;
  }
  
  public StringTermVec getContextLocal() {
    if (this.memoContextLocal == null) {
      this.memoContextLocal = new StringTermVec();
      Set<String> seen = new HashSet<>();
      for (Mention s : this)
        if (seen.add(s.getCommTokIdShort()))
          this.memoContextLocal.add(s.getContextLocal());
    }
    return this.memoContextLocal;
  }
  
  public int numMentions() {
    return mentions.size();
  }

  @Override
  public Iterator<Mention> iterator() {
//    return mentions.iterator();
    List<Mention> m = new ArrayList<>();
    for (EntLink l : mentions)
      m.add(l.source);
    return m.iterator();
  }
}
