package edu.jhu.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.Tokenization;

public class TokenizationIter implements Iterable<Tokenization> {
  
  private Communication comm;
  
  public TokenizationIter(Communication comm) {
    this.comm = comm;
  }

  @Override
  public Iterator<Tokenization> iterator() {
    List<Tokenization> toks = new ArrayList<>();
    if (comm.isSetSectionList()) {
      for (Section section : comm.getSectionList()) {
        if (section.isSetSentenceList()) {
          for (Sentence sent : section.getSentenceList()) {
            toks.add(sent.getTokenization());
          }
        }
      }
    }
    return toks.iterator();
  }

}
