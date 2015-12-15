package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.tutils.IntPair;

public final class TV extends IntPair {
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