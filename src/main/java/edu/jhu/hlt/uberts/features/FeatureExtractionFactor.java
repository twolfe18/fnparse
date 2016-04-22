package edu.jhu.hlt.uberts.features;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HNode;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.StateEdge;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Arg;

public abstract class FeatureExtractionFactor<T> {

  public abstract List<T> features(HypEdge yhat, Uberts x);


  public static class Weight<T> {
    int nObs = 0;
    double theta = 0;
    final T item;
    public Weight(T item) {
      this.item = item;
      this.nObs = 0;
      this.theta = 0;
    }
    public void increment(double amount) {
      theta += amount;
      nObs++;
    }
    @Override
    public String toString() {
      return String.format("(%s %+.2f n=%d)", item.toString(), theta, nObs);
    }
  }

  public static class WeightAdjoints<T> implements Adjoints {
    private List<T> fx;
    private Map<T, Weight<T>> theta;

    public WeightAdjoints(List<T> features, Map<T, Weight<T>> weights) {
      this.fx = features;
      this.theta = weights;
    }

    public List<T> getFeatures() {
      return fx;
    }

    @Override
    public double forwards() {
      double s = 0;
      for (T index : fx) {
        Weight<T> w = theta.get(index);
        if (w != null)
          s += w.theta;
      }
      return s;
    }

    @Override
    public void backwards(double dErr_dForwards) {
      for (T index : fx) {
        Weight<T> w = theta.get(index);
        if (w == null) {
          w = new Weight<>(index);
          theta.put(index, w);
        }
        w.increment(-dErr_dForwards);
      }
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("(Adj");
      for (T index : fx) {
        Weight<T> w = theta.get(index);
        if (w == null)
          w = new Weight<>(index);
        sb.append(' ');
        sb.append(w.toString());
        if (sb.length() > 200) {
          sb.append("...");
          break;
        }
      }
      sb.append(')');
      return sb.toString();
    }
  }

  private Map<Relation, Map<T, Weight<T>>> theta = new HashMap<>();

  /**
   * Returns a non-caching Adjoints.
   */
  public Adjoints score(HypEdge yhat, Uberts x) {
    // Look up weight vector based on Relation of the given edge
    Map<T, Weight<T>> t = theta.get(yhat.getRelation());
    if (t == null) {
      t = new HashMap<>();
      theta.put(yhat.getRelation(), t);
    }
    List<T> feats = features(yhat, x);
    return new WeightAdjoints<>(feats, t);
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
    private boolean lastStepIncludesValue;
    private Set<String> nodeTypesIgnoreValue;
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
        dfs2(new HNode(t), x.getState(), features);
        steps.pop();
        curArgs--;
      }
      return features;
    }

    private void dfs2(HNode n, State s, List<String> addTo) {

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
        dfs2(t, s, addTo);
        steps.pop();
        curArgs--;
        if (incVal)
          curValues--;
      }
    }

    private void dfs(HypNode n, State s, List<String> addTo) {
      // Extract features
      // I think I want to do this before checking seen since I want to include
      // multiple paths.
      if (curValues >= minValues && lastStepIncludesValue) {
        StringBuilder sb = new StringBuilder();
        for (String st : steps) {
          if (sb.length() > 0) {
//            sb.append("_");
            sb.append(" ||| ");
          }
          sb.append(st);
        }
        addTo.add(sb.toString());
      }

      if (!seen.add(n))
        return;

//      if (steps.size() == maxDepth)
//        return;
      assert curArgs >= 0 && curValues >= 0;
      if (curArgs >= maxArgs || curValues >= maxValues)
        return;

      // Recurse
      for (StateEdge se : s.neighbors2(new HNode(n))) {
        HNode rel = se.getTarget();
        assert rel.isRight();
        HypEdge e = rel.getRight();
        int nt = e.getNumTails();
        for (int i = 0; i < nt; i++) {
          HypNode node = e.getTail(i);
          if (node == n)
            continue;
          String step = "arg" + i + "-of-" + e.getRelation().getName() + "=";
          boolean ignoreVal = nodeTypesIgnoreValue.contains(node.getNodeType().getName());
          lastStepIncludesValue = !ignoreVal;
          curArgs++;
          if (ignoreVal) {
            step += "?";
          } else {
            curValues++;
            step += node.getValue().toString();
          }
          steps.push(step);
          dfs(node, s, addTo);
          steps.pop();
          curArgs--;
          if (!ignoreVal)
            curValues--;
        }
        curArgs++;
        steps.push("head-of-" + e.getRelation().getName());
        lastStepIncludesValue = false;
        dfs(e.getHead(), s, addTo);
        steps.pop();
        curArgs--;
      }
    }
  }

  /**
   * Uses {@link FeatletIndex} for features.
   */
  public static class Simple extends FeatureExtractionFactor<String> {
    private List<FeatletIndex.Feature> features;

    public Simple(List<Relation> relevant, Uberts u) {
      NodeType tokenIndex = u.lookupNodeType("tokenIndex", false);
      FeatletIndex featlets = new FeatletIndex(tokenIndex, relevant);
      this.features = featlets.getFeatures();
    }

    public List<String> features(HypEdge yhat, Uberts x) {
      List<String> fx = new ArrayList<>();
      fx.add("INTERCEPT");
      int n = features.size();
      for (int i = 0; i < n; i++) {
        FeatletIndex.Feature f = features.get(i);
        List<String> ls = f.extract(yhat, x);
        for (String fs : ls)
          fx.add(f.getName() + "/" + fs);
      }
      return fx;
    }
  }
}

