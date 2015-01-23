package edu.jhu.hlt.fnparse.rl.params;

import java.util.Random;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.FrameRoleEmbeddings;
import edu.jhu.hlt.fnparse.rl.params.ContextEmbedding.CtxEmb;
import edu.jhu.hlt.fnparse.util.RandomInitialization;
import edu.jhu.prim.util.math.FastMath;

/**
 * TODO write a test where I replace either v_x and/or v_y with 1 or f(y,x), interchangably
 * I should be able to learn the same weights as OldFeatureParams.
 */

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

  public static boolean regular(double[] fr) {
    for (int i = 0; i < fr.length; i++) {
      if (Double.isNaN(fr[i])) return false;
      if (!Double.isFinite(fr[i])) return false;
    }
    return true;
  }

  class QuadAdjoints implements Adjoints {
    public final FNTagging frames;
    public final Action action;
    public final double[] fr;
    public final CtxEmb ctx;

    private double prodSum; // wx
    private double[] frTimesTheta;  // indexed with j < ctx.length
    private double[] ctxTimesTheta; // indexed with i < fr.length
    private double[][] frTimesCtx;  // indexed with i,j

    public QuadAdjoints(FNTagging frames, Action a, double[] fr, CtxEmb ctx) {
      this.frames = frames;
      this.action = a;
      this.fr = fr;
      this.ctx = ctx;
      assert fr.length == theta.length;
      assert ctx.getEmbedding().length == theta[0].length;
      assert regular(fr);
      assert regular(ctx.getEmbedding());
    }

    public void compute(double[][] theta) {
      int nR = theta.length;
      int nC = theta[0].length;
      frTimesTheta = new double[nC];
      ctxTimesTheta = new double[nR];
      frTimesCtx = new double[nR][nC];
      prodSum = 0d;
      double[] ctx = this.ctx.getEmbedding();
      for (int i = 0; i < nR; i++) {
        for (int j = 0; j < nC; j++) {
          prodSum += theta[i][j] * fr[i] * ctx[j];
          assert !Double.isNaN(prodSum);
          assert Double.isFinite(prodSum);
          frTimesTheta[j] += theta[i][j] * fr[i];
          ctxTimesTheta[i] += theta[i][j] * ctx[j];
          frTimesCtx[i][j] += fr[i] * ctx[j];
        }
      }
      assert !Double.isNaN(prodSum);
      assert Double.isFinite(prodSum);
    }

    public double[] getFrTimesTheta() {
      assert regular(frTimesTheta);
      return frTimesTheta;
    }

    public double[] getCtxTimesTheta() {
      assert regular(ctxTimesTheta);
      return ctxTimesTheta;
    }

    public double[][] getProd() {
      for (double[] fc : frTimesCtx)
        assert regular(fc);
      return frTimesCtx;
    }

    @Override
    public double forwards() {
      return prodSum;
    }

    @Override
    public Action getAction() {
      return action;
    }

    @Override
    public void backwards(double dErr_dForwards) {
      double learningRate = 1d;
      Frame f = frames.getFrameInstance(action.t).getFrame();

      double[] ctt = getCtxTimesTheta();
      LOG.info("2norm of ctx * theta = " + l2norm(ctt));
      frE.update(f, action.k, ctt, learningRate * dErr_dForwards);

      double[] ftt = getFrTimesTheta();
      LOG.info("2norm of fr * theta = " + l2norm(ftt));
      ctxE.update(ctx, ftt, learningRate * dErr_dForwards);

      LOG.info("2norm of fr * ctx = " + l2norm(frTimesCtx));
      LOG.info("2norm of theta = " + l2norm(theta));
      double l2Penalty = 1e-2;
      for (int i = 0; i < frTimesCtx.length; i++) {
        for (int j = 0; j < frTimesCtx[i].length; j++) {
          double dTheta = learningRate * dErr_dForwards * frTimesCtx[i][j];
//          assert !Double.isNaN(dTheta);
//          assert Double.isFinite(dTheta);
          theta[i][j] += dTheta - l2Penalty * theta[i][j];
        }
      }

    }
  }

  public static double l2norm(double[] v) {
    double d = 0d;
    for (double vv : v) d += vv * vv;
    return FastMath.sqrt(d);
  }

  public static double l2norm(double[][] v) {
    double d = 0d;
    for (double[] vv : v) {
      double s = l2norm(vv);
      d += s * s;
    }
    return FastMath.sqrt(d);
  }

  private FrameRoleEmbeddings frE;
  private ContextEmbedding ctxE;
  private double[][] theta;

  /**
   * @param k is a multiplier for how many params to use, 1 is very parsimonious
   * and 6 is a lot, 2 is a good default. The number of params is linear in k.
   * Powers of 2 are preferable for k.
   */
  public EmbeddingParams(int k, Random rand) {
    frE = new FrameRoleEmbeddings(8 * k, 16 * k, 8 * k);
    ctxE = new ContextEmbedding(16 * k);
    theta = new double[frE.dimension()][ctxE.getDimension()];
    int d = theta.length * theta[0].length;
    LOG.info("theta is (" + theta.length + ", " + theta[0].length + ") numParams=" + d);

    double variance = 10;
    LOG.info("randomly initializing with variance=" + variance);
    frE.initialize(variance, rand);
    ctxE.initialize(variance, rand);
    new RandomInitialization(rand).unif(theta, variance);
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
  public void doneTraining() {
    LOG.info("[doneTraining] currently doesn't support weight averaging");
  }
}
