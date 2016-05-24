package edu.jhu.hlt.uberts.features;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HNode;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.StateEdge;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Arg;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.tuple.Pair;

public abstract class FeatureExtractionFactor<T> {

  // Set this to non-null values to enable.
  // When enabled, an empty list, this.SKIP will be return from features().
  public Double pSkipNeg = null;
  public List<T> SKIP = new ArrayList<>();

  // If true, wraps all scores with Adjoints.cacheIfNeeded
  public boolean cacheAdjointsForwards = true;
//  private Map<Relation, Map<T, Weight<T>>> theta = new HashMap<>();
  private int numInstances = 0;   // for perceptron averaging
  protected boolean useAvg = false;

  // Keys in inner map are either Relation.getName() or results from customeRefinements
//  private Map<T, Map<String, Weight<HPair<String, T>>>> theta2;
  private Map<T, IntObjectHashMap<Weight<Pair<Integer, T>>>> theta2 = new HashMap<>();
  private IntObjectHashMap<IntObjectHashMap<Weight<Pair<Integer, T>>>> theta2Special = new IntObjectHashMap<>();
  protected Function<HypEdge, int[]> customRefinements = null;

  public abstract List<T> features(HypEdge yhat, Uberts x);

  /**
   * Returns a non-caching Adjoints.
   */
  public Adjoints score(HypEdge yhat, Uberts x) {

    WeightList<Pair<Integer, T>> weights = new WeightList<>(numInstances, useAvg);
    int[] refs;
    if (customRefinements == null)
      refs = new int[] {0};
    else
      refs = customRefinements.apply(yhat);
    for (T feat : features(yhat, x)) {
      IntObjectHashMap<Weight<Pair<Integer, T>>> m;
      if (feat instanceof Integer) {
        int f = (Integer) feat;
        m = theta2Special.get(f);
        if (m == null) {
          m = new IntObjectHashMap<>();
          theta2Special.put(f, m);
        }
      } else {
        m = theta2.get(feat);
        if (m == null) {
          m = new IntObjectHashMap<>();
          theta2.put(feat, m);
        }
      }
      for (int i = 0; i < refs.length; i++) {
        Weight<Pair<Integer, T>> w = m.get(refs[i]);
        if (w == null) {
          w = new Weight<>(new Pair<>(refs[i], feat));
          m.put(refs[i], w);
        }
        weights.add(w);
      }
    }
    if (cacheAdjointsForwards)
      return Adjoints.cacheIfNeeded(weights);
    return weights;

//    // Look up weight vector based on Relation of the given edge
//    Map<T, Weight<T>> t = theta.get(yhat.getRelation());
//    if (t == null) {
//      t = new HashMap<>();
//      theta.put(yhat.getRelation(), t);
//    }
//    List<T> feats = features(yhat, x);
//    Adjoints a;
//    if (useAvg)
//      a = new WeightAdjoints<>(feats, t, numInstances);
//    else
//      a = new WeightAdjoints<>(feats, t);
//    if (cacheAdjointsForwards)
//      a = Adjoints.cacheIfNeeded(a);
//    return a;
  }

  public void useAverageWeights(boolean useAvg) {
    this.useAvg = useAvg;
  }

  public void completedObservation() {
    numInstances++;
  }

//  public Object getWeights() {
//    assert theta2Special.size() == 0;
//    return theta2;
//  }
  public List<Weight<Pair<Integer, T>>> getWeights() {
    assert theta2Special.size() == 0;
    List<Weight<Pair<Integer, T>>> l = new ArrayList<>();
    for (IntObjectHashMap<Weight<Pair<Integer, T>>> x : theta2.values()) {
      IntObjectHashMap<Weight<Pair<Integer, T>>>.Iterator itr = x.iterator();
      while (itr.hasNext()) {
        itr.advance();
        l.add(itr.value());
      }
    }
    return l;
  }
//  public List<Pair<T, Weight<T>>> getWeights() {
//    if (theta2Special.size() > 0)
//      throw new RuntimeException("re-implement me");
//    List<Pair<T, Weight<T>>> l = new ArrayList<>();
//    for (Entry<T, IntObjectHashMap<Weight<Pair<Integer, T>>>> x : theta2.entrySet()) {
//      T r = x.getKey();
//      x.getValue().iterator();
////      for (Weight<Pair<Integer, T>> w : x.getValue().values())
////        l.add(new Pair<>(r, w));
//    }
//    return l;
//  }

  public static class Oracle extends FeatureExtractionFactor<String> {
    private Set<String> relevant;
    public Oracle(String... relevantRelations) {
      this.relevant = new HashSet<>();
      for (String r : relevantRelations)
        this.relevant.add(r);
    }
    @Override
    public List<String> features(HypEdge yhat, Uberts x) {
      if (relevant.contains(yhat.getRelation().getName())) {
        boolean y = x.getLabel(yhat);
        return Arrays.asList(y ? "oracleSaysYes" : "oracleSaysNo");
      }
      return Collections.emptyList();
    }
  }

