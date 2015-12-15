package edu.jhu.hlt.fnparse.rl.full2;

public final class HammingLoss {

  public static final HammingLoss TP = new HammingLoss(1, 0, 0, 0);
  public static final HammingLoss FP = new HammingLoss(0, 1, 0, 0);
  public static final HammingLoss FN = new HammingLoss(0, 0, 1, 0);
  public static final HammingLoss TN = new HammingLoss(0, 0, 0, 1);

  public static final HammingLoss ZERO = new HammingLoss(0, 0, 0, 0);

  private int tp, fp, fn, tn;

  public HammingLoss() {
    this(0, 0, 0, 0);
  }

  public HammingLoss(int tp, int fp, int fn, int tn) {
    this.tp = tp;
    this.fp = fp;
    this.fn = fn;
    this.tn = tn;
  }

  public int getTP() { return tp; }
  public int getFP() { return fp; }
  public int getFN() { return fn; }
  public int getTN() { return tn; }

  public int incTP() { return tp++; }
  public int incFP() { return fp++; }
  public int incFN() { return fn++; }
  public int incTN() { return tn++; }

  public HammingLoss plus(HammingLoss other) {
    return plus(other.tp, other.fp, other.fn, other.tn);
  }

  public HammingLoss plus(int tp, int fp, int fn, int tn) {
    assert tp >= 0;
    assert fp >= 0;
    assert fn >= 0;
    assert tn >= 0;
    return new HammingLoss(this.tp + tp, this.fp + fp, this.fn + fn, this.tn + tn);
  }
}
