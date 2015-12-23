package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.fnparse.rl.full.MaxLoss;
import edu.jhu.hlt.tutils.scoring.Adjoints;

/**
 * "Type and value"
 * T = type, e.g. an int representing Frame(Commerce_buy)
 * V = value, e.g. an int representing Span(3,5)
 *
 * N has growing meaning and currently represents something just weaker than
 * {@link MaxLoss}, but almost enough to compute loss on.
 * 
 * @author travis
 */
public class TVN {

  public final long prime;    // Not a product!
  public final int type, value;
  public final int numPossible;   // how many completions of this prefix are there?
  public final int goldMatching;  // this prefix, includes all completions. if this is a leaf (prefix==all columns), then this can be at most 1
  // numDetermined is a property dependent on where a TVN lies in Node2
  // NOTE: goldMatching will either go to FP or FN in MaxLoss

  public TVN(int type, int value, int numPossible, int goldMatching, long prime) {
    assert goldMatching <= numPossible : "goldMatching=" + goldMatching + " numPossible=" + numPossible;
    this.type = type;
    this.value = value;
    this.numPossible = numPossible;
    this.goldMatching = goldMatching;
    this.prime = prime;
  }

  @Override
  public String toString() {
    return "(TVN t=" + type + " v=" + value + " poss=" + numPossible + " goldMatching=" + goldMatching + ")";
  }

  public TVNS withScore(Adjoints model, double rand) {
    return new TVNS(type, value, numPossible, goldMatching, prime, model, rand);
  }
}