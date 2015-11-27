package edu.jhu.hlt.fnparse.rl.params;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.PruneAdjoints;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.prim.vector.IntDoubleDenseVector;

public class CheatingParams implements Params.Stateless, Params.PruneThreshold {
  private static final long serialVersionUID = 5980175705954633122L;

  public static final Logger LOG = Logger.getLogger(CheatingParams.class);
  public static boolean SHOW_ON_UPDATE = false;

  private Set<String> goldItems;
  private Set<String> goldParseIds;
  private LazyL2UpdateVector theta;

  public CheatingParams(Iterable<FNParse> parses) {
    theta = new LazyL2UpdateVector(new IntDoubleDenseVector(2), 1);
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
          goldItems.add(PriorScoreParams.itemKey(parseId, t, k, arg));
        }
      }
    }
    LOG.info("[init] added " + goldItems.size() + " items for "
        + goldParseIds.size() + " parses");
  }

  @Override
  public String toString() {
    return "(CheatingParams)";
  }

  public void setWeightsByHand() {
//    theta[0] = -1d;
//    theta[1] =  2d;
    theta.weights.set(0, -1);
    theta.weights.set(1, 2);
  }

  @Override
  public void showWeights() {
    StringBuilder sb = new StringBuilder("[CheatingParams weights:\n");
//    sb.append("intercept = " + theta[0] + "\n");
//    sb.append("isGold    = " + theta[1] + "\n");
    sb.append("intercept = " + theta.weights.get(0) + "\n");
    sb.append("isGold    = " + theta.weights.get(1) + "\n");
    sb.append("]");
    LOG.info(sb.toString());
  }

  public boolean isGold(FNTagging frames, Action a) {
    return goldItems.contains(PriorScoreParams.itemKey(frames, a));
  }

  @Override
  public Adjoints score(FNTagging frames, Action a) {
    if (!goldParseIds.contains(frames.getId()))
      throw new IllegalStateException("this parse is unknown, can't cheat");
    double[] f = new double[theta.weights.getNumImplicitEntries()];
    f[0] = 1d;
    f[1] = isGold(frames, a) ? 1d : 0d;
    double l2Penalty = 0;
    return new Adjoints.Vector(this, a,
        theta,
        new IntDoubleDenseVector(f),
        l2Penalty);
  }

  @Override
  public Adjoints score(FNTagging frames, PruneAdjoints pruneAction, String... providenceInfo) {
    assert pruneAction.getSpanSafe() == Span.nullSpan;
    return score(frames, pruneAction);
  }

  @Override
  public void doneTraining() {
    LOG.info("[doneTraining] currently doesn't support weight averaging");
  }

  @Override
  public void serialize(DataOutputStream out) throws IOException {
    throw new RuntimeException("you should never do this");
  }

  @Override
  public void deserialize(DataInputStream in) throws IOException {
    throw new RuntimeException("you should never do this");
  }

  @Override
  public void addWeights(Params other, boolean checkAlphabetEquality) {
    throw new RuntimeException("don't do this");
  }

  @Override
  public void scaleWeights(double scale) {
    throw new RuntimeException("don't do this");
  }
}
