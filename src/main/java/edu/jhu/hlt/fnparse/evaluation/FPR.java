package edu.jhu.hlt.fnparse.evaluation;

/**
 * Computes precision, recall, and F1.
 * 
 * Since this only tracks TP, FP, FN,
 * you need to keep track of TN or N yourself.
 * 
 * @author travis
 */
public final class FPR {
  public static enum Mode { PRECISION, RECALL, F1 }

  private double pSum = 0d, pZ = 0d;
  private double rSum = 0d, rZ = 0d;
  private boolean macro;

  private double tp, fp, fn;	// ignores macro/micro

  public FPR() {
    this(false);
  }

  public FPR(boolean macro) {
    this.macro = macro;
  }
  
  public double tpPlusFp() {
    return tp + fp;
  }
  
  public double tpPlusFn() {
    return tp + fn;
  }

  public void accum(double tp, double fp, double fn) {
    if(tp < 0d || fp < 0d || fn < 0d)
      throw new IllegalArgumentException();
    this.tp += tp;
    this.fp += fp;
    this.fn += fn;
    if(macro) {
      pSum += tp + fp == 0d ? 1d : tp / (tp + fp);
      pZ += 1d;
      rSum += tp + fn == 0d ? 1d : tp / (tp + fn);
      rZ += 1d;
    }
    else {
      pSum += tp;
      pZ += tp + fp;
      rSum += tp;
      rZ += tp + fn;
    }
  }

  public void accum(boolean gold, boolean hyp) {
    if(gold && !hyp) accumFN();
    if(!gold && hyp) accumFP();
    if(gold && hyp) accumTP();
  }

  public void accumTP() { accum(1d, 0d, 0d); }
  public void accumFP() { accum(0d, 1d, 0d); }
  public void accumFN() { accum(0d, 0d, 1d); }

  public void accum(FPR fpr) {
    if(macro != fpr.macro)
      throw new IllegalArgumentException();
    pSum += fpr.pSum;
    pZ += fpr.pZ;
    rSum += fpr.rSum;
    rZ += fpr.rZ;
  }

  /** reflects calls to accum without adjustment for micro/macro */
  public double getTP() { return tp; }

  /** reflects calls to accum without adjustment for micro/macro */
  public double getFP() { return fp; }

  /** reflects calls to accum without adjustment for micro/macro */
  public double getFN() { return fn; }

  /** reflects calls to accum without adjustment for micro/macro
   * @param N is the sum of TP + FP + FN + TN, this does the subtraction for you
   */
  public double getTN(double N) {
    double tn = N - tp - fp - fn;
    if (tn < 0d) {
      throw new IllegalArgumentException(
          "N=" + N + " is too small to explain " + this);
    }
    return tn;
  }

  public double get(Mode mode) {
    if(mode == Mode.PRECISION) return precision();
    if(mode == Mode.RECALL) return recall();
    if(mode == Mode.F1) return f1();
    throw new RuntimeException();
  }

  public double precision() {
    return pZ == 0d ? 1d : pSum / pZ; 
  }

  public double recall() {
    return rZ == 0d ? 1d : rSum / rZ;
  }

  public double f1() {
    return fMeasure(1d);
  }

  /**
   * @param beta higher values weight recall more heavily, lower values reward precision
   */
  public double fMeasure(double beta) {
    if(beta <= 0d)
      throw new IllegalArgumentException();
    double p = precision();
    double r = recall();
    if(p + r == 0d) return 0d;
    return (1d + beta * beta) * p * r / (beta * beta * p + r);
  }

  @Override
  public String toString() {
    return String.format("<%s F1=%.1f P=%.1f R=%.1f>",
        macro ? "Macro" : "Micro", 100*f1(), 100*precision(), 100*recall());
  }
}