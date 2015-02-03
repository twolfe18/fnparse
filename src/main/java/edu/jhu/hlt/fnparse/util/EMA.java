package edu.jhu.hlt.fnparse.util;

/** Exponentially weighted moving average */
public class EMA {
  private double history;
  private double avg;
  private int updates;
  public EMA(double history) {
    if (history <= 0d || history >= 1d)
      throw new IllegalArgumentException();
    this.history = history;
    this.avg = 0d;
    this.updates = 0;
  }
  public EMA(double history, double startingValue) {
    if (history <= 0d || history >= 1d)
      throw new IllegalArgumentException();
    this.history = history;
    this.avg = startingValue;
    this.updates = 1;
  }
  public double getHistory() {
    return history;
  }
  public void update(double value) {
    if (updates == 0)
      avg = value;
    else
      avg = history * avg + (1d - history) * value;
    updates++;
  }
  public double getAverage() {
    return avg;
  }
  public int getNumUpdates() {
    return updates;
  }
}