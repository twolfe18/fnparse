package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.fnparse.rl.full.MaxLoss;
import edu.jhu.hlt.tutils.IntPair;

/**
 * "Type and value"
 * T = type, e.g. an int representing Frame(Commerce_buy)
 * V = value, e.g. an int representing Span(3,5)
 * {@link MaxLoss} is going to have fn=fp=0, but it will store numPossible
 *
 * @author travis
 */
public class TVN extends IntPair implements HasMaxLoss {
  private static final long serialVersionUID = 5464423642943761171L;

  private MaxLoss loss;

  // TODO Should I switch out numPossible:int to be a full MaxLoss?
  // This could let me store FN in eggs:LLML<TVN>...
//  public TVN(int type, int value, int numPossible) {
//    super(type, value);
//    loss = new MaxLoss(numPossible);
//  }
  public TVN(int type, int value, MaxLoss loss) {
    super(type, value);
    this.loss = loss;
  }

  public int getType() {
    return first;
  }

  public int getValue() {
    return second;
  }

  @Override
  public MaxLoss getLoss() {
    return loss;
  }
}