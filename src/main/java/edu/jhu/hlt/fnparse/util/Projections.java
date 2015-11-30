package edu.jhu.hlt.fnparse.util;

public class Projections {

  public static void l2Ball(double[] values, double radius) {
    double l2 = 0d;
    for (int i = 0; i < values.length; i++) {
      double d = values[i];
      l2 += d * d;
    }
    l2 = Math.sqrt(l2);
    if (l2 > radius) {
      final double scale = radius / l2;
      for (int i = 0; i < values.length; i++)
        values[i] *= scale;
    }
  }

}
