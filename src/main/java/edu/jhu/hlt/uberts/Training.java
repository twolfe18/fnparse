package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.tuple.Pair;

/**
 * TODO Update to use {@link Uberts#getLabel(HypEdge)} instead of {@link Instance}.
 *
 * @author travis
 */
public class Training {

  private Uberts u;
  private List<? extends Instance> instances;

  public Training(Uberts u, List<? extends Instance> yx) {
    this.u = u;
    this.instances = yx;
  }

  public void train(int maxEpoch) {
    int inst = 0;
    double k = instances.size() * maxEpoch;
    for (int epoch = 0; epoch < maxEpoch; epoch++) {
      Collections.shuffle(instances, u.getRandom());
      for (Instance yx : instances) {
        double pFollowOracle = k / (k + inst);
        interestingTrainingProcedure(yx, pFollowOracle);
        inst++;
      }
    }
  }

  /**
   * @param pFollowOracle anneal this from 1 to 0 during training
   */
  public void interestingTrainingProcedure(Instance yx, double pFollowOracle) {
    yx.setupState(u);
    Agenda agenda = u.getAgenda();
    List<Adjoints> good = new ArrayList<>();
    List<Adjoints> bad = new ArrayList<>();
    while (agenda.size() > 0) {
      Pair<HypEdge, Adjoints> p = agenda.popBoth();
      HypEdge e = p.get1();
      Adjoints a = p.get2();
      double y = yx.label(e);
      if (y == 1)
        good.add(a);
      else
        bad.add(a);
      double r = u.getRandom().nextDouble();
      if (y < 1 && r <= pFollowOracle) {
        // skip
      } else {
        u.addEdgeToState(e, Adjoints.Constant.ZERO);
      }
    }

    double n = good.size() + bad.size();
    assert n > 0;
    for (Adjoints a : good)
      a.backwards(-1/n);
    for (Adjoints a : bad)
      a.backwards(+1/n);
  }
}
