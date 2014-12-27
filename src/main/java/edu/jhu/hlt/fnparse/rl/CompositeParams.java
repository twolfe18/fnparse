package edu.jhu.hlt.fnparse.rl;

import edu.jhu.hlt.fnparse.rl.Learner.Adjoints;
import edu.jhu.hlt.fnparse.rl.Learner.Params;
import edu.jhu.hlt.fnparse.rl.State.Action;

public class CompositeParams implements Params {

  static class CompositeAdj implements Adjoints {
    private Adjoints[] adjoints;
    public CompositeAdj(Adjoints[] adj) {
      for (int i = 1; i < adj.length; i++)
        assert adj[i].getAction() == adj[0].getAction();
      this.adjoints = adj;
    }
    @Override
    public double getScore() {
      double score = 0d;
      for (int i = 0; i < adjoints.length; i++)
        score += adjoints[i].getScore();
      return score;
    }
    @Override
    public Action getAction() {
      return adjoints[0].getAction();
    }
  }

  private Params[] params;

  public CompositeParams(Params... params) {
    this.params = params;
  }

  @Override
  public Adjoints score(State s, Action a) {
    Adjoints[] adj = new Adjoints[params.length];
    for (int i = 0; i < params.length; i++)
      adj[i] = params[i].score(s, a);
    return new CompositeAdj(adj);
  }

  @Override
  public void update(Adjoints a, double reward) {
    CompositeAdj ca = (CompositeAdj) a;
    for (int i = 0; i < ca.adjoints.length; i++)
      params[i].update(ca.adjoints[i], reward);
  }
}
