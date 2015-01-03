package edu.jhu.hlt.fnparse.rl.rerank;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.Adjoints;
import edu.jhu.hlt.fnparse.rl.Params;
import edu.jhu.hlt.fnparse.rl.State;

/**
 * Stores the prior score for an item in a HashMap keyed on (t,k,span)
 * 
 * @author travis
 */
public class PriorScoreParams implements Params {
  public static final Logger LOG = Logger.getLogger(PriorScoreParams.class);

  private Map<String, Double> index;

  public PriorScoreParams(ItemProvider ip) {
    index = new HashMap<>();
    for (int i = 0; i < ip.size(); i++) {
      FNParse y = ip.label(i);
      List<Item> items = ip.items(i);
      for (Item it : items) {
        String k = key(y.getId(), it.t(), it.k(), it.getSpan());
        Double old = index.put(k, it.getScore());
        assert old == null;
      }
    }
    LOG.info("[init] index contains " + index.size() + " items");
  }

  public static String key(String parseId, int t, int k, Span s) {
    return parseId + " " + t + " " + k + " " + s.shortString();
  }

  @Override
  public Adjoints score(State s, Action a) {
    String id = s.getFrames().getId();
    Span arg = a.hasSpan() ? a.getSpan() : Span.nullSpan;
    String key = key(id, a.t, a.k, arg);
    Double score = index.get(key);
    if (score == null)
      score = Double.NEGATIVE_INFINITY;
    return new Adjoints.Explicit(score, a, "priorScore");
  }

  @Override
  public void update(Adjoints a, double reward) {
    LOG.debug("[update] not doing anything");
  }
}
