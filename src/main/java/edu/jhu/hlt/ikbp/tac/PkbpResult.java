package edu.jhu.hlt.ikbp.tac;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.LL;

/**
 * A entry in the mapping n-entities => list of situations containing these entities as participants.
 * 
 * @author travis
 */
public class PkbpResult implements Serializable {
  private static final long serialVersionUID = -3661191613878095335L;
  
  /** we need an id b/c we maintain s2r and e2r, and we have to de-duplicate results across the union */
  public final String id;
  public final boolean containsSeed;

  //  double salience;    // for ranking PkbEntries to show to the user based on their query
  private List<PkbpEntity> args;   // for now: length=2 and arg0 is always the seed entity
  private List<PkbpSituation> situations;    // should this be mentions instead of situations?
  
  public PkbpResult(String id, boolean containsSeed) {
    this.id = id;
    this.containsSeed = containsSeed;
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
    sb.append("(PR ");
    sb.append(id);
    if (containsSeed)
      sb.append(" seed=Y");
    else
      sb.append(" seed=N");
    sb.append(" args=");
    sb.append(argHeads());
    sb.append(" sits=");
    sb.append(sitHeads());
    
    List<Feat> s = getSalience();
    sb.append(String.format(" salience=%.2f b/c %s)", Feat.sum(s), s));

    return sb.toString();
  }
  
  public List<Feat> getSalience() {
    List<Feat> fs = new ArrayList<>();
    
    // Having lots of arguments means we have lots of info about these events
    fs.add(new Feat("numArgs", Math.sqrt(args.size() + 1)));
    
    // Having a situation with lots of mentions is good
    int m = 0;
    for (PkbpSituation sit : situations)
      m = Math.max(m, sit.mentions.size());
    fs.add(new Feat("numSit", Math.sqrt(m + 1)));
    
    int ne = getEntityMentions().size();
    int ns = getSituationMentions().size();
    double nf = (2d*ne*ns) / (ne+ns+1);
    fs.add(new Feat("numEnt*Sit", nf*10d));
    
    if (containsSeed)
      fs.add(new Feat("containsSeed", 10));
    
    return fs;
  }
  
  public List<PkbpEntity.Mention> getEntityMentions() {
    List<PkbpEntity.Mention> m = new ArrayList<>();
    for (PkbpEntity e : args)
      for (PkbpEntity.Mention mm : e)
        m.add(mm);
    return m;
  }
  
  public List<PkbpSituation.Mention> getSituationMentions() {
    List<PkbpSituation.Mention> m = new ArrayList<>();
    for (PkbpSituation e : situations)
      for (PkbpSituation.Mention mm : e)
        m.add(mm);
    return m;
  }
  
  public List<PkbpEntity> getArguments() {
    return args;
  }
  
  public void addArgument(PkbpEntity entity, Map<PkbpEntity, LL<PkbpResult>> invMapping) {
    args.add(entity);
    invMapping.put(entity, new LL<>(this, invMapping.get(entity)));
  }

  public List<PkbpSituation> getSituations() {
    return situations;
  }
  
  public void addSituation(PkbpSituation sit, Map<PkbpSituation, LL<PkbpResult>> invMapping) {
    situations.add(sit);
    invMapping.put(sit, new LL<>(this, invMapping.get(sit)));
  }
}
