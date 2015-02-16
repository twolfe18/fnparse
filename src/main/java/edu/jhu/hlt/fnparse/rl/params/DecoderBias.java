package edu.jhu.hlt.fnparse.rl.params;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.rl.PruneAdjoints;

/**
 * This is a type of Param which adds a value to actions that result in a
 * nullSpan. This is not used in regular training, which optimizes Hamming loss,
 * but rather is only imposed at test time, and has its single parameter which
 * is set by line search on the actual loss function (F1) after primary learning
 * has occurred, likely on other data.
 * 
 * @author travis
 */
public class DecoderBias implements Params.PruneThreshold {

  private double recallBias = 0d;

  @Override
  public String toString() {
    return String.format("<DecoderBias recallBias=%.2f>", recallBias);
  }

  public void setRecallBias(double b) {
    this.recallBias = b;
  }

  public double getRecallBias() {
    return recallBias;
  }

  @Override
  public Adjoints score(FNTagging frames, PruneAdjoints pruneAction, String... providenceInfo) {
    return new Adjoints.Explicit(-recallBias, pruneAction, "decoderBias");
  }

  @Override
  public void doneTraining() {
    // no-op
  }

  @Override
  public void showWeights() {
    LOG.info("[DecoderBias] recallBias=" + recallBias);
  }

  @Override
  public void serialize(DataOutputStream out) throws IOException {
    out.writeDouble(recallBias);
  }

  @Override
  public void deserialize(DataInputStream in) throws IOException {
    recallBias = in.readDouble();
  }
}