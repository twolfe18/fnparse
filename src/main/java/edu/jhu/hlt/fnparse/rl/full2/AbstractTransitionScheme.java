package edu.jhu.hlt.fnparse.rl.full2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.rl.full.Beam;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full.Beam.Mode;
import edu.jhu.hlt.fnparse.rl.full.State.Info;
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
 * @param Y is the type this transistion system will predict/derive
 *
 * @author travis
 */
public abstract class AbstractTransitionScheme<Y> {

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
  abstract Iterable<LL<TV>> encode(Y y);
  abstract Y decode(Iterable<LL<TV>> z);


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


  /* NON-ABSTRACT STUFF *******************************************************/
  protected Counts<HashableIntArray> counts;

  public Random getRand() {
    return getInfo().getRand();
  }

  public Y decode(Node2 root) {
    throw new RuntimeException("implement me");
  }

  public void provideLabel(Y y) {
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
  public void provideLabel(Iterable<LL<TV>> goldYeses) {
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


  /* NEXT-RELATED *************************************************************/

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
      if (Node2.DEBUG) Log.debug("starting iter=" + i);
      DoubleBeam<State2> t = cur; cur = next; next = t;
      assert next.size() == 0;
      for (int b = 0; cur.size() > 0; b++) {
        State2 s = cur.pop();
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

  public Node2 genRootNode() {
    LL<TV> eggs = genEggs(null);
    return newNode(null, eggs, null, null);
  }

  public State2 genRootState() {
    Info info = getInfo();
    return new State2(genRootNode(), StepScores.zero(info));
  }
}
