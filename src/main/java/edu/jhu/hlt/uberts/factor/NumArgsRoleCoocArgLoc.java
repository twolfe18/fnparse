package edu.jhu.hlt.uberts.factor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Term;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;

public class NumArgsRoleCoocArgLoc implements GlobalFactor {
  public static int DEBUG = 0;
  public static boolean GF_DEBUG = false;
  public static boolean NA_DEBUG = false;

  /*
   * If true, then the Adjoints for edges on the agenda are immutable.
   * This is REQUIRED for some training methods, like daggerLike.
   * If this is false, then some global features can be efficiently updated
   * without allocating any new objects (e.g. globalAdjoints.numArgs++).
   *
   * Put another way, this class looks like:
   * def rescore(agenda, ...):
   *   (e,a1) = agenda.remove(...)
   *   a2 = globalFactor(state, e)
   *   agenda.add(e,a2)
   * => a2 may not point to (transitively) ANY MUTABLE pieces from a1
   */
  public static boolean IMMUTABLE_FACTORS = true;

  private String firesFor;      // e.g. argument4
  private long firesForHash;
  private int aggregateArgPos;  // e.g. 0 for t in argument4(t,f,s,k)
  private int refineArgPos;     // e.g. 1 for f in argument4(t,f,s,k), meaning create extra conjunction feature with frame value
  private int dimension;
  private AveragedPerceptronWeights theta;
  private boolean useAvg = false;
  private Alphabet<String> featureNames;  // for debugging

  private Counts<String> events = new Counts<>();

  // Each is called on f(newEdge,fOldEdge) where each argument is a firesFor Relation
  private List<PairFeat> pairwiseFeaturesFunctions;

  private double globalToLocalScale = 1;  //0.25

  // Stats
  private int nRescore = 0;
  private int nEdgeRescore = 0;

  public static class MultiGlobalParams {
    Map<String, GlobalParams> name2params = new HashMap<>();

    public void configure(ExperimentProperties config) {
      String key = "globalFeats";
      if (!config.containsKey(key)) {
        Log.info("[main] no globalFeats value provided!");
        return;
      }
      String[] vals = config.getStrings(key);
      for (String i : vals) {
        GlobalParams gp = new GlobalParams(i);
        Object old = name2params.put(gp.name, gp);
        assert old == null;
      }
    }

    public GlobalParams getOrAddDefault(String name) {
      GlobalParams gp = name2params.get(name);
      if (gp == null) {
        gp = new GlobalParams(name);  // everything is false
        name2params.put(name, gp);
      }
      return gp;
    }
  }

  public static class GlobalParams {
    public String name;
    public boolean frameCooc = false;
    public boolean numArgs = false;
    public boolean argLocPairwise = false;
    public boolean argLocGlobal = false;
    public boolean argLocRoleCooc = false;
    public boolean roleCooc = false;

    public GlobalParams(String desc) {
      String[] toks = desc.split("\\+");
      if (toks.length > 1)
        Log.info("[main] " + desc);
      name = toks[0];
      for (int i = 1; i < toks.length; i++) {
        switch (toks[i]) {
        case "none":
          break;
        case "full":
          numArgs = true;
          argLocPairwise = true;
          argLocGlobal = true;
          argLocRoleCooc = true;
          roleCooc = true;
          break;
        case "frameCooc":
          frameCooc = true;
          break;
        case "numArgs":
          numArgs = true;
          break;
        case "argLoc":
          argLocPairwise = true;
          argLocGlobal = true;
          break;
        case "argLocPairwise":
          argLocPairwise = true;
          break;
        case "argLocGlobal":
          argLocGlobal = true;
          break;
        case "argLocRoleCooc":
          argLocRoleCooc = true;
          break;
        case "roleCooc":
          roleCooc = true;
          break;
        }
      }
    }

    public GlobalParams() {
      // everything is false
    }

    public boolean any() {
      return frameCooc || numArgs || argLocPairwise || argLocGlobal || argLocRoleCooc || roleCooc;
    }