  /**
   * Does a DFS search starting from each tail of the {@link HypEdge} being
   * featurized. Parameters let you set a max walk length and ignore certain
   * values in the walk which are known to not be discriminative
   * (e.g. "tokenIndex=32" => "tokenIndex=?").
   * You might want to use this for arguments which are head node types, e.g.
   * in `srl1'(s1,...) & event1'(e1,...) => srl2(s1,s2)`, if you where to walk
   * through the srl2 node to its tails, you probably don't want to spit out
   * something like "arg-0-of-srl2=(EqArr 3 5)".
   * but rather "arg-0-of-srl2*arg-0-of-srl1=3"
   * even that... sort of should be compressed
   */
  public static class GraphWalks extends FeatureExtractionFactor<String> {
    private Set<HypNode> seen;
    private Set<HNode> seen2;
    private Set<Arg> seen3;
    private Set<Arg> seen3Exceptions;
    private Deque<String> steps;
    private int curArgs = 0;
    private int curValues = 0;
    private int maxArgs = 4;
    private int maxValues = 4;
    private int minValues = 1;
    private Set<String> nodeTypesIgnoreValue;
//    private boolean lastStepIncludesValue;
//    private Set<Arg> args;

    public GraphWalks() {
      steps = new ArrayDeque<>();
      seen = new HashSet<>();
      seen2 = new HashSet<>();
      seen3 = new HashSet<>();
      nodeTypesIgnoreValue = new HashSet<>();
      nodeTypesIgnoreValue.add("tokenIndex");

      // These are relations where one arg is really a label for the rest
      // of the args. We want to allow the walks to hop over to the label node
      // and hop back multiple times, as opposed to it only being able to see
      // one label, as would be the case if you could only cross a (rel,argPos)
      // once.
      seen3Exceptions = new HashSet<>();
      seen3Exceptions.add(new Arg("dsyn3-basic", 2));
      seen3Exceptions.add(new Arg("dsyn3-col", 2));
      seen3Exceptions.add(new Arg("dsyn3-colcc", 2));
      seen3Exceptions.add(new Arg("csyn3-stanford", 2));
      seen3Exceptions.add(new Arg("csyn3-gold", 2));
    }

    @Override
    public List<String> features(HypEdge yhat, Uberts x) {
      // Do DFS on the state graph from every tail node in yhat
      Relation yhatRel = yhat.getRelation();
      List<String> features = new ArrayList<>();
      for (int i = 0; i < yhatRel.getNumArgs(); i++) {

        // Each walk is independently constrained (for now)
        seen.clear();
        seen2.clear();
        seen3.clear();

        // Start a walk here
        HypNode t = yhat.getTail(i);
        curArgs++;
        steps.push(yhat.getRelation().getName() + "=arg" + i);
        dfs(new HNode(t), x.getState(), features);
        steps.pop();
        curArgs--;
      }
      return features;
    }

    private void dfs(HNode n, State s, List<String> addTo) {

      assert curArgs >= 0 && curValues >= 0;
      if (curArgs > maxArgs || curValues > maxValues)
        return;

      if (curValues >= minValues) {// && n.isEdge()) {
        StringBuilder sb = new StringBuilder();
        for (String st : steps) {
          if (sb.length() > 0) {
            sb.append(" ||| ");
          }
          sb.append(st);
        }
        addTo.add(sb.toString());
      }

      List<StateEdge> nei = s.neighbors2(n);
      if (nei.size() > 10)
        Log.warn("lots of neighbors of " + n + ", " + nei.size());
      for (StateEdge se : nei) {
        assert se.getSource().equals(n) : "n=" + n + " source=" + se.getSource();
        HNode t = se.getTarget();
        boolean incVal = false;

        // You can only cross an Edge=(relation, argPosition) once.
        String relName;
        if (t.isEdge()) {
          relName = t.getEdge().getRelation().getName();
        } else {
          assert se.getSource().isEdge();
          relName = se.getSource().getEdge().getRelation().getName();
        }
        Arg a = new Arg(relName, se.argPos);
        if (!seen3Exceptions.contains(a) && !seen3.add(a))
          continue;

        String key, value;
        if (t.isNode()) {
          HypNode val = t.getNode();
          if (!seen.add(val))
            continue;
          key = val.getNodeType().getName();
          if (se.argPos == State.HEAD_ARG_POS || nodeTypesIgnoreValue.contains(val.getNodeType().getName())) {
            value = "?";
          } else {
            value = val.getValue().toString();
            incVal = true;
            curValues++;
          }
        } else {
          HypEdge rel = t.getEdge();
          key = rel.getRelation().getName();
          value = "arg" + se.argPos;
        }
        curArgs++;
        steps.push(key + "=" + value);
        dfs(t, s, addTo);
        steps.pop();
        curArgs--;
        if (incVal)
          curValues--;
      }
    }
  }


}

