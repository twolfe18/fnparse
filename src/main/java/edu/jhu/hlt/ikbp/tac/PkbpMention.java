package edu.jhu.hlt.ikbp.tac;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.util.TokenizationIter;

public class PkbpMention implements Serializable {
  private static final long serialVersionUID = 795646509667723395L;

  /** TODO Do not keep this here! This is a shared resource and should be looked up by a owner of the resource. */
  Communication comm;
  String commId;

  Tokenization toks;
  String tokUuid;

  DependencyParse deps;

  public final int head;

  /**
   * for entity mentions: triage features
   * for situation mentions: {@link DependencySyntaxEvents.CoverArgumentsWithPredicates#situation2features}
   */
  private List<Feat> feats;
  
  /**
   * term-freq vector for document containing this mention
   */
  private StringTermVec contextDoc;
  
  /** term-freq vector for words near this mention */
  private StringTermVec contextLocal;

  
  public PkbpMention(int head, Tokenization toks, DependencyParse deps, Communication comm) {
    this(head, toks.getUuid().getUuidString(), toks, deps, comm.getId(), comm);
  }

  public PkbpMention(int head, String tokUuid, Tokenization toks, DependencyParse deps, String commId, Communication comm) {
    if (comm != null && commId != null && !comm.getId().equals(commId))
      throw new IllegalArgumentException();
    if (tokUuid != null && toks != null && !toks.getUuid().getUuidString().equals(tokUuid))
      throw new IllegalArgumentException();
    this.commId = commId;
    this.comm = comm;
    this.deps = deps;
    this.toks = toks;
    this.tokUuid = tokUuid;
    this.head = head;
    this.feats = new ArrayList<>();
  }

  public String getHeadNer() {
    TokenTagging ner = IndexCommunications.getPreferredNerTags(toks);
    return ner.getTaggedTokenList().get(head).getTag();
  }

  public String getHeadPos() {
    TokenTagging pos = IndexCommunications.getPreferredPosTags(toks);
    return pos.getTaggedTokenList().get(head).getTag();
  }
  
  public StringTermVec getContextDoc() {
    if (contextDoc == null) {
      contextDoc = new StringTermVec(getCommunication());
    }
    return contextDoc;
  }
  
  public StringTermVec getContextLocal() {
    if (contextLocal == null) {
      Map<String, Integer> t2i = new HashMap<>();
      List<Tokenization> toks = new ArrayList<>();
      int idx = 0;
      int idxThisTok = -1;
      for (Tokenization t : new TokenizationIter(getCommunication())) {
        String id = t.getUuid().getUuidString();
        toks.add(t);
        t2i.put(id, idx);
        if (id.equals(tokUuid)) {
          assert idxThisTok < 0;
          idxThisTok = idx;
        }
        idx++;
      }
      if (idxThisTok < 0)
        throw new RuntimeException();
      contextLocal = new StringTermVec();
      for (int i = 0; i < idx; i++) {
        int dist = Math.abs(idxThisTok - i);
        double w = 2 / (1 + dist);
        for (Token t : toks.get(i).getTokenList().getTokenList())
          contextLocal.add(t.getText(), w);
      }
    }
    return contextLocal;
  }
  
  @Override
  public String toString() {
    String[] cn = getClass().getName().split("\\.");
    return "(" + cn[cn.length-1] + " h=" + getHeadWord() + "@" + head + " in " + getCommTokIdShort() + ")";
  }
  
  /** returns strings like "said.V@12", human readable and uniq within a {@link Tokenization} */
  public String getHeadWordAndPosition() {
    return getHeadWord() + "." + getHeadPos().charAt(0) + "@" + head;
  }

  /** returns "commId/tokUuidSuf" */
  public String getCommTokIdShort() {
    return getCommunicationId() + "/" + tokUuid.substring(tokUuid.length()-4, tokUuid.length());
  }
  
  public String getCommTokHeadWordAndLoc() {
    return getCommTokIdShort() + "/" + getHeadWordAndPosition();
  }
  
  @Override
  public int hashCode() {
    return Hash.mix(tokUuid.hashCode(), head);
  }
  
  @Override
  public boolean equals(Object other) {
    if (other instanceof PkbpMention) {
      PkbpMention m = (PkbpMention) other;
      return head == m.head && tokUuid.equals(m.tokUuid);
    }
    return false;
  }

