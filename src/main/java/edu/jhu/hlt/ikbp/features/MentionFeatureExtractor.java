package edu.jhu.hlt.ikbp.features;

import java.util.List;

import edu.jhu.hlt.ikbp.data.FeatureType;
import edu.jhu.hlt.ikbp.data.Id;

public interface MentionFeatureExtractor {
  
  /**
   * @param m_id should have type {@link FeatureType}.MENTION
   */
  public void extract(String m_id, List<Id> addTo);

}