    public GlobalParams(GlobalParams copy) {
      frameCooc = copy.frameCooc;
      roleCooc = copy.roleCooc;
      argLocGlobal = copy.argLocGlobal;
      argLocPairwise = copy.argLocPairwise;
      argLocRoleCooc = copy.argLocRoleCooc;
      numArgs = copy.numArgs;
    }

    /** does &= */
    public void and(GlobalParams other) {
      frameCooc &= other.frameCooc;
      roleCooc &= other.roleCooc;
      argLocGlobal &= other.argLocGlobal;
      argLocPairwise &= other.argLocPairwise;
      argLocRoleCooc &= other.argLocRoleCooc;
      numArgs &= other.numArgs;
    }

    @Override
    public String toString() {
      return toString(true);
    }
    public String toString(boolean includeSpaces) {
      StringBuilder sb = new StringBuilder();
      if (numArgs) {
        if (includeSpaces) sb.append(' ');
        sb.append("+numArgs");
      }
      if (argLocPairwise) {
        if (includeSpaces) sb.append(' ');
        sb.append("+argLocPairwise");
      }
      if (argLocGlobal) {
        if (includeSpaces) sb.append(' ');
        sb.append("+argLocGlobal");
      }
      if (roleCooc) {
        if (includeSpaces) sb.append(' ');
        sb.append("+roleCooc");
      }
      if (frameCooc) {
        if (includeSpaces) sb.append(' ');
        sb.append("+frameCooc");
      }
      if (argLocRoleCooc) {
        if (includeSpaces) sb.append(' ');
        sb.append("+argLocRoleCooc");
      }
      return sb.toString().trim();
    }
  }
  private GlobalParams params;

