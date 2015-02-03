package edu.jhu.hlt.fnparse.util;

import java.util.Random;

public class RandomInitialization {

  private Random rand;

  public RandomInitialization(Random rand) {
    this.rand = rand;
  }

  public void unif(double[][] matrix, double variance) {
    int D = getSize(matrix);
    assert D > 0;
    double s = 2 * variance / Math.sqrt(D);
    for (int i = 0; i < matrix.length; i++) {
      if (matrix[i] == null) continue;
      for (int j = 0; j < matrix[i].length; j++)
        matrix[i][j] = (rand.nextDouble() - 0.5) * s;
    }
  }

  public void unif(double[][][] matrix, double variance) {
    int D = getSize(matrix);
    assert D > 0;
    double s = 2 * variance / Math.sqrt(D);
    for (int i = 0; i < matrix.length; i++) {
      if (matrix[i] == null) continue;
      for (int j = 0; j < matrix[i].length; j++) {
        if (matrix[i][j] == null) continue;
        for (int k = 0; k < matrix[i][j].length; k++)
          matrix[i][j][k] = (rand.nextDouble() - 0.5) * s;
      }
    }
  }

  public static int getSize(double[][] m) {
    if (m == null) return 0;
    int c = 0;
    for (int i = 0; i < m.length; i++) {
      if (m[i] == null) continue;
      c += m[i].length;
    }
    return c;
  }

  public static int getSize(double[][][] m) {
    if (m == null) return 0;
    int c = 0;
    for (int i = 0; i < m.length; i++)
      c += getSize(m[i]);
    return c;
  }
}
