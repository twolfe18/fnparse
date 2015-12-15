package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.function.ToLongFunction;

import edu.jhu.hlt.fnparse.rl.full.State.Info;
import edu.jhu.hlt.fnparse.rl.full.State.StepScores;
import edu.jhu.hlt.fnparse.rl.full3.PrimesLL;
import edu.jhu.hlt.fnparse.rl.full3.RoleLL;
import edu.jhu.hlt.tutils.Counts;

public class Node2 {

  public static class State {
    private Node2 root;
    private StepScores scores;

    public State(Node2 root, StepScores scores) {
      this.root = root;
      this.scores = scores;
    }

    public Node2 getRoot() {
      return root;
    }

    public StepScores getScore() {
      return scores;
    }
  }

  /**
   * How you control node and LL/cons construction.
   *
   * NOTE: Generally cons should be compliant with taking a null second argument
   * indicating NIL/empty list.
   */
  public static abstract class AbstractTransitionScheme {

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

    /**
     * @param goldYeses is a collection of relation indices s.t. y_i=1, all
     * other indices are assumed to be 0.
     *
     * We assume that the finest grain types come first in the label, e.g.
     *   s -> k -> f -> k -> null
     * for the transition system [T,F,K,S]
     */
    void provideLabel(Iterable<LL<TV>> goldYeses) {
      counts = new Counts<>();
      int prevLen = -1;
      for (LL<TV> x : goldYeses) {
        HashableIntArray xx = prefixValues2ar(x);
        if (prevLen < 0)
          prevLen = xx.length();
        else
          assert prevLen == xx.length();
        
        // TODO prefix counts (e.g. [t,f,k] counts)
        assert false : "not finished impl";
        
        counts.increment(xx);
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

    public HammingLoss lossHatch(Node2 n) {
      TV e = n.eggs.car();
      HashableIntArray x = prefixValues2ar(consPrefix(e, n.prefix));

      throw new RuntimeException();
    }
    public HammingLoss lossPrune(Node2 n) {
      throw new RuntimeException();
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

    public List<Node2> nextStates(Node2 root) {
      List<Node2> l = new ArrayList<>();
      nextStates(consChild(root, null), l);
      return l;
    }

    // f(wife) * f(egg) * f(hatch/squash)

    /**
     * TODO Add ability to bound new-node scores and bail out early (don't recurse).
     *
     * @param spine is the path from the current node (first element) to root (last element).
     * @param addTo is a collection of subsequent root nodes.
     */
    public void nextStates(LL<Node2> spine, Collection<Node2> addTo) {

      // Generate new nodes
      Node2 wife = spine.car();
      if (wife.eggs != null) {
        Node2 mother = hatch(wife);
        Node2 wife2 = squash(wife);
        if (spine.cdr() == null) {  // mother is root!
          addTo.add(mother);
          addTo.add(wife2);
        } else {
          Node2 momInLaw = spine.cdr().car();
          addTo.add(replaceChild(momInLaw, wife, mother));
          addTo.add(replaceChild(momInLaw, wife, wife2));
        }
      }

      // Recurse
      for (LL<Node2> cur = wife.children; cur != null; cur = cur.cdr()) {
        LL<Node2> spine2 = consChild(cur.car(), spine);
        nextStates(spine2, addTo);
      }
    }

    public Node2 genRootNode() {
      LL<TV> eggs = genEggs(null);
      return newNode(null, eggs, null, null);
    }

    public State getRootState(Info info) {
      return new State(genRootNode(), StepScores.zero(info));
    }
  }

  /**
   * Some properties:
   * - fixed loop order: [T,F,K,S]
   * - generates 5 random eggs at every node
   */
  public static class DebugTS extends AbstractTransitionScheme {
    private ToLongFunction<LL<TV>> getPrimes;
    private Random rand;
    public DebugTS() {
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
