package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;

import edu.jhu.hlt.fnparse.rl.full.MaxLoss;
import edu.jhu.hlt.fnparse.rl.full.StepScores;

/**
 * Current use: LL<Node2> for children. Keeps track of the product of their
 * primes and the sum of their score/loss/rand.
 *
 * @author travis
 */
public class LLSSP extends LL<Node2> {

  public static final boolean DISABLE_PRIMES = true;

//  private BigInteger primeProd;
//  private long primeProd2;
  private StepScores<?> scoreSum;

  public LLSSP(Node2 item, LLSSP next) {
    super(item, next);
    if (next == null) {
//      primeProd = DISABLE_PRIMES ? BigInteger.ZERO : item.getSig();
//      primeProd2 = DISABLE_PRIMES ? 0 : item.getSig().longValue();
      scoreSum = item.getStepScores();
    } else {
//      primeProd = DISABLE_PRIMES ? BigInteger.ZERO : item.getSig().multiply(next.primeProd);
//      primeProd2 = DISABLE_PRIMES ? 0 : item.getSig().longValue() * next.primeProd2;
      scoreSum = StepScores.sum(item.getStepScores(), next.scoreSum);
    }
    assert DISABLE_PRIMES;
  }

  public static int sumPossible(LLSSP l) {
    if (l == null)
      return 0;
    return l.sumPossible();
  }
  public int sumPossible() {
    return scoreSum.getLoss().numPossible;
  }

  public static int sumDetermined(LLSSP l) {
    if (l == null)
      return 0;
    return l.sumDetermined();
  }
  public int sumDetermined() {
    return scoreSum.getLoss().numDetermined;
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

  public static BigInteger getPrimeProd(LLSSP l) {
    if (l == null)
      return BigInteger.ONE;
    return l.getPrimeProd();
  }
  public BigInteger getPrimeProd() {
//    return primeProd;
//    return BigInteger.valueOf(primeProd2);
    throw new RuntimeException("implement me");
  }

  @Override
  public LLSSP cdr() {
    return (LLSSP) next;
  }
}
