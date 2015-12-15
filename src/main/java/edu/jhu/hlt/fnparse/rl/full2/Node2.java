package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.function.ToLongFunction;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.rl.full.Beam;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full.Beam.Mode;
import edu.jhu.hlt.fnparse.rl.full.Beam.StateLike;
import edu.jhu.hlt.fnparse.rl.full.Config;
import edu.jhu.hlt.fnparse.rl.full.State.Info;
import edu.jhu.hlt.fnparse.rl.full.State.StepScores;
import edu.jhu.hlt.fnparse.rl.full3.PrimesLL;
import edu.jhu.hlt.fnparse.rl.full3.RoleLL;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.tuple.Pair;

public class Node2 {

  public static boolean DEBUG = false;

  /** Wraps a root node and keeps track of scores */
  public static class State2 implements StateLike {
    private Node2 root;
    private StepScores scores;

    @Override
    public String toString() {
      return String.format("(State\n\t%s\n\t%s\n)", scores.toString(), root.toString());
    }

    public static StepScores safeScores(State2 s) {
      if (s == null)
        return null;
      return s.scores;
    }

    public State2(Node2 root, StepScores scores) {
      this.root = root;
      this.scores = scores;
    }

    public Node2 getNode() {
      return root;
    }

    public StepScores getStepScores() {
      return scores;
    }

    @Override
    public BigInteger getSignature() {
      return ((NodeWithSignature) root).getSignature();
    }
  }

  /**
   * How you control node and LL/cons construction.
   *
   * NOTE: Generally cons should be compliant with taking a null second argument
   * indicating NIL/empty list.
   *
   * @param Y is the type this transistion system will predict/derive
   */
  public static abstract class AbstractTransitionScheme<Y> {

    /** See {@link TFKS}, maybe the only reason not to use LL<TV>.init */
    abstract LL<TV> consPrefix(TV car, LL<TV> cdr);

    /** Create the appropriate list which stores aggregates for global features */
    abstract LL<Node2> consChild(Node2 car, LL<Node2> cdr);

    abstract Node2 newNode(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children);

    /**
     * META: Specifies the loop order (e.g. [T,F,K,S]).
     * Create a list of possible next values to expand given a node at prefix.
     * For now I'm assuming the returned list is type-homongenous.
     */
    abstract LL<TV> genEggs(LL<TV> prefix);

    /** Guides search (contains coefficients) and other misc configuration info */
    abstract Info getInfo();

    /**
     * Global/dynamic features.
     *
     * NOTE: static features should be wrapped in this.
     */
    abstract Adjoints featsHatch(Node2 n);
    abstract Adjoints featsSquash(Node2 n);

    /**
     * Takes a label and converts it into a set of tuples.
     * For now I'm assuming a total ordering over types, and thus the returned
     * values should match this order.
     */
    abstract Iterable<LL<TV>> encode(Y y);
    abstract Y decode(Iterable<LL<TV>> z);

    /* NON-ABSTRACT STUFF *****************************************************/

    public Random getRand() {
      return getInfo().getRand();
    }

    public Y decode(Node2 root) {
      throw new RuntimeException("implement me");
    }

    void provideLabel(Y y) {
      provideLabel(encode(y));
    }
    /**
     * @param goldYeses is a collection of relation indices s.t. y_i=1, all
     * other indices are assumed to be 0.
     *
     * We assume that the finest grain types come first in the label, e.g.
     *   s -> k -> f -> k -> null
     * for the transition system [T,F,K,S]
     */
    void provideLabel(Iterable<LL<TV>> goldYeses) {
      if (counts == null)
        counts = new Counts<>();
      else
        counts.clear();
      int prevLen = -1;
      for (LL<TV> x : goldYeses) {
        HashableIntArray xx = prefixValues2ar(x);
        if (prevLen < 0)
          prevLen = xx.length();
        else
          assert prevLen == xx.length();

        counts.increment(xx);

        // prefix counts (e.g. [t,f,k] counts)
        for (LL<TV> cur = x.cdr(); cur != null; cur = cur.cdr())
          counts.increment(prefixValues2ar(cur));
      }
    }
    protected Counts<HashableIntArray> counts;

    public HashableIntArray prefixValues2ar(LL<TV> x) {
      int prevType = -1;
      int len = 0;
      for (LL<TV> cur = x; cur != null; cur = cur.cdr()) {
        assert prevType < cur.car().getType();
        prevType = cur.car().getType();
        len++;
      }
      int[] ar = new int[len];
      int i = 0;
      for (LL<TV> cur = x; cur != null; cur = cur.cdr())
        ar[i++] = cur.car().getValue();
      return new HashableIntArray(ar);
    }

