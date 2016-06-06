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

  @Override
  public int head(Span s, Sentence sent) {
    DependencyParse deps = sent.getBasicDeps();
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
    return h;
  }
}
