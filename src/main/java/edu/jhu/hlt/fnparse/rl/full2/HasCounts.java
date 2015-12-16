package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.HashableIntArray;

// Labels is where it is easy to accidentally make stuff non-general.
// But we need to ask for the label in one form or another to compute loss -> do oracle/mv search -> etc
// TODO
public interface HasCounts {
  public Counts<HashableIntArray> getCounts();
}