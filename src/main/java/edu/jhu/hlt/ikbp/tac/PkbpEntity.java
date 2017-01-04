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
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;

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
    List<Feat> attrCommFeatures;
    List<Feat> attrTokFeatures;
    
    public Mention(SitSearchResult ss) {
      this(ss.yhatQueryEntityHead,
          ss.getTokenization(),
          IndexCommunications.getPreferredDependencyParse(ss.getTokenization()),
          ss.getCommunication());
      span = ss.yhatQueryEntitySpan;
      nerType = ss.yhatQueryEntityNerType;
      triageFeatures = Feat.promote(1, ss.triageFeatures);
    }

    public Mention(int head, Tokenization toks, DependencyParse deps, Communication comm) {
      super(head, toks, deps, comm);
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
    Log.info(this);
  }
  
  public void addMention(Mention mention) {
    if (mention.head < 0)
      throw new IllegalArgumentException();
    if (mention.span == null)
      throw new IllegalArgumentException();
    this.mentions.add(mention);
  }

  public double getRelevanceWeight() {
    return Feat.sum(relevantReasons);
  }

  @Override
  public String toString() {
    return "(PkbpEntity id=" + id + " nMentions=" + mentions.size() + " weight=" + getRelevanceWeight() + ")";
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
