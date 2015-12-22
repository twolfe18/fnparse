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
public class TVN extends IntPair { //implements HasMaxLoss {
  private static final long serialVersionUID = 5464423642943761171L;

//  private MaxLoss loss;
//  public TVN(int type, int value, MaxLoss loss) {
//    super(type, value);
//    this.loss = loss;
//  }

  public final int numPossible;   // how many completions of this prefix are there?
  public final int goldMatching;  // this prefix, includes all completions. if this is a leaf (prefix==all columns), then this can be at most 1
  // numDetermined is a property dependent on where a TVN lies in Node2

  public TVN(int type, int value, int numPossible, int goldMatching) {
    super(type, value);
    assert goldMatching <= numPossible : "goldMatching=" + goldMatching + " numPossible=" + numPossible;
    this.numPossible = numPossible;
    this.goldMatching = goldMatching;
  }

  // Hypothetical aggregate:
  // if you were to aggregate sum(1 + f(left) + f(right)) over a hypothetical sub-tree, what would you get?
//  public final int numPossible;
//
//  // Is this prefix/item in the gold label?
//  public final Boolean gold;

  /*
   * Do you need to know an int for gold recusive (all completions of a prefix?)
   * You do at some point...
   * If you want to weight the cost of doing squash on a shallow node, you need to know this is costly!
   * And there is the rub: if you know the sub-tree num-matches, you know if this state matched!
   */


  public int getType() {
    return first;
  }

  public int getValue() {
    return second;
  }

  @Override
  public String toString() {
    return "(TVN t=" + getType() + " v=" + getValue() + " poss=" + numPossible + " goldMatching=" + goldMatching + ")";
  }

//  @Override
//  public MaxLoss getLoss() {
//    return loss;
//  }
}