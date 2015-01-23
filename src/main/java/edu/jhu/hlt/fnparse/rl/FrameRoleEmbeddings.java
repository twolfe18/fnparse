package edu.jhu.hlt.fnparse.rl;

import java.util.List;
import java.util.Random;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.rl.params.EmbeddingParams;
import edu.jhu.hlt.fnparse.util.RandomInitialization;
import edu.jhu.util.Alphabet;

/**
 * Implements an embedding for (frame,role) as
 * concat(embed(f), embed(f,r), embed(r))
 * 
 * @author travis
 */
public class FrameRoleEmbeddings {
  public static final Logger LOG = Logger.getLogger(FrameRoleEmbeddings.class);

  public final int dFrame;
  public final int dFrameRole;
  public final int dRole;

  private Alphabet<String> roleNames;   // for role embeddings
  private double[][] frameEmb;
  private double[][][] frameRoleEmb;  // indexed by [frame.getId][role]
  private double[][] roleEmb;
  private int numParams;

  public FrameRoleEmbeddings(int dFrame, int dFrameRole, int dRole) {
    LOG.info("initializing dFrame=" + dFrame + " dFrameRole=" + dFrameRole + " dRole=" + dRole);
    assert dFrame > 0 && dFrameRole > 0 && dRole > 0;
    this.dFrame = dFrame;
    this.dFrameRole = dFrameRole;
    this.dRole = dRole;
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

  public double[] embed(Frame f, int role) {
    double[] fE = frameEmb[f.getId()];
    double[] frE = frameRoleEmb[f.getId()][role];
    double[] rE = roleEmb[roleNames.lookupIndex(f.getRole(role), false)];
    double[] emb = new double[dimension()];
    System.arraycopy(fE, 0, emb, 0, fE.length);
    assert EmbeddingParams.regular(emb);
    System.arraycopy(frE, 0, emb, fE.length, frE.length);
    assert EmbeddingParams.regular(emb);
    System.arraycopy(rE, 0, emb, fE.length + frE.length, rE.length);
    assert EmbeddingParams.regular(emb);
    return emb;
  }

  public void update(Frame f, int role, double[] dErr_dEmbedding, double learningRate) {
    if (dErr_dEmbedding.length != dimension())
      throw new IllegalArgumentException();
    double[] fE = frameEmb[f.getId()];
    double[] frE = frameRoleEmb[f.getId()][role];
    double[] rE = roleEmb[roleNames.lookupIndex(f.getRole(role), false)];
    int ofs1 = fE.length;
    int ofs2 = fE.length + frE.length;
    
    double l2Penalty = 1e-2;
    
    for (int i = 0; i < dErr_dEmbedding.length; i++) {
      if (i < ofs1)
        fE[i] += learningRate * dErr_dEmbedding[i] - l2Penalty * fE[i];
      else if (i < ofs2)
        frE[i - ofs1] += learningRate * dErr_dEmbedding[i] - l2Penalty * frE[i - ofs1];
      else
        rE[i - ofs2] += learningRate * dErr_dEmbedding[i] - l2Penalty * rE[i - ofs2];
    }
  }

}
