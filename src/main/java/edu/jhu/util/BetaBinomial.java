package edu.jhu.util;

public class BetaBinomial {

  /**
   * @param k number of successes
   * @param n number of trials
   * @param alpha pseudo count (prior) of successes
   * @param beta pseudo count (prior) of trials
   *
   * https://people.csail.mit.edu/regina/6864/mlvsmap.pdf
   * https://en.wikipedia.org/wiki/Beta_distribution
   *
   * View the alpha/beta curves in R with:
   *  x = seq(0, 1, by=0.01)
   *  plot(x, dbeta(x, alpha, beta), type="l")
   */
  public static double map(double k, double n, double alpha, double beta) {
    assert alpha >= 0;
    assert beta >= 0;
    assert k >= 0;
    assert k <= n;
    double p = (k + alpha - 1) / (n + alpha + beta - 2);
    assert p >= 0;
    assert p <= 1;
    return p;
  }

  public static void main(String[] args) {
    double alpha = 2;
    double beta = 3;
    System.out.println(map(2, 3, alpha, beta));
    System.out.println(map(4, 6, alpha, beta));
    System.out.println(map(20, 30, alpha, beta));
    System.out.println(map(200, 300, alpha, beta));
    System.out.println(map(10, 12, alpha, beta));
  }
}
