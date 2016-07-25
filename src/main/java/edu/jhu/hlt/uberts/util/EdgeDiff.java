package edu.jhu.hlt.uberts.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.Relation;

public class EdgeDiff {

  public static class Grouping {
    // Relation and argument positions specify how to construct a key to group on
    private Relation r;   // null means group by Relation, non-null means filter on equality with this relation then group by argPos
    private int[] argPos;
    private Map<List<Object>, List<HypEdge>> values;
    private int edges = 0;

    public Grouping(Relation r, int[] argPos) {
      if (r == null && !(argPos == null || argPos.length == 0))
        throw new IllegalArgumentException();
      this.r = r;
      this.argPos = argPos;
      this.values = new HashMap<>();
    }

    public List<Map.Entry<List<Object>, List<HypEdge>>> getKeysWithMoreThanOneValue() {
      List<Map.Entry<List<Object>, List<HypEdge>>> l = new ArrayList<>();
      for (Entry<List<Object>, List<HypEdge>> x : values.entrySet())
        if (x.getValue().size() > 1)
          l.add(x);
      return l;
    }

    @Override
    public String toString() {
      return "(Grouping r=" + (r == null ? "null" : r.getName())
          + " args=" + Arrays.toString(argPos)
          + " keys=" + values.size()
          + " edges=" + edges + ")";
    }

    public List<Object> getKey(HypEdge e) {
      List<Object> k = new ArrayList<>();
      if (r != null)
        k.add(e.getRelation().getName());
      if (argPos != null)
        for (int i : argPos)
          k.add(e.getTail(i).getValue());
      return k;
    }

    public void add(HypEdge e) {
      if (r != null && r != e.getRelation())
        return;
      edges++;
      List<Object> k = getKey(e);
      List<HypEdge> v = values.get(k);
      if (v == null) {
        v = new ArrayList<>();
        values.put(k, v);
      }
      v.add(e);
    }

    public void addAll(Collection<HypEdge> es) {
      for (HypEdge e : es)
        add(e);
    }
  }

  public static Map<Relation, Grouping> makeSingleArgGroupingsByRelation(List<HypEdge> f) {
    Map<Relation, Grouping> rg = new HashMap<>();
    for (HypEdge e : f) {
      Grouping g = rg.get(e.getRelation());
      if (g == null) {
        g = new Grouping(e.getRelation(), null);
        rg.put(e.getRelation(), g);
      }
      g.add(e);
    }
    return rg;
  }

  /**
   * Returns true if they are the same. Prints diffs and returns false otherwise.
   */
  public static boolean sameKeys(Grouping g1, Grouping g2) {
    // Check if keys are the same
    // If not, then show key diff
    boolean keyDiff = false;
    for (List<Object> k1 : g1.values.keySet()) {
      if (!g2.values.containsKey(k1)) {
        keyDiff = true;
        System.out.println("-" + k1);
      }
    }
    for (List<Object> k2 : g2.values.keySet()) {
      if (!g1.values.containsKey(k2)) {
        keyDiff = true;
        System.out.println("+" + k2);
      }
    }
    if (!keyDiff)
      System.out.println(g1 + " and " + g2 + " have the same keys (nKeys=" + g1.values.keySet().size() + ")");
    return !keyDiff;
  }

  /** Assumes the keys are the same */
  public static boolean sameValues(Grouping g1, Grouping g2) {
    boolean same = true;
    for (List<Object> key : g1.values.keySet())
      same &= sameValues(g1, g2, key);
    if (same)
      System.out.println(g1 + " and " + g2 + " have the same values");
    return same;
  }
  public static boolean sameValues(Grouping g1, Grouping g2, List<Object> key) {
    List<HypEdge> e1 = g1.values.get(key);
    List<HypEdge> e2 = g2.values.get(key);
    Set<HashableHypEdge> uniq = new HashSet<>();
    for (HypEdge e : e1)
      uniq.add(new HashableHypEdge(e));
    boolean same = true;
    for (HypEdge e : e2) {
      if (uniq.remove(new HashableHypEdge(e))) {
        System.out.println("+" + e);
        same = false;
      }
    }
    for (HashableHypEdge he : uniq) {
      System.out.println("-" + he.getEdge());
      same = false;
    }
    return same;
  }

  public static void showDiff(List<HypEdge> f1, List<HypEdge> f2) {
    Log.info("comparing " + f1.size() + " and " + f2.size() + " eges");
    // First separate by relation
    Grouping r1 = new Grouping(null, null);
    Grouping r2 = new Grouping(null, null);
    r1.addAll(f1);
    r2.addAll(f2);
    boolean sameByRelations = sameKeys(r1, r2);

    // For each relation, create a diff based on argPos = single position arrays
    if (sameByRelations) {
      Log.info("same set of relations!");
      Map<Relation, Grouping> rg1 = makeSingleArgGroupingsByRelation(f1);
      Map<Relation, Grouping> rg2 = makeSingleArgGroupingsByRelation(f2);
      for (Relation r : rg1.keySet()) {
        Grouping gg1 = rg1.get(r);
        Grouping gg2 = rg2.get(r);
        System.out.println("diff over " + r.getName());
        if (sameKeys(gg1, gg2))
          sameValues(gg1, gg2);
        System.out.println();
      }
    }
  }

  public static List<HypEdge> duplicates(Collection<HypEdge> edges) {
    HashSet<HashableHypEdge> uniq = new HashSet<>();
    List<HypEdge> dups = new ArrayList<>();
    for (HypEdge e : edges)
      if (!uniq.add(new HashableHypEdge(e)))
        dups.add(e);
    return dups;
  }
}
