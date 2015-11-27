package edu.jhu.hlt.fnparse.inference.heads;

import java.io.Serializable;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.tutils.Span;

public interface HeadFinder extends Serializable {
  public int head(Span s, Sentence sent);
}
