package edu.jhu.hlt.fnparse.rl.full2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.rl.full.Beam;
import edu.jhu.hlt.fnparse.rl.full.Beam.DoubleBeam;
import edu.jhu.hlt.fnparse.rl.full.Beam.Mode;
import edu.jhu.hlt.fnparse.rl.full.HowToSearch;
import edu.jhu.hlt.fnparse.rl.full.MaxLoss;
import edu.jhu.hlt.fnparse.rl.full.StepScores;
import edu.jhu.hlt.fnparse.util.HasRandom;
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

  public static boolean DEBUG = false;
  public static boolean DEBUG_LOSS = true;

  /*
   * Am I sure this shouldn't just all go into Node2?
   * Note that the SUM does not live in Node2!
   * Ah, this is the tricky bit.
   * You could have Node2->MaxLoss and not have SUM in the LL.
   * When you do hatch/squash you have to construct a new Node2 anyway...
   * Ah crap (nice?), does this mean that I can scrap my "subclass LL" way of doing things?
   * Node2 just has a field which is a monad composed of all the aggregates we need to keep (MaxLoss being one of them)
   * Lets do a stress test: subclass Node2 with Node2Loss and override newNode...
   *
   * => I believe that I actually need to both:
   * 1) have a MaxLoss field in Node2 since (fp,fn) come from pruned and determined comes from (pruned,children)
   * 2) subclass LL<Node2Loss> in order to get cheap access to sum:MaxLoss for hatch/squash (e.g. computing determined in 1)
   *
   * Lets make sure I understand this:
   * - its clear how to aggregate MaxLoss from everything under a node (supping fn,fp and determined)
   * - how are MaxLoss allocated upon hatch?
   *   there will be a new child, which will have a MaxLoss[fn=fp=0] from eggs
   *   genEggs needs to know about how many sub-nodes there are in each egg
   */



  /**
   * See {@link TFKS}, maybe the only reason not to use LL<TV>.init
   */
  abstract TFKS consPrefix(TVN car, TFKS cdr);

  abstract LL<TVN> consEggs(TVN car, LL<TVN> cdr);
  abstract LLTVN consPruned(TVN car, LLTVN cdr);

  /**
   * Create the appropriate list which stores aggregates for global features
   */
  abstract LLML<Node2> consChild(Node2 car, LLML<Node2> cdr);

  /**
   * Construct a node. Needed to be a method so that if you want to instantiate
   * a sub-class of Node2, you can do that behind this layer of indirection.
   */
  abstract Node2 newNode(TFKS prefix, LL<TVN> eggs, LLTVN pruned, LLML<Node2> children);


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
  public abstract Y decode(Iterable<LL<TVN>> z, Z info);


  /**
   * META: Specifies the loop order (e.g. [T,F,K,S]).
   * Create a list of possible next values to expand given a node at prefix.
   * For now I'm assuming the returned list is type-homongenous.
   */
  abstract LL<TVN> genEggs(TFKS prefix, Z z);

  // NOTE: The new goal is that MaxLoss is snuck in via TVN from genEggs
//  abstract int subtreeSize(LL<TV> prefix);

  /**
   * Global/dynamic features.
   *
   * NOTE: Pay attention to the corresponding methods of hatch and squash;
   * they both involve taking the first egg (don't just look at prefix!)
   *
   * NOTE: static features should be wrapped in this.
   */
  abstract Adjoints featsHatch(Node2 n, Z z);
  abstract Adjoints featsSquash(Node2 n, Z z);


  /* NON-ABSTRACT STUFF *******************************************************/

  public void collectChildrensSpines(Node2 node, List<LL<TVN>> addTo) {
    addTo.add(node.prefix);
    for (LL<Node2> cur = node.children; cur != null; cur = cur.cdr())
      collectChildrensSpines(cur.car(), addTo);
  }
  public Y decode(State2<Z> root) {
    if (DEBUG) {
      Log.info("running on:");
      root.getRoot().show(System.out);
    }
    List<LL<TVN>> childrensSpines = new ArrayList<>();
    collectChildrensSpines(root.getRoot(), childrensSpines);
    if (DEBUG)
      Log.info("numSpines=" + childrensSpines.size());
    return decode(childrensSpines, root.getStepScores().getInfo());
  }

  // Loss should now be handled through genEggs and cons*
