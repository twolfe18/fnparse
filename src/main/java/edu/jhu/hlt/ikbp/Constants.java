package edu.jhu.hlt.ikbp;

public class Constants {
  
  public enum NodeIdType {
    ENTITY,
    SITUATION,
    MENTION_ENT,
    MENTION_SIT,
  }
  
  public enum EdgeRelationType {
    COREF_SIT,
    COREF_ENT,
  }

  public enum FeatureType {
    REGULAR,  // just interpret as a regular string-indexed feature
    MENTION,  // names of the form "<doc>/<m_id>" e.g. "12_6ecb/11"
  }
}
