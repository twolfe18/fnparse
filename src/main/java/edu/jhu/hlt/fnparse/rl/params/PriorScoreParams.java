package edu.jhu.hlt.fnparse.rl.params;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.math3.util.FastMath;
import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.rerank.Item;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.util.Projections;

/**
 * Stores the prior score for an item in a HashMap keyed on (t,k,span)
 * 
 * @author travis
 */
public class PriorScoreParams implements Params.Stateless {
  public static final Logger LOG = Logger.getLogger(PriorScoreParams.class);
  public static boolean SHOW_PARAMS_AFTER_UPDATE = true;

  /**
   * You give this class items via store(), and this will set the rank field
   * in Item for you after you call computeRanks().
   */
  static class ItemRanker {
    static class Key {
      public String parseId;
      public int t, k;
      public Key(String parseId, int t, int k) {
        this.parseId = parseId;
        this.t = t;
        this.k = k;
      }
      @Override
      public int hashCode() {
        int tk = (t << 16) ^ k;
        return parseId.hashCode() ^ tk;
      }
      @Override
      public boolean equals(Object other) {
        if (other instanceof Key) {
          Key k = (Key) other;
          return t == k.t && this.k == k.k && parseId.equals(k.parseId);
        }
        return false;
      }
    }
    private Map<Key, List<Item>> byTK;
    public ItemRanker() {
      byTK = new HashMap<>();
    }
    public void store(ItemProvider ip) {
      int n = ip.size();
      for (int i = 0; i < n; i++) {
        List<Item> items = ip.items(i);
        String parseId = ip.label(i).getId();
        for (Item it : items) {
          Key k = new Key(parseId, it.t(), it.k());
          List<Item> value = byTK.get(k);
          if (value == null) {
            value = new ArrayList<>();
            byTK.put(k, value);
          }
          value.add(it);
        }
      }
    }
    public void computeRanks() {
      for (List<Item> items : byTK.values()) {
        items.sort(new Comparator<Item>() {
          @Override
          public int compare(Item o1, Item o2) {
            if (o1.getScore() > o2.getScore())
              return 1;
            if (o1.getScore() < o2.getScore())
              return -1;
            return 0;
          }
        });
        int n = items.size();
        for (int i = 0; i < n; i++)
          items.get(i).rank = i + 1;
      }
    }
  }

  private Map<String, Item> index;
  private double[] theta;
  private double learningRate = 0.01d;
  private double l2Radius = 10d;

  /**
   * If featureMode = true, then this will learn weights for two features:
   *  [I(item was in k-best list), logProb(item)]
   * Otherwise, it will not learn any parameters and add the log prob of the
   * item to the score (-infinity if it was not in the k-best list).
   */
  public PriorScoreParams(ItemProvider ip, boolean featureMode) {
    if (featureMode)
      theta = new double[9 * 2];
    index = new HashMap<>();
    for (int i = 0; i < ip.size(); i++) {
      FNParse y = ip.label(i);
      List<Item> items = ip.items(i);
      for (Item it : items) {
        String k = itemKey(y.getId(), it.t(), it.k(), it.getSpan());
        Item old = index.put(k, it);
        assert old == null;
      }
    }
    ItemRanker ir = new ItemRanker();
    ir.store(ip);
    ir.computeRanks();
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
    Item score = index.get(key);
    int offset = 0;
    if (a.hasSpan())
      offset = theta.length / 2;
    if (theta != null) {
      double[] feats = new double[theta.length];
      feats[offset + 0] = 1d;
      if (score == null) {
        feats[offset + 1] = 1d;
      } else {
        feats[offset + 2] = score.getScore();
        assert score.rank > 0;
        if (score.rank == 1) feats[offset + 3] = 1d;
        if (score.rank == 2) feats[offset + 4] = 1d;
        if (score.rank == 3) feats[offset + 5] = 1d;
        if (score.rank == 4) feats[offset + 6] = 1d;
        if (score.rank == 5) feats[offset + 7] = 1d;
        if (score.rank > 5)  feats[offset + 8] = 1d;
      }
      return new Adjoints.DenseFeatures(feats, theta, a);
    } else {
      double s = score == null ? Double.NEGATIVE_INFINITY : score.getScore();
      return new Adjoints.Explicit(s, a, "priorScore");
    }
  }

  public void setParamsByHand() {
    double recallBias = 2d;
    theta[0] = FastMath.sqrt(1d / recallBias);
    theta[2] = 1d;
    theta[3] = 1d;
    theta[9 + 0] = FastMath.sqrt(recallBias);
    theta[9 + 1] = -999d;
    theta[9 + 2] = 1d;
    theta[9 + 3] = 1d;
    if (SHOW_PARAMS_AFTER_UPDATE)
      logParams();
  }

  public void logParams() {
    LOG.debug(String.format("[update] NS theta(intercept)     = %+.3f", theta[0]));
    LOG.debug(String.format("[update] NS theta(not-in-k-best) = %+.3f", theta[1]));
    LOG.debug(String.format("[update] NS theta(item-log-prob) = %+.3f", theta[2]));
    LOG.debug(String.format("[update] NS theta(rank==1)       = %+.3f", theta[3]));
    LOG.debug(String.format("[update] NS theta(rank==2)       = %+.3f", theta[4]));
    LOG.debug(String.format("[update] NS theta(rank==3)       = %+.3f", theta[5]));
    LOG.debug(String.format("[update] NS theta(rank==4)       = %+.3f", theta[6]));
    LOG.debug(String.format("[update] NS theta(rank==5)       = %+.3f", theta[7]));
    LOG.debug(String.format("[update] NS theta(rank>5)        = %+.3f", theta[8]));

    LOG.debug(String.format("[update] theta(intercept)        = %+.3f", theta[9 + 0]));
    LOG.debug(String.format("[update] theta(not-in-k-best)    = %+.3f", theta[9 + 1]));
    LOG.debug(String.format("[update] theta(item-log-prob)    = %+.3f", theta[9 + 2]));
    LOG.debug(String.format("[update] theta(rank==1)          = %+.3f", theta[9 + 3]));
    LOG.debug(String.format("[update] theta(rank==2)          = %+.3f", theta[9 + 4]));
    LOG.debug(String.format("[update] theta(rank==3)          = %+.3f", theta[9 + 5]));
    LOG.debug(String.format("[update] theta(rank==4)          = %+.3f", theta[9 + 6]));
    LOG.debug(String.format("[update] theta(rank==5)          = %+.3f", theta[9 + 7]));
    LOG.debug(String.format("[update] theta(rank>5)           = %+.3f", theta[9 + 8]));

    LOG.debug("");
  }

  @Override
  public void update(Adjoints a, double reward) {
    if (theta != null) {
      ((Adjoints.DenseFeatures) a).update(reward, learningRate);
      Projections.l2Ball(theta, l2Radius);
      if (SHOW_PARAMS_AFTER_UPDATE)
        logParams();
    } else {
      LOG.debug("[update] not doing anything");
    }
  }
}
