package edu.jhu.hlt.ikbp.tac;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.StringTermVec;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SitSearchResult;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TokenObservationCounts;

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
    private List<Feat> attrCommFeatures;
    private List<Feat> attrTokFeatures;
    
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
      String mentionText = getEntitySpanGuess();
      String[] headwords = mentionText.split("\\s+");
      TokenObservationCounts tokObs = null;
      TokenObservationCounts tokObsLc = null;
      triageFeatures = Feat.promote(1, IndexCommunications.getEntityMentionFeatures(
          mentionText, headwords, nerType, tokObs, tokObsLc));
    }

    @Override
    public String toString() {
      String t = tokUuid.substring(0, 3);
      t += "..";
      t += tokUuid.substring(tokUuid.length()-4);
      String nTf = triageFeatures == null ? "null" : "" + triageFeatures.size();
      String nAf = "(c=" + (attrCommFeatures == null ? "null" : attrCommFeatures.size());
      nAf += ",t=" + (attrTokFeatures == null ? "null" : attrTokFeatures.size()) + ")";
      return "(EM h=" + getEntityHeadGuess() + "@" + head
          + " neType=" + nerType
          + " s=" + Span.safeShortString(span)
          + " nTf=" + nTf
          + " nAf=" + nAf
          + " t=" + t
          + ")";
    }
    
    public List<Feat> getAttrCommFeatures() {
      if (attrCommFeatures == null) {
        attrCommFeatures = Feat.promote(1,
            NNPSense.extractAttributeFeatures(null, getCommunication(), getEntitySpanGuess().split("\\s+")));
      }
      return attrCommFeatures;
    }
    
    public List<Feat> getAttrTokFeatures() {
      if (attrTokFeatures == null) {
        attrTokFeatures = Feat.promote(1,
            NNPSense.extractAttributeFeatures(tokUuid, getCommunication(), getEntitySpanGuess().split("\\s+")));
      }
      return attrTokFeatures;
    }
    
    public String getEntityHeadGuess() {
      return toks.getTokenList().getTokenList().get(head).getText();
    }

    public String getEntitySpanGuess() {
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

  private List<Mention> mentions;

  /** Reasons why this entity is central to the PKB/seed */
  List<Feat> relevantReasons;

  public PkbpEntity(String id, Mention canonical, List<Feat> relevanceReasons) {
    if (id == null)
      throw new IllegalArgumentException();
    if (canonical == null)
      throw new IllegalArgumentException();
    this.id = id;
    this.relevantReasons = relevanceReasons;
    this.mentions = new ArrayList<>();
    addMention(canonical);
    //Log.info(this);
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
    return mentions.get(0).getHeadString();
  }
  
  public void addMention(Mention mention) {
    if (mention.head < 0)
      throw new IllegalArgumentException();
    if (mention.span == null)
      throw new IllegalArgumentException();
    this.mentions.add(mention);
  }
  
  public Mention getMention(int i) {
    return mentions.get(i);
  }

  public double getRelevanceWeight() {
    return Feat.sum(relevantReasons);
  }

  @Override
  public String toString() {
    return "(PkbpEntity id=" + id
        + " nMentions=" + mentions.size()
        + " relevanceWeight=" + getRelevanceWeight() + ""
        + " b/c " + Feat.sortAndPrune(relevantReasons, 3) + ")";
  }

  public StringTermVec getDocVec() {
    StringTermVec tvAll = new StringTermVec();
    Set<String> seenComms = new HashSet<>();
    for (PkbpMention s : mentions) {
      if (seenComms.add(s.getCommunicationId())) {
        StringTermVec tv = new StringTermVec(s.getCommunication());
        tvAll.add(tv);
      }
    }
    return tvAll;
  }
  
  public int numMentions() {
    return mentions.size();
  }

  @Override
  public Iterator<Mention> iterator() {
    return mentions.iterator();
  }
}
