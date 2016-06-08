package edu.jhu.hlt.uberts.factor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Term;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;

public class NumArgsRoleCoocArgLoc implements GlobalFactor {
  public static int DEBUG = 0;

  public static boolean GF_DEBUG = false;

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

  private Uberts u;
  private Relation firesFor;    // e.g. argument4
  private int aggregateArgPos;  // e.g. 0 for t in argument4(t,f,s,k)
  private int refineArgPos;     // e.g. 1 for f in argument4(t,f,s,k), meaning create extra conjunction feature with frame value
  private int dimension;
  private AveragedPerceptronWeights theta;
  private boolean useAvg = false;
  private Alphabet<String> featureNames;  // for debugging

  private Counts<String> events = new Counts<>();

  private double globalToLocalScale = 0.25;

  // Each is called on f(newEdge,fOldEdge) where each argument is a firesFor Relation
  private List<PairFeat> pairwiseFeaturesFunctions;

  // Stats
  private int nRescore = 0;
  private int nEdgeRescore = 0;

  public static boolean numArgs = true;
  public static boolean argLocPairwise = true;
  public static boolean argLocGlobal = true;
  public static boolean roleCooc = true;
  interface PairFeat {
    String getName();
    List<Integer> describe(HypEdge stateEdge, HypEdge agendaEdge);
  }

  static final PairFeat ROLE_COOC_FEAT = new PairFeat() {
    int salt = 15_485_863;
    public String getName() { return "roleCoocPW"; }
    public List<Integer> describe(HypEdge stateEdge, HypEdge agendaEdge) {
      String r1 = role(stateEdge);
      if (r1 == null)
        return Collections.emptyList();
      String r2 = role(agendaEdge);
      if (r2 == null)
        return Collections.emptyList();
      int r1i = r1.hashCode();
      int r2i = r2.hashCode();
      int t1 = agendaEdge.getRelation().getName().hashCode();
      int t2 = stateEdge.getRelation().getName().hashCode();
      return Arrays.asList(
          Hash.mix(salt, t1, r1i, r2i),
          Hash.mix(salt, t1, r1i, r2i, t2));
    }
  };
  static final PairFeat ARG_LOC_PAIRWISE_FEAT = new PairFeat() {
    int salt = 982_451_653;
    public String getName() { return "argLocPW"; }
    public List<Integer> describe(HypEdge stateEdge, HypEdge agendaEdge) {
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
      int f1 = BasicFeatureTemplates.spanPosRel2(s1, s2).getProdFeatureSafe();
      int t1 = agendaEdge.getRelation().getName().hashCode();
      int t2 = stateEdge.getRelation().getName().hashCode();
      return Arrays.asList(
          Hash.mix(salt, mode, t1, f1),
          Hash.mix(salt, mode, t1, f1, t2));
    }
  };

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

