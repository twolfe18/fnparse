package edu.jhu.hlt.fnparse.inference.heads;

import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.tutils.Span;

/**
 * Given a span, find the token which is closest to root.
 *
 * @author travis
 */
public class DependencyHeadFinder implements HeadFinder {
  private static final long serialVersionUID = 310997481386881205L;

  public enum Mode {
    PARSEY,
    BASIC,
  }

  private Mode mode;

  public DependencyHeadFinder() {
    this(Mode.PARSEY);
  }

  public DependencyHeadFinder(Mode m) {
    mode = m;
  }

  public DependencyParse getDeps(Sentence s) {
    switch (mode) {
    case PARSEY:
      return s.getParseyDeps();
    case BASIC:
      return s.getBasicDeps();
    default:
      throw new RuntimeException("mode=" + mode);
    }
  }

  @Override
  public int head(Span s, Sentence sent) {
    if (s.start < 0 || s.end > sent.size())
      throw new IllegalArgumentException(s.shortString() + " is not in [" + sent.size() + "]");
    if (s.width() == 1)
      return s.start;
    DependencyParse deps = getDeps(sent);
    int h = -1;
    int hd = 0;
    for (int i = s.start; i < s.end; i++) {
      if (sent.isPunc(i) || deps.getLabel(i).equals("UNK"))
        continue;

      int d = deps.getDepth(i);
      if (h < 0 || d < hd) {
        h = i;
        hd = d;
      }
    }
    if (h < 0)
      return s.end-1;
    return h;
  }
}
