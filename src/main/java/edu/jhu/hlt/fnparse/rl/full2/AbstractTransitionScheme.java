package edu.jhu.hlt.fnparse.rl.full2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.rl.full.Beam;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full.Beam.Mode;
import edu.jhu.hlt.fnparse.rl.full.State.HowToSearch;
import edu.jhu.hlt.fnparse.rl.full.State.StepScores;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.HashableIntArray;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.tuple.Pair;

/**
 * How you control node and LL/cons construction.
 *
 * NOTE: Generally cons should be compliant with taking a null second argument
 * indicating NIL/empty list.
 *
 * NOTE: The way I've implemented this, you're going to need on instance per
 * {@link Sentence}.
 *
 * @param Y is the type this transistion system will predict/derive
 *
 * @param Z is an argument to the State type. It must be at least big enough to
 * store counts/label and know how to search. The reason the transition system
 * itself doesn't know how to search is because we want to thread-through a Z
 * instance which specifies this behavior as late as possible (e.g. oracle,
 * decoder, and MV will all have different search coefficients but hopefully
 * can all be done with a single transition system instance).
 * TODO Calling this Z is misleading, implying that it is the "state" type,
 * change to something less confusing like Q.
 *
 * @author travis
 */
public abstract class AbstractTransitionScheme<Y, Z extends HowToSearch & HasCounts & HasRandom> {

  /**
   * See {@link TFKS}, maybe the only reason not to use LL<TV>.init
   */
  abstract LL<TV> consPrefix(TV car, LL<TV> cdr);

  /**
   * Create the appropriate list which stores aggregates for global features
   */
  abstract LL<Node2> consChild(Node2 car, LL<Node2> cdr);

  /**
   * Construct a node. Needed to be a method so that if you want to instantiate
   * a sub-class of Node2, you can do that behind this layer of indirection.
   */
  abstract Node2 newNode(LL<TV> prefix, LL<TV> eggs, LL<TV> pruned, LL<Node2> children);


  /**
   * Takes a label and converts it into a set of tuples.
   * For now I'm assuming a total ordering over types, and thus the returned
   * values should match this order.
   */
  public abstract Iterable<LL<TV>> encode(Y y);

  /**
   * Must be able to ignore prefixes. E.g. if an element of z only specifies
   * [t,f,k] (but not s), then it should not be added to y.
   */
  public abstract Y decode(Iterable<LL<TV>> z);


  /**
   * META: Specifies the loop order (e.g. [T,F,K,S]).
   * Create a list of possible next values to expand given a node at prefix.
   * For now I'm assuming the returned list is type-homongenous.
   */
  abstract LL<TV> genEggs(LL<TV> prefix, Z z);


  /**
   * Global/dynamic features.
   *
   * NOTE: static features should be wrapped in this.
   */
  abstract Adjoints featsHatch(Node2 n, Z z);
  abstract Adjoints featsSquash(Node2 n, Z z);


  /* NON-ABSTRACT STUFF *******************************************************/

