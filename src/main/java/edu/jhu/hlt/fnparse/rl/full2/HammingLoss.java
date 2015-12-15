package edu.jhu.hlt.fnparse.rl.full2;

public final class HammingLoss {

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
