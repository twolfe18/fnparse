package edu.jhu.hlt.ikbp.features;

import java.util.List;

import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.ikbp.data.PKB;
import edu.jhu.hlt.ikbp.data.Query;

/**
 * Local features describing a mention. This will likely serve as input to
 * a bilinear embedding model, so try to keep these features tight (small
 * nnz per instance and small-ish vocabulary).
 * 
 * DO NOT worry about global/state features yet (which get to look at the
 * {@link Query}'s context {@link PKB}. Those features aren't implemented
 * by this interface.
 *
 * @author travis
 */
public interface MentionFeatureExtractor {
  
  /**
   * @param m_id depends on the implementation but should pick out a mention.
   * e.g. for {@link ConcreteMentionFeatureExtractor} m_id will be a {@link SituationMention} or {@link EntityMention} UUID string.
   */
//  public void extract(String m_id, List<Id> addTo);
  public void extract(Node n, List<Id> addTo);

}
