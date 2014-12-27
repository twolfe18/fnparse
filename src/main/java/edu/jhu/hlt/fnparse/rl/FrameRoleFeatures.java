package edu.jhu.hlt.fnparse.rl;

import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.util.Alphabet;

/**
 * Implements an embedding for (frame,role) as
 * concat(embed(f), embed(f,r), embed(r))
 * 
 * @author travis
 */
public class FrameRoleFeatures {
  public static final Logger LOG = Logger.getLogger(FrameRoleFeatures.class);

  public final int dFrame;
  public final int dFrameRole;
  public final int dRole;

  private Alphabet<String> roleNames;   // for role embeddings
  private double[][] frameEmb;
  private double[][][] frameRoleEmb;  // indexed by [frame.getId][role]
  private double[][] roleEmb;
  private int numParams;

  public FrameRoleFeatures(int dFrame, int dFrameRole, int dRole) {
    LOG.info("initializing dFrame=" + dFrame + " dFrameRole=" + dFrameRole + " dRole=" + dRole);
    this.dFrame = dFrame;
    this.dFrameRole = dFrameRole;
    this.dRole = dRole;
    List<Frame> frames = FrameIndex.getInstance().allFrames();
    int nF = frames.size();
    numParams = 0;
    frameRoleEmb = new double[nF][][];
    roleNames = new Alphabet<>();
    for (Frame f : frames) {
      int K = f.numRoles();
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
    System.arraycopy(frE, 0, emb, fE.length, frE.length);
    System.arraycopy(rE, 0, emb, fE.length + frE.length, rE.length);
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
    for (int i = 0; i < dErr_dEmbedding.length; i++) {
      if (i < ofs1)
        fE[i] += learningRate * dErr_dEmbedding[i];
      else if (i < ofs2)
        frE[i - ofs1] += learningRate * dErr_dEmbedding[i];
      else
        rE[i - ofs2] += learningRate * dErr_dEmbedding[i];
    }
  }

}
