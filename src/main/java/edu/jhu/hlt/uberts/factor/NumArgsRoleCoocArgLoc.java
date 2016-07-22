package edu.jhu.hlt.uberts.factor;

import java.io.BufferedWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
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
import edu.jhu.hlt.uberts.auto.UbertsLearnPipeline;
import edu.jhu.hlt.uberts.features.DebugFeatureAdj;
import edu.jhu.hlt.uberts.srl.EdgeUtils;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;

public class NumArgsRoleCoocArgLoc implements GlobalFactor {
  public static int DEBUG = 0;
  public static boolean GF_DEBUG = false;
  public static boolean NA_DEBUG = false;

  // Print which edges (on the agenda) are being re-scored on account of other
  // edges (recently added to the state)
  public static boolean SHOW_GLOBAL_FEAT_COMMUNICATION = false;

  public static final boolean SIMPLE_SPAN_POS_REL = true;

  public static final boolean USING_PROPBANK;
  public static final boolean ALLOW_ROLE_COOC_FRAME_REFINEMENT;
  public static final int MIN_BETWEEN_BIGGEST_WEIGHTS;

  static {
    ExperimentProperties config = ExperimentProperties.getInstance();
    String t = config.getString("train.facts"); // see UbertsLearnPipeline
    boolean pb = t.contains("propbank");
    boolean fn = t.contains("framenet");
    assert pb != fn;
    USING_PROPBANK = pb;
    ALLOW_ROLE_COOC_FRAME_REFINEMENT = USING_PROPBANK;
    Log.info("[main] USING_PROPBANK=" + USING_PROPBANK + " ALLOW_ROLE_COOC_FRAME_REFINEMENT=" + ALLOW_ROLE_COOC_FRAME_REFINEMENT);
    MIN_BETWEEN_BIGGEST_WEIGHTS = config.getInt("minBetweenBiggestWeights", 10);
  }

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
        assert i.indexOf(' ') < 0 && i.indexOf('\t') < 0
            : "did you proivde a whitespace separated list when you wanted comma-separated? " + Arrays.toString(vals);
        GlobalParams gp = new GlobalParams(i);
        Object old = name2params.put(gp.name, gp);
        assert old == null;
      }
    }

    public GlobalParams getOrAddDefault(String name) {
      GlobalParams gp = name2params.get(name);
      if (gp == null) {
        Log.info("[main] " + name);
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

  public interface PairFeat {
    String getName();
    List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge);

    default public boolean allowRefinement() {
      return true;
    }

    default public boolean isFeatureOfMine(String name) {
      return name.startsWith(getName());
    }

    default public void refineByEdgeTypes(HypEdge stateEdge, HypEdge agendaEdge, List<String> feats, boolean backoff) {
      if (agendaEdge.getRelation() != stateEdge.getRelation()) {
        String m = "eRel=" + agendaEdge.getRelation().getName()
            + "_" + stateEdge.getRelation().getName();
        m = shorten(m);
        int n = feats.size();
        if (backoff) {
          for (int i = 0; i < n; i++)
            feats.add(feats.get(i) + "/" + m);
        } else {
          for (int i = 0; i < n; i++)
            feats.set(i, feats.get(i) + "/" + m);
        }
      }
    }

    default public void refineByTargets(HypEdge stateEdge, HypEdge agendaEdge, List<String> feats, boolean backoff) {
      Span ts = EdgeUtils.target(stateEdge);
      Span ta = EdgeUtils.target(agendaEdge);
      String m = null;
      if (ts != null && ta != null)
        m = "tRel=" + BasicFeatureTemplates.spanPosRel(ts, ta, SIMPLE_SPAN_POS_REL);
      else if (ts != null)
        m = "tRel=s";
      else if (ta != null)
        m = "tRel=a";
      if (m != null) {
        int n = feats.size();
        if (backoff) {
          for (int i = 0; i < n; i++)
            feats.add(feats.get(i) + "/" + m);
        } else {
          for (int i = 0; i < n; i++)
            feats.set(i, feats.get(i) + "/" + m);
        }
      }
    }
  }

  public static final PairFeat FRAME_COOC_FEAT = new PairFeat() {
    private boolean allowDiffTargets = true;
    private boolean targetRelBackoff = true;
    private boolean edgeRelBackoff = false;
    @Override
    public String getName() { return "fcPW"; }
    @Override
    public List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge) {
      Span ts = EdgeUtils.target(stateEdge);
      Span ta = EdgeUtils.target(agendaEdge);
      if (!allowDiffTargets && ts != null && !ts.equals(ta))
        return Collections.emptyList();

      String f1 = EdgeUtils.frame(stateEdge);
      String f2 = EdgeUtils.frame(agendaEdge);
      if (f1 == null || f2 == null)
        return Collections.emptyList();

      String s = prefix + "/" + agendaEdge.getRelation().getName() + "/" + f1 + "/" + f2;
      s = shorten(s);
      List<String> feats = new ArrayList<>();
      feats.add(s);
      if (allowDiffTargets)
        refineByTargets(stateEdge, agendaEdge, feats, targetRelBackoff);
      refineByEdgeTypes(stateEdge, agendaEdge, feats, edgeRelBackoff);
      return feats;
    }
  };
  public static final PairFeat ROLE_COOC_FEAT = new PairFeat() {
    private boolean allowDiffTargets = false;
    private boolean targetRelBackoff = false;
    private boolean edgeRelBackoff = false;
    @Override
    public String getName() { return "rcPW"; }
    @Override
    public List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge) {
      Span ts = EdgeUtils.target(stateEdge);
      Span ta = EdgeUtils.target(agendaEdge);
      if (!allowDiffTargets && ts != null && !ts.equals(ta))
        return Collections.emptyList();

      String r1 = EdgeUtils.role(stateEdge);
      String r2 = EdgeUtils.role(agendaEdge);
      if (r1 == null || r2 == null)
        return Collections.emptyList();

      String s = prefix + "/" + agendaEdge.getRelation().getName() + "/A=" + r1 + "/a=" + r2;
      s = shorten(s);
      List<String> feats = new ArrayList<>();
      feats.add(s);
      if (allowDiffTargets)
        refineByTargets(stateEdge, agendaEdge, feats, targetRelBackoff);
      refineByEdgeTypes(stateEdge, agendaEdge, feats, edgeRelBackoff);
      return feats;
    }
    @Override
    public boolean allowRefinement() {
      return ALLOW_ROLE_COOC_FRAME_REFINEMENT;
    }
  };
  public static final PairFeat ARG_LOC_PAIRWISE_FEAT = new PairFeat() {
    private boolean allowDiffTargets = false;
    private boolean targetRelBackoff = false;
    private boolean edgeRelBackoff = false;
    private boolean includeTa = true;
    private boolean includeTaBackoff = true;
    @Override
    public String getName() { return "alPW"; }
    @Override
    public List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge) {
      Span ts = EdgeUtils.target(stateEdge);
      Span ta = EdgeUtils.target(agendaEdge);
      if (!allowDiffTargets && ts != null && !ts.equals(ta))
        return Collections.emptyList();

      Span s1 = EdgeUtils.arg(stateEdge);
      Span s2 = EdgeUtils.arg(agendaEdge);
      if (s1 == null || s2 == null)
        return Collections.emptyList();

      String f1 = "Aa=" + BasicFeatureTemplates.spanPosRel(s1, s2, SIMPLE_SPAN_POS_REL);
      String t1 = agendaEdge.getRelation().getName();
      String s = prefix + "/" + t1 + "/" + f1;
      s = shorten(s);

      List<String> feats = new ArrayList<>();
      feats.add(s);

      // loc(A,a) * loc(T,a)
      if (includeTa && ts != null) {
        String ss = s + "/Ta=" + BasicFeatureTemplates.spanPosRel(ts, s2, SIMPLE_SPAN_POS_REL);
        if (includeTaBackoff) {
          feats.add(ss);
        } else {
          assert feats.size() == 1;
          feats.set(0, ss);
        }
      }

      if (allowDiffTargets)
        refineByTargets(stateEdge, agendaEdge, feats, targetRelBackoff);
      refineByEdgeTypes(stateEdge, agendaEdge, feats, edgeRelBackoff);
      return feats;
    }
  };
  public static final PairFeat ARG_LOC_AND_ROLE_COOC = new PairFeat() {
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
    @Override
    public boolean allowRefinement() {
      return false;
    }
  };

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
    static Span target(HypNode t) {
      if (t.getValue() instanceof String)
        return Span.inverseShortString((String) t.getValue());
      return null;
    }

    HypEdge e;
    Span arg;
    Span target;
    public Spany(HypNode t) {
      this.target = target(t);
    }
    public Spany(HypEdge e) {
      this.e = e;
      this.arg = EdgeUtils.arg(e);
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

  private List<String> argLocGlobal(HypNode t, LL<HypEdge> stateEdgesInGroup, HypEdge agendaEdge) {
    events.increment("argLocGlobal");

    // SANITY CHECK: These should all share the same target
    Span tg = EdgeUtils.target(agendaEdge);
    assert tg != null;
    for (LL<HypEdge> cur = stateEdgesInGroup; cur != null; cur = cur.next)
      assert tg == EdgeUtils.target(cur.item);

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
    for (LL<HypEdge> cur = stateEdgesInGroup; cur != null; cur = cur.next) {
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
        String x = "AA" + i + "=" + BasicFeatureTemplates.spanPosRel(aPrev.getSpan(), a.getSpan(), SIMPLE_SPAN_POS_REL);
        globalFeatSWithT.append(x);
        globalFeatSWithT.append('-');
      }
      String x = "TA" + i + "=" + BasicFeatureTemplates.spanPosRel(target.target, a.getSpan(), SIMPLE_SPAN_POS_REL);
      globalFeatSWithT.append(x);
      globalFeatSNoT.append(x);
      aPrev = a;
    }

    String aeh = agendaEdge.getTail(refineArgPos).getValue().toString();
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

  private Adjoints rescoreImmutable(HypEdge agendaEdge, Adjoints oldScore, Agenda a, HypEdge stateEdge, HypNode t, LL<HypEdge> otherStateEdgesInGroup) {
    String base = UbertsLearnPipeline.isNilFact(agendaEdge) ? "n" : "s";

    // Create new adjoints with local score
    GlobalFactorAdjoints gs;
    GlobalFactorAdjoints gsOld;
    oldScore = Adjoints.uncacheIfNeeded(oldScore);
    if (oldScore instanceof GlobalFactorAdjoints) {
      events.increment("rescoreImmutable/oldIsGlobal");
      gsOld = (GlobalFactorAdjoints) oldScore;
      gs = new GlobalFactorAdjoints(agendaEdge, gsOld.getLocalScore(), globalToLocalScale);
    } else {
      events.increment("rescoreImmutable/oldIsLocal");
      gsOld = null;
      gs = new GlobalFactorAdjoints(agendaEdge, oldScore, globalToLocalScale);
    }

    if (params.numArgs) {
      events.increment("numArgs");
      String key = "numArgs";
      String refinement = refineArgPos < 0 ? "na" : (String) agendaEdge.getTail(refineArgPos).getValue();
      long refinementHash = Hash.hash(refinement);
      NumArgAdj adj = this.new NumArgAdj(firesFor, firesForHash, refinement, refinementHash);
      adj.dbgEdge = agendaEdge;
      if (gsOld != null) {
        NumArgAdj na = (NumArgAdj) gsOld.getGlobalScore(key);
        adj.setNumCommitted(na.numCommitted + 1);
      } else {
        adj.setNumCommitted(1);
      }
      gs.addToGlobalScore(key, adj);
    }

    String frame = EdgeUtils.frame(agendaEdge);
    String role = EdgeUtils.role(agendaEdge);

    // roleCooc and argLocPairwise
    if (!pairwiseFeaturesFunctions.isEmpty()) {
      String key = "pairwise";
      // Compute new pairwise features
      List<String> fyx = new ArrayList<>();
      List<String> fx = new ArrayList<>();
      List<String> fy = Arrays.asList("1", "fk", "k");

      // INTERCEPT
//      fx.add(base);

//      PairwiseAdj pa = this.new PairwiseAdj();
      for (PairFeat f : pairwiseFeaturesFunctions) {
        List<String> fxs = f.describe(f.getName() + "/" + base, stateEdge, agendaEdge);
        for (String foo : fxs) {
          fx.add(foo);
          // Do this with strings rather than hash(f(y)) * alph(f(x)) so that
          // we can look up what weights are.
          fyx.add(foo);
          fyx.add(foo + "/" + frame + "-" + role);
          fyx.add(foo + "/" + role);
        }

        events.increment(f.getName());
        if (fxs.isEmpty())
          events.increment(f.getName() + "/noFire");

//        for (String fx : fxs) {
//          fx = shorten(fx);
//          int idx = featureNames.lookupIndex(fx);
//          pa.add(fx, idx);
//
//          if (refineArgPos >= 0 && f.allowRefinement()) {
//            String ref = agendaEdge.getTail(refineArgPos).getValue().toString();
//            ref = shorten(ref);
//            String fx2 = fx + "/" + ref;
//            int idx2 = featureNames.lookupIndex(fx2);
//            pa.add(fx2, idx2);
//          }
//        }

      }

//      int[] fxi = new int[fx.size()];
//      for (int i = 0; i < fxi.length; i++)
//        fxi[i] = featureNames.lookupIndex(fx.get(i));
//      int rK = Hash.hash(role);
//      int rFK = Hash.mix(Hash.hash(frame), rK);
//      int[] fyx = new int[fxi.length * 3];
//      for (int i = 0; i < fxi.length; i++) {
//        fyx[3 * i + 0] = fxi[i];
//        fyx[3 * i + 1] = Hash.mix(rK, fxi[i]);
//        fyx[3 * i + 2] = Hash.mix(rFK, fxi[i]);
//      }
      int[] fyxi = new int[fyx.size()];
      for (int i = 0; i < fyxi.length; i++)
        fyxi[i] = featureNames.lookupIndex(fyx.get(i));
      boolean reindex = true;
      Adjoints pa = useAvg ? theta.averageView().score(fyxi, reindex) : theta.score(fyxi, reindex);
      if (Uberts.LEARN_DEBUG)
        pa = new DebugFeatureAdj(pa, fy, fx, "GLOBAL: agenda=" + agendaEdge + " state=" + stateEdge);

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
      List<String> argLocGF = argLocGlobal(t, otherStateEdgesInGroup, agendaEdge);
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

  private List<Object> groupKey(HypEdge f) {
    // (t,k)
    assert f.getRelation().getName().equals("argument4");
    List<Object> key = new ArrayList<>(2);
    key.add(f.getTail(0));
    key.add(f.getTail(3));
    return key;
  }

  private TimeMarker tm = new TimeMarker();
  private void metaRescore(Uberts u, HypEdge stateEdge) {
    assert !UbertsLearnPipeline.isNilFact(stateEdge);
    nRescore++;
    Agenda a = u.getAgenda();
    Relation firesForR = u.getEdgeType(firesFor);
    HypNode t = stateEdge.getTail(aggregateArgPos);
    Iterable<HypEdge> affected = a.match(aggregateArgPos, firesForR, t);

    // NOTE: This should include stateEdge
    LL<HypEdge> otherStateEdgesInGroup = null;
    if (params.argLocGlobal) {
      State s = u.getState();
      otherStateEdgesInGroup = s.match(aggregateArgPos, firesForR, t);  // for ArgLoc global

      // Check that stateEdge is in this list (I believe this is called after adding to the state).
      boolean found = false;
      for (LL<HypEdge> cur = otherStateEdgesInGroup; cur != null && !found; cur = cur.next)
        found |= cur.item == stateEdge;
      if (!found) {
        throw new RuntimeException("didn't find " + stateEdge + " in " + otherStateEdgesInGroup);
      }
    }

    List<Object> newEdgeGroupKey = groupKey(stateEdge);
    int n = 0;
    for (HypEdge agendaEdge : affected) {

      // TEMPORARY HACK:
      // Do not allow global features to signal between facts in the same group
      List<Object> affEdgeGroupKey = groupKey(agendaEdge);
      if (newEdgeGroupKey.equals(affEdgeGroupKey)) {
        continue;
      }

      if (UbertsLearnPipeline.isNilFact(agendaEdge)) {
        // Do not re-score e.g. nullSpan arg4 facts.
        // They are basically an intercept. Rescore everything else, keep intercept the same.
        continue;
      }

      if (SHOW_GLOBAL_FEAT_COMMUNICATION || Uberts.LEARN_DEBUG) {
        System.out.println("[Global rescore] " + agendaEdge + " is going to be re-scored according to " + stateEdge);
      }

      nEdgeRescore++;
      n++;
      Adjoints oldScore = a.getScore(agendaEdge);
      a.remove(agendaEdge);
      Adjoints newScore;
      if (IMMUTABLE_FACTORS)
        newScore = rescoreImmutable(agendaEdge, oldScore, a, stateEdge, t, otherStateEdgesInGroup);
      else
        newScore = rescoreMutable(agendaEdge, oldScore, a, stateEdge, t, affected, otherStateEdgesInGroup);
      if (DEBUG > 1) {
        System.out.println("IMMUTABLE_FACTORS=" + IMMUTABLE_FACTORS + " e=" + stateEdge + " newScore=" + newScore + " oldScore=" + oldScore);
      }
      a.add(agendaEdge, newScore);
      if (GF_DEBUG && new HashableHypEdge(agendaEdge).hashCode() % 20 == 0) {
        System.out.println("[rescore] GF_DEBUG " + toString());
      }
    }

    if (tm.enoughTimePassed(MIN_BETWEEN_BIGGEST_WEIGHTS * 60)) {
      System.out.println("showing stats for: " + this.toString());
      int k = 30;
      for (boolean byAvg : Arrays.asList(true, false)) {
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=frameCoocPW: " + getBiggestWeights(k, byAvg, FRAME_COOC_FEAT::isFeatureOfMine));
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=numArgs: " + getBiggestWeights(k, byAvg, NumArgsRoleCoocArgLoc::isNumArgsFeat));
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=argLocG: " + getBiggestWeights(k, byAvg, NumArgsRoleCoocArgLoc::isArgLocGlobalFeature));
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=argLocPW: " + getBiggestWeights(k, byAvg, ARG_LOC_PAIRWISE_FEAT::isFeatureOfMine));
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=argLocRoleCoocPW: " + getBiggestWeights(k, byAvg, ARG_LOC_AND_ROLE_COOC::isFeatureOfMine));
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=roleCoocPW: " + getBiggestWeights(k, byAvg, ROLE_COOC_FEAT::isFeatureOfMine));
      }
      System.out.println("feature counts: " + featureStats());
      System.out.println("event counts: " + toString());
      maybeDumpDictionary(ExperimentProperties.getInstance());
//      events.clear();
    }

    if (DEBUG > 0) {
      Log.info("rescored " + n + " " + firesFor + " relations connected to " + t);
      if (DEBUG > 1)
        for (HypEdge e : affected)
          System.out.println("\t" + e);
    }
  }

  /** Returns a string like "argument4_0_1+numArgs" */
  public String name() {
    return firesFor + "_" + aggregateArgPos + "_" + refineArgPos + params.toString(false);
  }

  public void maybeDumpDictionary(ExperimentProperties config) {
    String where = config.getString("globalFeatDumpDictionaryDir", "");
    if (!where.isEmpty()) {
      File wh = new File(where);
      assert !wh.isFile();
      if (!wh.isDirectory())
        wh.mkdirs();
      String name = firesFor + "_" + aggregateArgPos + "_" + refineArgPos;
      name += params.toString(false);
      File f = new File(wh, name + ".alphabet.txt.gz");
      Log.info("writing to " + f.getPath());
      try (BufferedWriter w = FileUtil.getWriter(f)) {
        int n = featureNames.size();
        for (int i = 0; i < n; i++) {
          w.write(featureNames.lookupObject(i));
          w.newLine();
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
      Log.info("done");
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

  public List<Pair<String, Double>> getBiggestWeights(int k, boolean byAvg, Predicate<String> keep) {
    if (featureNames == null)
      throw new IllegalStateException("must call storeExactFeatureIndices to use this!");
    List<Pair<String, Double>> l = new ArrayList<>();
    int n = featureNames.size();
    for (int i = 0; i < n; i++) {
      String f = featureNames.lookupObject(i);
      if (keep.test(f)) {
        double w = byAvg ? theta.getAveragedWeight(i) : theta.getWeight(i);
        l.add(new Pair<>(f, w));
      }
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

  public static boolean isNumArgsFeat(String name) {
    return name.startsWith("na1/") || name.startsWith("na2/");
  }

  public static boolean isArgLocGlobalFeature(String name) {
    if (name.startsWith("alG1/"))
      return true;
    if (name.startsWith("alG2/"))
      return true;
    return false;
  }

}
