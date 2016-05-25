package edu.jhu.hlt.uberts.factor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;

public class NumArgs implements GlobalFactor {
  public static boolean DEBUG = false;
  public static boolean COARSE_DEBUG = true;

  private Relation firesFor;    // e.g. argument4
  private int aggregateArgPos;  // e.g. 0 for t in argument4(t,f,s,k)
  private int refineArgPos;     // e.g. 1 for f in argument4(t,f,s,k), meaning create extra conjunction feature with frame value
  private int dimension;
  private AveragedPerceptronWeights theta;
  private Alphabet<String> featureNames;  // for debugging

  /**
   * @param refinementArgPos can be <0 if you don't want a refinement
   */
  public NumArgs(Relation firesFor, int aggregateArgPos, int refinementArgPos) {
    this.firesFor = firesFor;
    this.aggregateArgPos = aggregateArgPos;
    this.refineArgPos = refinementArgPos;
    dimension = 1<<16;
    int numIntercept = 0;
    theta = new AveragedPerceptronWeights(dimension, numIntercept);
  }

  /**
   * Call this before using this factor if you want to use an Alphabet instead
   * of feature hashing.
   */
  public void storeExactFeatureIndices() {
    if (featureNames == null)
      featureNames = new Alphabet<>();
  }

  public List<Pair<String, Double>> getBiggestWeights(int k) {
    if (featureNames == null)
      throw new IllegalStateException("must call storeExactFeatureIndices to use this!");
    List<Pair<String, Double>> l = new ArrayList<>();
    int n = featureNames.size();
    for (int i = 0; i < n; i++) {
      double w = theta.getWeight(i);
      String f = featureNames.lookupObject(i);
      l.add(new Pair<>(f, w));
    }
    Collections.sort(l, new Comparator<Pair<String, Double>>() {
      @Override
      public int compare(Pair<String, Double> o1, Pair<String, Double> o2) {
        double w1 = Math.abs(o1.get2());
        double w2 = Math.abs(o2.get2());
        if (w1 > w2) return -1;
        if (w2 < w2) return +1;
        return 0;
      }
    });
    if (l.size() > k)
      l = l.subList(0, k);
    return l;
  }

  public TKey[] getTrigger(Uberts u) {
    return new TKey[] {
        new TKey(State.HEAD_ARG_POS, firesFor),
    };
  }

  @Override
  public void rescore(Agenda a, GraphTraversalTrace match) {
    HypEdge srl4Fact = match.getBoundEdge(0);
    HypNode t = srl4Fact.getTail(aggregateArgPos);
    Iterable<HypEdge> affected = a.match(aggregateArgPos, firesFor, t);
    int n = 0;
    for (HypEdge e : affected) {
      n++;
      Adjoints score = a.getScore(e);
      a.remove(e);
      if (!(score instanceof GlobalFactorAdjoints))
        score = new GlobalFactorAdjoints(score);
      GlobalFactorAdjoints gs = (GlobalFactorAdjoints) score;

      // Get the previous count from the previous Adjoints
      Adj numArgs = (Adj) gs.getGlobalScore("numArgs");
      if (numArgs == null) {
        String refinement = refineArgPos < 0 ? "na" : (String) e.getTail(refineArgPos).getValue();
        numArgs = this.new Adj(firesFor, refinement);
        gs.addToGlobalScore("numArgs", numArgs);
      }
      numArgs.incrementNumCommitted();
      a.add(e, gs);
    }
    if (DEBUG || COARSE_DEBUG) {
      Log.info("rescored " + n + " " + firesFor.getName() + " relations connected to " + t);
      if (DEBUG)
        for (HypEdge e : affected)
          System.out.println("\t" + e);
    }
  }

  /**
   * Stores the number of args added already and other needed details like the
   * frame and relation (type of action).
   */
  public class Adj implements Adjoints {
    Relation rel;
    String frame;
    int numCommitted;
    Adjoints aFromTheta;
    public Adj(Relation rel, String frame) {
      this.rel = rel;
      this.frame = frame;
      this.numCommitted = 0;
    }
    public void incrementNumCommitted() {
      numCommitted++;
      aFromTheta = null;
    }
    @Override
    public double forwards() {
      if (aFromTheta == null) {
        int c = Math.min(6, numCommitted);
        if (featureNames != null) {
          int[] feats = new int[] {
              featureNames.lookupIndex(rel.getName() + ",numArgs=" + c),
              featureNames.lookupIndex(rel.getName() + ",numArgs=" + c + ",refinement=" + frame),
          };
          boolean reindex = false;
          aFromTheta = theta.score(feats, reindex);
        } else {
          int r = rel.hashCode();
          int f = frame.hashCode();
          int[] feats = new int[] {
              Hash.mix(r, c),
              Hash.mix(r, c, f),
          };
          boolean reindex = true;
          aFromTheta = theta.score(feats, reindex);
        }
      }
      return aFromTheta.forwards();
    }
    @Override
    public void backwards(double dErr_dForwards) {
      aFromTheta.backwards(dErr_dForwards);
    }
  }
}
