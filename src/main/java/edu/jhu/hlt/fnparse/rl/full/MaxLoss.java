package edu.jhu.hlt.fnparse.rl.full;


/**
 * Maintains the maximum loss (FP+FN) over a set where you know the size, the
 * number of indices filled in, and the number of FPs and FNs.
 *
 * @author travis
 */
public final class MaxLoss {

  public static final MaxLoss ZERO = new MaxLoss(0, 0, 0, 0);

  public final int numPossible;
  public final int numDetermined;
  public final int fp;
  public final int fn;

  /** if a and b represent disjoint sets, this returns a MaxLoss representing their union */
  public static MaxLoss sum(MaxLoss a, MaxLoss b) {
    return new MaxLoss(
        a.numPossible + b.numPossible,
        a.numDetermined + b.numDetermined,
        a.fp + b.fp,
        a.fn + b.fn);
  }
  public static MaxLoss sumSafe(MaxLoss a, MaxLoss b) {
    if (b == null)
      return a;
    if (a == null)
      return b;
    return sum(a, b);
  }

  public MaxLoss(int numPossible) {
    this(numPossible, 0, 0, 0);
  }

  public MaxLoss(int numPossible, int numDetermined, int fp, int fn) {
    System.out.flush();
    assert numPossible >= 0 : "numPossible=" + numPossible;
    assert numDetermined <= numPossible : "numDetermined=" + numDetermined + " numPossible=" + numPossible;

    // TODO return to this...
//    assert fp + fn <= numDetermined : "fp=" + fp + " fn=" + fn + " numDetermined=" + numDetermined;

    assert fp >= 0 && fn >= 0;
    this.numPossible = numPossible;
    this.numDetermined = numDetermined;
    this.fp = fp;
    this.fn = fn;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof MaxLoss) {
      MaxLoss x = (MaxLoss) other;
      return numPossible == x.numPossible
          && numDetermined == x.numDetermined
          && fp == x.fp
          && fn == x.fn;
    }
    return false;
  }

  public boolean noLoss() {
    return fp == 0 && fn == 0;
  }

  public boolean noneDetermined() {
    return numDetermined == 0;
  }

  public int maxLoss() {
    return (numPossible - numDetermined) + fp + fn;
  }

  public int minLoss() {
    return fp + fn;
  }

  /** Returns a new MaxLoss equal to this plus another FP */
  public MaxLoss fp() {
    return new MaxLoss(numPossible, numDetermined+1, fp+1, fn);
  }

  /** Returns a new MaxLoss equal to this plus another FN */
  public MaxLoss fn() {
    return new MaxLoss(numPossible, numDetermined+1, fp, fn+1);
  }

  @Override
  public String toString() {
    return "((N=" + numPossible + " - D=" + numDetermined + ") + fp=" + fp + " fn=" + fn + ")";
  }
}