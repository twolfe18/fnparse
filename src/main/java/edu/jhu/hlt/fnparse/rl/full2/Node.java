package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.fnparse.rl.full.Beam;

import java.math.BigInteger;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Node {

  enum Mode {
    // Name refers to whether you can tell if an index is set to 1/0 by only matching value:type's along the path to root.
    YES,          // All indices matching this node are set to 1
    NO,           // All indices matching this node are set to 0
    MAYBE,        // Union over children are set to 1, rest are set to 0
  }

  private int type;           // e.g. an int representing "target" or "role".
  private int value;          // Special value of 0 is used to indicate "stop", e.g. for type=span value=0 could be Span.nullSpan. When next() is called at the parent, this special value can be checked indicating that there are no actions to add. Note that this must be coordinated with other fields like closed.
  private boolean frozen;
  private Node rightSib;      // this.parent.type/value == rightSib.parent.type/value
  private Node leftChild;

  // Aggregates
  private BigInteger memberSignature;   // Product of primes for all final elements. Note: this should not count prefixes like (t,f,k,?), even though they are counted in loss.
  private List<Aggregator<?>> aggregates;

  interface Aggregator<T> {
    public T zero();
    public T aggregate(Path node, T prevAggregate);
  }

  /**
   * Aggregators.
   * For dynamic features, you have access to aggregators, which are sets of value:type pairs.
   * These aggregators let you know things about the state of the tree above you without traversing it.
   * For example, if this node is:
   *   ARG2:role <- throw.02.v:frame <- [3,4):target
   * Then the aggregators for the parent node (frame) tell you, for instance, how many args have been realized already.
   * The frame node would have an aggregator for every possible sub-set of the types remaining after removing the types on the path from the frame node to root.
   * That's a lot of aggregators.
   * Perhaps we should specify which aggregators should be kept at various type paths.
   * I already said that this is how we would do next() definitions: you define listeners on sets of types which are functions from values of those types to a list of next states.
   * 1) How do you specify what aggregators to keep?
   * 2) How do you do a general update (i.e. a function of type:value's)
   *
   * Note: An aggregator only needs to store the elements of nodes below it in the tree,
   * the values above can be represented as an implicit cartesian product.
   * e.g. if we are at a type=k node, we should have an aggregator over {s}, which we can implicitly get up to sets over {k,s}, {f,k,s}, {t,k,s}, and {t,f,k,s} by looking at the values above this node.
   * All of these "bigger" aggregates will have the same cardinality, but bigger elements.
   * They can be implemented as views on the base set: {s}.
   * If we were at a lower node, say type=f, then we would need the base set to be {k,s}.
   * And perhaps sum_s {k}, sum_k {s}?
   * If we wanted features that looked at count({t,f,k}) then I guess we would need those roll ups...
   */

  public static class Path {
    public final int types;
    public final int values;  // <0 means any value
    public final Path next;
    public Path(int types, int values, Path next) {
      this.types = types;
      this.values = values;
      this.next = next;
    }
  }

  interface ActionType {
    /** A set of path (types?) indicating when next() should be called */
    Set<Path> prerequisits();
    /** path[0] is root, and path is gauranteed to match one prerequisit, add next states to beam */
    void next(Beam beam, List<Node> path);
  }

  public static class GenerateTargets implements ActionType {
    private Set<Path> required;
    public GenerateTargets() {
      this.required = new HashSet<>();
      this.required.add(new Path(-1, -1, null));    // Root type
    }
    @Override
    public Set<Path> prerequisits() {
      return required;
    }
    @Override
    public void next(Beam beam, List<Node> rootToNode) {
      // How to check that we haven't double generated targets?
      // We need an aggregator.
      // What nodes are we modifying? => A root node.
      assert rootToNode.size() == 1;
      Node root = rootToNode.get(0);

      /*
      Example:
      (State NIL) -> (
        (State (t 0-1))
        (State (t 1-2))
        ...
      )

      Now what do we do if we have (State (t 2-3))
      How do we add more targets?
      The State node maintains a an index of targets used so far.

      Does this mean that I can't follow this with any children of State other than type=Target?
      Lets say that I wanted to add a Arg child: (State (t 2-3) (s 4-6))
      I should allow this under the constraint that z_{t',f,k,s}=0 forall f,k,t'!=2-3 (where s=4-6)
      This seems like too strict of a penalty which will lead to fixed decoding orders.
      How is the model supposed to know that (s 4-6), which looks really good, does/doesn't belong to (t 2-3), and thus should/shouldn't be added to root?
        We can have features for this...
        But it seems like the essential features will need to look at (t 2-3) and (s 4-6), which is *very* dynamic :)

      Fixed loop order actually looks like a smart idea now :)

      I think I'm not seeing things properly: if (t ?) is chosen at the top-level, the any non-t slice of the tensor (node) is going to intersect with it.

      Ah, but a (t 9-10 k 3-5) node would not intersect (t 2-3)!
      And if we chose (t 9-10 k 3-5) first, it would not intersect with (k 0-3), which could reflect "this is an arg, but I don't know to which predicate"
      But this is talking as if these are all children of root, it seem like choosing just predicates as children of root is fair.
      But you really can't have (t ?) and (k ?) as both children of root, or any node for that matter.
      If you made two t nodes below root, you could weigh a two actions which add them as children of those two nodes respectively.

      I think I'm missing something deep about how to represent this problem.
      It seems very natural to choose t's first, but somewhere in the middle choose some subset of s, then decide where to attach them
      Crucially: decide on an s, THEN attach it.
      If t was chosen first, then in the simple model the only way to add an arg is independently for t1 and t2.
      I did say that I want to have a separate index for s=4-6 which I get some credit for predicting correctly, irrespective of attachment (which gets it's own reward).

      This sounds like what I really need is some type of parsing algorithm.
      We have "built constituents" of various kinds, e.g. (t,), (t,f), (s,), (t,f,s), etc
      I was worried that this isn't append-only, but maybe it sort of is.
      One way to do it is where you have an append-only list of constituents for each type.
      But maybe we should stick closer to parsing and index our chart by values.
      The thing I don't like about parsing algs is that you seem to lose the centrally-controlled transition system which I believe leads to efficient computation.
      I think I can maintain this with a parsing alg though.
      Cute title: "No grammar, lots of features"

      I'm starting to think that I will run into the same problems.
      If we're going to have a setup where we can combine items from different kinds, e.g. (s,) and (t,f)
        Note: possibly with dynamic features where the (t,f) really has a whole bunch more details hanging off of it, like previously decided args
      Then how do we handle constituents which can conflict?
      - You can always re-use an item from a smaller kind when building a bigger kind, e.g. if you decided a particular (t,f) is good, you can attach many k to it
      - if you have oneXperY constraints, then you must do an argmax when you attach an X to an existing Y
      - Can have STOP actions which close a list of a particular kind (these can be dynamically scored fairly easily)
      - Computational efficiency? Do this agenda based: have push-time features which compute dynamic features before adding to the Q
        Can we guarantee that an action remains valid indefinitely?
        Not if we have STOP actions...
        Remember the benefits of having a partially formed hyp: it is easy to check constraints and generate global features!
      So this is sort of what I was doing with the tree of partial constituents/slices of the tensor
        It was a "something I have already + X" where X is looped over in next()
        Nice properties:
        - "something I have already" can carry with it rich aggregators for global features
        - Simple model of conflicts: nothing ever is allowed to overlap => fixed loop order
          (You clearly can't have any notion of a fixed loop order with agenda based parsing)
        - Some of the benefits of "don't stupidly loop over everything" due to early bail out based on whats currently on the beam
      => Continued at [1]

      Whats the deal with bail out early: I had imagined it as a within-beam-step method, but can't we extend it to be inter-beam-step?
      As in, say we're going through succ states for the first thing on the beam.
        Maybe at some point we notice that all the succ states have score < score(second state on the prev beam) + reasonable bound
        Can we reach the conclusion that this state is a lost cause/dead end?
        => Yes, and this actually falls out of the way I currently wrote the code
          The beam is shared across all States' calls to next()
          So good succ states from the first state on the beam can force prunes of possibly entire states on the rest of the beam.
      These are black-box benefits which are difficult to weigh against the merits of different approaches...

      [1] Perhaps I'm being too worried about conflicts.
      Remember: the only problem with having two possible "maybe"s out there is that it doesn't let you easily enforce oneXperY constraints
      Perhaps I should do the following:
      1) Describe a transition system where there are not oneXperY constraints
      2) Figure out what the implications on the transition system are if you would like to introduce a oneXperY constraint

      Towards (1): Immediate difficulty: computing loss
      What is the actual assumption:
      "matchValuesToRoot && !specifiedOneInSubtree => 0"
      See Yes/No/Maybe comment at the top of this file.

      # Attempts, bad formalisms
      Lemma: Maybe nodes must have Maybe parents
      Lemma: Yes nodes must have Maybe parents
      Lemma: No nodes are only the left child of a Maybe node
        (Other way to do this to say there is no No, there is only [Maybe, closed=True]
        (Note: Yes => closed=True)
        (Perhaps we need to remove closed and have Yes, Done (formerly Maybe,closed=True), and Maybe (which has closed=False)
      Lemma: Yes nodes must not have Yes parents (Done or Maybe is fine)
      Lemma: Maybe nodes must have Maybe parents
      Lemma: Done nodes must have Maybe parents
      Put another way, every path follows the regex: Maybe* Done? Yes?
      ...seems wrong on account of the fact that Yes is Done...

      # Use this as the formalism
      1) A Node/Path matches some sub-set of all indices.
      2) A Node/Path has a yes:boolean flag indicating if all matching indices are set to 1.
      3) A Node/Path has a frozen:boolean flag indicating if a) children can be added and b) if all matching indices that do not match a Node in the sub-tree are set to 0.

      Now it is clear why overlapping Node/Paths are a problem: a Node/Path has no right to claim (3b) if there could be another Node/Path which wants some index to be 1 by nature of (2)
      One costly solution I had considered is that Node/Paths which may conflict could be forced to not conflict by checking the sub-nodes of another.
      But this doesn't compose, could later add a node which re-violates the checked assumptions.
      => Need to check down to a Done node!
        Lets say we have a node p = (t,f) and we want to make a new node q = (s,)
        If p is !frozen, then even if we required q to be more specific: q = (t',f',s) where x' means x'!=x
        We could add a child (t,f,s) to p and set it to done, which would set indices to 0 which match q, which is still open, and could have some sub-set set to 1
        But if p where frozen, then we know that there could not be any children which would be added to p which could conflict with our refined q.
        What do we need to refine q to?
          some set of indices which doesn't overlap in any way with p (with 1s or 0s)
          we can refine q to be some subset of the complement of p by ensuring that at least one value:type on path(p) can prove that we're in the complement of p
          so if p = (t,f), we could refine q to be (s,t') or (s,f')
          I think we can't refine q based on any value of k because that cannot prove on its own that it will not match p.

      frozen Nodes are append-only!
      => Problem: You don't need to guarantee that you don't conflict with any given frozen node (easy to do, described above)
        You need to prove that you don't conflict with *ANY* other node.
        => choose a different value to a sibling: you are provably covering a different set of indices
        => if a sibling is not done, you must prove that you cover a different subspace than *one* of the children
          NOTE: Your neices/newphews are assumed to be disjoint by an inductive step

      This can't possibly work.
      Let say that I had some type=T node under root and I can prove that I can add a type=K node by specifying some other T value
      So under root I have a T subtree and a (K,T) subtree.
      If I want to go add another T child under root, then I have to check the (K,T) subtree to ensure that my T value doesn't match that?!
      This can't possibly be efficient.

      => You cannot be dynamic to the degree where you will mix loop orders (i.e. type @ depth == constant) on the fly

       */

      /*
      Simplification time!

      1) The loop order is fixed (type @ depth)
      2) Every node implements next(), a given node can only add nodes of the same type as its siblings due to (1)
      3) Breaking out of next() is done by bounding the score generated by any node in the sub-tree + score(path)
         Nodes can cache their upper bounds, only need to look at the upper bound of their first child (since they are all of the same type and bounds don't care about values, only types)
      4) Calls to next are given a Path/List<Node> which may be needed for surgery/constructing the tree.
      5) Loop order is fixed and next() methods are registered by creating a listener keyed on List<Type> for a given path; perhaps pack these next() functions into a LL (or trie in the general case which we're not dealing with)
      6) Every node has an aggregate of the committed values for every subset of the types on the way to a leaf
         e.g. a (t,f) node has a set of {k}, {s}, and {(k,s)}
         the parent type terms are implicit, do not need to be represented in the set
         all sub-sets is strong, but the worst case is 2^4 = 16
           could restrict to only type-string-prefixes, e.g. (t,f) nodes only get {k} and {(k,s)} aggregators if the loop order is (t,f,k,s)
         these aggregators are sets, merging them (I'm assuming) is O(log(n)) where n is the number of items in the set, which is typically small except for maybe the root node
      7) The "members" index is the truest O(1) aggregator, efficiently represents all indices
      8) The LL of incomplete/unfrozen nodes is kept as an intrusive LL
         I considered simply removing the frozen nodes from the tree, and using the aggregators to prevent generating new nodes which have a value which duplicates a previously frozen node
         BUT, I don't see any benefit to this over the jump LL of unfrozen nodes
         The only trick needed is that the unfrozen LL may need to be lazily updated to be correct:
           When a node is frozen, a special indicator first child is prepended
           Aside from the surgery up the tree that needs to occur, the node that pointed to this node in the unfrozen list needs to be updated
           But this is impossible because we don't have backpointers.
           Thus, the next time we traverse the unfrozen LL which this original node was a member of, we need to update the frozen pointer for the node that points to this node.
      9) def frozen(self): return self.leftChild.type == -2 # type of root is -1
       */

    }
  }
}
