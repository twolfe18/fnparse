package edu.jhu.hlt.fnparse.rl.params;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

import org.apache.log4j.Logger;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.util.RandomInitialization;
import edu.jhu.prim.util.Lambda.FnIntDoubleToDouble;
import edu.jhu.prim.util.math.FastMath;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;
import edu.jhu.prim.vector.IntDoubleVector;

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
    public void initialize(double variance, java.util.Random rand);
    public EmbeddingAdjoints embed(Frame frame, int role);
  }

  public interface ContextEmbeddingParams {
    public int dimension(); // returned EmbeddingAdjoints should have forward scores of this dimension
    public void initialize(double variance, java.util.Random rand);
    public EmbeddingAdjoints embed(FNTagging frames, Span target, Action action);
  }

  public static interface EmbeddingAdjoints {
    public boolean takesUpdates();   // TODO add this to Adjoints
    public IntDoubleVector forwards();
    public void backwards(IntDoubleVector dScore_dForwards);
  }

  // Returns an embedding which is really just features, doesn't update anything.
  // Used for debugging.
  public static class FeatureEmbeddingAdjoints implements EmbeddingAdjoints {
    private IntDoubleVector features;
    public FeatureEmbeddingAdjoints(FeatureVector features) {
      this.features = features;
    }
    @Override
    public IntDoubleVector forwards() {
      return features;
    }
    @Override
    public void backwards(IntDoubleVector dScore_dForwards) {
      // no-op (we're not learning an embedding, features have no params)
      // TODO interesting side note, this would make for an interesting paper
      // where you take all of the features that we typically use for an NLP
      // task and then put "dampening" or "meta" features on them, which
      // control the features activation. For example, you might want your
      // capitalization feature to not fire if its the first word in a sentence.
    }
    @Override
    public boolean takesUpdates() {
      return false;
    }
  }

  // Returns an embedding which is just a vector of ones and does no updating.
  public static class OnesEmbeddingAdjoints implements EmbeddingAdjoints {
    private final IntDoubleVector ones;
    public OnesEmbeddingAdjoints(int dimension) {
      ones = new IntDoubleDenseVector(new double[] {1d});
    }
    @Override
    public IntDoubleVector forwards() {
      return ones;
    }
    @Override
    public void backwards(IntDoubleVector dScore_dForwards) {
      // no-op
    }
    @Override
    public boolean takesUpdates() {
      return false;
    }
  }

  class QuadAdjoints2 implements Adjoints {
    public Action action;
    // double[][] theta comes from outer class
    public EmbeddingAdjoints left;  // dense
    public EmbeddingAdjoints right; // can be sparse

    // Results of forward computation, needed for backwards computation
    private IntDoubleVector leftTimesTheta;   // dense
    private IntDoubleVector rightTimesTheta;  // dense
    private IntDoubleVector[] leftTimesRight; // dense
    private double prod;
    private int computed;

    public QuadAdjoints2(Action action, EmbeddingAdjoints left, EmbeddingAdjoints right) {
      this.action = action;
      this.left = left;
      this.right = right;
      this.computed = 0;
    }

    // score = 1xR * RxC * Cx1
    public void compute() {
      assert computed == 0;

      // Compute left and right embeddings
      IntDoubleVector leftEV = left.forwards();
      assert leftEV instanceof IntDoubleDenseVector : "sparse left values not supported";
      double[] leftE = ((IntDoubleDenseVector) leftEV).getInternalElements();
      IntDoubleVector rightE = right.forwards();

      // Compute leftTimesRight (dense * sparse?)
      if (updateTheta) {
        boolean rightIsSparse = rightE instanceof IntDoubleUnsortedVector;
        assert rightIsSparse || rightE instanceof IntDoubleDenseVector;
        leftTimesRight = new IntDoubleVector[nR];
        if (rightIsSparse) {
          for (int i = 0; i < nR; i++)
            leftTimesRight[i] = new IntDoubleUnsortedVector();
        } else {
          for (int i = 0; i < nR; i++)
            leftTimesRight[i] = new IntDoubleDenseVector(nC);
        }
        rightE.apply(new FnIntDoubleToDouble() {
          @Override
          public double call(int j, double v2) {
            for (int i = 0; i < nR; i++) {
              double v1 = leftE[i];
              leftTimesRight[i].add(j, v1 * v2);
            }
            return v2;
          }
        });
      }

      // Compute leftTimesTheta (dense * dense)
      // leftTimesTheta :: 1xR * RxC = 1xC
      // left=dense * theta=dense = dense update to sparse params, who wont even apply the update anyway
      if (right.takesUpdates()) {
        leftTimesTheta = new IntDoubleDenseVector(nC);
        for (int i = 0; i < nR; i++) {
          for (int j = 0; j < nC; j++) {
            double v = leftE[i] * theta[i][j];
            leftTimesTheta.add(i, v);
          }
        }
      }

      // Compute rightTimesTheta (sparse? * dense)
      // rightTimesTheta :: RxC * Cx1 = Rx1
      // rtt[i] = dot(theta[i,], right)
      if (left.takesUpdates()) {
        rightTimesTheta = new IntDoubleDenseVector(nR);
        for (int i = 0; i < nR; i++)
          rightTimesTheta.add(i, rightE.dot(theta[i]));
      }


      // Compute prod
      prod = 0;
      if (rightTimesTheta != null) {
        prod = rightTimesTheta.dot(leftE);
      } else {
        for (int i = 0; i < nR; i++)
          prod += leftE[i] * rightE.dot(theta[i]);
      }

      // In a dense-only world:
//      for (int i = 0; i < nR; i++) {
//        for (int j = 0; j < nC; j++) {
//          leftTimesTheta[j] += leftE[i] * theta[i][j];
//          rightTimesTheta[i] += rightE[j] * theta[i][j];
//          leftTimesRight[i][j] = leftE[i] * rightE[j];
//          prod += leftE[i] * theta[i][j] * rightE[j];
//        }
//      }
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
    public void backwards(double dScore_dForwards) {
      if (computed == 0)
        compute();
      assert computed == 1;

      if (left.takesUpdates() && (!onlyUpdateTheta || !updateTheta)) {
        rightTimesTheta.scale(dScore_dForwards);
        left.backwards(rightTimesTheta);
      }

      if (right.takesUpdates() && (!onlyUpdateTheta || !updateTheta)) {
        leftTimesTheta.scale(dScore_dForwards);
        right.backwards(leftTimesTheta);
      }

      if (updateTheta) {
        for (int i_loop = 0; i_loop < theta.length; i_loop++) {
          final int i = i_loop;
          leftTimesRight[i].apply(new FnIntDoubleToDouble() {
            @Override
            public double call(int j, double ltr_ij) {
              double g = dScore_dForwards * ltr_ij;
              double l2p = l2Penalty * theta[i][j];
              theta[i][j] += g - l2p;
              return ltr_ij;
            }
          });
//          for (int j = 0; j < theta[i].length; j++) {
//            double g = dScore_dForwards * leftTimesRight[i].get(j);
//            double l2p = l2Penalty * theta[i][j];
//            theta[i][j] += g - l2p;
//          }
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
  private int nR;   // nR = #rows in theta = length of frEmbedding
  private int nC;   // nC = #cols in theta = length of ctxEmbedding
  private boolean updateTheta;  // if false, don't apply gradient updates to theta
  private boolean onlyUpdateTheta = false;    // when true updates are made to either theta OR the embeddings, otherwise updateTheta only says whether theta receives a gradient update (embeddings always do).
  private double l2Penalty = 1e-5;

  // If true, use the params below
  private FeatureParams debugParams;
  public void debug(FeatureParams debugParams, double l2Penalty) {
    this.updateTheta = true;
    this.l2Penalty = l2Penalty;
    this.debugParams = debugParams;
    this.nR = 1;
    this.nC = debugParams.getNumHashingBuckets();
    this.theta = new double[nR][nC];
    this.frParams = null;
    this.ctxParams = null;
    LOG.info("[debug] theta is (" + nR + ", " + nC + ") numParams=" + (nR*nC));
  }

  /**
   * @param k is a multiplier for how many params to use, 1 is very parsimonious
   * and 6 is a lot, 2 is a good default. The number of params is linear in k.
   * Powers of 2 are preferable for k.
   */
  public EmbeddingParams(int k, double l2Penalty, Random rand) {
    this.l2Penalty = l2Penalty;
    this.updateTheta = true;
    this.frParams = new FrameRoleEmbeddings(8 * k, 16 * k, 8 * k, l2Penalty);
    this.ctxParams = new ContextEmbedding(16 * k, l2Penalty);
    this.nR = frParams.dimension();
    this.nC = ctxParams.dimension();
    this.theta = new double[nR][nC];
    LOG.info("[init] theta is (" + nR + ", " + nC + ") numParams=" + (nR*nC));

    double variance = 10;
    LOG.info("[init] randomly initializing with variance=" + variance);
    frParams.initialize(variance, rand);
    ctxParams.initialize(variance, rand);
    new RandomInitialization(rand).unif(theta, variance);
  }

  public boolean isLearningTheta() {
    return updateTheta;
  }

  public void learnTheta(boolean learnTheta) {
    this.updateTheta = learnTheta;
  }

  @Override
  public QuadAdjoints2 score(FNTagging frames, Action a) {
    EmbeddingAdjoints fr, ctx;
    if (debugParams != null) {
      // Use features instead of embeddings
      FeatureVector fv = debugParams.getFeatures(frames, a);
      fr = new OnesEmbeddingAdjoints(1);
      ctx = new FeatureEmbeddingAdjoints(fv);
    } else {
      // Normally, do this, compute embeddings
      FrameInstance fi = frames.getFrameInstance(a.t);
      fr = frParams.embed(fi.getFrame(), a.k);
      ctx = ctxParams.embed(frames, fi.getTarget(), a);
    }
    QuadAdjoints2 adj = new QuadAdjoints2(a, fr, ctx);
    return adj;
  }

  @Override
  public void doneTraining() {
    LOG.info("[doneTraining] currently doesn't support weight averaging");
  }

  @Override
  public void showWeights() {
    throw new RuntimeException("implement me!");
  }

  @Override
  public void serialize(DataOutputStream out) throws IOException {
    throw new RuntimeException("implement me!");
  }

  @Override
  public void deserialize(DataInputStream in) throws IOException {
    throw new RuntimeException("implement me!");
  }
}
