package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.ToLongFunction;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.rl.full.Config;
import edu.jhu.hlt.fnparse.rl.full.State.Info;
import edu.jhu.hlt.fnparse.rl.full2.Node2.NodeWithSignature;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;

/**
 * Some properties:
 * - fixed loop order: [T,F,K,S]
 * - generates 5 random eggs at every node
 *
 * @author travis
 */
public class DebugTransitionSystem extends AbstractTransitionScheme<FNParse> {

  private ToLongFunction<LL<TV>> getPrimes;
  private Random rand;
  private Info info;

  public DebugTransitionSystem() {
    rand = new Random(9001);
    getPrimes = new ToLongFunction<LL<TV>>() {
      // TODO Implement some sort of trie
      @Override
      public long applyAsLong(LL<TV> value) {
        return BigInteger.probablePrime(20, rand).longValue();
      }
    };
    info = new Info(Config.FAST_SETTINGS).setOracleCoefs();
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
  public Info getInfo() {
    return info;
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
  public Iterable<LL<TV>> encode(FNParse y) {
    List<LL<TV>> yy = new ArrayList<>();
    for (FrameInstance fi : y.getFrameInstances()) {
      Frame fr = fi.getFrame();
      int f = fr.getId();
      int t = Span.index(fi.getTarget());
      int K = fr.numRoles();
      for (int k = 0; k < K; k++) {
        Span a = fi.getArgument(k);
        if (a != Span.nullSpan) {
          int s = Span.index(a);
          yy.add(Node2Tests.lltvSugar(TFKS.S, s, TFKS.K, k+0*K, TFKS.F, f, TFKS.T, t));
        }
        for (Span ca : fi.getContinuationRoleSpans(k)) {
          int s = Span.index(ca);
          yy.add(Node2Tests.lltvSugar(TFKS.S, s, TFKS.K, k+1*K, TFKS.F, f, TFKS.T, t));
        }
        for (Span ra : fi.getReferenceRoleSpans(k)) {
          int s = Span.index(ra);
          yy.add(Node2Tests.lltvSugar(TFKS.S, s, TFKS.K, k+2*K, TFKS.F, f, TFKS.T, t));
        }
      }
    }
    return yy;
  }

  @Override
  public FNParse decode(Iterable<LL<TV>> z) {
    throw new RuntimeException("implement me");
  }
}