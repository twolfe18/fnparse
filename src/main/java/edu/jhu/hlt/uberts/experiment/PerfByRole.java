package edu.jhu.hlt.uberts.experiment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.Labels;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.prim.tuple.Pair;

public class PerfByRole {
  private Counts<Pair<String, String>> tp, fp, fn;
  private Set<Pair<String, String>> roles;

  public PerfByRole() {
    tp = new Counts<>();
    fp = new Counts<>();
    fn = new Counts<>();
    roles = new HashSet<>();
  }

  public int numRoles() {
    return roles.size();
  }

  public void clear() {
    tp.clear();
    fp.clear();
    fn.clear();
    roles.clear();
  }

  public List<Pair<Pair<String, String>, FPR>> getValues() {
    List<Pair<Pair<String, String>, FPR>> v = new ArrayList<>();
    for (Pair<String, String> role : roles) {
      int tp = this.tp.getCount(role);
      int fp = this.fp.getCount(role);
      int fn = this.fn.getCount(role);
      FPR f = new FPR();
      f.accum(tp, fp, fn);
      v.add(new Pair<>(role, f));
    }
    return v;
  }

  boolean relevant(HashableHypEdge e) {
    return e.getEdge().getRelation().getName().equals("argument4");
  }

  Pair<String, String> getKey(HashableHypEdge he) {
    assert relevant(he);
    HypEdge e = he.getEdge();
    String f = (String) e.getTail(1).getValue();
    String k = (String) e.getTail(3).getValue();
    return new Pair<>(f, k);
  }

  public void add(Labels.Perf perf) {
    Pair<Set<HashableHypEdge>, Set<HashableHypEdge>> gp = perf.getGoldAndPred();
    add(gp.get1(), gp.get2());
  }

  public void add(Set<HashableHypEdge> gold, Set<HashableHypEdge> pred) {
    for (HashableHypEdge g : gold) {
      if (!relevant(g))
        continue;
      Pair<String, String> k = getKey(g);
      roles.add(k);
      if (pred.contains(g))
        tp.increment(k);
      else
        fn.increment(k);
    }
    for (HashableHypEdge p : pred) {
      if (!relevant(p))
        continue;
      if (!gold.contains(p)) {
        Pair<String, String> k = getKey(p);
        roles.add(k);
        fp.increment(k);
      }
    }
  }
}