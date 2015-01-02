package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.File;
import java.util.Comparator;
import java.util.List;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.rl.Adjoints;
import edu.jhu.hlt.fnparse.rl.FeatureParams;
import edu.jhu.hlt.fnparse.rl.NumCommittedParamsWrapper;
import edu.jhu.hlt.fnparse.rl.Params;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.StateSequence;
import edu.jhu.hlt.fnparse.rl.TransitionFunction;
import edu.jhu.hlt.fnparse.util.Beam;

/**
 * Lets think about how this model compares to the previous one I have, which
 * roughly parameterizes the same set of variables (but just not sequences over
 * how to label them).
 * I could just use the previous model for p(s|t,k) and then just learn a decoder.
 * What would this look like?
 * Features would all look like "reranking" features, consider a lot of state
 * like what roles have been assigned where.
 * 
 * Sort all of the (t,k,s) by probability, indexed by rank as i
 * The set of items chosen so far and the parse so far is represented as state s
 * Each item therefore gets features f(i,s) which add to the original log-prob p(i)
 * Algorithm:
 * 1) sort list by s(i) = p(i) + theta * f(i,s)
 * 2) if s(0) < 0, the parse is done.
 * 2) else take top item and add it to the parse, s += i, goto 1
 * 
 * This is a nice proving-ground for my idea. We already have a model which is
 * decent (has some idea about what a good (t,k,s) is). We should be able to
 * improve it. Probably with just the decoder, but if not, we can start throwing
 * in more realistic scoring features (e.g. embedding stuff) and anneal p(i)
 * towards the uniform distribution.
 */
public class Reranker {
  public static final Logger LOG = Logger.getLogger(Reranker.class);

  // Higher score is better, this puts highest scores at the end of the list
  public static final Comparator<Item> byScore = new Comparator<Item>() {
    @Override
    public int compare(Item o1, Item o2) {
      if (o1.getScore() < o2.getScore())
        return -1;
      if (o1.getScore() > o2.getScore())
        return 1;
      return 0;
    }
  };

  private Params theta;

  public Reranker() {
    this.theta = new FeatureParams();
    //this.theta = new EmbeddingParams(2);
    //this.theta = new CompositeParams(new EmbeddingParams(1), new FeatureParams());
  }

  public ItemProvider getItemProvider() {
    int n = 100;
    File cache = new File("data/rl/items." + n + ".train");
    FrameInstanceProvider fip = FileFrameInstanceProvider.dipanjantrainFIP;
    ItemProvider items;
    if (cache.isFile()) {
      LOG.info("[getItemProvider] getting cached items from " + cache.getPath());
      items = new ItemProvider.Caching(cache, fip);
    } else {
      LOG.info("[getItemProvider] parsing to get items...");
      List<FNParse> parses = DataUtil.iter2list(fip.getParsedSentences());
      parses = parses.subList(0, n);
      ItemProvider.Caching c = new ItemProvider.Caching(new ItemProvider.Slow(parses));
      LOG.info("[getItemProvider] saving cached items to " + cache.getPath());
      try { c.save(cache); }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
      items = c;
    }
    LOG.info("[getItemProvider] done");
    return items;
  }

  /**
   * Solves problem 1: \max_z s(y,z) = \max_z \sum_i s(y,z_i)
   * 
   * This is accomplished through beam search, starting at y and performing
   * "undo"s on potential actions that would lead to a starting point (empty
   * parse or initial decoder state).
   * 
   * @param y
   */
  public StateSequence backward(FNParse y) {
    LOG.info("[backwards] starting " + y.getId());

    // Right now I'm adding this as a search heuristic, but perhaps I should
    // include it always... TODO think this through!
    // TODO when I include actions other than COMMIT, I should change this from
    // numCommitted to numPossible
    Params p = new NumCommittedParamsWrapper(1d, theta);
    //Params p = theta;
    TransitionFunction trans = new TransitionFunction.Simple(y, p);

    Beam<StateSequence> beam = new Beam<>(500);
    State finalState = State.finalState(y);
    StateSequence init = new StateSequence(null, null, finalState, null);
    beam.push(init, 0d);
    while (true) {
      // Choose an item to extend
      StateSequence frontier = beam.pop();
      // For each of its extensions, check if they should be put on the beam.
      int added = 0;
      for (StateSequence ss : trans.previousStates(frontier)) {
        // TODO since all the weights are 0, all the actions are 0, and the
        // ordering on the beam is arbitrary, and we have no hope of finding an
        // initial State unless we initialize the parameters intelligently.

        // If I set value(state) = const - epsilon * state.movesApplied then there
        // should be a slight preference to get to early states, which will at
        // least make this backwards pass succeed.
        
        // But later in learning, when we have non-zero parameters, what will
        // enforce that we actually get from y_0 to y?
        
        // As long as all of the paths from y_0 to y have the same number of
        // steps in them, then we should be able to give arbitrary reward to
        // decreasing movesApplied.
        
        // I can actually put whatever biases in this step that I want, because
        // this is basically the oracle.
        // But the original spirit of this step was to search over all paths to
        // find the least costly one according to the model.
        
        // I can think of movesApplied as a partition of the state space, and
        // we're giving a slight preference over all states in a given equivalence
        // class.
        // You can generalize this to additive features pretty easily to encode
        // a prior on what a good state is.
        // But if we do this, what is the semantics of what we're doing?
        // We could say that s(y,z) is a sum of a prior model and a learned model.
        // and that s.prior(y,z_i) = 1 if z_i.mode == COMMIT

        // None of this guarantees that we're going to be able to find s(y,z)
        // via beam search...
        // Perhaps the way to think of this is that by adding epsilon perturbations
        // to s(y,z), we will do no worse than O(epsilon) from the best solution.
        // But what if s(y,z) is maximal for sequences of actions that do not
        // form a path from y_0 to y?
        // 1) All of our actions are monotonic in |possible|
        // 2) All of our actions are consistent with y

        added++;
        beam.push(ss, ss.getScore());
      }
      LOG.debug("[backwards] added=" + added);
      if (added == 0) {
        // There were no previous states, so frontier must be the empty parse,
        // or initial state.
        return frontier;
      }
    }
  }