    /**
     * Hatch takes a node parent = z_{v_1, v_2, ... v_k} and creates a new node
     * child = z_{v_1, v_2, ... v_k, v_{k+1}}.
     *
     * We assume that z is an extension of y to include all prefixes of indices
     * allowed by the transition system.
     * (So far we're working with a full order over types, so
     *  {v_1}, {v_1, v_2}, ... {v_1, v_2, ... v_k})
     *
     * The hamming loss for hatch is only defined on the sub-space defined by
     * the exact length of child's prefix. Loss on longer sub-spaces must be
     * won/lost by sub-sequent actions (these slots are considered ? until
     * another child is added). Loss on shorter sub-spaces is claimed by actions
     * that lead to parent (and ancestors).
     *
     * Why don't I express loss not conditionally on oneXperY, but change the
     * transition system in light of oneXperY by:
     *   a hatch/commit under oneXperY will force many more squash/prune actions
     *   we must have a way to flatten many actions into a single action.
     * => Never worry about fn in hatch: this will live exclusively in squash
     *    and be forced by a change in the transition system.
     *
     * Hatch can only do ? -> 1 and thus can only introduce tp or fp.
     * Squash can only do ? -> 0 and thus can only introduce tn or fn.
     */
    public HammingLoss lossHatch(Node2 n) {
      if (counts == null)
        throw new IllegalStateException("you need to call provideLabel() first!");
      TV e = n.eggs.car();
      HashableIntArray x = prefixValues2ar(consPrefix(e, n.prefix));
      if (counts.getCount(x) == 0)
        return HammingLoss.FP;
      else
        return HammingLoss.TP;
    }
    public HammingLoss lossSquash(Node2 n) {
      if (counts == null)
        throw new IllegalStateException("you need to call provideLabel() first!");
      TV e = n.eggs.car();
      HashableIntArray x = prefixValues2ar(consPrefix(e, n.prefix));
      if (counts.getCount(x) == 0)
        return HammingLoss.TN;
      else
        return HammingLoss.FN;
    }

    /* COHERENCE RULES ********************************************************/
    // The goal is to build these basic operations from the operations defined
    // above. Thus, the implementation of the mehtods above must make sense in
    // light of the implementation below.

    /** Returns a copy of n with the child formed by hatching the next egg */
    public Node2 hatch(Node2 n) {
      LL<TV> newPrefix = consPrefix(n.eggs.car(), n.prefix);
      Node2 hatched = newNode(newPrefix, genEggs(newPrefix), null, null);
      LL<Node2> children = consChild(hatched, n.children);
      return newNode(n.prefix, n.eggs.cdr(), n.pruned, children);
    }

    /** Returns a copy of n with the next egg moved to pruned */
    public Node2 squash(Node2 n) {
      LL<TV> newPruned = consPrefix(n.eggs.car(), n.pruned);
      return newNode(n.prefix, n.eggs.cdr(), newPruned, n.children);
    }

    // NOTE: This has to be in the module due to the need for consChild
    public Node2 replaceChild(Node2 parent, Node2 searchChild, Node2 replaceChild) {
      // Search and store prefix
      ArrayDeque<Node2> stack = new ArrayDeque<>();
      LL<Node2> newChildren = parent.children;
      while (newChildren != null && newChildren.car() != searchChild) {
        stack.push(newChildren.car());
        newChildren = newChildren.cdr();
      }
      // Replace or delete searchChild
      if (replaceChild == null)
        newChildren = newChildren.cdr();
      else
        newChildren = consChild(replaceChild, newChildren.cdr());
      // Pre-pend the prefix appearing before searchChild
      while (!stack.isEmpty())
        newChildren = consChild(stack.pop(), newChildren);
      // Reconstruct the parent node
      return newNode(parent.prefix, parent.eggs, parent.pruned, newChildren);
    }

    /** prev may be null, root may not */
    public List<State2> nextStatesL(State2 prev, Node2 root) {
      int n = 64;
      DoubleBeam<State2> b = new DoubleBeam<>(n, Mode.CONSTRAINT_OBJ);
      nextStates(prev, consChild(root, null), b);
      if (b.size() == b.capacity())
        throw new RuntimeException("fixme");
      List<State2> l = new ArrayList<>();
      while (b.size() > 0)
        l.add(b.pop());
      return l;
    }

