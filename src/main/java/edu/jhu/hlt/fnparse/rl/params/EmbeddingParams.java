package edu.jhu.hlt.fnparse.rl.params;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.FrameRoleFeatures;
import edu.jhu.hlt.fnparse.rl.params.ContextEmbedding.CtxEmb;

/**
 * implements e(f,r)' \theta \phi(t,a,s,x)
 * 
 * e(f,r) is an embedding for a frame f and a role r
 * 
 * t is the target for which we're classifying args
 * a is the aspan being scored
 * s is the state of the predictor so far (e.g. location of other arguments)
 * x is the sentence and all metadata
 * 
 * NOTE: What I really want is a spare linear model in addition to this
 * embedding model. I want sparse features like argument width, indicators for
 * if an action creates a span that crosses a previous span, etc.
 * 
 * @author travis
 */
public class EmbeddingParams implements Params.Stateless {
  public static final Logger LOG = Logger.getLogger(EmbeddingParams.class);

  static class QuadAdjoints implements Adjoints {
    public final FNTagging frames;
    public final Action action;
    public final double[] fr;
    public final CtxEmb ctx;

    private double[][] prod;  // pointwise product of ((fr' ctx) theta)
    private double[] prodRowSums;
    private double[] prodColSums;
    private double prodSum;

    public QuadAdjoints(FNTagging frames, Action a, double[] fr, CtxEmb ctx) {
      this.frames = frames;
      this.action = a;
      this.fr = fr;
      this.ctx = ctx;
    }

    public void compute(double[][] theta) {
      int nR = theta.length;
      int nC = theta[0].length;
      prod = new double[nR][nC];
      prodRowSums = new double[nR];
      prodColSums = new double[nC];
      prodSum = 0d;
      double[] ctx = this.ctx.getEmbedding();
      for (int i = 0; i < nR; i++) {
        for (int j = 0; j < nC; j++) {
          double p = theta[i][j] * fr[i] * ctx[j];
          prod[i][j] = p;
          prodRowSums[i] += p;
          prodColSums[j] += p;
          prodSum += p;
        }
      }
    }

    public double[] getFrTimesTheta() {
      assert prod != null;
      double[] ctx = this.ctx.getEmbedding();
      int n = ctx.length;
      double[] p = new double[n];
      for (int i = 0; i < n; i++)
        p[i] = prodColSums[i] / ctx[i];
      return p;
    }

    public double[] getCtxTimesTheta() {
      assert prod != null;
      int n = fr.length;
      double[] p = new double[n];
      for (int i = 0; i < n; i++)
        p[i] = prodRowSums[i] / fr[i];
      return p;
    }

    public double[][] getProd() {
      assert prod != null;
      return prod;
    }

    @Override
    public double getScore() {
      assert prod != null;
      return prodSum;
    }

    @Override
    public Action getAction() {
      return action;
    }

//    public boolean matches(State s, Action a) {
//      return s == state && a == action;
//    }
  }

  private FrameRoleFeatures frE;
  private ContextEmbedding ctxE;
  private double[][] theta;

  /**
   * @param k is a multiplier for how many params to use, 1 is very parsimonious
   * and 5 is a lot, 2 is a good default.
   */
  public EmbeddingParams(int k) {
    frE = new FrameRoleFeatures(16 * k, 32 * k, 16 * k);
    ctxE = new ContextEmbedding(32 * k);
    theta = new double[frE.dimension()][ctxE.getDimension()];
    int d = theta.length * theta[0].length;
    LOG.info("theta is (" + theta.length + ", " + theta[0].length + ") numParams=" + d);
  }

  @Override
  public QuadAdjoints score(FNTagging frames, Action a) {
    //long t0 = System.currentTimeMillis();
    FrameInstance fi = frames.getFrameInstance(a.t);
    double[] fr = frE.embed(fi.getFrame(), a.k);
    //long t1 = System.currentTimeMillis();
    CtxEmb ctx = ctxE.embed(fi.getSentence(), fi.getTarget(), a);
    //long t2 = System.currentTimeMillis();
    QuadAdjoints adj = new QuadAdjoints(frames, a, fr, ctx);
    adj.compute(theta);
    //long t3 = System.currentTimeMillis();
    //LOG.info("[score] frE=" + (t1-t0) + " ctxE=" + (t2-t1) + " quad=" + (t3-t2));
    return adj;
  }

  @Override
  public void update(Adjoints adjoints, double reward) {
    QuadAdjoints adj = (QuadAdjoints) adjoints;
    Action a = adj.getAction();
    double learningRate = 0.1d;
    Frame f = adj.frames.getFrameInstance(a.t).getFrame();
    frE.update(f, a.k, adj.getCtxTimesTheta(), learningRate);
    ctxE.update(adj.ctx, adj.getFrTimesTheta(), learningRate);
    double[][] prod = adj.getProd();
    int nR = prod.length;
    int nC = prod[0].length;
    for (int i = 0; i < nR; i++) {
      for (int j = 0; j < nC; j++) {
        double dtheta = prod[i][j] / theta[i][j];
        theta[i][j] += learningRate * dtheta;
      }
    }
  }

}
