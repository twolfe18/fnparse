package edu.jhu.hlt.fnparse.util;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;

public class SentencePosition {
  public int index;
  public Sentence sentence;

  public SentencePosition() {
    this.index = TemplateContext.UNSET;
    this.sentence = null;
  }

  public SentencePosition(Sentence s, int i) {
    this.index = i;
    this.sentence = s;
  }

  public boolean indexInSent() {
    return index >= 0 && index < sentence.size();
  }
}