//  public HammingLoss lossHatch(Node2 n, Z info) {
//    Counts<HashableIntArray> counts = info.getCounts();
//    TVN e = n.eggs.car();
//    LL<TVN> childPrefix = consPrefix(e, n.prefix);
//    HashableIntArray x = prefixValues2ar(childPrefix);
//    int c = counts.getCount(x);
//    if (c == 0) {
//      if (DEBUG) Log.info(" FP " + x + " c=" + c + " childPrefix=" + childPrefix);
//      return HammingLoss.FP;
//    } else {
//      if (DEBUG) Log.info(" TP " + x + " c=" + c + " childPrefix=" + childPrefix);
//      return HammingLoss.TP;
//    }
//  }
//
//  public HammingLoss lossSquash(Node2 n, Z info) {
//    Counts<HashableIntArray> counts = info.getCounts();
//    TVN e = n.eggs.car();
//    LL<TVN> childPrefix = consPrefix(e, n.prefix);
//    HashableIntArray x = prefixValues2ar(childPrefix);
//    int c = counts.getCount(x);
//    if (c == 0) {
//      if (DEBUG) Log.info("TN " + x + " c=" + c + " childPrefix=" + childPrefix);
//      return HammingLoss.TN;
//    } else {
//      if (DEBUG) Log.info("FN " + x + " c=" + c + " childPrefix=" + childPrefix);
////      return HammingLoss.FN;
//      return new HammingLoss(0, 0, c, 0);
//    }
//  }

  /* COHERENCE RULES ********************************************************/
  // The goal is to build these basic operations from the operations defined
  // above. Thus, the implementation of the mehtods above must make sense in
  // light of the implementation below.

  /** Returns a copy of n with the child formed by hatching the next egg */
  public Node2 hatch(Node2 wife, Z z) {
    TFKS newPrefix = consPrefix(wife.eggs.car(), wife.prefix);
    LL<TVN> newEggs = genEggs(newPrefix, z);
    Node2 hatched = newNode(newPrefix, newEggs, null, null);
    LLML<Node2> children = consChild(hatched, wife.children);
    Node2 mother = newNode(wife.prefix, wife.eggs.cdr(), wife.pruned, children);
    return mother;
  }

  /** Returns a copy of n with the next egg moved to pruned */
  public Node2 squash(Node2 n) {
    /*
     * How would I do this to efficiently preserve MaxLoss?
     * eggs = LL<TVML>
     * pruned = LL<TVML>
     * children = LL<Node2Loss>
     *
     * I'm missing some function like:
     * squashEgg :: egg:TVML[fp=fn=0] -> info:Counts -> pruned:MaxLoss[fn,fp populated]
     *
     * def squashLoss(n):
     *   MaxLoss eggLoss = squashEgg(n.egg)
     *   LL<TVML> newPruned = consPruned(new TVML(n.egg, eggLoss), n.pruned)
     *   LL<TV> newEggs = n.eggs.cdr()
     *   return newNode(n.prefix, newEggs, newPruned, n.children)
     *
     * => MaxLoss only needs to live in pruned:LL<TVML>
     *  NOT QUITE!
     *  MaxLoss has both numDetermined (which can come from children) and fn,fp (which comes from pruned)
     *  This seems to indicate that MaxLoss needs to live in Node2 (above pruned and children) to be coherent
     *  Node2.MaxLoss.fn,fp comes from pruned:LLTVML
     *  Node2.MaxLoss.determined comes from pruned:LLTVML + children:LLNode2Loss
     */
    LLTVN newPruned = consPruned(n.eggs.car(), n.pruned);
    return newNode(n.prefix, n.eggs.cdr(), newPruned, n.children);
  }

  // NOTE: This has to be in the module due to the need for consChild
  public Node2 replaceChild(Node2 parent, Node2 searchChild, Node2 replaceChild) {
    // Search and store prefix
    ArrayDeque<Node2> stack = new ArrayDeque<>();
    LLML<Node2> newChildren = parent.children;
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
    LLML<Node2> newChildren = parent.children;
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
    Node2 newParent = newNode(parent.prefix, parent.eggs, parent.pruned, newChildren);
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
////    LL<Node2> spine = consChild(cur.getNode(), null);
//    LL<Node2> spine = new LL<>(cur.getRoot(), null);
//    nextStates(cur, spine, composite);
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
      Random r = info.getRandom();

      // TODO check oneXperY with egg.getType() and wife.getType(), and if
      // true, after `mother = hatch(wife)`
      // do `while (mother.eggs) mother = squash(mother)`
      // (and make sure you keep track of loss too).
      // This will mean that the FNs you would normally have to count in
      // hatch will be counted naturally and easily by squash. If this is too
      // slow then optimize, but this is so clean.
      Node2 mother = hatch(wife, info);
      Adjoints hatchFeats = Adjoints.sum(featsHatch(wife, info), prevScores.getModel());
      double hatchRand = r.nextGaussian() + prevScores.getRand();
      MaxLoss hatchLoss = mother.getLoss();
      StepScores<Z> hatchScore = new StepScores<>(info, hatchFeats, hatchLoss, hatchRand);

      if (DEBUG_LOSS && DEBUG) {
        System.out.println("[hatch] mother.loss=" + mother.getLoss());
      }


      Node2 wife2 = squash(wife);
      Adjoints squashFeats = featsSquash(wife, info);
      double squashRand = r.nextGaussian();
      MaxLoss squashLoss = wife2.getLoss();
      StepScores<Z> squashScore = new StepScores<>(info, squashFeats, squashLoss, squashRand);
      /* These are features for just this action.
       * If I want scores for the entire state, then I need to take them as
       * an argument... maybe steal them from root:State2
       * StepScores = {
       *   MaxLoss which can be read off from root:Node2->getLoss
       *   model features which are root:State2->StepScores->modelScore
       *   rand which is root:State2.rand + newRand()
       * }
       * The only hard one is model score, which requires that I pass features computed
       * somewhere in the tree up to the root.
       *
       * Why not just force Node2 to have StepScores?
       * This would make everything run on replaceNode.
       * For a node on a length D path from root, this would require D new StepScores
       * The old way, we could just cons a new Adjoints onto a right-branching tree of Adjoints.Sum
       * This is not the end of the world... its not clear that you can remove this O(D) factor anyway, you are likely just forcing the complexity onto the call stack
       * What about bounding score of actions? Will I need Node2 to have StepScores for that?
       * I need StepScores to bound the score of an action (since the coefs may look at model score)
       * I'm not sure... but it seems like it would be helpful, or at least make the implementation simpler.
       * => Make change provided implementation is clear:
       *   consChild: adds all terms in StepScores, previously it just added MaxLoss
       *     does this mean that every Node2 has a rand? before it was action...
       *     every Node2 is created by a hatch action...
       *     only non-trivial implication is that every TVN in pruned must also have a rand.
       *     => TVN now goes to TVSS = (TV,StepScores)
       *   consPrefix: should just be LL<TVN> (I remember needing numPossible and/or goldMatching in prefix.car())
       *   consPruned: needs to sum up (rand:double, loss:MaxLoss, model:Adjoints)
       *   consEggs: eggs have (rand:double, modelStatic:Adjoints) but loss:MaxLoss is ambiguous because all you know is goldMatching, its not clear if they'll end up as FP or FN because they could go in pruned or children respectively
       * => Static scores can be stored in eggs!
       */

      if (DEBUG_LOSS && DEBUG) {
        System.out.println("[squash] wife2.loss=" + wife2.getLoss());
      }


      assert mother.prefix == wife2.prefix;
      assert wife.getLoss().numPossible == mother.getLoss().numPossible
          : "possible should not change when you have a kid"
            + " wife.poss=" + wife.getLoss().numPossible
            + " mother.poss=" + mother.getLoss().numPossible;
      assert mother.getLoss().numPossible == wife2.getLoss().numPossible
          : "having vs not having shouldn't change possible";



      if (spine.cdr() == null) {  // mother is root!
        addTo.offer(new State2<Z>(mother, hatchScore, "hatch"));
        addTo.offer(new State2<Z>(wife2, squashScore, "squash"));
      } else {
        LL<Node2> momInLaw = spine.cdr();
        Node2 rootHatch = replaceNode(momInLaw, wife, mother);
        Node2 rootSquash = replaceNode(momInLaw, wife, wife2);
        addTo.offer(new State2<Z>(rootHatch, hatchScore, "hatch"));
        addTo.offer(new State2<Z>(rootSquash, squashScore, "squash"));
      }
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
    if (DEBUG) {
      Log.info("starting inference from state:");
//      assert s0.getStepScores().forwards() == 0;
////      System.out.println(s0.getNode());
//      s0.getRoot().show(System.out);
    }
    Z inf = s0.getStepScores().getInfo();

    // Objective: s(z) + max_{y \in Proj(z)} loss(y)
    // [where s(z) may contain random scores]
    DoubleBeam<State2<Z>> all = new DoubleBeam<>(inf.numConstraints(), Beam.Mode.CONSTRAINT_OBJ);
//    DoubleBeam<State2<Z>> all = new DoubleBeam<>(inf.numConstraints(), Beam.Mode.BEAM_SEARCH_OBJ);

    // Objective: search objective, that is,
    // coef:      accumLoss    accumModel      accumRand
    // oracle:    -1             0              0
    // mv:        +1            +1              0
    Beam.Mode bsearchObj = Beam.Mode.BEAM_SEARCH_OBJ;
    DoubleBeam<State2<Z>> cur = new DoubleBeam<>(inf.beamSize(), bsearchObj);
    DoubleBeam<State2<Z>> next = new DoubleBeam<>(inf.beamSize(), bsearchObj);

    State2<Z> lastState = null;
    //      push(next, all, s0);
    next.offer(s0); all.offer(s0);
    for (int i = 0; true; i++) {
      if (DEBUG) Log.debug("starting iter=" + i);
      DoubleBeam<State2<Z>> t = cur; cur = next; next = t;
      assert next.size() == 0;
      for (int b = 0; cur.size() > 0; b++) {
        State2<Z> s = cur.pop();
        if (b == 0) // best item in cur
          lastState = s;
        //          s.next(next, all);
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
    LL<TVN> eggs = genEggs(null, info);
    return newNode(null, eggs, null, null);
  }

  public State2<Z> genRootState(Z info) {
    Node2 root = genRootNode(info);
    StepScores<Z> s = new StepScores<>(info, Adjoints.Constant.ZERO, root.getLoss(), 0);
    return new State2<>(root, s);
  }

  /**
   * Convenient conversion method, preserves order like:
   *   (a -> b -> c) goes to [a, b, c]
   */
  public static HashableIntArray prefixValues2ar(LL<TVN> x) {
    int len = 0;
    for (LL<TVN> cur = x; cur != null; cur = cur.cdr())
      len++;
    int[] ar = new int[len];
    int i = 0;
    for (LL<TVN> cur = x; cur != null; cur = cur.cdr())
      ar[i++] = cur.car().getValue();
    return new HashableIntArray(ar);
  }
}
