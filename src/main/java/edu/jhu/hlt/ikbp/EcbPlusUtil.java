package edu.jhu.hlt.ikbp;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.ikbp.Constants.FeatureType;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.tutils.hash.Hash;

public class EcbPlusUtil {
  
  enum EcbPlusMentionType {
    NON_HUMAN_PART,
    NON_HUMAN_PART_GENERIC,
    HUMAN_PART,
    HUMAN_PART_MET,
    HUMAN_PART_PER,
    HUMAN_PART_VEH,
    HUMAN_PART_ORG,
    HUMAN_PART_GPE,
    HUMAN_PART_FAC,
    HUMAN_PART_GENERIC,
    ACTION_CAUSATIVE,
    ACTION_OCCURRENCE,
    ACTION_REPORTING,
    ACTION_PERCEPTION,
    ACTION_ASPECTUAL,
    ACTION_STATE,
    ACTION_GENERIC,
    NEG_ACTION_CAUSATIVE,
    NEG_ACTION_OCCURRENCE,
    NEG_ACTION_REPORTING,
    NEG_ACTION_PERCEPTION,
    NEG_ACTION_ASPECTUAL,
    NEG_ACTION_STATE,
    NEG_ACTION_GENERIC,
    LOC_GEO,
    LOC_FAC,
    LOC_OTHER,
    TIME_DATE,
    TIME_OF_THE_DAY,
    TIME_DURATION,
    TIME_REPETITION,
    UNKNOWN_INSTANCE_TAG,
  }

  public static List<String> getMentionIds(Node n) {
    List<String> m = new ArrayList<>();
    for (Id f : n.getFeatures()) {
      if (f.getType() == Constants.FeatureType.MENTION.ordinal()) {
        assert f.getName() != null;
        m.add(f.getName());
      }
    }
    return m;
  }

  /** Accepts mention id (m_id) like "12_3ecb/26" and return "12_3ecb" */
  public static String getDocumentId(String id) {
    String[] toks = id.split("/");
    assert toks.length == 2;
    return toks[0];
  }

  /** Accepts strings like "12_3ecb/26" and return "12" */
  public static String getTopic(String id) {
    String[] toks = id.split("_");
    assert toks.length == 2;
    return toks[0];
  }
  
  /** Before calling, you should ensure that the given node's m_id has the document prefix */
  public static Node createNode(EcbPlusXmlWrapper.Node n, String[] tokens) {
    EcbPlusUtil.EcbPlusMentionType t;
    try {
      t = EcbPlusUtil.EcbPlusMentionType.valueOf(n.type);
    } catch (IllegalArgumentException e) {
      if (EcbPlusAnnotator.VERBOSE)
        System.out.println("ERROR: TODO add this enum instance: " + n.type);
      t = EcbPlusUtil.EcbPlusMentionType.ACTION_OCCURRENCE;
    }
  
    // Add this mention to the PKB as a Node
    Id id = new Id();
    id.setType(t.ordinal());
    id.setName(n.m_id);
    id.setId((int) Hash.sha256(n.type + "/" + n.m_id));
    Node kbNode = new Node();
    kbNode.setId(id);
    kbNode.addToFeatures(EcbPlusAnnotator.s2f(n.type, FeatureType.NODE_TYPE));
    kbNode.addToFeatures(EcbPlusAnnotator.s2f("ground=" + n.isGrounded()));
    if (n.isGrounded()) {
      kbNode.addToFeatures(EcbPlusAnnotator.s2f(n.m_id, FeatureType.MENTION));
    } else {
      //          kbNode.addToFeatures(s2f("desc=" + n.descriptor));
    }
  
    return kbNode;
  }

}