  public String getHeadLemma() {
    TokenTagging lemma = IndexCommunications.getPreferredLemmas(getTokenization());
    TaggedToken tt = lemma.getTaggedTokenList().get(head);
    assert tt.getTokenIndex() == head;
    return tt.getTag();
  }

  public String getHeadWord() {
    return getTokenization().getTokenList().getTokenList().get(head).getText();
  }
  
  public String getCommunicationId() {
    return commId;
  }
  
  public void setCommunication(Communication c) {
    this.comm = c;
    if (commId == null)
      commId = c.getId();
    else
      assert commId.equals(c.getId());
  }
  
  public Communication getCommunication() {
    return comm;
  }
  
  public Tokenization getTokenization() {
    return toks;
  }
  
  public Iterable<Feat> getFeatures() {
    return feats;
  }
  
  public void addFeature(String name, double weight) {
    addFeature(new Feat(name, weight));
  }
  
  public void addFeature(Feat f) {
    feats.add(f);
  }

  public String getContextAroundHead() {
    return getContextAroundHead(false);
  }
  public String getContextAroundHead(boolean forHtml) {
    StringBuilder sb = new StringBuilder();
    if (head < 0)
      sb.append(forHtml ? "[NO_HEAD/]" : "<NO_HEAD/>");
    List<Token> toks = getTokenization().getTokenList().getTokenList();
    for (Token t : toks) {
      sb.append(' ');
      if (t.getTokenIndex() == head)
        sb.append(forHtml ? "[HEAD]" : "<HEAD>");
      sb.append(t.getText());
      if (t.getTokenIndex() == head)
        sb.append(forHtml ? "[/HEAD]" : "</HEAD>");
    }
    return sb.toString();
  }

  public String getContextAroundHead(int charsLeft, int charsRight, boolean whitespacePadding) {
    StringBuilder sb = new StringBuilder();

    if (whitespacePadding)
      for (int i = 0; i < charsLeft; i++)
        sb.append(' ');
    
    int start = -1, end = -1;
    
    List<Token> toks = getTokenization().getTokenList().getTokenList();
    for (Token t : toks) {
      sb.append(' ');
      if (t.getTokenIndex() == head) {
        start = sb.length();
        sb.append("[HEAD]");
      }
      sb.append(t.getText());
      if (t.getTokenIndex() == head) {
        sb.append("[/HEAD]");
        end = sb.length();
      }
    }

    if (whitespacePadding) {
      for (int i = 0; i < charsRight; i++)
        sb.append(' ');
    }
    
    String all = sb.toString();
    if (whitespacePadding)
      return all.substring(start - charsLeft, end + charsRight);

    start = Math.max(0, start - charsLeft);
    end = Math.min(all.length(), end + charsLeft);
    return all.substring(start, end);
  }
  
//  public String getContextAroundHead(int charsLeft, int charsRight, boolean whitespacePadding) {
//    Deque<String> lc = new ArrayDeque<>();    // LIFO
//    List<String> rc = new ArrayList<>();      // FIFO
//    List<Token> toks = getTokenization().getTokenList().getTokenList();
//    String headStr = null;
//    for (Token t : toks) {
//      if (t.getTokenIndex() < head) {
//        if (!lc.isEmpty()) lc.push(" ");
//        lc.push(t.getText());
//      } else if (t.getTokenIndex() > head) {
//        if (!rc.isEmpty()) rc.add(" ");
//        rc.add(t.getText());
//      } else {
//        headStr = t.getText();
//      }
//    }
//    
//    StringBuilder ls = new StringBuilder();
//    while (!lc.isEmpty() && ls.length() < charsLeft)
//      ls.append(lc.pop());
//    if (whitespacePadding) {
//      String r = ls.toString();
//      ls = new StringBuilder();
//      while (ls.length() + r.length() < charsLeft)
//        ls.append(' ');
//      ls.append(r);
//    }
//    
//    StringBuilder rs = new StringBuilder();
//    for (int i = 0; i < rc.size() && rs.length() < charsRight; i++)
//      rs.append(rc.get(i));
////    if (whitespacePadding) {
////      while (rs.length() < charsRight)
////        rs.append(' ');
////    }
//    
//    return ls.toString() + " <HEAD>" + headStr + "</HEAD> " + rs.toString();
//  }
}
