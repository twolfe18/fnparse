package edu.jhu.hlt.fnparse.datatypes;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.tutils.Span;

/**
 * a Sentence with Frame's identified with their arguments.
 * 
 * @author travis
 */
public class FNParse extends FNTagging implements Serializable {
  private static final long serialVersionUID = -1190604209550969930L;

  /**
   * @param s is the Sentence that has been parsed
   * @param frameInstances are the frames that appear in the Sentence
   *        (must have arguments)
   * @param isFullParse is true if an attempt has been made to annotate all
   *        Frames that might appear in this sentence. A case where this will be
   *        false is for lexical examples from FrameNet (where only one Frame is
   *        annotated).
   */
  public FNParse(Sentence s, List<FrameInstance> frameInstances) {
    super(s, frameInstances);
    for(FrameInstance fi : frameInstances) {
      if(fi.onlyTargetLabeled())
        throw new IllegalArgumentException();
    }
  }

  /**
   * Convenience method. Doesn't cache/memoize or mutate this instance.
   * 
   * Only includes non-null roles/args in keys.
   */
  public Map<FrameRoleInstance, Span> getMapRepresentation() {
    Map<FrameRoleInstance, Span> explicit = new HashMap<>();
    for (FrameInstance fi : this.frameInstances) {
      Frame f = fi.getFrame();
      for (int k = 0; k < f.numRoles(); k++) {
        Span s = fi.getArgument(k);
        if (s == Span.nullSpan) continue;
        FrameRoleInstance key = new FrameRoleInstance(f, fi.getTarget(), k);
        Span old = explicit.put(key, s);
        assert old == null;
      }
    }
    return explicit;
  }

  /**
   * Convenience method. Doesn't cache/memoize or mutate this instance.
   *
   * Only includes non-null roles/args in keys.
   */
  public List<FrameArgInstance> getListRepresentation() {
    List<FrameArgInstance> l = new ArrayList<>();
    for (FrameInstance fi : this.frameInstances) {
      Span t = fi.getTarget();
      Frame f = fi.getFrame();
      for (int k = 0; k < f.numRoles(); k++) {
        Span s = fi.getArgument(k);
        if (s == Span.nullSpan) continue;
        l.add(new FrameArgInstance(f, t, k, s));
      }
    }
    return l;
  }

  public boolean hasContOrRefRoles() {
    for (FrameInstance fi : getFrameInstances()) {
      Frame f = fi.getFrame();
      int K = f.numRoles();
      for (int k = 0; k < K; k++) {
        if (!fi.getContinuationRoleSpans(k).isEmpty())
          return true;
        if (!fi.getReferenceRoleSpans(k).isEmpty())
          return true;
      }
    }
    return false;
  }
}
