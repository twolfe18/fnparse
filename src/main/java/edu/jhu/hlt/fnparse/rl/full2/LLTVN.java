package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;

public class LLTVN extends LL<TVN> {

  public final int sumGoldMatching;
  public final int sumPossible;
  public final int numGoldMachingEqZero;
  public final BigInteger primesProd;

  public LLTVN(TVN item, LLTVN next) {
    super(item, next);
    int a = item.goldMatching == 0 ? 1 : 0;
    if (next == null) {
      sumGoldMatching = item.goldMatching;
      sumPossible = item.numPossible;
      numGoldMachingEqZero = a;
      if (LLSSP.DISABLE_PRIMES)
        primesProd = null;
      else
        primesProd = BigInteger.valueOf(item.prime);
    } else {
      sumGoldMatching = item.goldMatching + next.sumGoldMatching;
      sumPossible = item.numPossible + next.sumPossible;
      numGoldMachingEqZero = a + next.numGoldMachingEqZero;
      if (LLSSP.DISABLE_PRIMES)
        primesProd = null;
      else
        primesProd = next.primesProd.multiply(BigInteger.valueOf(item.prime));
    }
  }

  public static BigInteger getPrimesProd(LLTVN l) {
    if (l == null)
      return BigInteger.ONE;
    return l.primesProd;
  }
  public BigInteger getPrimesProd() {
    if (primesProd == null)
      return BigInteger.ZERO;
    return primesProd;
  }

  public static int sumPossible(LLTVN l) {
    if (l == null)
      return 0;
    return l.sumPossible;
  }

  public static int sumGoldMatching(LLTVN l) {
    if (l == null)
      return 0;
    return l.sumGoldMatching;
  }

  public static int numGoldMatchingEqZero(LLTVN l) {
    if (l == null)
      return 0;
    return l.numGoldMachingEqZero;
  }

  public static int numGoldMatchingGtZero(LLTVN l) {
    if (l == null)
      return 0;
    return l.length - l.numGoldMachingEqZero;
  }

  @Override
  public LLTVN cdr() {
    // Perfectly safe since there is only one constructor which proves next is a LLTVN
    return (LLTVN) next;
  }
}
