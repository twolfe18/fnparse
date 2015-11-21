package edu.jhu.hlt.fnparse.datatypes;

import java.util.ArrayList;
import java.util.BitSet;
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
//  private Set<Span> targets;

//  private Set<int[]> tfks;      // [t.start, t.end, f.id, k, s.start, s.end]
//  private Set<int[]> tfk;       // [t.start, t.end, f.id, k]
//  private Set<int[]> tfs;       // [t.start, t.end, f.id, s.start, s.end]

  // Roll-ups by leaving off various dimensions, see encode
//  private Map<int[], int[]> tf2;
//  private Map<int[], int[]> tfk2;
//  private Map<int[], int[]> tfs2;

  private Map<int[], Set<int[]>> all;

  // TODO convert this to a trie
//  private Map<Pair<Span, Frame>, Set<int[]>> tf2tfks;
//  private Map<Pair<Span, Frame>, Set<int[]>> tf2tfk;
//  private Map<Pair<Span, Frame>, Set<int[]>> tf2tfs;
//  private Map<Pair<Span, Frame>, Set<Pair<Integer, Span>>> tf2;
//  private Map<Pair<Span, Frame>, BitSet> tf2k;
//  private Map<Pair<Span, Frame>, Set<Span>> tf2s;

  public LabelIndex(FNParse y) {
    this.y = y;
    this.byTarget = new HashMap<>();
    this.fis = new HashSet<>();
//    this.targets = new HashSet<>();

    this.all = new HashMap<>();

//    this.tfks = new HashSet<>();
//    this.tfs = new HashSet<>();
//    this.tfk = new HashSet<>();
 
//    this.tf2 = new HashMap<>();

//    this.tf2 = new HashMap<>();
//    this.tf2k = new HashMap<>();
//    this.tf2s = new HashMap<>();
    for (FrameInstance fi : y.getFrameInstances()) {
      Span t = fi.getTarget();
      Frame f = fi.getFrame();
      Pair<Span, Frame> tf = new Pair<>(t, f);

      List<FrameInstance> fis = byTarget.get(t);
      if (fis == null) fis = new ArrayList<>();
      fis.add(fi);
      this.byTarget.put(t, fis);
      this.fis.add(tf);

//      Set<int[]> tf_tfks = new HashSet<>();
//      Set<int[]> tf_tfk = new HashSet<>();
//      Set<int[]> tf_tfs = new HashSet<>();

//      Set<Pair<Integer, Span>> ks = new HashSet<>();
      int K = f.numRoles();
      for (int k = 0; k < K; k++) {
        Span s = fi.getArgument(k);
        if (s == null || s == Span.nullSpan)
          continue;

//        tfks.add(new int[] {t.start, t.end, f.getId(), k, s.start, s.end});
//        tfks.add(new int[] {t.start, t.end, f.getId(), k});
//        tfks.add(new int[] {t.start, t.end, f.getId(), s.start, s.end});

//        tf_tfks.add(new int[] {t.start, t.end, f.getId(), k, s.start, s.end});
//        tf_tfk.add(new int[] {t.start, t.end, f.getId(), k});
//        tf_tfs.add(new int[] {t.start, t.end, f.getId(), s.start, s.end});

        int[] v = encode(t, f, k, s);
        add(encode(), v);
        add(encode(t, f), v);
        add(encode(t, f, k), v);
        add(encode(t, f, s), v);

//        BitSet bs = tf2k.get(tf);
//        if (bs == null) {
//          bs = new BitSet();
//          tf2k.put(tf, bs);
//        }
//        bs.set(k);
//
//        Set<Span> ss = tf2s.get(tf);
//        if (ss == null) {
//          ss = new HashSet<>();
//          tf2s.put(tf, ss);
//        }
//        ss.add(s);
//
//        ks.add(new Pair<>(k, s));
      }
//      tf2.put(tf, ks);

//      Set<int[]> prev;
//      prev = tf2tfks.put(tf, tf_tfks); assert prev == null;
//      prev = tf2tfk.put(tf, tf_tfk); assert prev == null;
//      prev = tf2tfs.put(tf, tf_tfs); assert prev == null;
    }
  }

  private void add(int[] k, int[] v) {
    Set<int[]> vs = all.get(k);
    if (vs == null) {
      vs = new HashSet<>();
      all.put(k, vs);
    }
    vs.add(v);
  }

  public Set<int[]> get(int[] k) {
    return all.getOrDefault(k, Collections.emptySet());
  }

  public boolean contains(Span t, Frame f, int k) {
    return get(encode(t, f, k)).size() > 0;
  }

  public boolean contains(Span t, Frame f, Span s) {
    return get(encode(t, f, s)).size() > 0;
  }

  public boolean contains(Span t, Frame f, int k, Span s) {
    return get(encode(t, f, k, s)).size() > 0;
  }

  public static int[] encode() {
    return new int[] {-2};
  }

  public static int[] encode(Span t, Frame f) {
    return new int[] {-1, t.start, t.end, f.getId()};
  }

  public static int[] encode(Span t, Frame f, int k) {
    return new int[] {0, t.start, t.end, f.getId(), k};
  }

  public static int[] encode(Span t, Frame f, Span s) {
    return new int[] {1, t.start, t.end, f.getId(), s.start, s.end};
  }

  public static int[] encode(Span t, Frame f, int k, Span s) {
    return new int[] {2, t.start, t.end, f.getId(), k, s.start, s.end};
  }

//  public BitSet getRoles(Span t, Frame f) {
//    BitSet bs = tf2k.get(new Pair<>(t, f));
//    if (bs == null) bs = new BitSet();
//    return bs;
//  }
//
//  public Set<Span> getSpans(Span t, Frame f) {
//    return tf2s.getOrDefault(new Pair<>(t, f), Collections.emptySet());
//  }
//
//  public Set<Pair<Integer, Span>> getArguments(Span t, Frame f) {
//    return tf2.getOrDefault(new Pair<>(t, f), Collections.emptySet());
//  }

//  public Set<int[]> borrowTfksSet(Span t, Frame f) {
//    return tf2tfks.getOrDefault(new Pair<>(t, f), Collections.emptySet());
//  }
//
//  public Set<int[]> borrowTfkSet(Span t, Frame f) {
//    return tf2tfk.getOrDefault(new Pair<>(t, f), Collections.emptySet());
//  }
//
//  public Set<int[]> borrowTfsSet(Span t, Frame f) {
//    return tf2tfs.getOrDefault(new Pair<>(t, f), Collections.emptySet());
//  }

//  public Set<int[]> borrowTfksSet() {
//    return tfks;
//  }
//
//  public Set<int[]> borrowTfkSet() {
//    return tfk;
//  }
//
//  public Set<int[]> borrowTfsSet() {
//    return tfs;
//  }
//
//  public Set<int[]> buildTfksSet() {
//    Set<int[]> s = new HashSet<>();
//    s.addAll(tfks);
//    return s;
//  }
//
//  public Set<int[]> buildTfkSet() {
//    Set<int[]> s = new HashSet<>();
//    s.addAll(tfk);
//    return s;
//  }
//
//  public Set<int[]> buildTfsSet() {
//    Set<int[]> s = new HashSet<>();
//    s.addAll(tfs);
//    return s;
//  }

  public FNParse getParse() { return y; }

  /** Never return null */
  public List<FrameInstance> getFramesAtTarget(Span t) {
    return byTarget.getOrDefault(t, Collections.emptyList());
  }

  public boolean containsTarget(Span target) {
//    return targets.contains(target);
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
