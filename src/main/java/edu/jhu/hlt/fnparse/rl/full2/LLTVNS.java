package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.tutils.scoring.Adjoints;

public class LLTVNS extends LLTVN {

  public final Adjoints modelSum; // Prune score, should NOT include rand
  public final double randSum;

  public LLTVNS(TVNS item, LLTVNS next) {
    super(item, next);
    if (next == null) {
      modelSum = item.getModel();
      randSum = item.getRand();
    } else {
      modelSum = new Adjoints.Sum(item.getModel(), next.modelSum);
      randSum = item.getRand() + next.randSum;
    }
  }

  public Adjoints getModelSum() {
    return modelSum;
  }

  public double getRandSum() {
    return randSum;
  }

  @Override
  public LLTVNS cdr() {
    return (LLTVNS) next;
  }
}
