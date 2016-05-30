package edu.jhu.hlt.uberts.factor;

import java.util.LinkedHashMap;
import java.util.Map.Entry;

import edu.jhu.hlt.tutils.scoring.Adjoints;

/**
 * When re-scoring items on the agenda with global features, we will often need
 * to know details of the features which have already been added to them, rather
 * than just seeing a flat {@link Adjoints}. This class lets us de-structure
 * the global+local scores.
 *
 * @author travis
 */
public class GlobalFactorAdjoints implements Adjoints {

  private Adjoints localScore;
  private LinkedHashMap<String, Adjoints> globalScores;
  private double globalToLocalScale = 0.25;

  public GlobalFactorAdjoints(Adjoints localScore, double globalToLocalScale) {
    this.localScore = Adjoints.cacheIfNeeded(localScore);
    this.globalScores = new LinkedHashMap<>();
    this.globalToLocalScale = globalToLocalScale;
  }

  /**
   * Assumes local features are immutable!
   */
  public static GlobalFactorAdjoints copy(Adjoints a, double globalToLocalScale) {
    if (a instanceof GlobalFactorAdjoints) {
      GlobalFactorAdjoints gsOld = (GlobalFactorAdjoints) a;
      GlobalFactorAdjoints gsNew = new GlobalFactorAdjoints(gsOld.getLocalScore(), globalToLocalScale);
      for (Entry<String, Adjoints> x : gsOld.globalScores.entrySet())
        gsNew.addToGlobalScore(x.getKey(), x.getValue());
      return gsNew;
    } else {
      // Assumes local features are immutable
      return new GlobalFactorAdjoints(a, globalToLocalScale);
    }
  }

  public Adjoints getLocalScore() {
    return localScore;
  }

  public Adjoints getGlobalScore(String name) {
    return globalScores.get(name);
  }

  public Adjoints replaceGlobalScore(String name, Adjoints a) {
    return globalScores.put(name, a);
  }

  /** returns the sum represented by name */
  public Adjoints addToGlobalScore(String name, Adjoints a) {
    Adjoints p = globalScores.get(name);
    if (p == null) {
      globalScores.put(name, a);
      return a;
    } else {
      Adjoints s = Adjoints.sum(a, p);
      globalScores.put(name, s);
      return s;
    }
  }

  @Override
  public double forwards() {
    double f = localScore.forwards();
    for (Adjoints a : globalScores.values())
      f += globalToLocalScale * a.forwards();
    return f;
  }

  @Override
  public void backwards(double dErr_dForwards) {
    localScore.backwards(dErr_dForwards);
    for (Adjoints a : globalScores.values())
      a.backwards(globalToLocalScale * dErr_dForwards);
  }

  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("(GFAdj local=" + localScore);
    for (Entry<String, Adjoints> x : globalScores.entrySet())
      sb.append(" "  + x.getKey() + "=" + x.getValue());
    sb.append(')');
    return sb.toString();
  }
}