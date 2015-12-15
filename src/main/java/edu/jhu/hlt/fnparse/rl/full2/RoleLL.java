package edu.jhu.hlt.fnparse.rl.full2;

import java.util.function.ToLongFunction;

/**
 * Use this for any node where all of the children are K nodes and this will
 * keep track of that set in O(1) time for things like global features like
 * numArgs.
 *
 * @author travis
 */
public class RoleLL extends PrimesLL {

  /*
   * Set representing every value of k s.t. \sum_{t,f,s} z_{tfks} > 0
   */
  protected long realizedMask;

  public RoleLL(Node2 item, LL<Node2> next, ToLongFunction<LL<TV>> getPrimes) {
    super(item, next, getPrimes);
    if (item.getType() != TFKS.K)
      throw new IllegalArgumentException();
    if (!(next instanceof RoleLL))
      throw new IllegalArgumentException();
    RoleLL rest = (RoleLL) next;
    int k = item.getValue();
    assert k < 64;
    realizedMask = (1L << k) | rest.realizedMask;
  }

  public int getNumRealizedRoles() {
    return Long.bitCount(realizedMask);
  }
}