  interface PairFeat {
    String getName();
    List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge);
  }

  static final PairFeat FRAME_COOC_FEAT = new PairFeat() {
    @Override
    public String getName() { return "fcPW"; }
    @Override
    public List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge) {
      String f1 = frame(stateEdge);
      if (f1 == null)
        return Collections.emptyList();
      String f2 = frame(agendaEdge);
      if (f2 == null)
        return Collections.emptyList();
      String s = prefix + "/" + agendaEdge.getRelation().getName() + "/" + f1 + "/" + f2;
      s = shorten(s);
      if (agendaEdge.getRelation() == stateEdge.getRelation())
        return Arrays.asList(s);
      return Arrays.asList(s, s + "/" + shorten(stateEdge.getRelation().getName()));
    }
  };
  static final PairFeat ROLE_COOC_FEAT = new PairFeat() {
    @Override
    public String getName() { return "rcPW"; }
    @Override
    public List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge) {
      String r1 = role(stateEdge);
      if (r1 == null)
        return Collections.emptyList();
      String r2 = role(agendaEdge);
      if (r2 == null)
        return Collections.emptyList();
      String s = prefix + "/" + agendaEdge.getRelation().getName() + "/" + r1 + "/" + r2;
      s = shorten(s);
      if (agendaEdge.getRelation() == stateEdge.getRelation())
        return Arrays.asList(s);
      return Arrays.asList(s, s + "/" + shorten(stateEdge.getRelation().getName()));
    }
  };
  static final PairFeat ARG_LOC_PAIRWISE_FEAT = new PairFeat() {
    @Override
    public String getName() { return "alPW"; }
    @Override
    public List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge) {
      Span s1, s2;
      int mode = 0;
      s1 = arg(stateEdge);
      s2 = arg(agendaEdge);
      if (s1 == null) {
        mode |= (1<<0);
        s1 = target(stateEdge.getTail(0));
      }
      if (s2 == null) {
        mode |= (1<<1);
        s2 = target(agendaEdge.getTail(0));
      }
      if (s1 == null || s2 == null)
        return Collections.emptyList();
      String f1 = BasicFeatureTemplates.spanPosRel(s1, s2);
      String t1 = agendaEdge.getRelation().getName();
      String s = prefix + "/" + mode + "/" + t1 + "/" + f1;
      s = shorten(s);
      if (agendaEdge.getRelation() == stateEdge.getRelation())
        return Arrays.asList(s);
      return Arrays.asList(s, s + "/" + shorten(stateEdge.getRelation().getName()));
    }
  };
  static final PairFeat ARG_LOC_AND_ROLE_COOC = new PairFeat() {
    @Override
    public String getName() {
      return "alrcPW";
    }
    @Override
    public List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge) {
      List<String> a = ARG_LOC_PAIRWISE_FEAT.describe(prefix, stateEdge, agendaEdge);
      int an = a.size();
      if (an == 0)
        return a;
      List<String> b = ROLE_COOC_FEAT.describe(prefix, stateEdge, agendaEdge);
      int bn = b.size();
      if (bn == 0)
        return b;
      List<String> c = new ArrayList<>(an * bn);
      for (int i = 0; i < an; i++)
        for (int j = 0; j < bn; j++)
          c.add(a.get(i) + "*" + b.get(j));
      return c;
    }
  };

  static String frame(HypEdge e) {
    switch (e.getRelation().getName()) {
    case "predicate2":
    case "srl3":
    case "argument4":
      return e.getTail(1).getValue().toString();
    default:
      return null;
    }
  }

  static String role(HypEdge e) {
    switch (e.getRelation().getName()) {
    case "argument4":
      // argument4(t,f,s,k)
      assert e.getNumTails() == 4;
      return (String) e.getTail(3).getValue();
    case "srl3":
      // srl2(t,s)
      assert e.getNumTails() == 3;
      return (String) e.getTail(2).getValue();
    default:
      return null;
    }
  }

  static Span arg(HypEdge e) {
    switch (e.getRelation().getName()) {
    case "argument4":
      // argument4(t,f,s,k)
      assert e.getNumTails() == 4;
      return Span.inverseShortString((String) e.getTail(2).getValue());
    case "srl2":
      // srl2(t,s)
      assert e.getNumTails() == 2;
      return Span.inverseShortString((String) e.getTail(1).getValue());
    default:
      return null;
    }
  }

  static Span target(HypNode t) {
    if (t.getValue() instanceof String)
      return Span.inverseShortString((String) t.getValue());
    return null;
  }

  static String shorten(String longFeatureName) {
    String x = longFeatureName;
    x = x.replaceAll("argument4", "a4");
    x = x.replaceAll("propbank", "pb");
    x = x.replaceAll("framenet", "fn");
    return x;
  }

  /**
   * @param refinementArgPos can be <0 if you don't want a refinement
   */
  public NumArgsRoleCoocArgLoc(String firesFor, int aggregateArgPos, int refinementArgPos, GlobalParams p, Uberts u) {
    Log.info("[main] firesFor=" + firesFor + " agg=" + aggregateArgPos + " ref=" + refinementArgPos);
    this.params = p;
    this.firesFor = firesFor;
    this.firesForHash = Hash.sha256(firesFor);
    this.aggregateArgPos = aggregateArgPos;
    this.refineArgPos = refinementArgPos;

    ExperimentProperties config = ExperimentProperties.getInstance();
    dimension = config.getInt("global.hashDimension", 1 << 25);
    int numIntercept = 0;
    theta = new AveragedPerceptronWeights(dimension, numIntercept);

    this.globalToLocalScale = config.getDouble("globalToLocalScale", globalToLocalScale);
    Log.info("[main] globalToLocalScale=" + globalToLocalScale);

    this.pairwiseFeaturesFunctions = new ArrayList<>();
    if (p.argLocPairwise)
      this.pairwiseFeaturesFunctions.add(ARG_LOC_PAIRWISE_FEAT);
    if (p.roleCooc)
      this.pairwiseFeaturesFunctions.add(ROLE_COOC_FEAT);
    if (p.argLocRoleCooc)
      this.pairwiseFeaturesFunctions.add(ARG_LOC_AND_ROLE_COOC);
    if (p.frameCooc)
      this.pairwiseFeaturesFunctions.add(FRAME_COOC_FEAT);
  }

  static class Spany implements Comparable<Spany> {
    HypEdge e;
    Span arg;
    Span target;
    public Spany(HypNode t) {
      this.target = target(t);
    }
    public Spany(HypEdge e) {
      this.e = e;
      this.arg = arg(e);
    }
    public boolean hasSpan() {
      return arg != null || target != null;
    }
    public Span getSpan() {
      assert (arg == null) != (target == null);
      if (arg != null)
        return arg;
      return target;
    }
    @Override
    public int compareTo(Spany x) {
      int a = getSpan().end;
      int b = x.getSpan().end;
      return a - b;
    }
    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("(Spany " + e);
      if (arg != null)
        sb.append(" arg=" + arg.shortString());
      if (target != null)
        sb.append(" target=" + target.shortString());
      sb.append(')');
      return sb.toString();
    }
  }

  /**
   * Compute global argLoc feature for agendaEdge
   * @param t is the aggregate node
   * @param stateEdge was the last edge added to the state
   * @param agendaEdge is the edge on the agenda being re-scored
   */
