package edu.jhu.hlt.fnparse.rl.full2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.rl.full.Beam;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full.HowToSearch;
import edu.jhu.hlt.fnparse.rl.full.Info;
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
public abstract class AbstractTransitionScheme<Y, Z extends /*HowToSearch &*/ HasCounts & HasRandom> {

  public static boolean DEBUG = false;
  public static boolean DEBUG_SEARCH = false;
  public static boolean DEBUG_ACTION_MAX_LOSS = false;
  public static boolean DEBUG_COLLAPSE = true;
  public static boolean DEBUG_REPLACE_NODE = false;

  /**
   * This is a hook for clamping down on the set of actions which are considered
   * out of a given state. What this means is that you can only perform actions
   * generated from one sub-tree (of a node of the specified type), for example
   * F node at a time, or more strictly, a K node at a time [1]. The way you do
   * this is, starting at the parent of the type-at-a-time level, allow a new F
   * actions iff the last F sub-tree has no actions in it. You can see that
   * there is an inductive proof that if a given F node has no actions out of
   * it, then all of its older siblings don't either since that F node could not
   * have been created unless the older siblings were done with their actions.
   *
   * [1] Note that K-at-a-time does not imply F-at-a-time. In fact, you may want
   * K-and-F-at-a-time. K-at-a-time alone would mean that you could have many
   * frames going at the same time, but that you must immediately make all S
   * valued children assesments of a given K node (hopefully N-1 prunes and 1
   * hatch).
   *
   * @see AbstractTransitionScheme#nextStates(State2, LL, Beam) for how this
   * mehtod's return value is used and affects next states.
   *
   * @param type is the type for which you may only generate actions from one
   * sub-tree at a time.
   */
  boolean oneAtATime(int type) {
    return false;
  }