    public void nextStatesB(State2 cur, final Beam<State2> nextBeam, final Beam<State2> constraints) {
      Beam<State2> composite = new Beam<State2>() {
        @Override
        public void offer(State2 next) {
          if (nextBeam != null)
            nextBeam.offer(next);
          if (constraints != null)
            constraints.offer(next);
        }
        @Override
        public Double lowerBound() {
          throw new RuntimeException("figure out how to bound, use nextBeam? use constraints? must split into two bounds?");
        }
        @Override
        public State2 pop() {
          throw new RuntimeException("not supported");
        }
      };
      nextStates(cur, new LL<>(cur.getNode(), null), composite);
    }

    // f(wife) * f(egg) * f(hatch/squash)

    /**
     * TODO Add ability to bound new-node scores and bail out early (don't recurse).
     *
     * @param spine is the path from the current node (first element) to root (last element).
     * @param addTo is a collection of subsequent root nodes.
     */
    public void nextStates(State2 prev, LL<Node2> spine, Beam<State2> addTo) {

      // Generate new nodes
      Node2 wife = spine.car();
      if (wife.eggs != null) {
        Info i = getInfo();
        Random r = i.getRand();
        StepScores prevScores = State2.safeScores(prev);

        // TODO check oneXperY with egg.getType() and wife.getType(), and if
        // true, after `mother = hatch(wife)`
        // do `while (mother.eggs) mother = squash(mother)`
        // (and make sure you keep track of loss too).
        // This will mean that the FNs you would normally have to count in
        // hatch will be counted naturally and easily by squash. If this is too
        // slow then optimize, but this is so clean.
        Node2 mother = hatch(wife);
        HammingLoss hatchLoss = lossHatch(wife);
        Adjoints hatchFeats = featsHatch(wife);
        double hatchRand = r.nextGaussian();
        StepScores hatchScore = new StepScores(i, hatchFeats, hatchLoss, hatchRand, prevScores);

        Node2 wife2 = squash(wife);
        HammingLoss squashLoss = lossSquash(wife);
        Adjoints squashFeats = featsSquash(wife);
        double squashRand = r.nextGaussian();
        StepScores squashScore = new StepScores(i, squashFeats, squashLoss, squashRand, prevScores);

        if (spine.cdr() == null) {  // mother is root!
          addTo.offer(new State2(mother, hatchScore));
          addTo.offer(new State2(wife2, squashScore));
        } else {
          Node2 momInLaw = spine.cdr().car();
          addTo.offer(new State2(replaceChild(momInLaw, wife, mother), hatchScore));
          addTo.offer(new State2(replaceChild(momInLaw, wife, wife2), squashScore));
        }
      }

      // Recurse
      for (LL<Node2> cur = wife.children; cur != null; cur = cur.cdr()) {
        LL<Node2> spine2 = consChild(cur.car(), spine);
        nextStates(prev, spine2, addTo);
      }
    }

    /** Takes Info/Config from the state of this instance, see getInfo */
    public Pair<State2, DoubleBeam<State2>> runInference(FNParse y) {
      Info inf = getInfo();
      State2 s0 = genRootState();

      // Objective: s(z) + max_{y \in Proj(z)} loss(y)
      // [where s(z) may contain random scores]
      DoubleBeam<State2> all = new DoubleBeam<>(inf.beamSize * 16, Beam.Mode.CONSTRAINT_OBJ);

      // Objective: search objective, that is,
      // coef:      accumLoss    accumModel      accumRand
      // oracle:    -1             0              0
      // mv:        +1            +1              0
      DoubleBeam<State2> cur = new DoubleBeam<>(inf.beamSize, Beam.Mode.BEAM_SEARCH_OBJ);
      DoubleBeam<State2> next = new DoubleBeam<>(inf.beamSize, Beam.Mode.BEAM_SEARCH_OBJ);

      State2 lastState = null;
//      push(next, all, s0);
      next.offer(s0); all.offer(s0);
      for (int i = 0; true; i++) {
        if (DEBUG) Log.debug("starting iter=" + i);
        DoubleBeam<State2> t = cur; cur = next; next = t;
        assert next.size() == 0;
        for (int b = 0; cur.size() > 0; b++) {
          State2 s = cur.pop();
          if (b == 0) // best item in cur
            lastState = s;
//          s.next(next, all);
          nextStatesB(s, next, all);
        }
        if (DEBUG) Log.info("collapseRate=" + next.getCollapseRate());
        if (next.size() == 0)
          break;
      }

      assert lastState != null;
      return new Pair<>(lastState, all);
    }

    public Node2 genRootNode() {
      LL<TV> eggs = genEggs(null);
      return newNode(null, eggs, null, null);
    }

