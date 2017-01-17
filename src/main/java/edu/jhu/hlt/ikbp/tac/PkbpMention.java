package edu.jhu.hlt.ikbp.tac;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.StringTermVec;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.hash.Hash;

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
   * TODO Do not keep this here! This is a shared resource and should be looked up by a owner of the resource.
   */
  private StringTermVec context;

  
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
  
  public StringTermVec getContext() {
    if (context == null) {
      context = new StringTermVec(getCommunication());
    }
    return context;
  }
  
  @Override
  public String toString() {
    String[] cn = getClass().getName().split("\\.");
    return "(" + cn[cn.length-1] + " h=" + getHeadString() + "@" + head + " in " + getCommTokIdShort() + ")";
  }
    
  /** returns "commId/tokUuidSuf" */
  public String getCommTokIdShort() {
    return getCommunicationId() + "/" + tokUuid.substring(tokUuid.length()-4, tokUuid.length());
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

  public String getHeadString() {
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
}
