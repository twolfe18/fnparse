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
        String tag;
        if (trs.isSetAnchorTokenIndex() && trs.getAnchorTokenIndex() >= 0
            && !((tag = t(trs.getAnchorTokenIndex(), tags))).equals("O")) {
          // Head tag
//          System.out.println("head: " + tag);
          em.setEntityType(tag);
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
          em.setEntityType(StringUtils.join(",", stag));
//          System.out.println("all: " + em.getEntityType());
        }
      }
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
    for (Section section : c.getSectionList()) {
      if (!section.isSetSentenceList())
        continue;
      for (Sentence sentence : section.getSentenceList()) {
        Tokenization t = sentence.getTokenization();
        Object old = m.put(t.getUuid().getUuidString(), t);
        assert old == null;
      }
    }
    return m;
  }
}