  public void train() {
    /*
     * So I never resolved the issue of how to train this thing...
     *
     * I had said that given a particular state, each action gives a next state,
     * which I could come up with a heuristic for its value.
     * 
     * I also forgot the other issue of whether or not I should allow the model
     * the flexibility to do something other than greedy selection (as imagined,
     * you have to take the next item off the stack OR be done with decoding).
     * This means that the re-ranking features have to be very good or else
     * precision will be very low (if forced to get to a reasonable level of
     * recall).
     *
     * I think a simple set of features/weights could lead to a fairly good
     * policy:
     *  weight("I've considered this (t,k) before") = -infinity
     *  weight(nullSpan) = -recallBias
     *
     * => RESOLVED: for now only consider policies which pick from the top.
     * 
     * As for the question of how to train this thing, I can do something like
     * SVM:
     *   sample a bunch of trajectories (probably in a biased/guided manner)
     *   score each one according to the true loss function (F1)
     *   ensure that the scores for the trajectories match margin constraints
     *     based on that loss function.
     * The only variation is the fact that in the SVM setup, you can always make
     * these constraints w.r.t. the gold answer. Can I give a gold trajectory?
     * Not really, I want the model to be free to choose a trajectory, but I
     * specify the gold label.
     * So the modified SVM constraint would be
     * \exists z s.t. s(y,z) > s(y',z') + loss(y,y') \forall y', z'
     * where z is a trajectory and y is a label.
     * This is very similar to a latent variable SVM.
     * 
     * Max over the min or min over the max?
     * violation = \max_y \min_z max(0, s(y,z) - [s(y',z') + loss(y,y')])
     * \min_w ||w||_2^2 + C * violation
     * I'm not sure which is right, but this gives the relation between them.
     * http://en.wikipedia.org/wiki/Max%E2%80%93min_inequality
     *
     * Regardless, I'm looking for a quick and dirty way to train this, so
     * for the oracle side of things, I know what y is, and I'll let the model
     * choose z greedily to maximize s(y,z).
     * For the negative instances (constraints or trajectories to do a
     * perceptron/MIRA style update to), I'll let the model maximize s(y',z') 
     * by just doing greedy decoding.
     *
     * On the oracle side, how to compute \max_z s(y,z)?
     * = \max_z \sum_i s(y,z_i)
     * because I want to have arbitrary features in the decoder, this max can't
     * be solved with dynamic programming, but perhaps something that looks like
     * A* could do it. The constrain in A* is that you never have negative edge
     * costs and that you have an admissible heuristic (which, as long as the
     * first is true, could always be a constant function, making A* => BFS).
     * The "no negative edge weights" could be enforced by making each edge
     * a probability (and since I was considering them as equivalent to
     * log-probs anyway, seems like its not an issue) => NOT REALLY because that
     * would be the max prob path, which is a PROD of edge costs rather than a
     * SUM. A* solves the SUM not PROD, can't have negative edge weights in SUM,
     * can't solve this with A*. => WELL TECHNICALLY... ||w||_2^2 is bounded so
     * the dot product with features is bounded, which means the edge costs are
     * bounded, so you could solve this with A* (using an offset to make sure
     * all costs are positive).
     *
     * => Also that is a mistatement of the problem, really need to include y:
     * \max_{y,z} s(y,z)
     * => Even that is a mistatement, really want
     * \max_{y,y',z,z'} s(y,z)
     *
     * NO^^^^ the y are given!
     * the y' are not given
     * \forall y \max_{y',z,z'} s(y,z) - [s(y',z')+loss(y,y')]
     * There must be a random way to roll out two trajectories z and z' where
     * one knows there its going (y) and the other is listening to the model
     * and maximizing s(y',z') as it goes, perhaps also taking hints from
     * loss(y,y') as to which way it can go to max [s(y',z')+loss(y,y')].
     * These really are two separate problems though.
     *
     * Problem 1): \max_z s(y,z)
     * Like an edit distance problem where you don't know the cost of the edits.
     * Perhaps this is a good approximation algorithm:
     *  fix the edit costs (all of the state-features go away), then find
     *  the cheapest edit sequence
     * This will fail, because the whole point is to have high state-feature
     * costs. Perhaps we could discretize this in time:
     *  Pick some way-points along the edit script to y
     *  For each way-point, the features can be frozen for subsequent problems.
     *  Once way-points are chosen, edit distance problems decompose.
     *  But it seems like you need to choose a lot of states....
     * YOU CAN DO DYNAMIC PROGRAMMING.
     * At each state, the cost going forward does not depend on the path
     * leading up to it, but only the state that you're at!
     * FUCK, this is going to be too large of a state space...
     * at time=1, you would have TK states, at time=2 choose(TK,2)...
     * (all of this is assuming the only action you have is to commit to a span)
     * How to do state pruning?
     * Beam search?
     *  Beam = {state, cost to reach it, path to reach it}.
     * But since this would have worked as a regular dynamic programming problem
     * without pruning states, you can use a different representation:
     *  Beam = {state, cost to reach it, prev state}
     *
     * => WAIT: what if the beam drifts away from getting to y?
     *
     * Bi-directional Beam Search (BDBS):
     * Looking at the diamond in the pyramid picture, it is clear that the
     * widest part of the diamond is way to wide (TK choose (TK/2)).
     * BDBS has the same problem as BS: we don't know if the beam coming from
     * one direction is going to match the beam coming from the other direction.
     * (e.g. all elements on the additive beam could have had a move that
     *  adds item X at time 2, and all of the items in the subtractive beam also
     *  have an add of item X, but at time 10: these beam items are not
     *  compatible under the assumption that you can only add an item once).
     *
     * => The "beams drifting away" business is not really an immediate problem
     * because whenever we consider items to extend beam entries, we will only
     * consider items that keep the beam elements consistent with both y and an
     * empty parse. The problem would be a garden path issue where we put off an
     * add until the very end, once we're almost to y, and by that time the add
     * is very costly (whereas it might not have been early on in the edit
     * script). THIS is just the cost of approximate search.
     *
     *
     *
     * Problem 2): \max_{y',z'} s(y',z') + loss(y,y')
     * If the loss decomposed over z_i, then this would be the same problem as 1
     * but with modified costs. This is probably the best way to solve this.
     * Have an extra feature for FP or FN with an associated cost, which you can
     * tune to make look like F1.
     * Perhaps even transition from coarse estimates to accurate ones as you
     * move through time. At each step you always know if you're making a FP or
     * FN, but as time goes on you get a better estimate of the denominators for
     * precision and recall (ACTUALLY, just precision, recall is known ahead of
     * time).
     * ===> ACTUALLY I'm an idiot, this is a very different problem because
     * there is no y that you are trying to get to. I think you can still use
     * something like beam search with modified costs.
     *
     */

    /*
     * Another what to think about this is in terms of what I hope this model
     * will learn to do.
     * I imagine that this model will learn that overlapping spans are bad
     * (they're not often present in the correct labels), that more confident
     * predictions should be done first and that a stopping criteria should be
     * reached as a tradeoff between what has been included and the confidence
     * of the frontier predictions.
     * But a more cynical way of looking at this is that you're just learning
     * the constraint that spans can't overlap, which could be easily implemented
     * as an ILP. Why not just do that (no additional learning needed)?
     * While it is a lot of variables, you could do a coarse threshold on the
     * top spans for each (t,k).
     * (I think I got on this by thinking about Tautonova's dynamic programming
     * algorithms for doing this where the spans are constituents in a tree).
     * (I also think that most of what I would be proposing is covered in that
     * Punakanok et al. SRL paper).
     * => Bottom line: what I'm proposing is more general with approximate
     * inference.
     */

    /*
     * Lets say we initialize the model to reasonable parameters, so it
     * basically knows how to mimic the old decoder. Now we want to learn some
     * type of regularity like r_i of frame X is often the same as r_j of frame
     * Y. How would that 1) feature be represented and 2) weight learned to be
     * non-zero.
     * 1) feature: (frame,role) of a previously committed to and matching item
     * 2) in some roll-outs for y (gold label), r_j will be tagged first, then
     *    the feature in 1 will help boost s(y,z).
     */
  }

  public static void main(String[] args) {
    Logger.getLogger(ConstituencyTreeFactor.class).setLevel(Level.FATAL);
    Reranker r = new Reranker();
    ItemProvider ip = r.getItemProvider();
//    for (int i = 0; i < ip.size(); i++) {
//      for (Item it : ip.items(i))
//        System.out.println(ip.label(i).getId() + "\t" + it);
//    }
    int i = 0;
    StateSequence ss = r.backward(ip.label(1));
    while (ss != null) {
      Adjoints a = ss.getAdjoints();
      if (a == null)
        LOG.info(String.format("[main] i=%d null", i));
      else
        LOG.info(String.format("[main] i=%d %s %.3f", i, a.getAction(), a.getScore()));
      ss = ss.neighbor();
      i++;
    }
  }
}
