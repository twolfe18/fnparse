package edu.jhu.hlt.ikbp.tac;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** n-entities => list of situations containing these entities */
public class PkbpResult implements Serializable {
  private static final long serialVersionUID = -3661191613878095335L;

  //  double salience;    // for ranking PkbEntries to show to the user based on their query
  List<PkbpEntity> args;   // for now: length=2 and arg0 is always the seed entity
  List<PkbpSituation> situations;    // should this be mentions instead of situations?
  
  public PkbpResult() {
    args = new ArrayList<>();
    situations = new ArrayList<>();
  }
  
  public double getSalience() {
    double s = 0;
    
    // Having lots of arguments means we have lots of info about these events
    s += Math.sqrt(args.size() + 1);
    
    // Having a situation with lots of mentions is good
    int m = 0;
    for (PkbpSituation sit : situations)
      m = Math.max(m, sit.mentions.size());
    s += Math.sqrt(m + 1);
    
    return s;
  }
}
