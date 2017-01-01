package edu.jhu.hlt.ikbp.tac;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.ikbp.tac.AccumuloIndex.StringTermVec;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SitSearchResult;
import edu.jhu.hlt.tutils.Log;

/**
 * To a first approximation: a list of mentions with an id.
 *
 * @author travis
 */
class PkbpSearchingEntity implements Serializable, Iterable<SitSearchResult> {
  private static final long serialVersionUID = 6258694570076400637L;

  public final String id;
  private List<SitSearchResult> mentions;
  /** Reasons why this entity is central to the PKB/seed */
  List<Feat> relevantReasons;

  public PkbpSearchingEntity(String id, SitSearchResult canonical, List<Feat> relevanceReasons) {
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
  
  public void addMention(SitSearchResult mention) {
    if (mention.yhatQueryEntityHead < 0)
      throw new IllegalArgumentException();
    if (mention.yhatQueryEntitySpan == null)
      throw new IllegalArgumentException();
    this.mentions.add(mention);
  }

  public double getRelevanceWeight() {
    return Feat.sum(relevantReasons);
  }

  @Override
  public String toString() {
    return "(PkbpSearchingEntity id=" + id + " nMentions=" + mentions.size() + " weight=" + getRelevanceWeight() + ")";
  }

  public StringTermVec getDocVec() {
    StringTermVec tvAll = new StringTermVec();
    Set<String> seenComms = new HashSet<>();
    for (SitSearchResult s : mentions) {
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
  public Iterator<SitSearchResult> iterator() {
    return mentions.iterator();
  }
}