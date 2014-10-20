package edu.jhu.hlt.fnparse.util;

import edu.jhu.hlt.fnparse.datatypes.Sentence;

public class SentencePosition {
  public int index;
  public Sentence sentence;

  public boolean indexInSent() {
    return index >= 0 && index < sentence.size();
  }
}
