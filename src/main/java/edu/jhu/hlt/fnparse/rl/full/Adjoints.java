package edu.jhu.hlt.fnparse.rl.full;

public interface Adjoints {
  public double forwards();
  public void backwards(double dErr_dObjective);
}