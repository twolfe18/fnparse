package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.File;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.rl.State.StateWithBackPointer;

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

  private RerankingFeatures features;
  private double[] theta;

  public FNParse decode(FNTagging frames, List<Item> items) {
    DecoderState s = new DecoderState(frames);
    while (items.size() > 0) {
      Collections.sort(items, byScore);
      Item best = items.remove(items.size() - 1);
      if (best.getScore() > 0) {
        // keep this item
        s.addItem(best);
        rescore(items, s);
      } else {
        // done
        break;
      }
    }
    return s.getParse();
  }

  private void rescore(List<Item> items, DecoderState s) {
    FeatureVector fv = new FeatureVector();
    for (Item i : items) {
      features.featurize(fv, s, i);
      i.setFeatureScore(fv.dot(theta));
    }
  }
  
  public StateWithBackPointer problem1(FNParse y) {

    throw new RuntimeException("implement me");
  }

  public void train() {
    File cache = new File("/tmp/items");
    ItemProvider items;
    if (cache.isFile()) {
      items = new ItemProvider.Caching(cache);
    } else {
      List<FNParse> parses = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
      ItemProvider.Caching c = new ItemProvider.Caching(new ItemProvider.Slow(parses));
      try { c.save(cache); }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
      items = c;
    }

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

}