  /**
   * How to glue stuff together. Hide instantiations of LL which keep track of
   * useful aggregators in the implementation of these methods.
   */
  abstract TFKS consPrefix(TVNS car, TFKS cdr, Z info);
  abstract LLTVN consEggs(TVN car, LLTVN cdr, Z info);
  abstract LLTVNS consPruned(TVNS car, LLTVNS cdr, Z info);
  abstract LLSSP consChild(Node2 car, LLSSP cdr, Z info);

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
  abstract Node2 newNode(TFKS prefix, LLTVN eggs, LLTVNS pruned, LLSSP children);

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
    return decode(childrensSpines, root.getInfo());
  }

  /* COHERENCE RULES ********************************************************/
  // The goal is to build these basic operations from the operations defined
  // above. Thus, the implementation of the mehtods above must make sense in
  // light of the implementation below.

  /** Returns a copy of n with the child formed by hatching the next egg */
  public Node2 hatch(Node2 wife, Z info) {
    TVNS cracked = scoreHatch(wife, info); // contains score(hatch)
    TFKS newPrefix = consPrefix(cracked, wife.prefix, info);
    LLTVN newEggs = genEggs(newPrefix, info);
    Node2 hatched = newNode(newPrefix, newEggs, null, null);
    LLSSP newChildrenhildren = consChild(hatched, wife.children, info);
    Node2 mother = newNode(wife.prefix, wife.eggs.cdr(), wife.pruned, newChildrenhildren);
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
    LLTVNS newPruned = consPruned(cracked, wife.pruned, info);
    Node2 wife2 = newNode(wife.prefix, wife.eggs.cdr(), newPruned, wife.children);
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
  public Node2 replaceChild(Node2 parent, Node2 searchChild, Node2 replaceChild, Z info) {
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
      newChildren = consChild(replaceChild, newChildren.cdr(), info);
    // Pre-pend the prefix appearing before searchChild
    while (!stack.isEmpty())
      newChildren = consChild(stack.pop(), newChildren, info);
    // Reconstruct the parent node
    return newNode(parent.prefix, parent.eggs, parent.pruned, newChildren);
  }

  /**
   * Looks for searchChild in the first node in the spine's children, replaces it,
   * and returns a node representing the modified parent (first in spine).
   */
  public Node2 replaceNode(LL<Node2> spine, Node2 searchChild, Node2 replaceChild, Z info) {
    if (DEBUG && DEBUG_REPLACE_NODE) {
      System.out.println("searchChild.loss=" + searchChild.getLoss());
      System.out.println("replaceChild.loss=" + replaceChild.getLoss());
      System.out.println("searchChild.model=" + searchChild.getModelScore());
      System.out.println("replaceChild.model=" + replaceChild.getModelScore());
    }
    if (spine == null) {
      if (DEBUG && DEBUG_REPLACE_NODE)
        System.out.println("returning b/c spine is null");
      return replaceChild;
    }
    if (DEBUG && DEBUG_REPLACE_NODE) {
      System.out.println("starting:");
      if (replaceChild.getLoss().fn > 0)
        System.out.println("break");
    }
    Node2 parent = spine.car();

    // Search and store prefix
    ArrayDeque<Node2> stack = new ArrayDeque<>();
    LLSSP newChildren = parent.children;
    while (newChildren != null && newChildren.car() != searchChild) {
      stack.push(newChildren.car());
      newChildren = newChildren.cdr();
    }

    if (DEBUG && DEBUG_REPLACE_NODE) {
      System.out.println("parent.children.length=" + parent.children.length
          + " stack.size=" + stack.size());
    }

    // Replace or delete searchChild
    if (replaceChild == null)
      newChildren = newChildren.cdr();
    else
      newChildren = consChild(replaceChild, newChildren.cdr(), info);

    // Pre-pend the prefix appearing before searchChild
    while (!stack.isEmpty())
      newChildren = consChild(stack.pop(), newChildren, info);

    // Reconstruct the parent node
    Node2 newParent = newNode(parent.prefix, parent.eggs, parent.pruned, newChildren);

    // Santity check
    if (DEBUG && DEBUG_REPLACE_NODE) {
      System.out.println("newParent.loss=" + newParent.getLoss());
      System.out.println("parent.loss=" + parent.getLoss());
      System.out.println("newParent.model=" + newParent.getModelScore());
      System.out.println("parent.model=" + parent.getModelScore());
    }
    assert newParent.getLoss().fn >= parent.getLoss().fn;
    assert newParent.getLoss().fp >= parent.getLoss().fp;

    return replaceNode(spine.cdr(), parent, newParent, info);
  }


  /* NEXT-RELATED *************************************************************/

  /* TODO Figure out how to do type-by-type (e.g. "frame-by-frame" for FN).
   * The trick is that you can only allow actions in a particular type-subtree
   * OR, if there are possible actions left in that type-subree, allow new-type.
   * If allow new-type to be in competition with various new-subtype actions,
   * then you will have to give it the cost of "as if you had done 'prune until
   * you can't prune anymore on the entire type-subtree'" to maintain the
   * desired semantics of only working on one type-subtree at a time.
   */

  public List<State2<Z>> dbgNextStatesL(State2<Z> state, HowToSearch hts) {
    DoubleBeam<State2<Z>> b = new DoubleBeam<>(hts);
    Z info = state.getInfo();
    nextStates(state, consChild(state.getRoot(), null, info), b);
    if (b.size() == b.capacity())
      throw new RuntimeException("fixme");
    List<State2<Z>> l = new ArrayList<>();
    while (b.size() > 0)
      l.add(b.pop());
    return l;
  }

  public void nextStatesB(State2<Z> cur, final DoubleBeam<State2<Z>> nextBeam, final DoubleBeam<State2<Z>> constraints) {
    Beam<State2<Z>> composite = new Beam<State2<Z>>() {
      @Override
      public boolean offer(State2<Z> next) {
        boolean a = false;
        if (nextBeam != null) {
          boolean b = nextBeam.offer(next);
          if (DEBUG && DEBUG_SEARCH) {
            SearchCoefficients c = nextBeam.getCoefficients();
            StepScores<?> ss = next.getStepScores();
            System.out.println("[nextBeam offer] add=" + b
                + " forwards=" + c.forwards(ss)
                + " model=" + ss.getModel().forwards()
                + " loss=" + ss.getLoss()
                + " coefs=" + c
                );
          }
          a |= b;
        }
        if (constraints != null)
          a |= constraints.offer(next);
        return a;
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
   * @return the number of next states offered.
   */
  public int nextStates(State2<Z> root, LL<Node2> spine, Beam<State2<Z>> addTo) {
    if (root == null) {
      throw new IllegalArgumentException("prev may not be null, needed at "
          + "least for Info (e.g. search coefs)");
    }

    Node2 wife = spine.car();
    if (DEBUG && DEBUG_SEARCH) {
      Log.info("expanding from wife:");
      System.out.println("root score: " + root.getStepScores());
      System.out.print("wife: ");
      wife.show(System.out);
    }

    int added = 0;

    // Recurse
    int i = 0;
    for (LL<Node2> cur = wife.children; cur != null; cur = cur.cdr(), i++) {
      if (DEBUG && DEBUG_SEARCH)
        Log.info("recursing on child " + i);
      LL<Node2> spine2 = new LL<>(cur.car(), spine);
      added += nextStates(root, spine2, addTo);
    }

    if (wife.eggs == null) {
      // No-op! Nothing more can be done with this node
      // TODO add in LL<Node2> frozen (pointer) for increased efficiency
      if (DEBUG && DEBUG_SEARCH)
        Log.info("wife.eggs is null");
    } else if (added == 0 || !oneAtATime(wife.getType())) {
      // Consider hatch or squash
      // Play-out the actions which lead to modified versions of this node (wife)
      Z info = root.getInfo();
      Node2 mother = hatch(wife, info);
      Node2 wife2 = squash(wife, info);

      // Zip everything back up to get a root reflecting these modifications
      LL<Node2> momInLaw = spine.cdr();
      if (DEBUG && DEBUG_REPLACE_NODE) Log.info("replaceNode for HATCH");
      Node2 rootHatch = replaceNode(momInLaw, wife, mother, info);
      if (DEBUG && DEBUG_REPLACE_NODE) Log.info("replaceNode for SQUASH");
      Node2 rootSquash = replaceNode(momInLaw, wife, wife2, info);

      // Santity check to ensure replaceNode worked:
      if (DEBUG && DEBUG_REPLACE_NODE) {
        Log.info("rootHatch.loss=" + rootHatch.getLoss());
        Log.info("rootSquash.loss=" + rootSquash.getLoss());
        Log.info("rootHatch.model=" + rootHatch.getModelScore());
        Log.info("rootSquash.model=" + rootSquash.getModelScore());
        if (rootSquash.getModelScore().forwards() < 0)
          System.out.println("break");
      }
      assert rootHatch.getLoss().fn >= mother.getLoss().fn;
      assert rootHatch.getLoss().fp >= mother.getLoss().fp;
      assert rootSquash.getLoss().fn >= wife2.getLoss().fn;
      assert rootSquash.getLoss().fp >= wife2.getLoss().fp;

      added += 2;
      addTo.offer(new State2<Z>(rootHatch, info, "hatch"));
      addTo.offer(new State2<Z>(rootSquash, info, "squash"));
    } else {
      if (DEBUG && DEBUG_SEARCH)
        Log.info("skipping this node because one at a time: " + wife.prefix);
    }

    return added;
  }

  public Pair<State2<Z>, DoubleBeam<State2<Z>>> runInference(State2<Z> s0, Info inf) {
    return runInference(s0, inf.htsBeam, inf.htsConstraints);
  }

  /** Takes Info/Config from the state of this instance, see getInfo */
  public Pair<State2<Z>, DoubleBeam<State2<Z>>> runInference(State2<Z> s0, HowToSearch beamSearch, HowToSearch constraints) {
    if (DEBUG && DEBUG_SEARCH)
      Log.info("starting inference from state:");
//    Z inf = s0.getStepScores().getInfo();

    // Objective: s(z) + max_{y \in Proj(z)} loss(y)
    // [where s(z) may contain random scores]
//    DoubleBeam<State2<Z>> all = new DoubleBeam<>(inf.numConstraints(), Beam.Mode.MAX_LOSS);
    DoubleBeam<State2<Z>> all = new DoubleBeam<>(constraints);

    // Objective: search objective, that is,
    // coef:      accumLoss    accumModel      accumRand
    // oracle:    -1             0              0
    // mv:        +1            +1              0
//    Beam.Mode bsearchObj = Beam.Mode.H_LOSS;
//    DoubleBeam<State2<Z>> cur = new DoubleBeam<>(inf.beamSize(), bsearchObj);
//    DoubleBeam<State2<Z>> next = new DoubleBeam<>(inf.beamSize(), bsearchObj);
    DoubleBeam<State2<Z>> cur = new DoubleBeam<>(beamSearch);
    DoubleBeam<State2<Z>> next = new DoubleBeam<>(beamSearch);

//    if (DEBUG && DEBUG_COLLAPSE)
//      Log.info("beamSize=" + inf.beamSize() + " numConstraints=" + inf.numConstraints());

    State2<Z> lastState = null;
    next.offer(s0); all.offer(s0);
    for (int i = 0; true; i++) {
      if (DEBUG && DEBUG_SEARCH) Log.debug("starting iter=" + i);
      DoubleBeam<State2<Z>> t = cur; cur = next; next = t;
      assert next.size() == 0;
      if (DEBUG && DEBUG_COLLAPSE)
        Log.info("cur.size=" + cur.size());
      for (int b = 0; cur.size() > 0; b++) {
        State2<Z> s = cur.pop();
        if (b == 0) // best item in cur
          lastState = s;
        nextStatesB(s, next, all);
      }
      if (DEBUG && DEBUG_COLLAPSE) {
        Log.info("next.size=" + next.size());
        Log.info("collapseRate=" + next.getCollapseRate());
      }
      if (next.size() == 0) {
        if (DEBUG && DEBUG_SEARCH) Log.info("returning because next.size==0");
        break;
      }
    }

    assert lastState != null;
    if (DEBUG && DEBUG_SEARCH) {
      StepScores<?> ss;

      Log.info("lastState:");
      ss = all.peek().getStepScores();
      System.out.println("model=" + ss.getModel());
      System.out.println("loss=" + ss.getLoss());
      lastState.getRoot().show(System.out);

      Log.info("bestState:");
      ss = all.peek().getStepScores();
      System.out.println("model=" + ss.getModel());
      System.out.println("loss=" + ss.getLoss());
      all.peek().getRoot().show(System.out);
    }
    return new Pair<>(lastState, all);
  }

  public Node2 genRootNode(Z info) {
    assert info != null;
    LLTVN eggs = genEggs(null, info);
    return newNode(null, eggs, null, null);
  }

  public State2<Z> genRootState(Z info) {
    assert info != null;
    Node2 root = genRootNode(info);
    return new State2<>(root, info);
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
