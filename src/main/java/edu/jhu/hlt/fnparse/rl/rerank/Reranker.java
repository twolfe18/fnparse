package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;

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
     * violation = \max_y \min_z s(y,z) - [s(y',z') + loss(y,y')]
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
     */
  }

}
