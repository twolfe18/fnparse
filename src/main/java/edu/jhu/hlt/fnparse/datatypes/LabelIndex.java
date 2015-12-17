package edu.jhu.hlt.fnparse.datatypes;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.fnparse.data.propbank.RoleType;
import edu.jhu.hlt.fnparse.rl.full2.AbstractTransitionScheme;
import edu.jhu.hlt.fnparse.rl.full2.LL;
import edu.jhu.hlt.fnparse.rl.full2.TV;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.HashableIntArray;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.prim.tuple.Pair;

/**
 * Builds an index over {@link FNParse}s to answer questions about a label
 * such as "are there an args at span (i,j)?".
 *
 * @author travis
 */
public class LabelIndex implements Serializable {
  private static final long serialVersionUID = -3723865423922884724L;

  private final FNParse y;
  private Map<Span, List<FrameInstance>> byTarget;
  private Set<Pair<Span, Frame>> fis;

  private Map<FrameArgInstance, Set<FrameArgInstance>> all2;


  // New stuff from AbstractTransitionSystem
  public Counts<HashableIntArray> getCounts2() {
    if (counts == null)
      throw new RuntimeException();
    return counts;
  }
  private Counts<HashableIntArray> counts;
  /**
   * @param goldYeses is a collection of relation indices s.t. y_i=1, all
   * other indices are assumed to be 0.
   *
   * We assume that the finest grain types come first in the label, e.g.
   *   s -> k -> f -> k -> null
   * for the transition system [T,F,K,S]
   */
  private void provideLabel(Iterable<LL<TV>> goldYeses) {
    if (AbstractTransitionScheme.DEBUG)
      Log.info("filling in counts");
    if (counts == null)
      counts = new Counts<>();
    else
      counts.clear();
    int prevLen = -1;
    for (LL<TV> x : goldYeses) {
      HashableIntArray xx = AbstractTransitionScheme.prefixValues2ar(x);
      if (prevLen < 0)
        prevLen = xx.length();
      else
        assert prevLen == xx.length();

//      counts.increment(xx);
//
//      // prefix counts (e.g. [t,f,k] counts)
//      for (LL<TV> cur = x.cdr(); cur != null; cur = cur.cdr()) {
//        HashableIntArray i = AbstractTransitionScheme.prefixValues2ar(cur);
//        counts.increment(i);
//      }
      for (LL<TV> cur = x; cur != null; cur = cur.cdr()) {
        HashableIntArray i = AbstractTransitionScheme.prefixValues2ar(cur);
        counts.increment(i);
      }
    }
    if (AbstractTransitionScheme.DEBUG) {
      for (HashableIntArray prefix : counts.getKeysSortedByCount(true)) {
        Log.info("count=" + counts.getCount(prefix) + " prefix=" + prefix);
      }
    }
  }
  public LabelIndex(FNParse y) {
    this(y, null);
  }

  public LabelIndex(FNParse y, AbstractTransitionScheme<FNParse, ?> ts) {
    this.y = y;

    if (ts != null) {
      Iterable<LL<TV>> conv = ts.encode(y);
      this.provideLabel(conv);
    }

    this.byTarget = new HashMap<>();
    this.fis = new HashSet<>();
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
        Span tt = fi.getTarget();
        RoleType q = RoleType.BASE;
        FrameArgInstance val = new FrameArgInstance(f, t, k, s);
        add(new FrameArgInstance(null, null, -1, null), val);
        add(new FrameArgInstance(null, tt, -1, null), val);
        add(new FrameArgInstance(f, tt, -1, null), val);
        add(new FrameArgInstance(f, tt, k(f,k,q), null), val);
        add(new FrameArgInstance(f, tt, -1, s), val);
        add(new FrameArgInstance(f, tt, k(f,k,q), s), val);

        for (Span sc : fi.getContinuationRoleSpans(k)) {
          add(new FrameArgInstance(f, tt, k(f,k,RoleType.CONT), null), val);
          add(new FrameArgInstance(f, tt, k(f,k,RoleType.CONT), sc), val);
        }
        for (Span sr : fi.getReferenceRoleSpans(k)) {
          add(new FrameArgInstance(f, tt, k(f,k,RoleType.REF), null), val);
          add(new FrameArgInstance(f, tt, k(f,k,RoleType.REF), sr), val);
        }
      }
    }
  }

  private void add(FrameArgInstance key, FrameArgInstance value) {
    Set<FrameArgInstance> vals = all2.get(key);
    if (vals == null) {
      vals = new HashSet<>();
      all2.put(key, vals);
    }
    vals.add(value);
  }

  public static int k(Frame f, int k, RoleType q) {
    int K = f.numRoles();
    return k + K * q.ordinal();
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
  public Set<FrameArgInstance> get(Span t, Frame f, int k, RoleType q) {
    return all2.getOrDefault(new FrameArgInstance(f, t, k(f,k,q), null), Collections.emptySet());
  }
  public Set<FrameArgInstance> get(Span t, Frame f, Span s) {
    return all2.getOrDefault(new FrameArgInstance(f, t, -1, s), Collections.emptySet());
  }
  public Set<FrameArgInstance> get(Span t, Frame f, int k, RoleType q, Span s) {
    return all2.getOrDefault(new FrameArgInstance(f, t, k(f,k,q), s), Collections.emptySet());
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

  public boolean contains(Span t, Frame f, int k, RoleType q) {
    return all2.containsKey(new FrameArgInstance(f, t, k(f,k,q), null));
  }

  public boolean contains(Span t, Frame f, Span s) {
    return all2.containsKey(new FrameArgInstance(f, t, -1, s));
  }

  public boolean contains(Span t, Frame f, int k, RoleType q, Span s) {
    return all2.containsKey(new FrameArgInstance(f, t, k(f,k,q), s));
  }

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
