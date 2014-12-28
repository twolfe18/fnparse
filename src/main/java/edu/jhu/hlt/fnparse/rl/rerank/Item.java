package edu.jhu.hlt.fnparse.rl.rerank;

import edu.jhu.hlt.fnparse.datatypes.Span;

public class Item {
  private int t;
  private int k;
  private Span span;
  private double priorScore;
  private double featureScore;

  public Item(int t, int k, Span s, double priorScore) {
    this.t = t;
    this.k = k;
    this.span = s;
    this.priorScore = priorScore;
  }

  public void setFeatureScore(double fs) {
    this.featureScore = fs;
  }

  public double getScore() {
    return priorScore + featureScore;
  }
}