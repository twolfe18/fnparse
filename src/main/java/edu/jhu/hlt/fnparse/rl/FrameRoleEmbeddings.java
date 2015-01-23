package edu.jhu.hlt.fnparse.rl;

import java.util.List;
import java.util.Random;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.rl.params.EmbeddingParams;
import edu.jhu.hlt.fnparse.rl.params.EmbeddingParams.EmbeddingAdjoints;
import edu.jhu.hlt.fnparse.rl.params.EmbeddingParams.FrameRoleEmbeddingParams;
import edu.jhu.hlt.fnparse.util.RandomInitialization;
import edu.jhu.util.Alphabet;

/**
 * Implements an embedding for (frame,role) as
 * concat(embed(f), embed(f,r), embed(r))
 * 
 * @author travis
 */
public class FrameRoleEmbeddings implements FrameRoleEmbeddingParams {
  public static final Logger LOG = Logger.getLogger(FrameRoleEmbeddings.class);

  public final int dFrame;
  public final int dFrameRole;
  public final int dRole;
  public final double l2Penalty;

  private Alphabet<String> roleNames;   // for role embeddings
  private double[][] frameEmb;
  private double[][][] frameRoleEmb;  // indexed by [frame.getId][role]
  private double[][] roleEmb;
  private int numParams;

  public FrameRoleEmbeddings(int dFrame, int dFrameRole, int dRole, double l2Penalty) {
    LOG.info("initializing dFrame=" + dFrame + " dFrameRole=" + dFrameRole + " dRole=" + dRole + " l2Penalty=" + l2Penalty);
    assert dFrame > 0 && dFrameRole > 0 && dRole > 0;
    this.dFrame = dFrame;
    this.dFrameRole = dFrameRole;
    this.dRole = dRole;
    this.l2Penalty = l2Penalty;
    List<Frame> frames = FrameIndex.getInstance().allFrames();
    int nF = frames.size();
    assert nF > 0;
    numParams = 0;
    frameRoleEmb = new double[nF][][];
    roleNames = new Alphabet<>();
    for (Frame f : frames) {
      int K = f.numRoles();
      if (K == 0) continue;
      frameRoleEmb[f.getId()] = new double[K][dFrameRole];
      numParams += K * dFrameRole;
      for (int k = 0; k < K; k++)
        roleNames.lookupIndex(f.getRole(k), true);
    }
    frameEmb = new double[nF][dFrame];
    numParams += nF * dFrame;
    roleEmb = new double[roleNames.size()][dRole];
    numParams += roleNames.size() * dRole;
    show();
  }

  class FrameRoleEmbeddingAdjoints implements EmbeddingAdjoints {
    private Frame frame;
    private int role;
    private double[] embedding;
    public FrameRoleEmbeddingAdjoints(Frame frame, int role) {
      this.frame = frame;
      this.role = role;
      this.embedding = null;
    }
    @Override
    public double[] forwards() {
      if (embedding == null)
        computeEmbedding();
      return embedding;
    }
    public void computeEmbedding() {
      assert embedding == null;
      double[] fE = frameEmb[frame.getId()];
      double[] frE = frameRoleEmb[frame.getId()][role];
      double[] rE = roleEmb[roleNames.lookupIndex(frame.getRole(role), false)];
      embedding = new double[fE.length + frE.length + rE.length];
      System.arraycopy(fE, 0, embedding, 0, fE.length);
      assert EmbeddingParams.regular(embedding);
      System.arraycopy(frE, 0, embedding, fE.length, frE.length);
      assert EmbeddingParams.regular(embedding);
      System.arraycopy(rE, 0, embedding, fE.length + frE.length, rE.length);
      assert EmbeddingParams.regular(embedding);
    }
    @Override
    public void backwards(double[] dErr_dForwards) {
      if (embedding == null)
        computeEmbedding();
      if (embedding.length != dErr_dForwards.length)
        throw new IllegalArgumentException();
      double[] fE = frameEmb[frame.getId()];
      double[] frE = frameRoleEmb[frame.getId()][role];
      double[] rE = roleEmb[roleNames.lookupIndex(frame.getRole(role), false)];
      int ofs1 = fE.length;
      int ofs2 = fE.length + frE.length;
      for (int i = 0; i < dErr_dForwards.length; i++) {
        if (i < ofs1)
          fE[i] += dErr_dForwards[i] - l2Penalty * fE[i];
        else if (i < ofs2)
          frE[i - ofs1] += dErr_dForwards[i] - l2Penalty * frE[i - ofs1];
        else
          rE[i - ofs2] += dErr_dForwards[i] - l2Penalty * rE[i - ofs2];
      }
    }
  }

  public void initialize(double variance, Random rand) {
    RandomInitialization init = new RandomInitialization(rand);
    init.unif(frameEmb, variance);
    init.unif(frameRoleEmb, variance);
    init.unif(roleEmb, variance);
  }

  public int dimension() {
    return dFrame + dFrameRole + dRole;
  }

  public void show() {
    LOG.info(String.format("[show] D=%d numParams=%d size=%.1f MB",
        dimension(), numParams(), (8d * numParams()) / (1024 * 1024)));
  }

  public int numParams() {
    return numParams;
  }

  @Override
  public EmbeddingAdjoints embed(Frame frame, int role) {
    return this.new FrameRoleEmbeddingAdjoints(frame, role);
  }
}
