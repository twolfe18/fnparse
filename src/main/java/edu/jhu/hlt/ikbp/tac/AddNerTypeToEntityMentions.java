package edu.jhu.hlt.ikbp.tac;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.tutils.StringUtils;

public class AddNerTypeToEntityMentions {
  
  private Communication comm;
//  private Map<String, Tokenization> tokz;
  private String emTool = "Stanford Coref";
  private String nerTool = "Stanford CoreNLP";
  
  public AddNerTypeToEntityMentions(Communication c) {
    this(c, buildTokzIndex(c));
  }
  public AddNerTypeToEntityMentions(Communication c, Map<String, Tokenization> tokz) {
    this.comm = c;
//    this.tokz = tokz;
    
    if (!comm.isSetEntityMentionSetList())
      return;
    for (EntityMentionSet ems : comm.getEntityMentionSetList()) {
      if (!emTool.equals(ems.getMetadata().getTool()))
        continue;
      for (EntityMention em : ems.getMentionList()) {
        TokenRefSequence trs = em.getTokens();
        String tag = getNerType(trs, tokz, nerTool);
        em.setEntityType(tag);
      }
    }
  }
  
  /**
   * Computes the NER type for an arbitrary span. If no NER span exactly matches,
   * this will return a string containing all of the NER tags in the sequence,
   * e.g. "Obama, president of the United States" => "PER,O,LOC"
   *
   * @param trs is the span which we are computing an NER type for.
   * @param tokz is a map containing Tokenization UUID string keys.
   * @param nerTool is the preferred NER tool to use, must match.
   */
  public static String getNerType(TokenRefSequence trs, Map<String, Tokenization> tokz, String nerTool) {
    if (trs == null)
      throw new IllegalArgumentException("must provide TokenRefSequence");
    if (tokz == null || nerTool == null)
      throw new IllegalArgumentException();
    Tokenization t = tokz.get(trs.getTokenizationId().getUuidString());
    List<TaggedToken> tags = null;
    for (TokenTagging tt : t.getTokenTaggingList()) {
      if (!"NER".equals(tt.getTaggingType()))
        continue;
      if (!nerTool.equals(tt.getMetadata().getTool()))
        continue;
      assert tags == null;
      tags = tt.getTaggedTokenList();
    }
    assert tags != null;
    String tag;
    if (trs.isSetAnchorTokenIndex() && trs.getAnchorTokenIndex() >= 0
        && !((tag = t(trs.getAnchorTokenIndex(), tags))).equals("O")) {
      // Head tag
      return tag;
    } else {
      // All tags
      Deque<String> stag = new ArrayDeque<>();
      for (int i : trs.getTokenIndexList()) {
        tag = t(i, tags);
        if (stag.isEmpty())
          stag.addLast(tag);
        else if (!tag.equals(stag.peekLast()))
          stag.addLast(tag);
      }
      return StringUtils.join(",", stag);
    }
  }
  
  public static String t(int i, List<TaggedToken> tags) {
    String tag = tags.get(i).getTag();
    int d = tag.indexOf('-');
    if (d >= 0)   // skip tag prefix, [BI]-(tag)
      tag = tag.substring(d+1);
    return tag;
  }

  public static Map<String, Tokenization> buildTokzIndex(Communication c) {
    Map<String, Tokenization> m = new HashMap<>();
    if (c.isSetSectionList()) {
      for (Section section : c.getSectionList()) {
        if (section.isSetSentenceList()) {
          for (Sentence sentence : section.getSentenceList()) {
            Tokenization t = sentence.getTokenization();
            Object old = m.put(t.getUuid().getUuidString(), t);
            assert old == null;
          }
        }
      }
    }
    return m;
  }
}
