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

  // TODO MENTION values should have a few different prefixes
  // like "id:12_6ecb/11" for lookup by id
  // or "cs:1244:1256" where "cs" == "character span"
  // or "ts:23:25" where "ts" == "token span"
  public enum FeatureType {
    REGULAR,      // just interpret as a regular string-indexed feature
    MENTION,      // names of the form "<doc>/<m_id>" e.g. "12_6ecb/11"
    NODE_TYPE,    // values are things like Entity vs Situation or EcbPlusMentionType values
    
    INTERCEPT,
    HEADWORD,
    HEADPOS,
    FIRSTWORD,
    FIRSTPOS,
    LASTWORD,
    LASTPOS,
    LEFTWORD,
    LEFTPOS,
    RIGHTWORD,
    RIGHTPOS,
    PARENTWORD,
    PARENTPOS,
  }
}
