package edu.jhu.hlt.uberts.factor;

import java.io.BufferedWriter;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.DecisionFunction;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Term;
import edu.jhu.hlt.uberts.auto.UbertsLearnPipeline;
import edu.jhu.hlt.uberts.features.DebugFeatureAdj;
import edu.jhu.hlt.uberts.features.FyMode;
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

//  public static final boolean USING_PROPBANK;
//  public static final boolean ALLOW_ROLE_COOC_FRAME_REFINEMENT;
  public static final int MIN_BETWEEN_BIGGEST_WEIGHTS;

  static {
    ExperimentProperties config = ExperimentProperties.getInstance();
//    String t = config.getString("train.facts"); // see UbertsLearnPipeline
//    boolean pb = t.contains("propbank");
//    boolean fn = t.contains("framenet");
//    assert pb != fn;
//    USING_PROPBANK = pb;
//    ALLOW_ROLE_COOC_FRAME_REFINEMENT = USING_PROPBANK;
//    Log.info("[main] USING_PROPBANK=" + USING_PROPBANK + " ALLOW_ROLE_COOC_FRAME_REFINEMENT=" + ALLOW_ROLE_COOC_FRAME_REFINEMENT);
    MIN_BETWEEN_BIGGEST_WEIGHTS = config.getInt("minBetweenBiggestWeights", 10);
  }

  private String firesFor;      // e.g. argument4
  private int aggregateArgPos;  // e.g. 0 for t in argument4(t,f,s,k)
  private int dimension;
  public AveragedPerceptronWeights theta;
  private boolean useAvg = false;
  public Alphabet<String> featureNames;  // for debugging

  // An agenda edge a can be influenced via global features by a state edge s iff
  // 1) a and s have the same Relation (firesFor)
  // 2) a and s have the same value in aggregateArgPos
  // 3) a and s are not in the same decoder group.
  // This field, which can be null if no decoder is in use, is used to check that last condition.
  private DecisionFunction.ByGroup decoder;
  private Uberts u; // keep a reference so that we can get the decoder as lazily as possible to avoid initialization order issues

  // If true, then when scoring an agenda edge when there are already 3
  // arguments in the state, the features will be roughly [n=1, n=2, n=3].
  // If false, then we don't include the prefix features, just [n=3].
  private boolean additiveNumArgs = false;
  private boolean additiveArgLocGlobal = false;

  private Counts<String> events = new Counts<>();

  // Each is called on f(newEdge,fOldEdge) where each argument is a firesFor Relation
  private List<PairFeat> pairwiseFeaturesFunctions;

  private double globalToLocalScale = 1;  //0.25

  // Stats
  private int nRescore = 0;
  private int nEdgeRescore = 0;
  private TimeMarker tm = new TimeMarker();

  /**
   * @param refinementArgPos can be <0 if you don't want a refinement
   */
  public NumArgsRoleCoocArgLoc(String firesFor, int aggregateArgPos, GlobalParams p, Uberts u) {
    Log.info("[main] firesFor=" + firesFor + " agg=" + aggregateArgPos + " params=" + p.toString(false));
    this.u = u;
    this.params = p;
    this.firesFor = firesFor;
    this.aggregateArgPos = aggregateArgPos;

    ExperimentProperties config = ExperimentProperties.getInstance();
    dimension = config.getInt("global.hashDimension", 1 << 25);
    int numIntercept = 0;
    theta = new AveragedPerceptronWeights(dimension, numIntercept);

    this.globalToLocalScale = config.getDouble("globalToLocalScale", globalToLocalScale);
    Log.info("[main] globalToLocalScale=" + globalToLocalScale);

    this.pairwiseFeaturesFunctions = new ArrayList<>();
    if (p.argLocPairwise != null)
      this.pairwiseFeaturesFunctions.add(argLocPairwiseFeat(p.argLocPairwise));
    if (p.roleCooc != null)
      this.pairwiseFeaturesFunctions.add(roleCoocFeat(p.roleCooc));
    if (p.argLocRoleCooc != null)
      this.pairwiseFeaturesFunctions.add(argLocAndRoleCooc(p.argLocRoleCooc));
    if (p.frameCooc != null)
      this.pairwiseFeaturesFunctions.add(frameCoocFeat(p.frameCooc));

    if (p.numArgs != null) {
      additiveNumArgs = config.getBoolean("additiveNumArgs", additiveNumArgs);
      Log.info("[main] additiveNumArgs=" + additiveNumArgs);
    }
    if (p.argLocGlobal != null) {
      additiveArgLocGlobal = config.getBoolean("additiveArgLocGlobal", additiveArgLocGlobal);
      Log.info("[main] additiveArgLocGlobal=" + additiveArgLocGlobal);
    }
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

    // Specific versions of this feature, whether to fire
    boolean use_ta_aa = true;
    boolean use_ta = true;
    boolean use_aa = true;

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
    StringBuilder gTA_AA = new StringBuilder("alG/TA_AA/");
    StringBuilder gTA = new StringBuilder("alG/TA/");
    StringBuilder gAA = new StringBuilder("alG/AA/");
    Spany aPrev = null;
    for (int i = 0; i < n; i++) {
      Spany a = allArgs.get(i);
      if (aPrev != null) {
        String aa = "AA" + i + "=" + BasicFeatureTemplates.spanPosRel(aPrev.getSpan(), a.getSpan(), SIMPLE_SPAN_POS_REL);
        gTA_AA.append(aa);
        gTA_AA.append('_');
        gAA.append(aa);
        gAA.append('_');
      }
      String ta = "TA" + i + "=" + BasicFeatureTemplates.spanPosRel(target.target, a.getSpan(), SIMPLE_SPAN_POS_REL);
      gTA_AA.append(ta);
      gTA_AA.append('_');
      gTA.append(ta);
      gTA.append('_');
      aPrev = a;
    }

    ArrayList<String> l = new ArrayList<>(3);
    if (use_ta_aa)
      l.add(gTA_AA.toString());
    if (use_ta)
      l.add(gTA.toString());
    if (use_aa)
      l.add(gAA.toString());
    return l;
  }

  /**
   * There are two types of Adjoints we need.
   * 1) {@link DebugFeatureAdj}, one for each type of feature (key)
   * 2) something fast: namely this class itself.
   */
  public class MultiFeatureLL implements Adjoints.ICaching {
    private Adjoints local;
    private LinkedHashMap<String, FeatureLL> globalByKey;
    private Double forwards;
    private HypEdge edge;

    public MultiFeatureLL(Adjoints score, HypEdge beingScored) {
      this.edge = beingScored;
      if (score instanceof MultiFeatureLL) {
        MultiFeatureLL m = (MultiFeatureLL) score;
        this.local = m.local;
        this.globalByKey = new LinkedHashMap<>(m.globalByKey);
      } else {
        this.local = score;
        this.globalByKey = new LinkedHashMap<>();
      }
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("(MultiFeatureLL");
      sb.append(" local=" + StringUtils.trunc(local, 100));
      for (Entry<String, FeatureLL> x : globalByKey.entrySet()) {
        sb.append(" global." + x.getKey() + "=" + x.getValue());
      }
      sb.append(')');
      return sb.toString();
    }

    @Override
    public double forwards() {
      if (forwards == null) {
        forwards = local.forwards();
        for (FeatureLL f : globalByKey.values())
          forwards += f.forwards();
      }
      return forwards;
    }
    @Override
    public void backwards(double dErr_dForwards) {
      if (dErr_dForwards == 0)
        return;
      local.backwards(dErr_dForwards);
      for (String k : globalByKey.keySet()) {
        if (Uberts.LEARN_DEBUG) {
          getDebugAdjFor(k).backwards(dErr_dForwards);
        } else {
          globalByKey.get(k).backwards(dErr_dForwards);
        }
      }
      forwards = null;
    }

    /** Keeps features previously associated with this key */
    public void add(String key, String[] fy, List<String> fx) {
      update(key, fy, fx, true);
    }

    /** Replaces features previously associated with this key */
    public void put(String key, String[] fy, List<String> fx) {
      update(key, fy, fx, false);
    }

    /**
     * @param add if true then like calling add, else like calling put
     */
    public void update(String key, String[] fy, List<String> fx, boolean add) {
      int nx = fx.size();
      int[] fyx = new int[fy.length * nx];
      for (int i = 0; i < fy.length; i++) {
        for (int j = 0; j < nx; j++) {
          // fx has to come first so we can tell when a feature belongs to a
          // particular fx template.
          String fn = fx.get(j) + "/" + fy[i];
          fyx[i*nx + j] = featureNames.lookupIndex(fn);
        }
      }
      FeatureLL prev = add ? globalByKey.get(key) : null;
      AveragedPerceptronWeights w = useAvg ? theta.averageView() : theta;
      globalByKey.put(key, new FeatureLL(w, fyx, fy, fx, prev));
    }

    public FeatureLL getGlobal(String key) {
      return globalByKey.get(key);
    }

    public DebugFeatureAdj getDebugAdjFor(String key) {
      FeatureLL fll = globalByKey.get(key);
      List<String> fx = new ArrayList<>();
      // Pull these out backwards because the LL already reverses the order of appearance
      Deque<FeatureLL> fs = new ArrayDeque<>();
      for (FeatureLL cur = fll; cur != null; cur = cur.prev)
        fs.push(cur);
      while (fs.size() > 0)
        for (String fxi : fs.pop().fx)
          fx.add(fxi);
      return new DebugFeatureAdj(fll, Arrays.asList(fll.fy), fx, "GLOBAL: agenda=" + edge);
    }
  }

  // Wrap this in a DebugFeatureAdj if you want it to look pretty
  public static class FeatureLL implements Adjoints.ICaching {
    private AveragedPerceptronWeights thetaView;
    private String[] fy;
    private int[] fyx;          // should be converted into [0,dimension)
    private List<String> fx;
    private double tfyx;        // theta * fyx + prev.tfyx
    private FeatureLL prev;

    public FeatureLL(AveragedPerceptronWeights w, int[] fyx, String[] fy, List<String> fx, FeatureLL prev) {
      this.thetaView = w;
      this.fy = fy;
      this.fx = fx;
      this.prev = prev;
      this.fyx = fyx;
      this.tfyx = prev == null ? 0 : prev.tfyx;
      for (int i = 0; i < fyx.length; i++)
        this.tfyx += thetaView.getWeight(fyx[i]);
    }

    @Override
    public String toString() {
      return "(fy=" + Arrays.toString(fy) + " fx=" + fx + ")";
    }

    @Override
    public double forwards() {
      return tfyx;
    }
    @Override
    public void backwards(double dErr_dForwards) {
      for (FeatureLL cur = this; cur != null; cur = cur.prev)
        for (int i = 0; i < cur.fyx.length; i++)
          thetaView.bwh(cur.fyx[i], dErr_dForwards);
    }

  }

  private Adjoints rescoreOneAgendaEdge(HypEdge agendaEdge, Adjoints oldScore, HypEdge stateEdge, HypNode t, LL<HypEdge> inGroupInState) {
    String base = UbertsLearnPipeline.isNilFact(agendaEdge) ? "n" : "s";
    if (UbertsLearnPipeline.INCLUDE_EMPTY_HISTORY_BOOL_IN_BASE)
      base += (inGroupInState == null ? "0" : "1");
    assert !UbertsLearnPipeline.isNilFact(agendaEdge);
    boolean stateIsNil = UbertsLearnPipeline.isNilFact(stateEdge);
//    if (stateIsNil)
//      Log.info("check it out!");

    MultiFeatureLL gfeats = new MultiFeatureLL(oldScore, agendaEdge);

    // Pariwise features
    for (PairFeat pf : pairwiseFeaturesFunctions) {
      String key = pf.getName();
      String[] fy = pf.fy(agendaEdge);
      String fxBase = pf.getName() + "/" + base;
      List<String> fx = pf.describe(fxBase, stateEdge, agendaEdge);
      gfeats.add(key, fy, fx);
    }

    // NUM_ARGS
    if (params.numArgs != null && !stateIsNil) {
      Span agendaTarget = EdgeUtils.target(agendaEdge);
      Span stateTarget = EdgeUtils.target(stateEdge);
      if (agendaTarget == stateTarget) {
        events.increment("numArgs");
        String key = "numArgs";
        String[] fy = params.numArgs.f(agendaEdge);
        FeatureLL fll = gfeats.getGlobal(key);
        int numArgsPrev;
        if (fll == null) {
          numArgsPrev = 0;
        } else {
          assert fll.fx.size() == 2;
          String c = fll.fx.get(0).substring(4);
          numArgsPrev = Integer.parseInt(c);
        }
        List<String> fx = Arrays.asList("na1/" + (numArgsPrev+1), "na2/" + Math.min(5, numArgsPrev+1));
        gfeats.update(key, fy, fx, additiveNumArgs);
      }
    }

    if (params.argLocGlobal != null && !stateIsNil) {
      String[] fy = params.argLocGlobal.f(agendaEdge);
      List<String> fx = argLocGlobal(t, inGroupInState, agendaEdge);
      gfeats.update("argLocGlobal", fy, fx, additiveArgLocGlobal);
    }

    return gfeats;
  }

  private List<Object> makeDecoderGroupKey(HypEdge f) {
    if (decoder == null) {
      decoder = (DecisionFunction.ByGroup) u.getThresh().get(firesFor);
      Log.info("[main] " + this + " is using " + decoder + " to exclude intra-decoder-group global features");
      assert decoder != null : "no-decoder mode not supported now";
    }
//    // (t,k)
//    assert f.getRelation().getName().equals("argument4");
//    List<Object> key = new ArrayList<>(2);
//    key.add(f.getTail(0));
//    key.add(f.getTail(3));
//    return key;
    return decoder.getKey(f);
  }

  @Override
  public void rescore(Uberts u, HypEdge[] trigger) {
    assert trigger.length == 1;
    HypEdge stateEdge = trigger[0];
    nRescore++;
    Agenda a = u.getAgenda();
    Relation firesForR = u.getEdgeType(firesFor);
    HypNode t = stateEdge.getTail(aggregateArgPos);
    Iterable<HypEdge> inGroupOnAgenda = a.match(aggregateArgPos, firesForR, t);

    // NOTE: This should include stateEdge
    LL<HypEdge> inGroupInState = null;
    if (params.argLocGlobal != null || UbertsLearnPipeline.INCLUDE_EMPTY_HISTORY_BOOL_IN_BASE) {
      State s = u.getState();
      inGroupInState = s.match(aggregateArgPos, firesForR, t);  // for ArgLoc global

      // Check that stateEdge is in this list (I believe this is called after adding to the state).
      boolean found = UbertsLearnPipeline.isNilFact(stateEdge);
      for (LL<HypEdge> cur = inGroupInState; cur != null && !found; cur = cur.next)
        found |= cur.item == stateEdge;
      if (!found) {
        throw new RuntimeException("didn't find " + stateEdge + " in " + inGroupInState);
      }
    }

    List<Object> newEdgeGroupKey = makeDecoderGroupKey(stateEdge);
    int n = 0;
    for (HypEdge agendaEdge : inGroupOnAgenda) {

      // Do not allow global features to signal between facts in the same group.
      // This is the job of the decoder to choose the best one out of items in
      // a group (they must also share the same definition of a group).
      // If you do not use a decoder, then perhaps you could try to re-introduce
      // these features, but they should be conjoined with a string indicating
      // that the relationship between the state and agenda edge is inter/intra group.
      List<Object> affEdgeGroupKey = makeDecoderGroupKey(agendaEdge);
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
      Adjoints newScore = rescoreOneAgendaEdge(agendaEdge, oldScore, stateEdge, t, inGroupInState);
      if (DEBUG > 1) {
        System.out.println("e=" + stateEdge + " newScore=" + newScore + " oldScore=" + oldScore);
      }
      a.add(agendaEdge, newScore);
      if (GF_DEBUG && new HashableHypEdge(agendaEdge).hashCode() % 20 == 0) {
        System.out.println("[rescore] GF_DEBUG " + toString());
      }
    }

    if (tm.enoughTimePassed(MIN_BETWEEN_BIGGEST_WEIGHTS * 60)) {
      System.out.println("showing stats for: " + this.toString());
      int k = 30;
      PairFeat frameCooc = frameCoocFeat(FyMode.K);
      PairFeat argLocPW = argLocPairwiseFeat(FyMode.K);
      PairFeat argLocRoleCooc = argLocAndRoleCooc(FyMode.K);
      PairFeat roleCooc = roleCoocFeat(FyMode.K);
      for (boolean byAvg : Arrays.asList(true, false)) {
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=frameCoocPW: " + getBiggestWeights(k, byAvg, frameCooc::isFeatureOfMine));
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=numArgs: " + getBiggestWeights(k, byAvg, NumArgsRoleCoocArgLoc::isNumArgsFeat));
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=argLocG: " + getBiggestWeights(k, byAvg, NumArgsRoleCoocArgLoc::isArgLocGlobalFeature));
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=argLocPW: " + getBiggestWeights(k, byAvg, argLocPW::isFeatureOfMine));
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=argLocRoleCoocPW: " + getBiggestWeights(k, byAvg, argLocRoleCooc::isFeatureOfMine));
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=roleCoocPW: " + getBiggestWeights(k, byAvg, roleCooc::isFeatureOfMine));
        System.out.println(k + " biggest weights " + name() + " byAvg=" + byAvg + " rel=ANY: " + getBiggestWeights(k, byAvg, e -> true));
      }
      System.out.println("feature counts: " + featureStats());
      System.out.println("event counts: " + toString());
      maybeDumpDictionary(ExperimentProperties.getInstance());
//      events.clear();
    }

    if (DEBUG > 0) {
      Log.info("rescored " + n + " " + firesFor + " relations connected to " + t);
      if (DEBUG > 1)
        for (HypEdge e : inGroupOnAgenda)
          System.out.println("\t" + e);
    }
  }

  /** Returns a string like "argument4_0_1+numArgs" */
  public String name() {
    return firesFor + "_" + aggregateArgPos + params.toString(false);
  }

  public void maybeDumpDictionary(ExperimentProperties config) {
    String where = config.getString("globalFeatDumpDictionaryDir", "");
    if (!where.isEmpty()) {
      File wh = new File(where);
      assert !wh.isFile();
      if (!wh.isDirectory())
        wh.mkdirs();
      String name = firesFor + "_" + aggregateArgPos;
      name += params.toString(false);
      File f = new File(wh, name + ".alphabet.txt.gz");
      Log.info("writing to " + f.getPath());
      try (BufferedWriter w = FileUtil.getWriter(f)) {
        int n = featureNames.size();
        for (int i = 0; i < n; i++) {
          w.write(theta.getAveragedWeight(i) + "\t");
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

  public Term[] getTrigger(Uberts u) {
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
    sb.append(" " + params);
    sb.append(" " + events);
    sb.append(')');
    return sb.toString();
  }

  @Override
  public String getName() {
    return toString();
  }

  public static boolean isNumArgsFeat(String name) {
    return name.startsWith("na1/") || name.startsWith("na2/");
  }

  public static boolean isArgLocGlobalFeature(String name) {
    if (name.startsWith("alG/"))
      return true;
    if (name.startsWith("alG/"))
      return true;
    return false;
  }

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
    public FyMode frameCooc = null;
    public FyMode numArgs = null;
    public FyMode argLocPairwise = null;
    public FyMode argLocGlobal = null;
    public FyMode argLocRoleCooc = null;
    public FyMode roleCooc = null;

    public GlobalParams(String desc) {
      String[] toks = desc.split("\\+");
      if (toks.length > 1)
        Log.info("[main] " + desc);
      name = toks[0];
      for (int i = 1; i < toks.length; i++) {
        int a = toks[i].indexOf('@');
        if (a < 0)
          throw new RuntimeException("each must be <argType>@<FyMode>");
        FyMode fy = FyMode.valueOf(toks[i].substring(a+1));
        String name = toks[i].substring(0, a);
        switch (name) {
        case "none":
          break;
        case "full":
          numArgs = fy;
          argLocPairwise = fy;
          argLocGlobal = fy;
          argLocRoleCooc = fy;
          roleCooc = fy;
          break;
        case "frameCooc":
          frameCooc = fy;
          break;
        case "numArgs":
          numArgs = fy;
          break;
        case "argLoc":
          argLocPairwise = fy;
          argLocGlobal = fy;
          break;
        case "argLocPairwise":
          argLocPairwise = fy;
          break;
        case "argLocGlobal":
          argLocGlobal = fy;
          break;
        case "argLocRoleCooc":
          argLocRoleCooc = fy;
          break;
        case "roleCooc":
          roleCooc = fy;
          break;
        }
      }
    }

    public GlobalParams() {
      // everything is false
    }

    public boolean any() {
      return frameCooc != null || numArgs != null || argLocPairwise != null || argLocGlobal != null || argLocRoleCooc != null || roleCooc != null;
    }

    public GlobalParams(GlobalParams copy) {
      frameCooc = copy.frameCooc;
      roleCooc = copy.roleCooc;
      argLocGlobal = copy.argLocGlobal;
      argLocPairwise = copy.argLocPairwise;
      argLocRoleCooc = copy.argLocRoleCooc;
      numArgs = copy.numArgs;
    }

    @Override
    public String toString() {
      return toString(true);
    }
    public String toString(boolean includeSpaces) {
      StringBuilder sb = new StringBuilder();
      if (numArgs != null) {
        if (includeSpaces) sb.append(' ');
        sb.append("+numArgs@" + numArgs);
      }
      if (argLocPairwise != null) {
        if (includeSpaces) sb.append(' ');
        sb.append("+argLocPairwise@" + argLocPairwise);
      }
      if (argLocGlobal != null) {
        if (includeSpaces) sb.append(' ');
        sb.append("+argLocGlobal@" + argLocGlobal);
      }
      if (roleCooc != null) {
        if (includeSpaces) sb.append(' ');
        sb.append("+roleCooc@" + roleCooc);
      }
      if (frameCooc != null) {
        if (includeSpaces) sb.append(' ');
        sb.append("+frameCooc@" + frameCooc);
      }
      if (argLocRoleCooc != null) {
        if (includeSpaces) sb.append(' ');
        sb.append("+argLocRoleCooc@" + argLocRoleCooc);
      }
      return sb.toString().trim();
    }
  }
  private GlobalParams params;

  public interface PairFeat {
    String getName();

    // fx
    List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge);

    // fy
    String[] fy(HypEdge e);

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

  public static PairFeat frameCoocFeat(final FyMode fy) {
    return new PairFeat() {
      private boolean allowDiffTargets = true;
      private boolean targetRelBackoff = true;
      private boolean edgeRelBackoff = false;
      @Override
      public String getName() { return "fcPW"; }
      @Override
      public List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge) {
        assert !UbertsLearnPipeline.isNilFact(stateEdge);
        assert !UbertsLearnPipeline.isNilFact(agendaEdge);
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
      @Override
      public String[] fy(HypEdge e) {
        return fy.f(e);
      }
    };
  }

  public static final PairFeat roleCoocFeat(FyMode fy) {
    return new PairFeat() {
      private boolean allowDiffTargets = false;
      private boolean targetRelBackoff = false;
      private boolean edgeRelBackoff = false;
      private boolean unordered = true;
      private boolean ordered = false;    // Use ordered=false and make it behave like ordered=true by using an K or FK label feature.
      @Override
      public String getName() { return "rcPW"; }
      @Override
      public List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge) {
//        assert !UbertsLearnPipeline.isNilFact(stateEdge);
        String stateNil = UbertsLearnPipeline.isNilFact(stateEdge) ? "-" : "+";
        assert !UbertsLearnPipeline.isNilFact(agendaEdge);
        Span ts = EdgeUtils.target(stateEdge);
        Span ta = EdgeUtils.target(agendaEdge);
        if (!allowDiffTargets && ts != null && !ts.equals(ta))
          return Collections.emptyList();

        String r1 = EdgeUtils.role(stateEdge);
        String r2 = EdgeUtils.role(agendaEdge);
        if (r1 == null || r2 == null)
          return Collections.emptyList();
        r1 = stateNil + r1;

        String r = shorten(agendaEdge.getRelation().getName());
        List<String> feats = new ArrayList<>();

        assert ordered || unordered;
        if (ordered) {
          feats.add(prefix + "/" + r + "/A=" + r1 + "/a=" + r2);
        }
        if (unordered) {
          String rMin, rMax;
          if (r1.compareTo(r2) <= 0) {
            rMin = r1; rMax = r2;
          } else {
            rMin = r2; rMax = r1;
          }
          feats.add(prefix + "/" + r + "/uA=" + rMin + "/uA=" + rMax);
        }


        if (allowDiffTargets)
          refineByTargets(stateEdge, agendaEdge, feats, targetRelBackoff);
        refineByEdgeTypes(stateEdge, agendaEdge, feats, edgeRelBackoff);
        return feats;
      }
      @Override
      public String[] fy(HypEdge e) {
        return fy.f(e);
      }
    };
  }

  public static final PairFeat argLocPairwiseFeat(FyMode fy) {
    return new PairFeat() {
      private boolean allowDiffTargets = false;
      private boolean targetRelBackoff = false;
      private boolean edgeRelBackoff = false;
      private boolean includeTa = true;
      private boolean includeTaBackoff = true;
      @Override
      public String getName() { return "alPW"; }
      @Override
      public List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge) {
        if (UbertsLearnPipeline.isNilFact(stateEdge))
          return Collections.emptyList();
        assert !UbertsLearnPipeline.isNilFact(agendaEdge);
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
      @Override
      public String[] fy(HypEdge e) {
        return fy.f(e);
      }
    };
  }

  public static final PairFeat argLocAndRoleCooc(FyMode fy) {
    return new PairFeat() {
      private PairFeat argLoc = argLocPairwiseFeat(fy);
      private PairFeat roleCooc = roleCoocFeat(fy);
      @Override
      public String getName() {
        return "alrcPW";
      }
      @Override
      public List<String> describe(String prefix, HypEdge stateEdge, HypEdge agendaEdge) {
        if (UbertsLearnPipeline.isNilFact(stateEdge))
          return Collections.emptyList();
        assert !UbertsLearnPipeline.isNilFact(agendaEdge);
        List<String> a = argLoc.describe(prefix, stateEdge, agendaEdge);
        int an = a.size();
        if (an == 0)
          return a;
        List<String> b = roleCooc.describe(prefix, stateEdge, agendaEdge);
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
      public String[] fy(HypEdge e) {
        return fy.f(e);
      }
    };
  }

  static String shorten(String longFeatureName) {
    String x = longFeatureName;
    x = x.replaceAll("argument4", "a4");
    x = x.replaceAll("propbank", "pb");
    x = x.replaceAll("framenet", "fn");
    return x;
  }


}
