package edu.jhu.hlt.uberts.features;

public class Weight<T> {
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