package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.tutils.IntPair;

/**
 * "Type and value"
 * T = type, e.g. an int representing Frame(Commerce_buy)
 * V = value, e.g. an int representing Span(3,5)
 *
 * @author travis
 */
public class TV extends IntPair {
  private static final long serialVersionUID = -6816158440933474245L;

  public TV(int type, int value) {
    super(type, value);
  }

  public int getType() {
    return first;
  }

  public int getValue() {
    return second;
  }
}