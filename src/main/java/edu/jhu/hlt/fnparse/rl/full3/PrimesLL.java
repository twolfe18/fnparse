package edu.jhu.hlt.fnparse.rl.full3;

import java.math.BigInteger;
import java.util.function.ToLongFunction;

import edu.jhu.hlt.fnparse.rl.full2.LL;
import edu.jhu.hlt.fnparse.rl.full2.Node2;
import edu.jhu.hlt.fnparse.rl.full2.TV;

/**
 * 
 * @author travis
 */
public class PrimesLL extends LL<Node2> {

  protected BigInteger primesProduct;

  public PrimesLL(Node2 item, LL<Node2> next, ToLongFunction<LL<TV>> getPrimes) {
    super(item, next);
    long p = getPrimes.applyAsLong(item.prefix);
    primesProduct = BigInteger.valueOf(p);
    if (next != null) {
      PrimesLL pp = (PrimesLL) next;
      primesProduct = primesProduct.multiply(pp.primesProduct);
    }
  }

  public BigInteger getPrimesProduct() {
    return primesProduct;
  }
}
