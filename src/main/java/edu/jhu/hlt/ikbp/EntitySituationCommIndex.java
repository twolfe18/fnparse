package edu.jhu.hlt.ikbp;

import java.util.HashMap;
import java.util.Map;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.UUID;

/**
 * Builds an index for {@link EntityMention}s and {@link SituationMention}s
 * in a {@link Communication} by their {@link UUID}.
 *
 * @author travis
 */
public class EntitySituationCommIndex {
  
  private Communication comm;
  private Map<String, EntityMention> emIndex;
  private Map<String, SituationMention> smIndex;
  
  // TODO add hints for which EMS and SMS can be skipped, e.g. by tool name.
  public EntitySituationCommIndex(Communication c) {
    this.comm = c;
  }

  public EntityMention getEntityMentionById(UUID id) {
    if (emIndex == null) {
      emIndex = new HashMap<>();
      for (EntityMentionSet ems : comm.getEntityMentionSetList()) {
        for (EntityMention em : ems.getMentionList()) {
          Object old = emIndex.put(em.getUuid().getUuidString(), em);
          assert old == null;
        }
      }
    }
    return emIndex.get(id.getUuidString());
  }

  public SituationMention getSituationMentionById(UUID id) {
    if (smIndex == null) {
      smIndex = new HashMap<>();
      for (SituationMentionSet ems : comm.getSituationMentionSetList()) {
        for (SituationMention em : ems.getMentionList()) {
          Object old = smIndex.put(em.getUuid().getUuidString(), em);
          assert old == null;
        }
      }
    }
    return smIndex.get(id.getUuidString());
  }
}
