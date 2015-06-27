package edu.jhu.hlt.fnparse.rl.params;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.tutils.Log;

/**
 * A module for Params/Adjoints which never get updated.
 *
 * @author travis
 */
public class Fixed {

  public static class Adjoints implements edu.jhu.hlt.fnparse.rl.params.Adjoints {
    private edu.jhu.hlt.fnparse.rl.params.Adjoints wrapped;
    public Adjoints(edu.jhu.hlt.fnparse.rl.params.Adjoints w) {
      this.wrapped = w;
    }
    @Override
    public Action getAction() {
      return wrapped.getAction();
    }
    @Override
    public double forwards() {
      return wrapped.forwards();
    }
    @Override
    public void backwards(double dScore_dForwards) {
      // no-op
    }
  }

  public static class Stateless implements Params.Stateless {
    private static final long serialVersionUID = -8515735269292485236L;
    private Params.Stateless wrapped;
    public Stateless(Params.Stateless w) {
      wrapped = w;
    }
    @Override
    public void doneTraining() {
      wrapped.doneTraining();
    }
    @Override
    public void showWeights() {
      wrapped.showWeights();
    }
    @Override
    public void serialize(DataOutputStream out) throws IOException {
      wrapped.serialize(out);
    }
    @Override
    public void deserialize(DataInputStream in) throws IOException {
      wrapped.deserialize(in);
    }
    @Override
    public void addWeights(Params other, boolean checkAlphabetEquality) {
      Log.info("not actually adding weights, params are fixed.");
    }
    @Override
    public void scaleWeights(double scale) {
      Log.info("not actually scaling weights, params are fixed.");
    }
    @Override
    public edu.jhu.hlt.fnparse.rl.params.Adjoints score(FNTagging f, Action a) {
      return new Adjoints(wrapped.score(f, a));
    }
  }

}
