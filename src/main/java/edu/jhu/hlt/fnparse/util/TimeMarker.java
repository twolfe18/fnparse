package edu.jhu.hlt.fnparse.util;

/**
 * Class designed to answer the question:
 * "has it been X seconds since I called you last?"
 *
 * @author travis
 */
public class TimeMarker {
  private long lastMark = -1;

  /**
   * @return true if enoughSeconds have passed since this this method last
   * returned true, or if this method has never been called.
   */
  public boolean enoughTimePassed(double enoughSeconds) {
    if (lastMark < 0) {
      lastMark = System.currentTimeMillis();
      return true;
    }
    long time = System.currentTimeMillis();
    double elapsed = (time - lastMark) / 1000d;
    if (elapsed >= enoughSeconds) {
      lastMark = time;
      return true;
    } else {
      return false;
    }
  }

  public double secondsSinceLastMark() {
    return (System.currentTimeMillis() - lastMark) / 1000d;
  }
}