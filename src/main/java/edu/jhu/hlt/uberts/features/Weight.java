package edu.jhu.hlt.uberts.features;

/**
 * See Alg 7 in http://www.ciml.info/dl/v0_8/ciml-v0_8-ch03.pdf for details on
 * averaging.
 */
public class Weight<T> {

//  private int nUpdates = 0;     // NOT the same as c, c is stored at the top level
//  private double theta = 0;     // w
//  private double thetaAvg = 0;  // u
//  private final T item;
  private float theta = 0;

  public Weight(T item) {
//    this.item = item;
//    this.nUpdates = 0;
//    this.theta = 0;
  }

  public void increment(double amount) {
    theta += amount;
//    nUpdates++;
  }

  public void incrementWithAvg(double amount, int numInstances) {
    theta += amount;
//    thetaAvg += numInstances * amount;
//    nUpdates++;
  }

  public double getWeight() {
    return theta;
  }

  public double getAvgWeight(int numInstances) {
//    return theta - thetaAvg / numInstances;
    return theta;
  }

  @Override
  public String toString() {
//    return String.format("(%s %+.2f n=%d)", item.toString(), theta, nUpdates);
    return "";
  }
}