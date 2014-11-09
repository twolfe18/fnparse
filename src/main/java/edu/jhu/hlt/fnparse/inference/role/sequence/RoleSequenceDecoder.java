package edu.jhu.hlt.fnparse.inference.role.sequence;

import java.util.Arrays;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class RoleSequenceDecoder {
  public static Logger LOG = Logger.getLogger(RoleSequenceDecoder.class);
  private double[][] alpha, beta;
  private int n = -1;

  /**
   * Score(role, i, j) =
   *  prod_{idx in [i,j)} p(role,idx)
   *  * prod_{idx < i} p(!role,idx)    == alpha[i-1, role]
   *  * prod_{idx >= j} p(!role,idx)   == beta[i, role]
   * 
   * let alpha[i, role]   = prod_{idx <= i} max_{r != role} p(r, idx)
   *  => alpha[i+1, role] = alpha[i, role] * max_{r != role} p(r, idx)
   *     alpha[0, role]   = 1
   * 
   * let beta[i, role]    = prod_idx >= j} max_{r != role} p(r, idx)
   *  => beta[i-1, role]  = beta[i, role] * max_{r != role} p(r, idx)
   *     beta[N, role]    = 1
   * 
   * 1) populate alpha
   * 2) populate beta
   * 3) foreach role
   *      best = null
   *      foreach i < N
   *        foreach j > i and j <= N
   *          Score(role, i, j)
   *          update best
   *
   * @param probs should be indexed as [word][role] and normalized so that it
   * sums to 1 for every word. should be in log space.
   * @param pruneThresh is the minimum score per token a role-span must
   * achieve to consider the span actually realized (otherwise the role gets
   * nullSpan).
   */
  public FrameInstance decode(
      double[][] probs, double pruneThresh, Frame f, Span target, Sentence s) {
    Span[] args = decode(probs, pruneThresh, f.numRoles(), target, s.size());
    return FrameInstance.newFrameInstance(f, target, args, s);
  }

  public Span[] decode(
      double[][] probs, double pruneThresh, int K, Span target, int n) {
    this.n = n;
    Span[] args = new Span[K];
    Arrays.fill(args, Span.nullSpan);
    assert n > 0;
    assert probs.length == n;
    assert probs[0].length == K + 1;
    buildAlpha(probs);
    buildBeta(probs);
    for (int k = 0; k < K; k++) {
      double bestScore = Double.NEGATIVE_INFINITY;
      int bestI = -1, bestJ = -1;
      for (int i = 0; i < n; i++) {
        double inSpanScore = 0d;
        for (int j = i + 1; j <= n; j++) {
          inSpanScore = prod(inSpanScore, probs[j - 1][k]);
          assert inSpanScore <= 0d;
          double score = prod(inSpanScore, alpha(i - 1, k), beta(j, k));
          //LOG.info(String.format("[RSD decode] role %d for %d-%d = %s ~ %.3f \t alpha=%.2f \t cur=%.2f \t beta=%.2f",
          //    k, target.start, target.end, Span.getSpan(i, j), score, alpha(i - 1, k), inSpanScore, beta(j, k)));
          if (score > bestScore) {
            //LOG.info("new best score");
            bestScore = score;
            bestI = i;
            bestJ = j;
          }
        }
      }

      // Consider not using this role at all
      if (bestScore < alpha(n-1, k))
        continue;

      if (bestScore / n > pruneThresh)
        args[k] = Span.getSpan(bestI, bestJ);
    }
    return args;
  }

  private double alpha(int wordIdx, int role) {
    if (wordIdx < 0)
      return 0d;
    return alpha[wordIdx][role];
  }

  private double beta(int wordIdx, int role) {
    if (wordIdx >= n)
      return 0d;
    return beta[wordIdx][role];
  }

  private void buildAlpha(double[][] probs) {
    assert n == probs.length;
    int K = probs[0].length;  // NOTE: not numRoles, this is bigger
    if (alpha == null || alpha.length < n || alpha[0].length < K)
      alpha = new double[n][K];
    for (int i = 0; i < n; i++) {
      for (int kCur = 0; kCur < K; kCur++) {
        if (i > 0)
          alpha[i][kCur] = prod(max(probs[i], kCur), alpha[i - 1][kCur]);
        else
          alpha[i][kCur] =     max(probs[i], kCur);
        assert alpha[i][kCur] <= 0d;
      }
    }
  }

  private void buildBeta(double[][] probs) {
    assert n == probs.length;
    int K = probs[0].length;  // NOTE: not numRoles, this is bigger
    if (beta == null || beta.length < n || beta[0].length < K)
      beta = new double[n][K];
    for (int i = n - 1; i >= 0; i--) {
      for (int kCur = 0; kCur < K; kCur++) {
        if (i < n - 1)
          beta[i][kCur] = prod(max(probs[i], kCur), beta[i + 1][kCur]);
        else
          beta[i][kCur] =     max(probs[i], kCur);
        assert beta[i][kCur] <= 0d;
      }
    }
  }

  private static double max(double[] values, int excludingIndex) {
    double m = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < values.length; i++)
      if (i != excludingIndex && values[i] > m)
        m = values[i];
    return m;
  }

  private static double prod(double a, double b, double c) {
    return a + b + c;
  }

  private static double prod(double a, double b) {
    return a + b;
  }

  public static void main(String[] args) {
    double[][] probs = log(new double[][] {
        {0.25, 0.25, 0.5},
        {1/3d, 1/3d, 1/3d},
        {0.1, 0.8, 0.1}
    });
    double[][] alpha = log(new double[][] {
        {0.5, 0.5, 0.25},
        {(1/3d)*0.5d, (1/3d)*0.5d, (1/3d)*0.25d},
        {0.8*(1/3d)*0.5, 0.1*(1/3d)*0.5, 0.8*(1/3d)*0.25}
    });
    double[][] beta = log(new double[][] {
        {(0.8/3d)*0.5, (0.1/3d)*0.5, (0.8/3d)*0.25},
        {0.8/3d, 0.1/3d, 0.8/3d},
        {0.8, 0.1, 0.8}
    });
    RoleSequenceDecoder rsd = new RoleSequenceDecoder();
    rsd.decode(probs, Double.NEGATIVE_INFINITY, probs[0].length - 1, null, probs.length);

    System.out.println("\n\nalpha");
    System.out.println("gold:");
    print2d(alpha);
    System.out.println("\nhyp:");
    print2d(rsd.alpha);
    assert equals(rsd.alpha, alpha, 1e-6);

    System.out.println("\n\nbeta");
    System.out.println("gold:");
    print2d(beta);
    System.out.println("\nhyp:");
    print2d(rsd.beta);
    assert equals(rsd.beta, beta, 1e-6);
  }

  public static double[][] log(double[][] m) {
    for (int i = 0; i < m.length; i++)
      for (int j = 0; j < m[i].length; j++)
        m[i][j] = Math.log(m[i][j]);
    return m;
  }
  public static void print2d(double[][] m) {
    int n = m.length;
    int K = m[0].length;
    for (int k = 0; k < K; k++) {
      for (int i = 0; i < n; i++) {
        System.out.printf("\t%.3f", m[i][k]);
      }
      System.out.println();
    }
  }
  public static boolean equals(double[][] a, double[][] b, double eps) {
    if (a.length != b.length) return false;
    for (int i = 0; i < a.length; i++) {
      if (a[i].length != b[i].length) return false;
      for (int j = 0; j < a[i].length; j++)
        if (Math.abs(a[i][j] - b[i][j]) > eps)
          return false;
    }
    return true;
  }
}
