package edu.jhu.hlt.fnparse.rl.params;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.rerank.Item;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;

/**
 * Stores the prior score for an item in a HashMap keyed on (t,k,span)
 * 
 * @author travis
 */
public class PriorScoreParams implements Params.Stateless {
  public static final Logger LOG = Logger.getLogger(PriorScoreParams.class);

  private Map<String, Double> index;
  private double[] theta;
  private double learningRate = 0.05d;

  /**
   * If featureMode = true, then this will learn weights for two features:
   *  [I(item was in k-best list), logProb(item)]
   * Otherwise, it will not learn any parameters and add the log prob of the
   * item to the score (-infinity if it was not in the k-best list).
   */
  public PriorScoreParams(ItemProvider ip, boolean featureMode) {
    if (featureMode)
      theta = new double[3];
    index = new HashMap<>();
    for (int i = 0; i < ip.size(); i++) {
      FNParse y = ip.label(i);
      List<Item> items = ip.items(i);
      for (Item it : items) {
        String k = itemKey(y.getId(), it.t(), it.k(), it.getSpan());
        Double old = index.put(k, it.getScore());
        assert old == null;
      }
    }
    LOG.info("[init] index contains " + index.size() + " items");
  }

  public static String itemKey(String parseId, int t, int k, Span s) {
    return parseId + " " + t + " " + k + " " + s.shortString();
  }

  public static String itemKey(FNTagging f, Action a) {
    String id = f.getId();
    Span arg = a.hasSpan() ? a.getSpan() : Span.nullSpan;
    return itemKey(id, a.t, a.k, arg);
  }

  @Override
  public Adjoints score(FNTagging f, Action a) {
    String key = itemKey(f, a);
    Double score = index.get(key);
    if (theta != null) {
      double[] feats = new double[theta.length];
      if (score == null)
        feats[0] = 1d;
      else
        feats[1] = score;
      feats[2] = 1d;
      return new Adjoints.DenseFeatures(feats, theta, a);
    } else {
      if (score == null)
        score = Double.NEGATIVE_INFINITY;
      return new Adjoints.Explicit(score, a, "priorScore");
    }
  }

  @Override
  public void update(Adjoints a, double reward) {
    if (theta != null) {
      ((Adjoints.DenseFeatures) a).update(reward, learningRate);
      LOG.debug("[update] theta(not-in-k-best) = " + theta[0]);
      LOG.debug("[update] theta(item-log-prob) = " + theta[1]);
      LOG.debug("[update] theta(intercept) = " + theta[2]);
    } else {
      LOG.debug("[update] not doing anything");
    }
  }
}
