package edu.jhu.hlt.uberts;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.tuple.Pair;

public class Step {
  public final HypEdge edge;
  public final Adjoints score;
  public final Boolean gold;    // can be null if you don't know

  public Step(Pair<HypEdge, Adjoints> es, Boolean gold) {
    this(es.get1(), es.get2(), gold);
  }

  public Step(HypEdge e, Adjoints score, Boolean gold) {
    this.edge = e;
    this.score = score;
    this.gold = gold;
  }
}