//  private List<Integer> argLocGlobal(HypNode t, LL<HypEdge> stateEdge, HypEdge agendaEdge) {
  private List<String> argLocGlobal(HypNode t, LL<HypEdge> stateEdge, HypEdge agendaEdge) {
    events.increment("argLocGlobal");

    // Gather target
    Spany target = new Spany(t);
    if (target.target == null) {
      events.increment("argLocGlobal/failNoTarget");
      return Collections.emptyList();
    }

    // Get the args
    Spany maybeArg = new Spany(agendaEdge);
    if (!maybeArg.hasSpan()) {
      events.increment("argLocGlobal/failNoArg");
      return Collections.emptyList();
    }
    List<Spany> allArgs = new ArrayList<>();
    allArgs.add(maybeArg);
    for (LL<HypEdge> cur = stateEdge; cur != null; cur = cur.next) {
      Spany arg = new Spany(cur.item);
      if (arg.hasSpan()) {
        allArgs.add(arg);
      } else {
        events.increment("argLocGlobal/aggNoSpan");
        return Collections.emptyList();
      }
    }

    // Sort spans left-right
    Collections.sort(allArgs);

    // Compute the hash of every t-a and a-a relation
    int n = allArgs.size();
//    long globalFeatWithT = n;
//    long globalFeatNoT = -(n+1);
    StringBuilder globalFeatSWithT = new StringBuilder("alG1/");
    StringBuilder globalFeatSNoT = new StringBuilder("alG2/");
    Spany aPrev = null;
    for (int i = 0; i < n; i++) {
      if (i > 0) {
        globalFeatSWithT.append('_');
        globalFeatSNoT.append('_');
      }
      Spany a = allArgs.get(i);
      if (aPrev != null) {
        String x = BasicFeatureTemplates.spanPosRel(aPrev.getSpan(), a.getSpan());
//        long x = BasicFeatureTemplates.spanPosRel2(aPrev.getSpan(), a.getSpan()).getProdFeature();
//        globalFeatWithT = Hash.mix64(globalFeatWithT, x);
        globalFeatSWithT.append(x);
        globalFeatSWithT.append('-');
      }
      String x = BasicFeatureTemplates.spanPosRel(target.target, a.getSpan());
//      long x = BasicFeatureTemplates.spanPosRel2(target.target, a.getSpan()).getProdFeature();
//      globalFeatWithT = Hash.mix64(globalFeatWithT, x);
//      globalFeatNoT = Hash.mix64(globalFeatNoT, x);
      globalFeatSWithT.append(x);
      globalFeatSNoT.append(x);
      aPrev = a;
    }

//    String aeh = agendaEdge.getRelation().getName();
    String aeh = agendaEdge.getTail(refineArgPos).getValue().toString();
//    int aeh = agendaEdge.getRelation().getName().hashCode();
//    return Arrays.asList(
//        (int) globalFeatWithT,
//        (int) globalFeatNoT,
//        Hash.mix((int) globalFeatWithT, aeh),
//        Hash.mix((int) globalFeatNoT, aeh));
    List<String> l = new ArrayList<>(4);
    l.add(globalFeatSWithT.toString());
    l.add(globalFeatSNoT.toString());
    globalFeatSWithT.append('/');
    globalFeatSWithT.append(aeh);
    l.add(globalFeatSWithT.toString());
    globalFeatSNoT.append('/');
    globalFeatSNoT.append(aeh);
    l.add(globalFeatSNoT.toString());
    return l;
  }

  private Adjoints rescoreMutable(HypEdge e, Adjoints oldScore, Agenda a, HypEdge srl4Fact, HypNode t, Iterable<HypEdge> affected, LL<HypEdge> existing) {
    oldScore = Adjoints.uncacheIfNeeded(oldScore);
    if (!(oldScore instanceof GlobalFactorAdjoints))
      oldScore = new GlobalFactorAdjoints(e, oldScore, globalToLocalScale);
    GlobalFactorAdjoints gs = (GlobalFactorAdjoints) oldScore;

    if (params.numArgs) {
      String key = "numArgs";
      NumArgAdj adj = (NumArgAdj) gs.getGlobalScore(key);
      if (adj == null) {
        String refinement = refineArgPos < 0 ? "na" : (String) e.getTail(refineArgPos).getValue();
        long refinementHash = Hash.hash(refinement);
        adj = this.new NumArgAdj(firesFor, firesForHash, refinement, refinementHash);
        gs.addToGlobalScore(key, adj);
      }
      adj.incrementNumCommitted();
    }

    // roleCooc and argLocPairwise
    if (!pairwiseFeaturesFunctions.isEmpty()) {
      String key = "pairwise";
      PairwiseAdj pa = (PairwiseAdj) gs.getGlobalScore(key);
      if (pa == null) {
        pa = this.new PairwiseAdj();
        gs.addToGlobalScore(key, pa);
      }
      for (PairFeat f : pairwiseFeaturesFunctions) {
        for (String fx : f.describe(f.getName(), srl4Fact, e)) {
          fx = shorten(fx);
          int idx = featureNames.lookupIndex(fx);
          pa.add(fx, idx);
          if (refineArgPos >= 0) {
            String ref = e.getTail(refineArgPos).getValue().toString();
            String fx2 = fx + "/" + ref;
            fx2 = shorten(fx2);
            int idx2 = featureNames.lookupIndex(fx2);
            pa.add(fx2, idx2);
          }
        }
      }
    }

    if (params.argLocGlobal) {
      String key = "argLocGlobal";
//      List<Integer> argLocGF = argLocGlobal(t, existing, e);
      List<String> argLocGF = argLocGlobal(t, existing, e);
      if (!argLocGF.isEmpty()) {
        boolean reindex = true;
        int[] feats = new int[argLocGF.size()];
        for (int i = 0; i < feats.length; i++)
          feats[i] = featureNames.lookupIndex(argLocGF.get(i));
        Adjoints argLocGA;
        if (useAvg)
          argLocGA = theta.averageView().score(feats, reindex);
        else
          argLocGA = theta.score(feats, reindex);
        gs.replaceGlobalScore(key, argLocGA);
      }
    }
    return gs;
  }

  /**
   * @param affected is an edge on the agenda which needs to be rescored
   * @param oldScore
   * @param a
   * @param srl4Fact
   * @param t
   * @param existing
   * @return
   */
  private Adjoints rescoreImmutable(HypEdge affected, Adjoints oldScore, Agenda a, HypEdge srl4Fact, HypNode t, LL<HypEdge> existing) {

    // Create new adjoints with local score
    GlobalFactorAdjoints gs;
    GlobalFactorAdjoints gsOld;
    oldScore = Adjoints.uncacheIfNeeded(oldScore);
    if (oldScore instanceof GlobalFactorAdjoints) {
      events.increment("rescoreImmutable/oldIsGlobal");
      gsOld = (GlobalFactorAdjoints) oldScore;
      gs = new GlobalFactorAdjoints(affected, gsOld.getLocalScore(), globalToLocalScale);
    } else {
      events.increment("rescoreImmutable/oldIsLocal");
      gsOld = null;
      gs = new GlobalFactorAdjoints(affected, oldScore, globalToLocalScale);
    }

    if (params.numArgs) {
      events.increment("numArgs");
      String key = "numArgs";
      String refinement = refineArgPos < 0 ? "na" : (String) affected.getTail(refineArgPos).getValue();
      long refinementHash = Hash.hash(refinement);
      NumArgAdj adj = this.new NumArgAdj(firesFor, firesForHash, refinement, refinementHash);
      adj.dbgEdge = affected;
      if (gsOld != null) {
        NumArgAdj na = (NumArgAdj) gsOld.getGlobalScore(key);
//        if (NA_DEBUG)
//          Log.info("[numArgs] affected=" + affected + " n=" + (na.numCommitted+1));
        adj.setNumCommitted(na.numCommitted + 1);
      } else {
//        if (NA_DEBUG)
//          Log.info("[numArgs] affected=" + affected + " n=1");
        adj.setNumCommitted(1);
      }
      gs.addToGlobalScore(key, adj);
    }

    // roleCooc and argLocPairwise
    if (!pairwiseFeaturesFunctions.isEmpty()) {
      String key = "pairwise";
      // Compute new pairwise features
      PairwiseAdj pa = this.new PairwiseAdj();
      for (PairFeat f : pairwiseFeaturesFunctions) {
//        List<Integer> fxs = f.describe(srl4Fact, e);
        List<String> fxs = f.describe(f.getName(), srl4Fact, affected);

        events.increment(f.getName());
        if (fxs.isEmpty())
          events.increment(f.getName() + "/noFire");

        for (String fx : fxs) {
          fx = shorten(fx);
          int idx = featureNames.lookupIndex(fx);
          pa.add(fx, idx);
          if (refineArgPos >= 0) {
            String ref = affected.getTail(refineArgPos).getValue().toString();
            String fx2 = fx + "/" + ref;
            fx2 = shorten(fx2);
            int idx2 = featureNames.lookupIndex(fx2);
            pa.add(fx2, idx2);
          }
        }
      }
      if (gsOld != null) {
        // Copy over old ones
        // (as if all of these features were computed at this step)
        Adjoints paOld = gsOld.getGlobalScore(key);
        gs.addToGlobalScore(key, Adjoints.sum(pa, paOld));
      } else {
        // Nothing to copy
        gs.addToGlobalScore(key, pa);
      }
    }

    if (params.argLocGlobal) {
      // Nothing old to copy, just generate new adjoints
      String key = "argLocGlobal";
//      List<Integer> argLocGF = argLocGlobal(t, existing, e);
      List<String> argLocGF = argLocGlobal(t, existing, affected);
      if (!argLocGF.isEmpty()) {
        boolean reindex = true;
        int[] feats = new int[argLocGF.size()];
        for (int i = 0; i < feats.length; i++)
          feats[i] = featureNames.lookupIndex(argLocGF.get(i));
        AveragedPerceptronWeights.Adj argLocGA;
        if (useAvg)
          argLocGA = theta.averageView().score(feats, reindex);
        else
          argLocGA = theta.score(feats, reindex);
        argLocGA.dbgAlphabet = featureNames;
        gs.addToGlobalScore(key, argLocGA);
      }
    }

    return gs;
  }

  @Override
  public void rescore(Uberts u, HypEdge[] trigger) {
    assert trigger.length == 1;
    HypEdge srl4Fact = trigger[0];
    metaRescore(u, srl4Fact);
  }

  private TimeMarker tm = new TimeMarker();
  private void metaRescore(Uberts u, HypEdge srl4Fact) {
    nRescore++;
    Agenda a = u.getAgenda();
    Relation firesForR = u.getEdgeType(firesFor);
    HypNode t = srl4Fact.getTail(aggregateArgPos);
    Iterable<HypEdge> affected = a.match(aggregateArgPos, firesForR, t);

    LL<HypEdge> existing = null;
    if (params.argLocGlobal) {
      State s = u.getState();
      existing = s.match(aggregateArgPos, firesForR, t);  // for ArgLoc global
    }

    int n = 0;
    for (HypEdge e : affected) {
      nEdgeRescore++;
      n++;
      Adjoints oldScore = a.getScore(e);
      a.remove(e);
      Adjoints newScore;
      if (IMMUTABLE_FACTORS)
        newScore = rescoreImmutable(e, oldScore, a, srl4Fact, t, existing);
      else
        newScore = rescoreMutable(e, oldScore, a, srl4Fact, t, affected, existing);
      if (DEBUG > 1) {
        System.out.println("IMMUTABLE_FACTORS=" + IMMUTABLE_FACTORS + " e=" + srl4Fact + " newScore=" + newScore + " oldScore=" + oldScore);
      }
      a.add(e, newScore);
      if (GF_DEBUG && new HashableHypEdge(e).hashCode() % 20 == 0) {
        System.out.println("[rescore] GF_DEBUG " + toString());
      }
    }

    if (tm.enoughTimePassed(60)) {
      System.out.println("showing stats for: " + this.toString());
      int k = 30;
      System.out.println(k + " biggest weights: " + getBiggestWeights(k));
      System.out.println("feature counts: " + featureStats());
      System.out.println("event counts: " + toString());
//      events.clear();
    }

    if (DEBUG > 0) {
      Log.info("rescored " + n + " " + firesFor + " relations connected to " + t);
      if (DEBUG > 1)
        for (HypEdge e : affected)
          System.out.println("\t" + e);
    }
  }

  /**
   * Call this before using this factor if you want to use an Alphabet instead
   * of feature hashing.
   */
  public void storeExactFeatureIndices() {
    if (featureNames == null)
      featureNames = new Alphabet<>();
  }

  private Counts<String> featureStats() {
    Counts<String> c = new Counts<>();
    int n = featureNames.size();
    for (int i = 0; i < n; i++) {
      double w = theta.getWeight(i);
      String f = featureNames.lookupObject(i);
      int s = f.indexOf('/');
      if (s >= 0) {
        String coarse = f.substring(0, s);
        if (Math.abs(w) > 1e-8) {
          c.increment("nnz");
          c.increment("nnz/gf/" + coarse);
          if (w > 0) {
            c.increment("nnz/pos");
            c.increment("nnz/pos/" + coarse);
          } else {
            c.increment("nnz/neg");
            c.increment("nnz/neg/" + coarse);
          }
        }
      }
    }
    return c;
  }

  public List<Pair<String, Double>> getBiggestWeights(int k) {
    if (featureNames == null)
      throw new IllegalStateException("must call storeExactFeatureIndices to use this!");
    List<Pair<String, Double>> l = new ArrayList<>();
    int n = featureNames.size();
    for (int i = 0; i < n; i++) {
      double w = theta.getWeight(i);
      String f = featureNames.lookupObject(i);
      l.add(new Pair<>(f, w));
    }
    Collections.sort(l, new Comparator<Pair<String, Double>>() {
      @Override
      public int compare(Pair<String, Double> o1, Pair<String, Double> o2) {
        double w1 = Math.abs(o1.get2());
        double w2 = Math.abs(o2.get2());
        if (w1 > w2) return -1;
        if (w1 < w2) return +1;
        return 0;
      }
    });
    if (l.size() > k)
      l = l.subList(0, k);
    return l;
  }

  public Term[] getTrigger2(Uberts u) {
    return new Term[] { Term.uniqArguments(u.getEdgeType(firesFor)) };
  }

  public void useAverageWeights(boolean useAvg) {
    Log.info("useAvg " + this.useAvg + " => " + useAvg);
    this.useAvg = useAvg;
  }

  public void completedObservation() {
    theta.completedObservation();
  }

  @Override
  public String getStats() {
    String s = "nRescore=" + nRescore + " nEdgeRescore=" + nEdgeRescore;
    nRescore = 0;
    nEdgeRescore = 0;
    return s;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append('(');
    String[] c = getClass().getName().split("\\.");
    sb.append(c[c.length - 1]);
    sb.append(' ');
//    sb.append(firesFor.getName());
    sb.append(firesFor);
    sb.append(" agg=" + aggregateArgPos);
//    sb.append(':');
//    sb.append(firesFor.getTypeForArg(aggregateArgPos).getName());
    if (refineArgPos >= 0) {
      sb.append(" ref=" + refineArgPos);
//      sb.append(':');
//      sb.append(firesFor.getTypeForArg(refineArgPos).getName());
    }
    sb.append(" " + params);
    sb.append(" " + events);
    sb.append(')');
    return sb.toString();
  }

  @Override
  public String getName() {
    return toString();
  }


  public class PairwiseAdj implements Adjoints {
    List<ProductIndex> features = new ArrayList<>();
    List<String> feats = new ArrayList<>();
    Adjoints aFromTheta;

    public void add(String feat, int index) {
      this.feats.add(feat);
      add(index);
    }

    private void add(int feature) {
      if (feature < 0)
        feature = -feature;
      features.add(new ProductIndex(feature));
    }

    @Override
    public double forwards() {
      if (aFromTheta == null) {
        if (useAvg)
          aFromTheta = theta.averageView().score(features, true);
        else
          aFromTheta = theta.score(features, true);
      }
      return aFromTheta.forwards();
    }

    @Override
    public void backwards(double dErr_dForwards) {
      assert !useAvg;
      aFromTheta.backwards(dErr_dForwards);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("(PA nf=" + features.size());
      if (feats.isEmpty()) {
        for (ProductIndex i : features)
          sb.append(" " + i.getProdFeatureModulo(1 << 12));
      } else {
        for (String fx : feats) {
          sb.append(' ');
          sb.append(fx);
        }
      }
      sb.append(')');
      return sb.toString();
    }
  }

  /**
   * Stores the number of args added already and other needed details like the
   * frame and relation (type of action).
   */
  public class NumArgAdj implements Adjoints {
    String rel;
    String frame;
    long relHash, frameHash;
    int numCommitted;
    Adjoints aFromTheta;

    private int[] feats;
    HypEdge dbgEdge;

    public NumArgAdj(String rel, long relHash, String frame, long frameHash) {
      this.rel = rel;
      this.frame = frame;
      this.relHash = relHash;
      this.frameHash = frameHash;
      this.numCommitted = 0;
    }

    public void incrementNumCommitted() {
      assert false : "this should only be used for mutable updates, which should be disabled";
      numCommitted++;
      aFromTheta = null;
    }

    public void setNumCommitted(int c) {
//      if (NA_DEBUG && c == 5 && frame.equals("propbank/go-v-8"))
//        Log.info("check this");
      if (numCommitted != c) {
        numCommitted = c;
        aFromTheta = null;
      }
    }

    @Override
    public String toString() {
      return "(NArgs " + numCommitted + " r=" + rel + " f=" + frame + ")";
    }

    @Override
    public double forwards() {
      if (aFromTheta == null) {
        int cCoarse = Math.min(8, numCommitted);
        int cFine = Math.min(24, numCommitted);
        if (featureNames != null) {
          int n = featureNames.size();
          feats = new int[] {
              featureNames.lookupIndex(shorten("na1/" + rel + ",F=" + cFine)),
              featureNames.lookupIndex(shorten("na2/" + rel + ",C=" + cCoarse + ",r=" + frame)),
          };
          if (featureNames.size() > n && featureNames.size() % 1000 == 0) {
            Log.info("[memLeak] featureNames.size=" + featureNames.size());
          }
        } else {
          feats = new int[] {
              (int) Hash.mix64(42, relHash, cFine),
              (int) Hash.mix64(9001, relHash, cCoarse, frameHash),
          };
        }
        boolean reindex = true;
        if (useAvg)
          aFromTheta = theta.averageView().score(feats, reindex);
        else
          aFromTheta = theta.score(feats, reindex);
      }
      return aFromTheta.forwards();
    }

    @Override
    public void backwards(double dErr_dForwards) {
      assert !useAvg;
      if (NA_DEBUG) {
        for (int i = 0; i < feats.length; i++) {
          int f = feats[i];
          String fs = featureNames.lookupObject(f);
          double w = theta.getWeight(f);
          System.out.println(fs + " [" + f + "]: w=" + w + " dErr_dForwards=" + dErr_dForwards + "\t" + dbgEdge);
        }
      }
      aFromTheta.backwards(dErr_dForwards);
    }
  }

}