  public void collectChildrensSpines(Node2 node, List<LL<TV>> addTo) {
    addTo.add(node.prefix);
    for (LL<Node2> cur = node.children; cur != null; cur = cur.cdr())
      collectChildrensSpines(cur.car(), addTo);
  }
  public Y decode(Node2 root) {
    List<LL<TV>> childrensSpines = new ArrayList<>();
    collectChildrensSpines(root, childrensSpines);
    return decode(childrensSpines);
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
  public HammingLoss lossHatch(Node2 n, Z info) {
    Counts<HashableIntArray> counts = info.getCounts();
    TV e = n.eggs.car();
    HashableIntArray x = prefixValues2ar(consPrefix(e, n.prefix));
    if (counts.getCount(x) == 0)
      return HammingLoss.FP;
    else
      return HammingLoss.TP;
  }

  public HammingLoss lossSquash(Node2 n, Z info) {
    Counts<HashableIntArray> counts = info.getCounts();
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
  public Node2 hatch(Node2 n, Z z) {
    LL<TV> newPrefix = consPrefix(n.eggs.car(), n.prefix);
    Node2 hatched = newNode(newPrefix, genEggs(newPrefix, z), null, null);
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


  /* NEXT-RELATED *************************************************************/

  public List<State2<Z>> nextStatesL(State2<Z> state) {
    int n = 64;
    DoubleBeam<State2<Z>> b = new DoubleBeam<>(n, Mode.CONSTRAINT_OBJ);
    nextStates(state, consChild(state.getNode(), null), b);
    if (b.size() == b.capacity())
      throw new RuntimeException("fixme");
    List<State2<Z>> l = new ArrayList<>();
    while (b.size() > 0)
      l.add(b.pop());
    return l;
  }

  public void nextStatesB(State2<Z> cur, final Beam<State2<Z>> nextBeam, final Beam<State2<Z>> constraints) {
    Beam<State2<Z>> composite = new Beam<State2<Z>>() {
      @Override
      public void offer(State2<Z> next) {
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
      public State2<Z> pop() {
        throw new RuntimeException("not supported");
      }
    };
//    LL<Node2> spine = consChild(cur.getNode(), null);
    LL<Node2> spine = new LL<>(cur.getNode(), null);
    nextStates(cur, spine, composite);
  }

  /**
   * TODO Add ability to bound new-node scores and bail out early (don't recurse).
   *
   * @param spine is the path from the current node (first element) to root (last element).
   * @param addTo is a collection of subsequent root nodes.
   */
  public void nextStates(State2<Z> prev, LL<Node2> spine, Beam<State2<Z>> addTo) {
    if (prev == null) {
      throw new IllegalArgumentException("prev may not be null, needed at "
          + "least for Info (e.g. search coefs)");
    }

    // Generate new nodes
    Node2 wife = spine.car();
    if (wife.eggs != null) {
      StepScores<Z> prevScores = prev.getStepScores();
      Z info = prevScores.getInfo();
      Random r = info.getRandom();

      // TODO check oneXperY with egg.getType() and wife.getType(), and if
      // true, after `mother = hatch(wife)`
      // do `while (mother.eggs) mother = squash(mother)`
      // (and make sure you keep track of loss too).
      // This will mean that the FNs you would normally have to count in
      // hatch will be counted naturally and easily by squash. If this is too
      // slow then optimize, but this is so clean.
      Node2 mother = hatch(wife, info);
      HammingLoss hatchLoss = lossHatch(wife, info);
      Adjoints hatchFeats = featsHatch(wife, info);
      double hatchRand = r.nextGaussian();
      StepScores<Z> hatchScore = new StepScores<>(info, hatchFeats, hatchLoss, hatchRand, prevScores);

      Node2 wife2 = squash(wife);
      HammingLoss squashLoss = lossSquash(wife, info);
      Adjoints squashFeats = featsSquash(wife, info);
      double squashRand = r.nextGaussian();
      StepScores<Z> squashScore = new StepScores<>(info, squashFeats, squashLoss, squashRand, prevScores);

      if (spine.cdr() == null) {  // mother is root!
        addTo.offer(new State2<Z>(mother, hatchScore));
        addTo.offer(new State2<Z>(wife2, squashScore));
      } else {
        Node2 momInLaw = spine.cdr().car();
        addTo.offer(new State2<Z>(replaceChild(momInLaw, wife, mother), hatchScore));
        addTo.offer(new State2<Z>(replaceChild(momInLaw, wife, wife2), squashScore));
      }
    }

    // Recurse
    for (LL<Node2> cur = wife.children; cur != null; cur = cur.cdr()) {
//      LL<Node2> spine2 = consChild(cur.car(), spine);
      LL<Node2> spine2 = new LL<>(cur.car(), spine);
      nextStates(prev, spine2, addTo);
    }
  }

  /** Takes Info/Config from the state of this instance, see getInfo */
  public Pair<State2<Z>, DoubleBeam<State2<Z>>> runInference(State2<Z> s0) {
    Z inf = s0.getStepScores().getInfo();

    // Objective: s(z) + max_{y \in Proj(z)} loss(y)
    // [where s(z) may contain random scores]
    DoubleBeam<State2<Z>> all = new DoubleBeam<>(inf.numConstraints(), Beam.Mode.CONSTRAINT_OBJ);

    // Objective: search objective, that is,
    // coef:      accumLoss    accumModel      accumRand
    // oracle:    -1             0              0
    // mv:        +1            +1              0
    DoubleBeam<State2<Z>> cur = new DoubleBeam<>(inf.beamSize(), Beam.Mode.BEAM_SEARCH_OBJ);
    DoubleBeam<State2<Z>> next = new DoubleBeam<>(inf.beamSize(), Beam.Mode.BEAM_SEARCH_OBJ);

    State2<Z> lastState = null;
    //      push(next, all, s0);
    next.offer(s0); all.offer(s0);
    for (int i = 0; true; i++) {
      if (Node2.DEBUG) Log.debug("starting iter=" + i);
      DoubleBeam<State2<Z>> t = cur; cur = next; next = t;
      assert next.size() == 0;
      for (int b = 0; cur.size() > 0; b++) {
        State2<Z> s = cur.pop();
        if (b == 0) // best item in cur
          lastState = s;
        //          s.next(next, all);
        nextStatesB(s, next, all);
      }
      if (Node2.DEBUG) Log.info("collapseRate=" + next.getCollapseRate());
      if (next.size() == 0)
        break;
    }

    assert lastState != null;
    return new Pair<>(lastState, all);
  }

  public Node2 genRootNode(Z info) {
    LL<TV> eggs = genEggs(null, info);
    return newNode(null, eggs, null, null);
  }

  public State2<Z> genRootState(Z info) {
    return new State2<>(genRootNode(info), StepScores.zero(info));
  }

  /**
   * Convenient conversion method, preserves order like:
   *   (a -> b -> c) goes to [a, b, c]
   */
  public static HashableIntArray prefixValues2ar(LL<TV> x) {
    int len = 0;
    for (LL<TV> cur = x; cur != null; cur = cur.cdr())
      len++;
    int[] ar = new int[len];
    int i = 0;
    for (LL<TV> cur = x; cur != null; cur = cur.cdr())
      ar[i++] = cur.car().getValue();
    return new HashableIntArray(ar);
  }
}
