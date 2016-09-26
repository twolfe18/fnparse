package edu.jhu.hlt.ikbp;

import java.util.Iterator;
import java.util.List;

import edu.jhu.hlt.concrete.Clustering;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.ikbp.ConcreteIkbpAnnotations.Topic;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.tuple.Pair;

/**
 * If you want to train my IKBP tool, implement this interface.
 * Instances of this class provide annotations via the Concrete spec.
 *
 * @author travis
 */
public interface ConcreteIkbpAnnotations
//    extends Iterator<Pair<Clustering, List<Communication>>> {
    extends Iterator<Topic> {
  
  public static class Topic {
    public final Clustering clustering;
    public final List<Communication> comms;
    public final String part; // train|dev|test

    public Topic(Clustering clustering, List<Communication> comms, String part) {
      this.clustering = clustering;
      this.comms = comms;
      this.part = part;
    }
    
    public Pair<Communication, SituationMention> findSitutionMention(UUID sitMentionId) {
      throw new RuntimeException("implement me");
    }
    public Sentence find(Communication c, TokenRefSequence trs) {
      throw new RuntimeException("implement me");
    }
  }
  
  /**
   * This should match the tool names for any annotations created by this instance.
   */
  public String getName();
  
  /**
   * Return a clustering which contains annotations. The list of
   * communications should contain the sources of each item in
   * the clustering.
   */
  @Override
//  public Pair<Clustering, List<Communication>> next();
  public Topic next();

  

  public static class Chain implements ConcreteIkbpAnnotations {
    private ConcreteIkbpAnnotations head;
    private ConcreteIkbpAnnotations tail;
    
    public Chain(ConcreteIkbpAnnotations head, ConcreteIkbpAnnotations tail) {
      if (!head.getName().equals(tail.getName()))
        throw new IllegalArgumentException();
      this.head = head;
      this.tail = tail;
    }

    @Override
    public boolean hasNext() {
      return head.hasNext() || tail.hasNext();
    }

    @Override
    public String getName() {
      return head.getName();
    }

    @Override
    public Topic next() {
      if (head.hasNext())
        return head.next();
      return tail.next();
    }
  }
  

  public static Communication lookup(List<Communication> dict, UUID key) {
    for (Communication sms : dict) {
      if (sms.getUuid().equals(key))
        return sms;
    }
    Log.info("WARNING: couldn't find Communication with id "
        + key.getUuidString());
    return null;
  }

  public static EntityMentionSet lookupEms(Communication dict, UUID key) {
    for (EntityMentionSet sms : dict.getEntityMentionSetList()) {
      if (sms.getUuid().equals(key))
        return sms;
    }
    Log.info("WARNING: couldn't find EntityMentionSet with id "
        + key.getUuidString() + " in Communication "
        + dict.getId() + " aka " + dict.getUuid().getUuidString());
    return null;
  }

  public static SituationMentionSet lookupSms(Communication dict, UUID key) {
    for (SituationMentionSet sms : dict.getSituationMentionSetList()) {
      if (sms.getUuid().equals(key))
        return sms;
    }
    Log.info("WARNING: couldn't find SituationMentionSet with id "
        + key.getUuidString() + " in Communication "
        + dict.getId() + " aka " + dict.getUuid().getUuidString());
    return null;
  }

  public static EntityMention lookup(EntityMentionSet dict, UUID key) {
    for (EntityMention sm : dict.getMentionList()) {
      if (sm.getUuid().equals(key))
        return sm;
    }
    Log.info("WARNING: couldn't find EntityMention with id "
        + key.getUuidString() + " in EntityMentionSet "
        + dict.getUuid().getUuidString());
    return null;
  }

  public static SituationMention lookup(SituationMentionSet dict, UUID key) {
    for (SituationMention sm : dict.getMentionList()) {
      if (sm.getUuid().equals(key))
        return sm;
    }
    Log.info("WARNING: couldn't find SituationMention with id "
        + key.getUuidString() + " in SituationMentionSet "
        + dict.getUuid().getUuidString());
    return null;
  }
}
