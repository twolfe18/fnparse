package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;

import edu.jhu.hlt.fnparse.rl.full.MaxLoss;
import edu.jhu.hlt.fnparse.rl.full.StepScores;

public class LLSSP extends LL<Node2> {

  private BigInteger primeProd;
  private StepScores<?> scoreSum;

  public LLSSP(Node2 item, LLSSP next) {
    super(item, next);
    if (next == null) {
      primeProd = item.getSig();
      scoreSum = item.getStepScores();
    } else {
      primeProd = item.getSig().multiply(next.primeProd);
      scoreSum = StepScores.sum(item.getStepScores(), next.scoreSum);
    }
  }

  public static int sumPossible(LLSSP l) {
    if (l == null)
      return 0;
    return l.sumPossible();
  }
  public int sumPossible() {
    return scoreSum.getLoss().numPossible;
  }

  public static MaxLoss getSumLoss(LLSSP l) {
    if (l == null)
      return MaxLoss.ZERO;
    return l.getSumLoss();
  }
  public MaxLoss getSumLoss() {
    return scoreSum.getLoss();
  }

  public StepScores<?> getScoreSum() {
    return scoreSum;
  }

  public BigInteger getPrimeProd() {
    return primeProd;
  }

  @Override
  public LLSSP cdr() {
    return (LLSSP) next;
  }
}
