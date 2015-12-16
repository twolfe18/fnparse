package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;
import java.util.Random;
import java.util.function.ToLongFunction;

import edu.jhu.hlt.fnparse.rl.full2.Node2.NodeWithSignature;
import edu.jhu.hlt.tutils.scoring.Adjoints;

/**
 * Some properties:
 * - fixed loop order: [T,F,K,S]
 * - generates 5 random eggs at every node
 *
 * @author travis
 */
public class DebugTransitionSystem extends AbstractTransitionScheme<int[]> {

  private ToLongFunction<LL<TV>> getPrimes;
  private Random rand;

  public DebugTransitionSystem() {
    rand = new Random(9001);
    getPrimes = new ToLongFunction<LL<TV>>() {
      // TODO Implement some sort of trie
      @Override
      public long applyAsLong(LL<TV> value) {
        return BigInteger.probablePrime(20, rand).longValue();
      }
    };
  }

  @Override
  public LL<TV> consPrefix(TV car, LL<TV> cdr) {
    return new TFKS(car, (TFKS) cdr);
  }

  @Override
  public LL<Node2> consChild(Node2 car, LL<Node2> cdr) {
    if (car.getType() == TFKS.K)
      return new RoleLL(car, cdr, getPrimes);
    return new PrimesLL(car, cdr, getPrimes);
  }

  @Override
  public NodeWithSignature newNode(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children) {
    return new NodeWithSignature(prefix, eggs, pruned, children);
  }

  @Override
  public LL<TV> genEggs(LL<TV> prefix) {
    int newType = prefix == null ? TFKS.T : prefix.car().getType() + 1;
    assert newType <= TFKS.S;
    // Generate some random values for eggs
    LL<TV> eggs = null;
    for (int i = 0; i < 5; i++)
      eggs = consPrefix(new TV(newType, rand.nextInt(100)), eggs);
    return eggs;
  }

  @Override
  public Adjoints featsHatch(Node2 n) {
    double s = rand.nextGaussian();
    return new Adjoints.NamedConstant("RandFeatsHatch", s);
  }

  @Override
  public Adjoints featsSquash(Node2 n) {
    double s = rand.nextGaussian();
    return new Adjoints.NamedConstant("RandFeatsSquash", s);
  }

  @Override
  public Iterable<LL<TV>> encode(int[] y) {
    throw new RuntimeException("implement me");
  }

  @Override
  public int[] decode(Iterable<LL<TV>> z) {
    throw new RuntimeException("implement me");
  }
}