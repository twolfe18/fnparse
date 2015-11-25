package edu.jhu.hlt.fnparse.datatypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.prim.tuple.Pair;

/**
 * Builds an index over {@link FNParse}s to answer questions about a label
 * such as "are there an args at span (i,j)?".
 *
 * @author travis
 */
public class LabelIndex {

  private final FNParse y;
  private Map<Span, List<FrameInstance>> byTarget;
  private Set<Pair<Span, Frame>> fis;

  private Map<int[], Set<int[]>> all;
  private Map<FrameArgInstance, Set<FrameArgInstance>> all2;

  public LabelIndex(FNParse y) {
    this.y = y;
    this.byTarget = new HashMap<>();
    this.fis = new HashSet<>();

    this.all = new HashMap<>();
    this.all2 = new HashMap<>();

    for (FrameInstance fi : y.getFrameInstances()) {
      Span t = fi.getTarget();
      Frame f = fi.getFrame();
      Pair<Span, Frame> tf = new Pair<>(t, f);

      List<FrameInstance> fis = byTarget.get(t);
      if (fis == null) fis = new ArrayList<>();
      fis.add(fi);
      this.byTarget.put(t, fis);
      this.fis.add(tf);

      int K = f.numRoles();
      for (int k = 0; k < K; k++) {
        Span s = fi.getArgument(k);
        if (s == null || s == Span.nullSpan)
          continue;

//        int[] v = encode(t, f, k, s);
//        add(encode(), v);
//        add(encode(t, f), v);
//        add(encode(t, f, k), v);
//        add(encode(t, f, s), v);

        Span tt = fi.getTarget();
        FrameArgInstance val = new FrameArgInstance(f, t, k, s);
        add(new FrameArgInstance(null, null, -1, null), val);
        add(new FrameArgInstance(null, tt, -1, null), val);
        add(new FrameArgInstance(f, tt, -1, null), val);
        add(new FrameArgInstance(f, tt, k, null), val);
        add(new FrameArgInstance(f, tt, -1, s), val);
        add(new FrameArgInstance(f, tt, k, s), val);
      }
    }
    throw new RuntimeException("add cont/ref roles!");
  }

  private void add(FrameArgInstance key, FrameArgInstance value) {
    Set<FrameArgInstance> vals = all2.get(key);
    if (vals == null) {
      vals = new HashSet<>();
      all2.put(key, vals);
    }
    vals.add(value);
  }

//  private void add(int[] k, int[] v) {
//    Set<int[]> vs = all.get(k);
//    if (vs == null) {
//      vs = new HashSet<>();
//      all.put(k, vs);
//    }
//    vs.add(v);
//  }

  public Set<int[]> get(int[] k) {
    return all.getOrDefault(k, Collections.emptySet());
  }


  public Set<FrameArgInstance> get() {
    return all2.getOrDefault(new FrameArgInstance(null, null, -1, null), Collections.emptySet());
  }
  public Set<FrameArgInstance> get(Span t) {
    return all2.getOrDefault(new FrameArgInstance(null, t, -1, null), Collections.emptySet());
  }
  public Set<FrameArgInstance> get(Span t, Frame f) {
    return all2.getOrDefault(new FrameArgInstance(f, t, -1, null), Collections.emptySet());
  }
  public Set<FrameArgInstance> get(Span t, Frame f, int k) {
    return all2.getOrDefault(new FrameArgInstance(f, t, k, null), Collections.emptySet());
  }
  public Set<FrameArgInstance> get(Span t, Frame f, Span s) {
    return all2.getOrDefault(new FrameArgInstance(f, t, -1, s), Collections.emptySet());
  }
  public Set<FrameArgInstance> get(Span t, Frame f, int k, Span s) {
    return all2.getOrDefault(new FrameArgInstance(f, t, k, s), Collections.emptySet());
  }

  /*
   * Can I just pack cont/ref roles down into a single k:int?
   * [0,k) are normal
   * [K,2K) are continuation
   * [2K,3K) are reference
   *
   * The only reason this solution isn't used everywhere is that the
   * transition system needs to be aware of cont/ref roles so that loops
   * aren't 3x longer than they need to be.
   */

  public boolean contains(Span t, Frame f, int k) {
//    return get(encode(t, f, k)).size() > 0;
    return all2.containsKey(new FrameArgInstance(f, t, k, null));
  }

  public boolean contains(Span t, Frame f, Span s) {
//    return get(encode(t, f, s)).size() > 0;
    return all2.containsKey(new FrameArgInstance(f, t, -1, s));
  }

  public boolean contains(Span t, Frame f, int k, Span s) {
//    return get(encode(t, f, k, s)).size() > 0;
    return all2.containsKey(new FrameArgInstance(f, t, k, s));
  }

//  public static int[] encode() {
//    return new int[] {-2};
//  }
//
//  public static int[] encode(Span t, Frame f) {
//    return new int[] {-1, t.start, t.end, f.getId()};
//  }
//
//  public static int[] encode(Span t, Frame f, int k) {
//    return new int[] {0, t.start, t.end, f.getId(), k};
//  }
//
//  public static int[] encode(Span t, Frame f, Span s) {
//    return new int[] {1, t.start, t.end, f.getId(), s.start, s.end};
//  }
//
//  public static int[] encode(Span t, Frame f, int k, Span s) {
//    return new int[] {2, t.start, t.end, f.getId(), k, s.start, s.end};
//  }

  public FNParse getParse() { return y; }

  /** Never return null */
  public List<FrameInstance> getFramesAtTarget(Span t) {
    return byTarget.getOrDefault(t, Collections.emptyList());
  }

  public boolean containsTarget(Span target) {
    return byTarget.containsKey(target);
  }

  public boolean contains(Span target, Frame f) {
    return fis.contains(new Pair<>(target, f));
  }

  public Set<Pair<Span, Frame>> borrowTargetFrameSet() {
    return fis;
  }

  public Set<Pair<Span, Frame>> buildTargetFrameSet() {
    Set<Pair<Span, Frame>> tf = new HashSet<>();
    for (FrameInstance fi : y.getFrameInstances())
      tf.add(new Pair<>(fi.getTarget(), fi.getFrame()));
    return tf;
  }

  public Set<Span> borrowTargetSet() {
    return byTarget.keySet();
  }

  public Set<Span> buildTargetSet() {
    Set<Span> tf = new HashSet<>();
    for (FrameInstance fi : y.getFrameInstances())
      tf.add(fi.getTarget());
    return tf;
  }

}
