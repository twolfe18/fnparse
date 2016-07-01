package edu.jhu.hlt.fnparse.inference.heads;

import java.io.Serializable;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.tutils.Span;

public interface HeadFinder extends Serializable {

  public int head(Span s, Sentence sent);

  default public int headSafe(Span s, Sentence sent) {
    if (s.width() == 1)
      return s.start;
    int h = head(s, sent);
    if (h < 0 || h > sent.size())
      h = s.end - 1;
    return h;
  }
}
