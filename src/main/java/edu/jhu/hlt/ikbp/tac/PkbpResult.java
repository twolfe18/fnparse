package edu.jhu.hlt.ikbp.tac;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.tutils.LL;

/** n-entities => list of situations containing these entities */
public class PkbpResult implements Serializable {
  private static final long serialVersionUID = -3661191613878095335L;

  //  double salience;    // for ranking PkbEntries to show to the user based on their query
  private List<PkbpEntity> args;   // for now: length=2 and arg0 is always the seed entity
  private List<PkbpSituation> situations;    // should this be mentions instead of situations?
  
  public PkbpResult() {
    args = new ArrayList<>();
    situations = new ArrayList<>();
  }
  
  public List<String> argHeads() {
    List<String> a = new ArrayList<>();
    for (PkbpEntity e : args)
      a.add(e.getCanonicalHeadString());
    return a;
  }

  public List<String> sitHeads() {
    List<String> a = new ArrayList<>();
    for (PkbpSituation s : situations)
      a.add(s.getCanonicalHeadString());
    return a;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("(PR args=");
    sb.append(argHeads());
    sb.append(" sits=");
    sb.append(sitHeads());
    sb.append(String.format(" salience=%.2f)", getSalience()));
    return sb.toString();
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
  
  public Iterable<PkbpEntity> getArguments() {
    return args;
  }
  
  public void addArgument(PkbpEntity entity, Map<PkbpEntity, LL<PkbpResult>> invMapping) {
    args.add(entity);
    invMapping.put(entity, new LL<>(this, invMapping.get(entity)));
  }
  
  public void addSituation(PkbpSituation sit, Map<PkbpSituation, LL<PkbpResult>> invMapping) {
    situations.add(sit);
    invMapping.put(sit, new LL<>(this, invMapping.get(sit)));
  }
}
