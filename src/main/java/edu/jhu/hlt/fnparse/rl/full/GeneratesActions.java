package edu.jhu.hlt.fnparse.rl.full;

public interface GeneratesActions {

    /**
     * Add actions to the beam.
     * @param beam
     * @param prefixScore should be added to the score of any item put onto the
     * beam, and passed down (with an update) recursively if this method's
     * implementation calls next on another GeneratesActions.
     */
    public void next(Beam beam, double prefixScore);

    /**
     * Assuming next adds items with a score of: prefixScore + localScore
     * @return an upper bound on localScore or Infinity if an upper bound is
     * not available.
     */
    public double partialUpperBound();
}
