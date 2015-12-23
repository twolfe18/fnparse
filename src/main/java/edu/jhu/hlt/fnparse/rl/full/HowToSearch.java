package edu.jhu.hlt.fnparse.rl.full;

public interface HowToSearch extends SearchCoefficients {
  public int beamSize();        // How many states to keep at every step
  public int numConstraints();  // How many states to keep for forming margin constraints (a la k-best MIRA)
}