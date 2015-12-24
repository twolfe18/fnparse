package edu.jhu.hlt.fnparse.rl.full2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.rl.full.Beam;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full.Beam.Mode;
import edu.jhu.hlt.fnparse.rl.full.HowToSearch;
import edu.jhu.hlt.fnparse.rl.full.MaxLoss;
import edu.jhu.hlt.fnparse.rl.full.SearchCoefficients;
import edu.jhu.hlt.fnparse.rl.full.StepScores;
import edu.jhu.hlt.fnparse.util.HasRandom;
import edu.jhu.hlt.tutils.HashableIntArray;
import edu.jhu.hlt.tutils.Log;
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

  public static boolean DEBUG = false;
  public static boolean DEBUG_LOSS = true;
  public static boolean DEBUG_ACTION_MAX_LOSS = true;

  /**
   * How to glue stuff together. Hide instantiations of LL which keep track of
   * useful aggregators in the implementation of these methods.
   */
  abstract TFKS consPrefix(TVNS car, TFKS cdr);
  abstract LLTVN consEggs(TVN car, LLTVN cdr);
  abstract LLTVNS consPruned(TVNS car, LLTVNS cdr);
  abstract LLSSP consChild(Node2 car, LLSSP cdr);

  /**
   * These parameterize the costs of moving from eggs -> (pruned|children).
   *
   * Global/dynamic features.
   *
   * NOTE: Pay attention to the corresponding methods of hatch and squash;
   * they both involve taking the first egg (don't just look at prefix!)
   *
   * NOTE: static features should be wrapped in this.
   *
   */
  abstract TVNS scoreSquash(Node2 parentWhoseNextEggWillBeSquashed, Z info);
  abstract TVNS scoreHatch(Node2 parentWhoseNextEggWillBeHatched, Z info);

  /**
   * Construct a node. Needed to be a method so that if you want to instantiate
   * a sub-class of Node2, you can do that behind this layer of indirection.
   */
  abstract Node2 newNode(SearchCoefficients coefs, TFKS prefix, LLTVN eggs, LLTVNS pruned, LLSSP children);

  /**
   * META: Specifies the loop order (e.g. [T,F,K,S]).
   * Create a list of possible next values to expand given a node at prefix.
   * For now I'm assuming the returned list is type-homongenous.
   */
  abstract LLTVN genEggs(TFKS prefix, Z z);

  /**
   * Takes a label and converts it into a set of tuples.
   * For now I'm assuming a total ordering over types, and thus the returned
   * values should match this order.
   */
  public abstract Iterable<LL<TVN>> encode(Y y);

  /**
   * Must be able to ignore prefixes. E.g. if an element of z only specifies
   * [t,f,k] (but not s), then it should not be added to y.
   */
  public abstract Y decode(Iterable<LL<TVNS>> z, Z info);


  /* NON-ABSTRACT STUFF *******************************************************/

  public void collectChildrensSpines(Node2 node, List<LL<TVNS>> addTo) {
    if (node.prefix != null)
      addTo.add(node.prefix);
    for (LLSSP cur = node.children; cur != null; cur = cur.cdr())
      collectChildrensSpines(cur.car(), addTo);
  }
  public Y decode(State2<Z> root) {
    if (DEBUG) {
      Log.info("running on:");
      root.getRoot().show(System.out);
    }
    List<LL<TVNS>> childrensSpines = new ArrayList<>();
    collectChildrensSpines(root.getRoot(), childrensSpines);
    if (DEBUG)
      Log.info("numSpines=" + childrensSpines.size());
    return decode(childrensSpines, root.getStepScores().getInfo());
  }

  /* COHERENCE RULES ********************************************************/
  // The goal is to build these basic operations from the operations defined
  // above. Thus, the implementation of the mehtods above must make sense in
  // light of the implementation below.

  /** Returns a copy of n with the child formed by hatching the next egg */
  public Node2 hatch(Node2 wife, Z z) {
    TVNS cracked = scoreHatch(wife, z); // contains score(hatch)
    TFKS newPrefix = consPrefix(cracked, wife.prefix);
    LLTVN newEggs = genEggs(newPrefix, z);
    Node2 hatched = newNode(wife.getCoefs(), newPrefix, newEggs, null, null);
    LLSSP newChildrenhildren = consChild(hatched, wife.children);
    Node2 mother = newNode(wife.getCoefs(), wife.prefix, wife.eggs.cdr(), wife.pruned, newChildrenhildren);
    if (DEBUG && DEBUG_ACTION_MAX_LOSS) {
      MaxLoss wl = wife.getStepScores().getLoss();
      MaxLoss ml = mother.getStepScores().getLoss();
      Log.info("wife:   " + wl);
      Log.info("mother: " + ml);
    }
    return mother;
  }

  /** Returns a copy of n with the next egg moved to pruned */
  public Node2 squash(Node2 wife, Z info) {
    TVNS cracked = scoreSquash(wife, info);
    LLTVNS newPruned = consPruned(cracked, wife.pruned);
    Node2 wife2 = newNode(wife.getCoefs(), wife.prefix, wife.eggs.cdr(), newPruned, wife.children);
    if (DEBUG && DEBUG_ACTION_MAX_LOSS) {
      MaxLoss wl = wife.getStepScores().getLoss();
      MaxLoss ml = wife2.getStepScores().getLoss();
      Log.info("wife:  " + wl);
      Log.info("wife2: " + ml);
      if (ml.minLoss() == 0)
        System.out.println("squash has no loss!");
    }
    return wife2;
  }

  // NOTE: This has to be in the module due to the need for consChild
  public Node2 replaceChild(Node2 parent, Node2 searchChild, Node2 replaceChild) {
    // Search and store prefix
    ArrayDeque<Node2> stack = new ArrayDeque<>();
    LLSSP newChildren = parent.children;
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
    return newNode(parent.getCoefs(), parent.prefix, parent.eggs, parent.pruned, newChildren);
  }

  /**
   * Looks for searchChild in the first node in the spine's children, replaces it,
   * and returns a node representing the modified parent (first in spine).
   */
  public Node2 replaceNode(LL<Node2> spine, Node2 searchChild, Node2 replaceChild) {
    if (spine == null)
      return replaceChild;
    Node2 parent = spine.car();
    // Search and store prefix
    ArrayDeque<Node2> stack = new ArrayDeque<>();
    LLSSP newChildren = parent.children;
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
    Node2 newParent = newNode(parent.getCoefs(), parent.prefix, parent.eggs, parent.pruned, newChildren);
    return replaceNode(spine.cdr(), parent, newParent);
  }


  /* NEXT-RELATED *************************************************************/

  public List<State2<Z>> dbgNextStatesL(State2<Z> state) {
    int n = 64;
    DoubleBeam<State2<Z>> b = new DoubleBeam<>(n, Mode.CONSTRAINT_OBJ);
    nextStates(state, consChild(state.getRoot(), null), b);
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
    nextStates(cur, composite);
  }

  public void nextStates(State2<Z> root, Beam<State2<Z>> addTo) {
    LL<Node2> spine = new LL<>(root.getRoot(), null);
    nextStates(root, spine, addTo);
  }

  /**
   * TODO Add ability to bound new-node scores and bail out early (don't recurse).
   *
   * @param spine is the path from the current node (first element) to root (last element).
   * @param addTo is a collection of subsequent root nodes.
   */
  public void nextStates(State2<Z> root, LL<Node2> spine, Beam<State2<Z>> addTo) {
    if (root == null) {
      throw new IllegalArgumentException("prev may not be null, needed at "
          + "least for Info (e.g. search coefs)");
    }

    Node2 wife = spine.car();
    if (DEBUG) {
      Log.info("expanding from wife:");
      System.out.println("root score: " + root.getStepScores());
      System.out.print("wife: ");
      wife.show(System.out);
    }

    // Generate new nodes
    if (wife.eggs == null) {
      if (DEBUG) Log.info("wife.eggs is null");
    } else {
      StepScores<Z> prevScores = root.getStepScores();
      Z info = prevScores.getInfo();

      // Play-out the actions which lead to modified versions of this node (wife)
      Node2 mother = hatch(wife, info);
      Node2 wife2 = squash(wife, info);

      // Zip everything back up to get a root reflecting these modifications
      LL<Node2> momInLaw = spine.cdr();
      Node2 rootHatch = replaceNode(momInLaw, wife, mother);
      Node2 rootSquash = replaceNode(momInLaw, wife, wife2);
      addTo.offer(new State2<Z>(rootHatch, "hatch"));
      addTo.offer(new State2<Z>(rootSquash, "squash"));
    }

    // Recurse
    int i = 0;
    for (LL<Node2> cur = wife.children; cur != null; cur = cur.cdr(), i++) {
      if (DEBUG)
        Log.info("recursing on child " + i);
      LL<Node2> spine2 = new LL<>(cur.car(), spine);
      nextStates(root, spine2, addTo);
    }
  }

  /** Takes Info/Config from the state of this instance, see getInfo */
  public Pair<State2<Z>, DoubleBeam<State2<Z>>> runInference(State2<Z> s0) {
    if (DEBUG)
      Log.info("starting inference from state:");
    Z inf = s0.getStepScores().getInfo();

    // Objective: s(z) + max_{y \in Proj(z)} loss(y)
    // [where s(z) may contain random scores]
    DoubleBeam<State2<Z>> all = new DoubleBeam<>(inf.numConstraints(), Beam.Mode.CONSTRAINT_OBJ);

    // Objective: search objective, that is,
    // coef:      accumLoss    accumModel      accumRand
    // oracle:    -1             0              0
    // mv:        +1            +1              0
    Beam.Mode bsearchObj = Beam.Mode.BEAM_SEARCH_OBJ;
    DoubleBeam<State2<Z>> cur = new DoubleBeam<>(inf.beamSize(), bsearchObj);
    DoubleBeam<State2<Z>> next = new DoubleBeam<>(inf.beamSize(), bsearchObj);

    State2<Z> lastState = null;
    next.offer(s0); all.offer(s0);
    for (int i = 0; true; i++) {
      if (DEBUG) Log.debug("starting iter=" + i);
      DoubleBeam<State2<Z>> t = cur; cur = next; next = t;
      assert next.size() == 0;
      for (int b = 0; cur.size() > 0; b++) {
        State2<Z> s = cur.pop();
        if (b == 0) // best item in cur
          lastState = s;
        nextStatesB(s, next, all);
      }
      if (DEBUG) Log.info("collapseRate=" + next.getCollapseRate());
      if (next.size() == 0) {
        if (DEBUG) Log.info("returning because next.size==0");
        break;
      }
    }

    assert lastState != null;
    if (DEBUG) {
      Log.info("lastState:");
      System.out.println(lastState.getStepScores());
      lastState.getRoot().show(System.out);
      Log.info("bestState:");
      System.out.println(all.peek().getStepScores());
      all.peek().getRoot().show(System.out);
    }
    return new Pair<>(lastState, all);
  }

  public Node2 genRootNode(Z info) {
    LLTVN eggs = genEggs(null, info);
    return newNode(info, null, eggs, null, null);
  }

  public State2<Z> genRootState(Z info) {
    Node2 root = genRootNode(info);
    return new State2<>(root);
  }

  /**
   * Convenient conversion method, preserves order like:
   *   (a -> b -> c) goes to [a, b, c]
   */
  public static <T extends TVN> HashableIntArray prefixValues2ar(LL<T> x) {
    int len = 0;
    for (LL<T> cur = x; cur != null; cur = cur.cdr())
      len++;
    int[] ar = new int[len];
    int i = 0;
    for (LL<T> cur = x; cur != null; cur = cur.cdr())
      ar[i++] = cur.car().value;
    return new HashableIntArray(ar);
  }
}
