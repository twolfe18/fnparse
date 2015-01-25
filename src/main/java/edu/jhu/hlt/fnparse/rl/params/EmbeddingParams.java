package edu.jhu.hlt.fnparse.rl.params;

import java.util.Arrays;
import java.util.Random;

import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.FrameRoleEmbeddings;
import edu.jhu.hlt.fnparse.util.RandomInitialization;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
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

  public interface FrameRoleEmbeddingParams {
    public int dimension(); // returned EmbeddingAdjoints should have forward scores of this dimension
    public void initialize(double variance, Random rand);
    public EmbeddingAdjoints embed(Frame frame, int role);
  }

  public interface ContextEmbeddingParams {
    public int dimension(); // returned EmbeddingAdjoints should have forward scores of this dimension
    public void initialize(double variance, Random rand);
    public EmbeddingAdjoints embed(FNTagging frames, Span target, Action action);
  }

  public static interface EmbeddingAdjoints {
    public double[] forwards();
    public void backwards(double[] dErr_dForwards);
  }

  // Returns an embedding which is really just features, doesn't update anything.
  // Used for debugging.
  public static class FeatureEmbeddingAdjoints implements EmbeddingAdjoints {
    private double[] features;
    /**
     * @param features are sparse features with unbounded indices
     * @param hashBuckets is the dimension of the dense features used. If an
     * index in features is larger than this, it is treated as a hash and
     * idx % hashBuckets is used instead.
     */
    public FeatureEmbeddingAdjoints(FeatureVector features, int hashBuckets) {
      final double[] dfeatures = new double[hashBuckets];
      features.apply(new FnIntDoubleToDouble() {
        @Override
        public double call(int arg0, double arg1) {
          dfeatures[arg0 % hashBuckets] += arg1;
          return arg1;
        }
      });
      this.features = dfeatures;
    }
    @Override
    public double[] forwards() {
      return features;
    }
    @Override
    public void backwards(double[] dErr_dForwards) {
      // no-op (we're not learning an embedding, features have no params)
      // TODO interesting side note, this would make for an interesting paper
      // where you take all of the features that we typically use for an NLP
      // task and then put "dampening" or "meta" features on them, which
      // control the features activation. For example, you might want your
      // capitalization feature to not fire if its the first word in a sentence.
    }
  }

  // Returns an embedding which is just a vector of ones and does no updating.
  public static class OnesEmbeddingAdjoints implements EmbeddingAdjoints {
    private final double[] ones;
    public OnesEmbeddingAdjoints(int dimension) {
      ones = new double[dimension];
      Arrays.fill(ones, 1d);
    }
    @Override
    public double[] forwards() {
      return ones;
    }
    @Override
    public void backwards(double[] dErr_dForwards) {
      // no-op
    }
  }

  class QuadAdjoints2 implements Adjoints {
    public Action action;
    public double[][] theta;    // not owned by this class
    public EmbeddingAdjoints left, right;

    // Results of forward computation, needed for backwards computation
    private double[] leftTimesTheta;
    private double[] rightTimesTheta;
    private double[][] leftTimesRight;
    private double prod;
    private int computed;

    public QuadAdjoints2(Action action, double[][] theta, EmbeddingAdjoints left, EmbeddingAdjoints right) {
      this.action = action;
      this.theta = theta;
      this.left = left;
      this.right = right;
      this.computed = 0;
    }

    public void compute() {
      assert computed == 0;
      double[] leftE = left.forwards();
      double[] rightE = right.forwards();
      leftTimesTheta = new double[rightE.length];
      rightTimesTheta = new double[leftE.length];
      leftTimesRight = new double[theta.length][theta[0].length];
      prod = 0;
      for (int i = 0; i < theta.length; i++) {
        for (int j = 0; j < theta[i].length; j++) {
          leftTimesTheta[j] += leftE[i] * theta[i][j];
          rightTimesTheta[i] += rightE[j] * theta[i][j];
          leftTimesRight[i][j] = leftE[i] * rightE[j];
          prod += leftE[i] * theta[i][j] * rightE[j];
        }
      }
      computed++;
    }

    @Override
    public Action getAction() {
      return action;
    }

    @Override
    public double forwards() {
      if (computed == 0)
        compute();
      assert computed == 1;
      return prod;
    }

    @Override
    public void backwards(double dErr_dForwards) {
      if (computed == 0)
        compute();
      assert computed == 1;
      for (int i = 0; i < leftTimesTheta.length; i++)
        leftTimesTheta[i] *= dErr_dForwards;
      for (int j = 0; j < rightTimesTheta.length; j++)
        rightTimesTheta[j] *= dErr_dForwards;
      left.backwards(rightTimesTheta);
      right.backwards(leftTimesTheta);
      for (int i = 0; i < theta.length; i++)
        for (int j = 0; j < theta[i].length; j++)
          theta[i][j] += dErr_dForwards * leftTimesRight[i][j] - l2Penalty * theta[i][j];
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

  public static boolean regular(double[] fr) {
    for (int i = 0; i < fr.length; i++) {
      if (Double.isNaN(fr[i])) return false;
      if (!Double.isFinite(fr[i])) return false;
    }
    return true;
  }

  private FrameRoleEmbeddingParams frParams;
  private ContextEmbeddingParams ctxParams;
  private double[][] theta;
  private double l2Penalty = 1e-5;

  // If true, use the params below
  private OldFeatureParams debugParams;
  public void debug(OldFeatureParams debugParams) {
    this.debugParams = debugParams;
    this.theta = new double[1][debugParams.getNumHashingBuckets()];
    this.frParams = null;
    this.ctxParams = null;
  }

  /**
   * @param k is a multiplier for how many params to use, 1 is very parsimonious
   * and 6 is a lot, 2 is a good default. The number of params is linear in k.
   * Powers of 2 are preferable for k.
   */
  public EmbeddingParams(int k, Random rand) {
    frParams = new FrameRoleEmbeddings(8 * k, 16 * k, 8 * k, l2Penalty);
    ctxParams = new ContextEmbedding(16 * k, l2Penalty);
    theta = new double[frParams.dimension()][ctxParams.dimension()];
    int d = theta.length * theta[0].length;
    LOG.info("theta is (" + theta.length + ", " + theta[0].length + ") numParams=" + d);

    double variance = 10;
    LOG.info("randomly initializing with variance=" + variance);
    frParams.initialize(variance, rand);
    ctxParams.initialize(variance, rand);
    new RandomInitialization(rand).unif(theta, variance);
  }

  @Override
  public QuadAdjoints2 score(FNTagging frames, Action a) {
    if (debugParams != null) {
      // Use features instead of embeddings
      FeatureVector fv = debugParams.getFeatures(frames, a);
      int dim = debugParams.getNumHashingBuckets();
      EmbeddingAdjoints right = new FeatureEmbeddingAdjoints(fv, dim);
      EmbeddingAdjoints left = new OnesEmbeddingAdjoints(1);
      QuadAdjoints2 adj = new QuadAdjoints2(a, theta, left, right);
      return adj;
    } else {
      // Normally, do this, compute embeddings
      FrameInstance fi = frames.getFrameInstance(a.t);
      EmbeddingAdjoints fr = frParams.embed(fi.getFrame(), a.k);
      EmbeddingAdjoints ctx = ctxParams.embed(frames, fi.getTarget(), a);
      QuadAdjoints2 adj = new QuadAdjoints2(a, theta, fr, ctx);
      return adj;
    }
  }

  @Override
  public void doneTraining() {
    LOG.info("[doneTraining] currently doesn't support weight averaging");
  }
}
