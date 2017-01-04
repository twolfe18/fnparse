package edu.jhu.hlt.ikbp.tac;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.StringTermVec;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;

public class PkbpMention implements Serializable {
  private static final long serialVersionUID = 795646509667723395L;

  Communication comm;
  String commId;

  Tokenization toks;
  String tokUuid;

  DependencyParse deps;

  public final int head;
  private List<Feat> feats;
  
  StringTermVec context;
  
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
    this.head = head;
    this.feats = new ArrayList<>();
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