    public State2 genRootState() {
      Info info = getInfo();
      return new State2(genRootNode(), StepScores.zero(info));
    }
  }

  /**
   * Some properties:
   * - fixed loop order: [T,F,K,S]
   * - generates 5 random eggs at every node
   */
  public static class DebugTS extends AbstractTransitionScheme<FNParse> {
    private ToLongFunction<LL<TV>> getPrimes;
    private Random rand;
    private Info info;
    public DebugTS() {
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
    // TODO Move this out of module and into TransitionSystem?
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


  /**
   * If children are {@link PrimesLL}, then this will let you get out a
   * signature
   */
  public static class NodeWithSignature extends Node2 {
    public NodeWithSignature(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children) {
      super(prefix, eggs, pruned, children);
      assert children instanceof PrimesLL;
    }
    public BigInteger getSignature() {
      if (children == null)
        return BigInteger.ZERO;
      if (isLeaf())
        return ((PrimesLL) children).getPrimesProduct();
      BigInteger p = BigInteger.ONE;
      for (LL<Node2> cur = children; cur != null; cur = cur.cdr()) {
        NodeWithSignature cs = (NodeWithSignature) cur.item;
        p = p.multiply(cs.getSignature());
      }
      return p;
    }
  }


//  public static class NodeWithActions extends NodeWithSignature {
//    public NodeWithActions(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children) {
//      super(prefix, eggs, pruned, children);
//    }
//
//    public List<String> getActions() {
//      List<String> actions = new ArrayList<>();
//      /*
//       * Nodes should generate *some* actions, but not the action set,
//       * which should be an operation one level up. If nodes generated an
//       * entire sub-tree, then the action would need to know too much.
//       *
//       * Right now I have nodes generation parts of actions with hatch/squash.
//       * They need to either point to a distinguished Node for sparse feature
//       * extraction or generate the features themselves.
//       * I had embraced the "build the state then read-off the features" model
//       * as opposed to the "featurize the action instead of the state" model.
//       */
//      return actions;
//    }
//  }
//
//  /**
//   * Generates actions that come with loss assesments on them.
//   */
//  public static class NodeWithGoldLabels extends NodeWithActions {
//    // TODO I need to have an index from prefix:LL<TV> to the set of all items
//    // in that domain.
//    // Note: I think I need more than just the types that may follow (e.g. at
//    // a TF node under B=[T,F,K,S] just the set of non-zero K values) since the
//    // loss must be computed at the item/token level.
//    public NodeWithGoldLabels(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children) {
//      super(prefix, eggs, pruned, children);
//    }
//  }

  public final LL<TV> prefix;        // path from root, including teis nodes (type,value)
  public final LL<TV> eggs;
  public final LL<TV> pruned;
  public final LL<Node2> children;

  public Node2(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children) {
    super();
    this.prefix = prefix;
    this.eggs = eggs;
    this.pruned = pruned;
    this.children = children;
  }

  public boolean isLeaf() {
    boolean leaf = eggs == null && children == null;
    if (leaf) assert pruned == null;
    return leaf;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("(Node\n");
    sb.append(" prefix=" + prefix + "\n");
    sb.append(" eggs=" + eggs + "\n");
    sb.append(" pruned=" + pruned + "\n");
    sb.append(" children=" + children + "\n");
    sb.append(')');
    return sb.toString();
  }

  public int getType() {
    if (prefix == null)
      return -1;
    return prefix.item.getType();
  }
  public int getValue() {
    if (prefix == null)
      return -1;
    return prefix.item.getValue();
  }

  /** Returns a list of error messages (empty implies everything is good) */
  public List<String> dbgSanityCheck() {
    List<String> errs = new ArrayList<>();
    BitSet pt = getTypes(prefix);

    BitSet et = getTypes(eggs);
    if (et.intersects(pt))
      errs.add("egg types should not overlap with prefix types");

    BitSet prt = getTypes(pruned);
    if (prt.intersects(pt))
      errs.add("pruned types should not overlap with prefix types");

    return errs;
  }
  public void dbgSantityCheckA() {
    List<String> errs = dbgSanityCheck();
    assert errs.isEmpty() : errs;
  }
  public void dbgSantityCheckE() {
    List<String> errs = dbgSanityCheck();
    if (!errs.isEmpty())
      throw new RuntimeException(errs.toString());
  }

  public static BitSet getTypes(LL<TV> items) {
    BitSet bs = new BitSet();
    for (LL<TV> cur = items; cur != null; cur = cur.next)
      bs.set(cur.item.getType());
    return bs;
  }
}
