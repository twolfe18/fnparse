package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LabelIndex;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.stanford.nlp.util.RuntimeInterruptedException;

import java.math.BigInteger;
import java.util.*;

public class Node {

  public static final int TYPE_ROOT = -1;
  public static final int TYPE_STOP = -2;
  public static final int TYPE_TARGET = 0;  // aka t
  public static final int TYPE_FRAME = 1;   // aka f
  public static final int TYPE_ROLE = 2;    // aka k
  public static final int TYPE_ARG = 3;     // aka s

  public static class Role {
    int k, q;
  }

  public static interface Bijection<A,B> {
    public B enc(A a);
    public A dec(B b);
  }
  public static Bijection<Span, Integer> spanBij;
  public static Bijection<Frame, Integer> frameBij;
  public static Bijection<Role, Integer> roleBij;


  private int type;           // e.g. an int representing "target" or "role".
  private int value;          // Special value of 0 is used to indicate "stop", e.g. for type=span value=0 could be Span.nullSpan. When next() is called at the parent, this special value can be checked indicating that there are no actions to add. Note that this must be coordinated with other fields like closed.
  private Node rightSib;      // this.parent.type/value == rightSib.parent.type/value
  private Node leftChild;

  // Aggregates
  private BigInteger memberSignature;   // Product of primes for all final elements. Note: this should not count prefixes like (t,f,k,?), even though they are counted in loss.

  // These are co-indexed
  private Aggregator[] aggregators;   // can be copied among any siblings
  private Object[] aggregates;        // must be allocated and updated with every cons


  public static interface LoopOrder {
    public Bijection<Object, Integer> getBijection(int type);
    public int numLoops();
  }


  public Node(int type, int value) {
    this.type = type;
    this.value = value;
  }

  /** Sets returned nodes left child to null, you should set it! */
  public Node prepend(List<Node> path2node, int type, int value) {
    Node n = new Node(type, value);
    n.leftChild = null;
    n.rightSib = this;
    n.type = type;
    n.value = value;

    // Update aggregates
    int N = aggregates.length;
    n.aggregators = aggregators;
    n.aggregates = new Object[N];
    for (int i = 0; i < N; i++)
      n.aggregates[i] = aggregators[i].aggregate(path2node, aggregates[i]);

    // Update signature
    // How to keep track of signatures?
    // At a minimum, a node needs to know when its a leaf so that it can look a prime up
    // I suppose if we're planning on passing in a List<Node> then we could measure the length of this (against the loopOrder.length()?)


    return n;
  }

  public static Node makeRoot() {
    return new Node(TYPE_ROOT, 0);
  }

  public boolean isRoot() {
    return type == TYPE_ROOT;
  }

  public boolean isFrozen() {
    return leftChild != null && leftChild.type == TYPE_STOP;
  }


  // TODO Do this untyped? T = Object?
  interface Aggregator<T> {
    public T zero();
    public T aggregate(List<Node> path2node, T prevAggregate);
  }


  interface ActionType {
    List<Node> firesFor();                // A set of path (types?) indicating when next() should be called
    List<Node> next(List<Node> toPerq);   // returns a list of new trees/roots
  }


  /** Generates targets for one Sentence */
  public static class GenerateTargets implements ActionType {

    private Sentence x;
    private LabelIndex y; // may be null
    private List<Span> possible;

  // TODO If I can keep this aggregate as a sorted list or heap of Spans, then I can do linear time merging with List<Span> possible

    // this list of aggregators will be set in every new Node created on this level
    private List<Aggregator<LazySet<Span>>> targetAgg = Arrays.asList(new Aggregator<LazySet<Span>>() {
      public LazySet<Span> zero() { return new LazySet<Span>(); }
      public LazySet<Span> aggregate(List<Node> path2node, LazySet<Span> prevAggregate) {
        assert path2node.size() == 2; // root -> t
        Node tNode = path2node.get(1);
        int tRepresentation = tNode.value;
        Span t = spanBij.dec(tRepresentation);
        return prevAggregate.add(t);
      }
    });

    @Override
    public List<Node> firesFor() {
      return Arrays.asList(makeRoot());
    }

    @Override
    public List<Node> next(List<Node> root2node) {
      assert root2node.size() == 1;   // just root
      Node root = root2node.get(0);

      /*
      First problem is to tackle surgery!
      I have done it assuming you know the shape of the tree, but this doesn't assume that, you're just given a Path
       */
      List<Node> nextStates = new ArrayList<>();

//      LazySet<Span> usedSoFar = root.get(something)
//
//      for (Span t : possible) {
//        if (
//      }

      // This is actually baby cakes: every new target is just prepending to the current state
      // One trick required: need to read the set of target already used from ROOT

      return nextStates;
    }
  }


  /**
   * I need aggregators which track spans, which is in general not possible in sublinear time if I'm not mistaken.
   * This class is a set aggregator which lazily builds a set. It uses linear search for the first K items, then
   * O(1) lookup for the rest.
   * This is an immutable class.
   */
  public static class LazySet<T> {
    // Either (item,next) are non-null or items is non-null
    private T item;
    private Set<T> items;
    private LazySet<T> next;
    private int size;

    // TODO I could keep a bloom filter for the LL part of this data structure
    // If the structure is 64 bits, then you can copy it in O(1) machine time :)

    public LazySet() {
      item = null;
      items = Collections.emptySet();
      next = null;
      size = 0;
    }

    public LazySet<T> add(T t) {
      // TODO
      throw new RuntimeException("implement me");
    }

    public boolean contains(T t) {
      // TODO
      throw new RuntimeException("implement me");
    }
  }

}




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

