package edu.jhu.hlt.uberts.factor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;

public class NumArgsRoleCoocArgLoc implements GlobalFactor {
  public static int DEBUG = 0;

  private Relation firesFor;    // e.g. argument4
  private int aggregateArgPos;  // e.g. 0 for t in argument4(t,f,s,k)
  private int refineArgPos;     // e.g. 1 for f in argument4(t,f,s,k), meaning create extra conjunction feature with frame value
  private int dimension;
  private AveragedPerceptronWeights theta;
  private Alphabet<String> featureNames;  // for debugging

  // Each is called on f(newEdge,fOldEdge) where each argument is a firesFor Relation
  private List<PairFeat> pairwiseFeatures;

  // TODO Take this as an argument
  private Uberts u;

  public boolean numArgs = true;
  public boolean argLocPairwise = true;
  public boolean argLocGlobal = false;
  public boolean roleCooc = true;

  interface PairFeat {
    String getName();
    List<Integer> describe(HypEdge stateEdge, HypEdge agendaEdge);
  }

  static final PairFeat ROLE_COOC_FEAT = new PairFeat() {
    int salt = 15_485_863;
    public String getName() { return "rc"; }
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
    public String getName() { return "al"; }
    public List<Integer> describe(HypEdge stateEdge, HypEdge agendaEdge) {
      Span s1 = arg(stateEdge);
      if (s1 == null)
        return Collections.emptyList();
      Span s2 = arg(agendaEdge);
      if (s2 == null)
        return Collections.emptyList();
      int f1 = BasicFeatureTemplates.spanPosRel2(s1, s2).getProdFeatureSafe();
      int t1 = agendaEdge.getRelation().getName().hashCode();
      int t2 = stateEdge.getRelation().getName().hashCode();
      return Arrays.asList(
          Hash.mix(salt, t1, f1),
          Hash.mix(salt, t1, f1, t2));
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
    this.u = u;
    this.firesFor = firesFor;
    this.aggregateArgPos = aggregateArgPos;
    this.refineArgPos = refinementArgPos;
    dimension = 1<<16;
    int numIntercept = 0;
    theta = new AveragedPerceptronWeights(dimension, numIntercept);

    argLocGlobal &= firesFor.getName().equals("argument4");
    argLocPairwise &= Arrays.asList("argument4", "srl2").contains(firesFor.getName());

    this.pairwiseFeatures = new ArrayList<>();
    if (argLocPairwise)
      this.pairwiseFeatures.add(ARG_LOC_PAIRWISE_FEAT);
    if (roleCooc)
      this.pairwiseFeatures.add(ROLE_COOC_FEAT);
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

  public TKey[] getTrigger(Uberts u) {
    return new TKey[] {
        new TKey(State.HEAD_ARG_POS, firesFor),
    };
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

//  static List<Integer> argLocGlobal(HypNode t, LL<HypEdge> stateEdge, HypEdge newStateEdge, HypEdge agendaEdge) {
  static List<Integer> argLocGlobal(HypNode t, LL<HypEdge> stateEdge, HypEdge agendaEdge) {
    // Gather target
    Spany target = new Spany(t);
    if (target.target == null)
      return Collections.emptyList();

    // Get the args
//    Spany newArg = new Spany(newStateEdge);
//    if (newArg.arg == null)
//      return Collections.emptyList();
    Spany maybeArg = new Spany(agendaEdge);
    if (maybeArg.arg == null)
      return Collections.emptyList();
    List<Spany> allArgs = new ArrayList<>();
//    allArgs.add(newArg);
    allArgs.add(maybeArg);
    for (LL<HypEdge> cur = stateEdge; cur != null; cur = cur.next) {
      Spany arg = new Spany(cur.item);
      if (arg.arg == null)
        return Collections.emptyList();
      allArgs.add(arg);
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
        long x = BasicFeatureTemplates.spanPosRel2(aPrev.arg, a.arg).getProdFeature();
        globalFeatWithT = Hash.mix64(globalFeatWithT, x);
      }
      long x = BasicFeatureTemplates.spanPosRel2(target.target, a.arg).getProdFeature();
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


  @Override
  public void rescore(Agenda a, GraphTraversalTrace match) {
    HypEdge srl4Fact = match.getBoundEdge(0);
    HypNode t = srl4Fact.getTail(aggregateArgPos);
    Iterable<HypEdge> affected = a.match(aggregateArgPos, firesFor, t);

    LL<HypEdge> existing = null;
    if (argLocGlobal)
      existing = u.getState().match(aggregateArgPos, firesFor, t);  // for ArgLoc global

    int n = 0;
    for (HypEdge e : affected) {
      n++;
      Adjoints score = a.getScore(e);
      a.remove(e);
      if (!(score instanceof GlobalFactorAdjoints))
        score = new GlobalFactorAdjoints(score);
      GlobalFactorAdjoints gs = (GlobalFactorAdjoints) score;

      { // NumArgs
        Adj adj = (Adj) gs.getGlobalScore("numArgs");
        if (adj == null) {
          String refinement = refineArgPos < 0 ? "na" : (String) e.getTail(refineArgPos).getValue();
          adj = this.new Adj(firesFor, refinement);
          gs.addToGlobalScore("numArgs", adj);
        }
        if (numArgs) {
          adj.incrementNumCommitted();
          a.add(e, gs);
        }
      }

      // roleCooc and argLocPairwise
      if (!pairwiseFeatures.isEmpty()) {
        PairwiseAdj pa = (PairwiseAdj) gs.getGlobalScore("pairwise");
        if (pa == null) {
          pa = this.new PairwiseAdj();
          gs.addToGlobalScore("pairwise", pa);
        }
        for (PairFeat f : pairwiseFeatures)
          for (Integer fx : f.describe(srl4Fact, e))
            pa.add(fx);
      }

      // ArgLoc global
      if (argLocGlobal) {
//        List<Integer> argLocGF = argLocGlobal(t, existing, srl4Fact, e);
        List<Integer> argLocGF = argLocGlobal(t, existing, e);
        boolean reindex = true;
        Adjoints argLocGA = theta.score2(argLocGF, reindex);
        gs.replaceGlobalScore("argLocGlobal", argLocGA);
      }
    }
    if (DEBUG > 0) {
      Log.info("rescored " + n + " " + firesFor.getName() + " relations connected to " + t);
      if (DEBUG > 1)
        for (HypEdge e : affected)
          System.out.println("\t" + e);
    }
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
      if (aFromTheta == null)
        aFromTheta = theta.score(features, true);
      return aFromTheta.forwards();
    }

    @Override
    public void backwards(double dErr_dForwards) {
      aFromTheta.backwards(dErr_dForwards);
    }
  }

  /**
   * Stores the number of args added already and other needed details like the
   * frame and relation (type of action).
   */
  public class Adj implements Adjoints {
    Relation rel;
    String frame;
    int numCommitted;
    Adjoints aFromTheta;

    public Adj(Relation rel, String frame) {
      this.rel = rel;
      this.frame = frame;
      this.numCommitted = 0;
    }

    public void incrementNumCommitted() {
      numCommitted++;
      aFromTheta = null;
    }

    @Override
    public double forwards() {
      if (aFromTheta == null) {
        int c = Math.min(6, numCommitted);
        if (featureNames != null) {
          int[] feats = new int[] {
              featureNames.lookupIndex(rel.getName() + ",numArgs=" + c),
              featureNames.lookupIndex(rel.getName() + ",numArgs=" + c + ",refinement=" + frame),
          };
          boolean reindex = false;
          aFromTheta = theta.score(feats, reindex);
        } else {
          int r = rel.hashCode();
          int f = frame.hashCode();
          int[] feats = new int[] {
              Hash.mix(r, c),
              Hash.mix(r, c, f),
          };
          boolean reindex = true;
          aFromTheta = theta.score(feats, reindex);
        }
      }
      return aFromTheta.forwards();
    }

    @Override
    public void backwards(double dErr_dForwards) {
      aFromTheta.backwards(dErr_dForwards);
    }
  }
}
