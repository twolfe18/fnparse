package edu.jhu.hlt.fnparse.rl.full;

import java.math.BigInteger;

/** Adapter which lets me use beam code with new and old states */
public interface StateLike {
  public StepScores<?> getStepScores();
  public BigInteger getSignature();
  // StateLike should be hashable
  public int hashCode();
  public boolean equals(Object other);
}