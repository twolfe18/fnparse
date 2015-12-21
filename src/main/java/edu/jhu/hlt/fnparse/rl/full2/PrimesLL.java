package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;
import java.util.function.ToLongFunction;

/**
 * 
 * @author travis
 */
public class PrimesLL extends LLML<Node2> {

  protected BigInteger primesProduct;

  public PrimesLL(Node2 item, LLML<Node2> next, ToLongFunction<LL<TVN>> getPrimes) {
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
