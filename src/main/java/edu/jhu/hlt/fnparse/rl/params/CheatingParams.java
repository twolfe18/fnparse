package edu.jhu.hlt.fnparse.rl.params;

import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.State;

public class CheatingParams implements Params {
  public static final Logger LOG = Logger.getLogger(CheatingParams.class);

  private Set<String> goldItems;
  private Set<String> goldParseIds;
  private double[] theta;
  private double learningRate;

  public CheatingParams(Iterable<FNParse> parses) {
    learningRate = 0.05d;
    theta = new double[2];
    goldItems = new HashSet<>();
    goldParseIds = new HashSet<>();
    for (FNParse p : parses) {
      String parseId = p.getId();
      boolean added = goldParseIds.add(parseId);
      assert added;
      int T = p.numFrameInstances();
      for (int t = 0; t < T; t++) {
        FrameInstance fi = p.getFrameInstance(t);
        int K = fi.getFrame().numRoles();
        for (int k = 0; k < K; k++) {
          Span arg = fi.getArgument(k);
          if (arg != Span.nullSpan)
            goldItems.add(PriorScoreParams.itemKey(parseId, t, k, arg));
        }
      }
    }
    LOG.info("[init] added " + goldItems.size() + " items for "
        + goldParseIds.size() + " parses");
  }

  public void setWeightsByHand() {
    theta[0] = -1d;
    theta[1] =  2d;
  }

  @Override
  public Adjoints score(State s, Action a) {
    if (!goldParseIds.contains(s.getFrames().getId()))
      throw new IllegalStateException("this parse is unknown, can't cheat");
    boolean isGold = goldItems.contains(PriorScoreParams.itemKey(s, a));
    double[] f = new double[theta.length];
    f[0] = 1d;
    f[1] = isGold ? 1d : 0d;
    return new Adjoints.DenseFeatures(f, theta, a);
  }

  @Override
  public void update(Adjoints a, double reward) {
    ((Adjoints.DenseFeatures) a).update(reward, learningRate);
  }
}