  /**
   * @param refinementArgPos can be <0 if you don't want a refinement
   */
  public NumArgsRoleCoocArgLoc(Relation firesFor, int aggregateArgPos, int refinementArgPos, Uberts u) {
    ExperimentProperties config = ExperimentProperties.getInstance();
    this.u = u;
    this.firesFor = firesFor;
    this.aggregateArgPos = aggregateArgPos;
    this.refineArgPos = refinementArgPos;
    dimension = config.getInt("hashDimension", 1 << 19);
    int numIntercept = 0;
    theta = new AveragedPerceptronWeights(dimension, numIntercept);

    Log.info("[main] firesFor=" + firesFor.getName() + " agg=" + aggregateArgPos + " ref=" + refinementArgPos);

    this.globalToLocalScale = config.getDouble("globalToLocalScale", globalToLocalScale);
    Log.info("[main] globalToLocalScale=" + globalToLocalScale);
//    argLocGlobal &= firesFor.getName().equals("argument4");
//    argLocPairwise &= Arrays.asList("argument4", "srl2").contains(firesFor.getName());

    this.pairwiseFeaturesFunctions = new ArrayList<>();
    if (argLocPairwise)
      this.pairwiseFeaturesFunctions.add(ARG_LOC_PAIRWISE_FEAT);
    if (roleCooc)
      this.pairwiseFeaturesFunctions.add(ROLE_COOC_FEAT);
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
  private List<Integer> argLocGlobal(HypNode t, LL<HypEdge> stateEdge, HypEdge agendaEdge) {
    events.increment("argLocGlobal");

    // Gather target
    Spany target = new Spany(t);
//    if (!target.hasSpan()) {
    if (target.target == null) {
      events.increment("argLocGlobal/failNoTarget");
      return Collections.emptyList();
    }

    // Get the args
    Spany maybeArg = new Spany(agendaEdge);
    if (!maybeArg.hasSpan()) {
//    if (maybeArg.arg == null) {
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
    long globalFeatWithT = n;
    long globalFeatNoT = -(n+1);
    Spany aPrev = null;
    for (int i = 0; i < n; i++) {
      Spany a = allArgs.get(i);
      if (aPrev != null) {
        long x = BasicFeatureTemplates.spanPosRel2(aPrev.getSpan(), a.getSpan()).getProdFeature();
        globalFeatWithT = Hash.mix64(globalFeatWithT, x);
      }
      long x = BasicFeatureTemplates.spanPosRel2(target.target, a.getSpan()).getProdFeature();
      globalFeatWithT = Hash.mix64(globalFeatWithT, x);
      globalFeatNoT = Hash.mix64(globalFeatNoT, x);
      aPrev = a;
    }

    int aeh = agendaEdge.getRelation().getName().hashCode();
    return Arrays.asList(
        (int) globalFeatWithT,
        (int) globalFeatNoT,
        Hash.mix((int) globalFeatWithT, aeh),
        Hash.mix((int) globalFeatNoT, aeh));
  }

  private Adjoints rescoreMutable(HypEdge e, Adjoints oldScore, Agenda a, HypEdge srl4Fact, HypNode t, Iterable<HypEdge> affected, LL<HypEdge> existing) {
    if (!(oldScore instanceof GlobalFactorAdjoints))
      oldScore = new GlobalFactorAdjoints(oldScore, globalToLocalScale);
    GlobalFactorAdjoints gs = (GlobalFactorAdjoints) oldScore;

    if (numArgs) {
      String key = "numArgs";
      NumArgAdj adj = (NumArgAdj) gs.getGlobalScore(key);
      if (adj == null) {
        String refinement = refineArgPos < 0 ? "na" : (String) e.getTail(refineArgPos).getValue();
        adj = this.new NumArgAdj(firesFor, refinement);
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
      for (PairFeat f : pairwiseFeaturesFunctions)
        for (Integer fx : f.describe(srl4Fact, e))
          pa.add(fx);
    }

    if (argLocGlobal) {
      String key = "argLocGlobal";
      List<Integer> argLocGF = argLocGlobal(t, existing, e);
      if (!argLocGF.isEmpty()) {
        boolean reindex = true;
        Adjoints argLocGA;
        if (useAvg)
          argLocGA = theta.averageView().score2(argLocGF, reindex);
        else
          argLocGA = theta.score2(argLocGF, reindex);
        gs.replaceGlobalScore(key, argLocGA);
      }
    }
    return gs;
  }

  private Adjoints rescoreImmutable(HypEdge e, Adjoints oldScore, Agenda a, HypEdge srl4Fact, HypNode t, Iterable<HypEdge> affected, LL<HypEdge> existing) {

    // Create new adjoints with local score
    GlobalFactorAdjoints gs;
    GlobalFactorAdjoints gsOld;
    if (oldScore instanceof GlobalFactorAdjoints) {
      gsOld = (GlobalFactorAdjoints) oldScore;
      gs = new GlobalFactorAdjoints(gsOld.getLocalScore(), globalToLocalScale);
    } else {
      gsOld = null;
      gs = new GlobalFactorAdjoints(oldScore, globalToLocalScale);
    }

    if (numArgs) {
      events.increment("numArgs");
      String key = "numArgs";
      String refinement = refineArgPos < 0 ? "na" : (String) e.getTail(refineArgPos).getValue();
      NumArgAdj adj = this.new NumArgAdj(firesFor, refinement);
      if (gsOld != null) {
        NumArgAdj na = (NumArgAdj) gsOld.getGlobalScore(key);
        adj.setNumCommitted(na.numCommitted + 1);
      } else {
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
        List<Integer> fxs = f.describe(srl4Fact, e);

        events.increment(f.getName());
        if (fxs.isEmpty())
          events.increment(f.getName() + "/noFire");

        for (Integer fx : fxs)
          pa.add(fx);
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

    if (argLocGlobal) {
      // Nothing old to copy, just generate new adjoints
      String key = "argLocGlobal";
      List<Integer> argLocGF = argLocGlobal(t, existing, e);
      if (!argLocGF.isEmpty()) {
        boolean reindex = true;
        Adjoints argLocGA;
        if (useAvg)
          argLocGA = theta.averageView().score2(argLocGF, reindex);
        else
          argLocGA = theta.score2(argLocGF, reindex);
        gs.addToGlobalScore(key, argLocGA);
      }
    }

    if (events.getTotalCount() > 75000) {
      System.out.println("event counts: " + toString());
      events.clear();
    }

    return gs;
  }

  @Override
  public void rescore(Agenda a, GraphTraversalTrace match) {
    HypEdge srl4Fact = match.getBoundEdge(0);
    metaRescore(a, srl4Fact);
  }

  @Override
  public void rescore3(Agenda a, State s, HypEdge[] trigger) {
    assert trigger.length == 1;
    HypEdge srl4Fact = trigger[0];
    metaRescore(a, srl4Fact);
  }

  private void metaRescore(Agenda a, HypEdge srl4Fact) {
    nRescore++;
    HypNode t = srl4Fact.getTail(aggregateArgPos);
    Iterable<HypEdge> affected = a.match(aggregateArgPos, firesFor, t);

    LL<HypEdge> existing = null;
    if (argLocGlobal)
      existing = u.getState().match(aggregateArgPos, firesFor, t);  // for ArgLoc global

    int n = 0;
    for (HypEdge e : affected) {
      nEdgeRescore++;
      n++;
      Adjoints oldScore = a.getScore(e);
      a.remove(e);
      Adjoints newScore;
      if (IMMUTABLE_FACTORS)
        newScore = rescoreImmutable(e, oldScore, a, srl4Fact, t, affected, existing);
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

    if (DEBUG > 0) {
      Log.info("rescored " + n + " " + firesFor.getName() + " relations connected to " + t);
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
        if (w2 < w2) return +1;
        return 0;
      }
    });
    if (l.size() > k)
      l = l.subList(0, k);
    return l;
  }

  public Term[] getTrigger2() {
    return new Term[] { Term.uniqArguments(firesFor) };
  }

  public TKey[] getTrigger(Uberts u) {
    return new TKey[] {
        new TKey(State.HEAD_ARG_POS, firesFor),
    };
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
    sb.append(firesFor.getName());
    sb.append(" agg=" + aggregateArgPos);
    sb.append(':');
    sb.append(firesFor.getTypeForArg(aggregateArgPos).getName());
    if (refineArgPos >= 0) {
      sb.append(" ref=" + refineArgPos);
      sb.append(':');
      sb.append(firesFor.getTypeForArg(refineArgPos).getName());
    }
    if (numArgs) sb.append(" +numArgs");
    if (argLocPairwise) sb.append(" +argLocPairwise");
    if (argLocGlobal) sb.append(" +argLocGlobal");
    if (roleCooc) sb.append(" +roleCooc");
    sb.append(" " + events.toString());
    sb.append(')');
    return sb.toString();
  }

  @Override
  public String getName() {
    return toString();
  }


  public class PairwiseAdj implements Adjoints {
    List<ProductIndex> features = new ArrayList<>();
    Adjoints aFromTheta;

    public void add(int feature) {
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
      for (ProductIndex i : features)
        sb.append(" " + i.getProdFeatureModulo(1 << 12));
      sb.append(')');
      return sb.toString();
    }
  }

  /**
   * Stores the number of args added already and other needed details like the
   * frame and relation (type of action).
   */
  public class NumArgAdj implements Adjoints {
    Relation rel;
    String frame;
    int numCommitted;
    Adjoints aFromTheta;

    public NumArgAdj(Relation rel, String frame) {
      this.rel = rel;
      this.frame = frame;
      this.numCommitted = 0;
    }

    public void incrementNumCommitted() {
      numCommitted++;
      aFromTheta = null;
    }

    public void setNumCommitted(int c) {
      if (numCommitted != c) {
        numCommitted = c;
        aFromTheta = null;
      }
    }

    @Override
    public String toString() {
      return "(NArgs " + numCommitted + " r=" + rel.getName() + " f=" + frame + ")";
    }

    @Override
    public double forwards() {
      if (aFromTheta == null) {
        int c = Math.min(6, numCommitted);
        int[] feats;
        if (featureNames != null) {
          feats = new int[] {
              featureNames.lookupIndex(rel.getName() + ",numArgs=" + c),
              featureNames.lookupIndex(rel.getName() + ",numArgs=" + c + ",refinement=" + frame),
          };
        } else {
          int r = rel.hashCode();
          int f = frame.hashCode();
          feats = new int[] {
              Hash.mix(r, c),
              Hash.mix(r, c, f),
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
      aFromTheta.backwards(dErr_dForwards);
    }
  }

}
