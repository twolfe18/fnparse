package edu.jhu.hlt.uberts;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
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
import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;

import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ArgMax;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda.AgendaItem;
import edu.jhu.hlt.uberts.Agenda.LabledAgendaItem;
import edu.jhu.hlt.uberts.Agenda.RescoreMode;
import edu.jhu.hlt.uberts.DecisionFunction.ByGroup;
import edu.jhu.hlt.uberts.DecisionFunction.ByGroup.ByGroupMode;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.auto.Rule;
import edu.jhu.hlt.uberts.auto.Term;
import edu.jhu.hlt.uberts.auto.Trigger;
import edu.jhu.hlt.uberts.auto.UbertsLearnPipeline;
import edu.jhu.hlt.uberts.auto.UbertsLearnPipeline.ClassEx;
import edu.jhu.hlt.uberts.factor.GlobalFactor;
import edu.jhu.hlt.uberts.factor.LocalFactor;
import edu.jhu.hlt.uberts.factor.NumArgsRoleCoocArgLoc;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;
import edu.jhu.hlt.uberts.rules.Env.Trie3;
import edu.jhu.hlt.uberts.srl.EdgeUtils;
import edu.jhu.hlt.uberts.transition.TransGen;
import edu.jhu.hlt.uberts.util.EdgeDiff;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;

/**
 * An uber transition system for joint predictions. Holds a state and agenda,
 * and you add global features and transition generators to define the state
 * lattice.
 *
 * Remember:
 * {@link TransGen} => local features
 * {@link GlobalFactor} => global features and hard constraints
 *
 * @author travis
 */
public class Uberts {
  public static int DEBUG = 1;
  public static boolean LEARN_DEBUG = false;

  public static final String REC_ORACLE_TRAJ = "recordOracleTrajectory";

  private List<NewStateEdgeListener> newStateEdgeListeners = new ArrayList<>();
  public void addNewStateEdgeListener(NewStateEdgeListener l) {
    Log.info("[main] adding " + l);
    newStateEdgeListeners.add(l);
  }

  private State state;
  private Agenda agenda;

  // When true, every time addEdgeToAgenda is called, a loss term
  // is added to the item's score.
  private boolean lossAugmentation = false;
  // TODO Have a flag for oracle which just does a no-op when addEdgeToAgenda and gold=false?

  // Index of Triggers which cause TransGens and GlobalFactors to fire.
  private Trie3 trie3;

  // All of these are co-indexed
  // What happens if we have multiple factors with the same trigger?
  // Every time you add a Trigger->TG|GF, then we check if its a new trigger.
  // If no, then we merge the TG|GF (they must be "summable/mergable").
  private Trigger[] triggers;     // first numTriggers values are non-null
  private TransGen[] transitionGenrators; // may contain nulls
  private GlobalFactor[] globalFactors;   // may contain nulls
  private int numTriggers;
  // TODO Add some debugging counts for how many times each trigger/transgen/globalfactor is used.
  // This will help with finding some un-expected grammar screw-ups.

  // When an edge is popped off the agenda, this is responsible for determining
  // if the edge should be added to the state. The default implementation checks
  // whether score(edge) > 0.
  private DecisionFunction.DispatchByRelation thresh;

  // So you can ask for Relations by name and keep them unique
  private Map<String, Relation> relations;

  // Alphabet of HypNodes which appear in either state or agenda.
  NodeStore.Composite nodes3;

  // Never call `new NodeType` outside of Uberts, use lookupNodeType
  private Map<String, NodeType> nodeTypes;

  // Supervision (set of gold HypEdges)
  private Labels goldEdges;

  public boolean showTrajDiagnostics = false;

  private Random rand;
  private MultiTimer timer;

  // Tracks things like number of pushes and pops, can be externally read/reset
  public Counts<String> stats = new Counts<>();
  // i^th value is the size of the agenda after applying i actions.
  // This must be cleared after each round of inference by the user.
  public IntArrayList statsAgendaSizePerStep = new IntArrayList();


  // If this document represents a sentence and you need to call older code
  // which only knows how to read Sentences, then set this.
  public Sentence dbgSentenceCache;
  public Alphabet<String> dbgSentenceCacheDepsAlph = new Alphabet<>();

  // Counts up dErr_dForwards for each edge
  public Map<HashableHypEdge, Double> dbgUpdate = new HashMap<>();

  // Used to implement classification update which may need to live side-by-side
  // with some other global feature update. Flip this switch, and then triggers
  // for global factors will never fire.
  // Keep this private!
  private boolean disableGlobalFeats = false;

  // TODO Remove
  // Ancillary data for features which don't look at the State graph.
  // New data backend (used to be fnparse.Sentence and FNParse)
  /** @deprecated Switch to a pure State/graph-based representation! */
  private edu.jhu.hlt.tutils.Document doc;
  /** @deprecated Switch to a pure State/graph-based representation! */
  public edu.jhu.hlt.tutils.Document getDoc() {
    return doc;
  }
  /** @deprecated Switch to a pure State/graph-based representation! */
  public void setDocument(edu.jhu.hlt.tutils.Document doc) {
    this.doc = doc;
  }

  public DecisionFunction.DispatchByRelation getThresh() {
    return thresh;
  }
  public void setThresh(DecisionFunction.DispatchByRelation thresh) {
    this.thresh = thresh;
  }

  /**
   * Sets the set of gold edges to null (appears as if no labels were ever provided).
   */
  public void clearLabels() {
    goldEdges = null;
  }
  public Labels getLabels() {
    return goldEdges;
  }
  public void addLabel(HypEdge e) {
    if (DEBUG > 1)
      System.out.println("Uberts addLabel: " + e);
    if (goldEdges == null)
      goldEdges = new Labels(this, thresh::getBucket);
    goldEdges.add(e);;
  }
  public boolean getLabel(HypEdge e) {
    return goldEdges.getLabel(e);
  }
  public boolean getLabel(HashableHypEdge e) {
    return goldEdges.getLabel(e);
  }
  public boolean getLabel(AgendaItem ai) {
    return goldEdges.getLabel(ai.getHashableEdge());
  }
  /**
   * Sets the set of gold edges to the empty set.
   */
  public void initLabels() {
    goldEdges = new Labels(this, thresh::getBucket);
  }

  /**
   * You need to do this to release memory if you are calling lookupNode on all
   * the data you're processing.
   */
  public void clearNonSchemaNodes() {
    stats.increment("clearNonSchemaNodes");
    nodes3.clearNonSchema();
    state.clearNonSchema();
  }

  public Uberts(Random rand) {
    this(rand, (edge, score) -> score.forwards(), null);
  }

  /**
   * You can provide a null agenda priority if you call setAgendaPriority soon
   * after construction.
   */
  public Uberts(Random rand, BiFunction<HypEdge, Adjoints, Double> agendaPriority, Comparator<AgendaItem> agendaComparator) {
    this.rand = rand;
    this.relations = new HashMap<>();
    this.agenda = new Agenda(agendaPriority, agendaComparator);
    this.state = new State.Split();
    this.trie3 = Trie3.makeRoot();

    this.nodes3 = new NodeStore.Composite(
        new NodeStore.Regular("schema"), new NodeStore.Regular("temporary"));

    this.nodeTypes = new HashMap<>();

    this.trie3 = Trie3.makeRoot();
    int n = 16;
    this.triggers = new Trigger[n];
    this.transitionGenrators = new TransGen[n];
    this.globalFactors = new GlobalFactor[n];
    this.numTriggers = 0;

    this.timer = new MultiTimer();
    this.timer.put(REC_ORACLE_TRAJ, new Timer(REC_ORACLE_TRAJ, 30, true));
  }

  /**
   * Allocates a new agenda with the given priority.
   */
  public void setAgendaPriority(BiFunction<HypEdge, Adjoints, Double> agendaPriority) {
    this.agenda = new Agenda(agendaPriority, null);
  }

  // TODO This is an ugly hack, fixme.
  public Relation addSuccTok(int n) {
    NodeType tokenIndex = lookupNodeType("tokenIndex", true);
    Relation succTok = addEdgeType(new Relation("succTok", tokenIndex, tokenIndex));
    boolean isSchema = true;
    HypNode prev = lookupNode(tokenIndex, String.valueOf(-1), true, isSchema);
    for (int i = 0; i < n; i++) {   // TODO figure out a better way to handle this...
      HypNode cur = lookupNode(tokenIndex, String.valueOf(i), true, isSchema);
      HypEdge e = makeEdge(isSchema, succTok, prev, cur);
      e = new HypEdge.WithProps(e, HypEdge.IS_SCHEMA);
      addEdgeToState(e, Adjoints.Constant.ZERO);
      prev = cur;
    }
    return succTok;
  }

  /**
   * Pops items off the agenda until score is below 0, then stops. Right now this
   * is a debug method since it prints stuff and inference is not finalized.
   *
   * @param oracle says whether only edges which are in the gold label set should
   * be added to state (others are just popped off the agenda and discarded).
   */
  public Pair<Labels.Perf, List<Step>> dbgRunInference(boolean oracle, boolean ignoreDecoder) {
    if (DEBUG > 1)
      Log.info("starting, oracle=" + oracle);
    statsAgendaSizePerStep.clear();

    Labels.Perf perf = null;
    if (goldEdges != null)
      perf = goldEdges.new Perf();

    List<Step> steps = new ArrayList<>();
    while (agenda.size() > 0) {
      statsAgendaSizePerStep.add(agenda.size());
      AgendaItem ai = agenda.popBoth2();
      Boolean y = perf == null ? null : getLabel(ai);
      if (DEBUG > 1)
        System.out.println("[dbgRunInference] popped=" + ai);

      Step s;
      boolean pred;
      if (ignoreDecoder) {
        pred = ai.score.forwards() > 0;
        s = new Step(ai, y, pred);
      } else {
        Pair<Boolean, Adjoints> dec = thresh.decide2(ai);
        pred = dec.get1();
        s = new Step(ai, y, pred);
        s.setDecision(dec.get2());
      }
      steps.add(s);

      // But maybe don't add apply it (add it to state)
      if ((oracle && y) || (!oracle && pred)) {
        if (perf != null)
          perf.add(ai.edge);
        addEdgeToState(ai);
      }
    }

    if (showTrajDiagnostics) {
      if (perf == null)
        Log.info("cannot show traj perf since there are no labels!");
      else
        showTrajPerf(steps, perf);
    }

    return new Pair<>(perf, steps);
  }
  public Pair<Labels.Perf, List<Step>> dbgRunInference() {
    return dbgRunInference(false, false);
  }

  /**
   * Runs inference with the current policy until an action with some loss is
   * taken, returning that step (or null if no mistake is made).
   *
   * See Collins and Roark (2004)
   * or algorithm 5 in http://www.aclweb.org/anthology/N12-1015
   */
  public Step earlyUpdatePerceptron() {
    statsAgendaSizePerStep.clear();
    while (agenda.size() > 0) {
      statsAgendaSizePerStep.add(agenda.size());
      AgendaItem ai = agenda.popBoth2();
      boolean yhat = ai.score.forwards() > 0;
      boolean y = getLabel(ai);
      if (y != yhat)
        return new Step(ai, y, yhat);
      if (yhat)
        addEdgeToState(ai);
    }
    return null;
  }

  /**
   * Doesn't store states, but is like a List<Step> with benefits such as score
   * and loss prefix sums.
   *
   * Used to implement update schemes which look at entire trajectories (as
   * opposed to methods which update p(a|s)).
   */
  public static class Traj {
    private Map<Relation, FPR> totalLoss;
    private Map<Relation, FPR> actionLoss;
    private double totalScore;
    private Step prevToCur;
    private Traj prev;
    public final int length;

    @Override
    public String toString() {
      return "(Traj step=" + prevToCur + " totalScore=" + totalScore + " actionLoss=" + actionLoss + ")";
    }

    /**
     * @param prevToCur
     * @param prev
     * @param actionLoss make sure this is computed based on Commits/Prunes and
     * not the closed world assumption used by {@link Labels.Perf}. For example,
     * if you predicted foo(x1) (a true pos) and foo(x2) (a false pos) and there
     * were 10 gold foo(?) facts, then by closed world FP=1,FN=9 when it should
     * really be FP=1,FN=1.
     */
    public Traj(Step prevToCur, Traj prev) {
      this.prevToCur = prevToCur;
      this.prev = prev;
      this.length = 1 + (prev == null ? 0 : prev.length);

      // We known from prevToCur what the loss is
      this.actionLoss = new HashMap<>();
      FPR fpr = new FPR();
      fpr.accum(prevToCur.gold, prevToCur.pred);
      this.actionLoss.put(prevToCur.edge.getRelation(), fpr);

      if (actionLoss != null) {
        if (prev == null)
          totalLoss = actionLoss;
        else
          totalLoss = FPR.combineStratifiedPerf(prev.totalLoss, actionLoss);
      }

      totalScore = prevToCur.score.forwards();
      if (prev != null)
        totalScore += prev.totalScore;
    }

    public Step getStep() {
      return prevToCur;
    }

    public Traj getPrev() {
      return prev;
    }

    public double getScorePlusLoss(Map<Relation, Double> costFP) {
      double s = totalScore;
      for (FPR fpr : totalLoss.values())
        s += fpr.getFP() + fpr.getFN();
      return s;
    }

    public double getActionLoss() {
      double s = 0;
      for (FPR fpr : actionLoss.values())
        s += fpr.getFP() + fpr.getFN();
      return s;
    }

    /**
     * Traj looks like a0 <- a1 <- ... <- aN
     * This returns a stack where a0 will be the first action/Step popped.
     */
    public Deque<Traj> reverse() {
      Deque<Traj> stack = new ArrayDeque<>();
      for (Traj cur = this; cur != null; cur = cur.prev)
        stack.push(cur);
      return stack;
    }
  }

  /**
   * Returns (gold trajectory, predicted trajectory).
   *
   * See http://www.aclweb.org/anthology/N12-1015
   *
   * @param latestUpdate when false, use max violation.
   *
   * @param useGoldPredicate2InMV if true, then addEdgeToState depends on the gold label for predicate2 facts,
   * as opposed to pred as for all other actions and all actions when this flag is false. You want to do this
   * when you are training both a predicate2 and argument4 model, as VFP can't really handle sequences that
   * diverge on frames (e.g. since the number of actions in the sequence changes depending on frame.numRoles).
   * This is equivalent to treading predicate2 and argument4 prediction as two separate tasks and training with VFP.
   */
  public Pair<Traj, Traj> violationFixingPerceptron(Map<Relation, Double> costFP, boolean latestUpdate, boolean useGoldPredicate2InMV) {
    assert agenda.getRescoreMode() == RescoreMode.NONE;

//    assert !lasoHack : "this is no longer supported through MV, actually use the getLaso2Update."
//        + " lasoHack here just lets the pred (mv) trajectory use gold arg4 actions, which is not the"
//        + " same as a proper per-state LOLS/LaSO update (the problem that does exist with VFP that I"
//        + " was trying to solve has to do with the use of oracle features).";

    timer.start("duplicate-state");
    State s0 = state.duplicate();
    timer.stop("duplicate-state");

    timer.start("duplicate-agenda");
    Agenda a0 = agenda.duplicate();
    timer.stop("duplicate-agenda");

    boolean debug = true;
    if (debug && DEBUG > 1) {
      Log.info("at the start of MV:");
      Log.info("latestUpdate=" + latestUpdate);
      Log.info(getStateFactCounts());
      int i = 0;
      for (AgendaItem ai : a0.getContentsInNoParticularOrder())
        System.out.println("agenda[" + (i++) + "]: " + ai);
    }

    // Oracle
    assert !lossAugmentation;
    Labels.Perf oPerf = goldEdges.new Perf();
    Traj t1 = null;
    {
      if (Agenda.DEBUG || DEBUG > 1)
        Log.info("starting ORACLE inference, agenda=" + agenda + " thresh=" + thresh);
      while (agenda.size() > 0) {
        AgendaItem ai = agenda.popBoth2();
        boolean y = getLabel(ai);

        Pair<Boolean, Adjoints> p = thresh.decide2(ai);
        boolean yhat = p.get1();
        if (DEBUG > 1)
          System.out.println("[maxViolationPerceptron] step=oracle gold=" + y + " pred=" + yhat + " addToState=" + y + " popped=" + ai);

        if (y) {
          oPerf.add(ai.edge);
          Step s = new Step(ai, y, yhat);
          t1 = new Traj(s, t1);
          addEdgeToState(ai);
        }
      }
    }
    if (t1 == null) {
      // This can happen if there are no role2 facts corresponding to a given
      // frame. This can happen if you build role2 (observed) from training
      // facts and there is a predicate2 frame which has no argument4s.
      stats.increment("maxViolation/emptyTraj");
    }
    Map<String, FPR> oPerfVals = oPerf.perfByRel();
    if (oPerfVals.get("predicate2").recall() < 1) {
      stats.increment("maxViolation/NO_UPDATE/lowRecall/predicate2");
      return null;
    }
    if (oPerfVals.get("argument4") != null && oPerfVals.get("argument4").recall() < 1) {
      stats.increment("maxViolation/NO_UPDATE/lowRecall/argument4");
      return null;
    }
    stats.increment("maxViolation/UPDATE");

    // Loss Augmented Inference
    state = s0;
    agenda = a0;
    thresh.clear();
    Traj t2 = null;
    {
      if (Agenda.DEBUG || DEBUG > 1)
        Log.info("starting LOSS_AUGMENTED inference, agenda=" + agenda + " thresh=" + thresh);

      // NOTE: Since the event1 facts are already on the agenda,
      // RescoreMode.LOSS_AUGMENTED doesn't apply to those edges.
      // This is not a problem since we are not learning event1 parameters.
      agenda.oneTimeRescore(RescoreMode.LOSS_AUGMENTED, goldEdges);
      lossAugmentation = true;  // add loss to score when adding to agenda

      statsAgendaSizePerStep.clear();
      while (agenda.size() > 0) {
        statsAgendaSizePerStep.add(agenda.size());
        AgendaItem ai = agenda.popBoth2();
        boolean y = getLabel(ai);
        Pair<Boolean, Adjoints> p = thresh.decide2(ai);
        boolean yhat = p.get1();

        if (yhat) {
          Step s = new Step(ai, y, yhat);
          t2 = new Traj(s, t2);
        }

//        boolean h = lasoHack && "argument4".equals(ai.getHashableEdge().getEdge().getRelation().getName());
//        boolean addToState = (h && y) || (!h && yhat);
        boolean pred2 = "predicate2".equals(ai.edge.getRelation().getName());
        boolean arg4 = "argument4".equals(ai.edge.getRelation().getName());
        assert pred2 || arg4 : "training is almost certainly broken";
        boolean addToState = (useGoldPredicate2InMV && pred2) ? y : yhat;
        if (DEBUG > 1)
          System.out.println("[maxViolationPerceptron] step=lossAugmented gold=" + y + " pred=" + yhat + " addToState=" + addToState + " popped=" + ai);
        if (addToState)
          addEdgeToState(ai);
      }
      lossAugmentation = false;
    }

    // Compute the violation
    assert (t1 == null) == (t2 == null);
    if (t1 == null) {
      Log.info("WARNING: null ORACLE in " + dbgSentenceCache);
      return null;
    }
    if (t2 == null) {
      Log.info("WARNING: null LOSS_AUGMENTED in " + dbgSentenceCache);
      return null;
    }
    Deque<Traj> t1r = t1.reverse();
    Deque<Traj> t2r = t2.reverse();

    // Lets assume that these trajectories are the same length
    // I have to make this true to implement the violation computation used in UbertsLearnPipeline.adHockSrl.
    if (t1r.size() != t2r.size()) {

      // Try to automatically figure out what is different
      List<HypEdge> e1 = new ArrayList<>();
      List<HypEdge> e2 = new ArrayList<>();
      for (Traj t : t1r)
        e1.add(t.prevToCur.edge);
      for (Traj t : t2r)
        e2.add(t.prevToCur.edge);
      EdgeDiff.showDiff(e1, e2);

      // What I want is a diff of keys when grouped by (t,f,k)
      System.out.println();
      EdgeDiff.Grouping g1 = new EdgeDiff.Grouping(getEdgeType("argument4"), new int[] {0,1,3});
      EdgeDiff.Grouping g2 = new EdgeDiff.Grouping(getEdgeType("argument4"), new int[] {0,1,3});
      g1.addAll(e1);
      g2.addAll(e2);
      EdgeDiff.sameKeys(g1, g2);

      System.out.println("e1 more than one per key:");
      for (Entry<List<Object>, List<HypEdge>> x : g1.getKeysWithMoreThanOneValue())
        System.out.println(x);
      System.out.println("e2 more than one per key:");
      for (Entry<List<Object>, List<HypEdge>> x : g2.getKeysWithMoreThanOneValue())
        System.out.println(x);

      System.out.println("e1 dups:");
      for (HypEdge e : EdgeDiff.duplicates(e1))
        System.out.println("e1 duplicate: " + e);
      System.out.println("e2 dups:");
      for (HypEdge e : EdgeDiff.duplicates(e2))
        System.out.println("e2 duplicate: " + e);

      System.out.println();
      for (HypEdge e : getLabels().getGoldEdges(false))
        System.out.println("gold: " + e);
      System.out.println();

      if (t1r.size() < 50 && t2r.size() < 50) {
        for (int i = 0; !t1r.isEmpty(); i++)
          System.out.println("oracle[" + i + "]: " + t1r.removeFirst());
        for (int i = 0; !t2r.isEmpty(); i++)
          System.out.println("mv[" + i + "]:     " + t2r.removeFirst());
      }

      System.out.println("@1-2: " + dbgSentenceCache.getLU(1));
      System.out.println(dbgSentenceCache.getId());
      System.out.println(Describe.sentenceWithDeps(dbgSentenceCache, dbgSentenceCache.getParseyDeps()));

      Log.info("WARNING: t1r.size=" + t1r.size() + " t2r.size=" + t2r.size());
      assert false;
    }

    // Compute either MAX_VIOLATION or LATEST_UPDATE
    double sCumOracle = 0;
    double sCumPred = 0;
    int bestIndex = -1, index = 0;
    ArgMax<Pair<Traj, Traj>> m = new ArgMax<>();
    while (!t1r.isEmpty() && !t2r.isEmpty()) {
      Traj sG = t1r.removeFirst();
      Traj sP = t2r.removeFirst();
      assert sG.getStep().gold;
      assert sP.getStep().pred;

      assert EdgeUtils.target(sG.getStep().edge) == EdgeUtils.target(sP.getStep().edge);
      if ("argument4".equals(sG.getStep().edge.getRelation().getName())) {
        assert EdgeUtils.frame(sG.getStep().edge).equals(EdgeUtils.frame(sP.getStep().edge));
        // This will not be true with dynamic orders
//        assert EdgeUtils.role(sG.getStep().edge).equals(EdgeUtils.role(sP.getStep().edge));
      }
      assert getLabel(sG.getStep().edge);
//      assert sP.getStep().score.forwards() == sP.getStep().scoreV;

      // NOTE: Loss should be baked into sP scores already!
      double sg, sp;
      sCumOracle += (sg = sG.getStep().score.forwards());
      sCumPred += (sp = sP.getStep().score.forwards());
      double violation = sCumPred - sCumOracle;

      if (Uberts.LEARN_DEBUG) {
        System.out.printf("[VFP compute violation] gold=%s\thyp=%s\tgoldScore=%+.2f\thypScore=%+.2f\tvio=%+.2f\tsumVio=%+.2f\n",
            sG.getStep().edge, sP.getStep().edge, sg, sp, sp-sg, violation);
      }

      if (latestUpdate) {
        // LATEST_UPDATE
        if (violation > 0) {
          m.offer(new Pair<>(sG, sP), index+1);
          bestIndex = index;
        }
      } else {
        // MAX_VIOLATION
        if (m.offer(new Pair<>(sG, sP), violation))
          bestIndex = index;
      }
      index++;
    }
    Pair<Traj, Traj> best = m.get();
    double violation = m.getBestScore();
    if (Uberts.LEARN_DEBUG) {
      Counts<String> gr = new Counts<>();
      for (Traj t = best.get1(); t != null; t = t.prev)
        gr.increment(t.getStep().edge.getRelation().getName());
      Counts<String> pr = new Counts<>();
      for (Traj t = best.get2(); t != null; t = t.prev)
        pr.increment(t.getStep().edge.getRelation().getName());
      System.out.println("maxViolation=" + violation
          + " mvIdx=" + bestIndex
          + " trajLength=" + index
          + " discreteLogViolation=" + UbertsLearnPipeline.discreteLogViolation(m.getBestScore())
          + " goldUpdateByRelation=" + gr
          + " predUpdateByRelation=" + pr);
    }

    if (violation <= 0)
      return null;
    return best;
  }

  /**
   * Like maxViolation but disables global features.
   *
   * Modifies {@link State}, {@link Agenda}, and {@link DecisionFunction} (and
   * potentially anything else in this class which can be modified).
   */
  public List<ClassEx> getClassificationUpdate() {
    boolean dgOld = disableGlobalFeats;
    disableGlobalFeats = true;

    // ORACLE INFERENCE
    State s0 = state.duplicate();
    Agenda a0 = agenda.duplicate();
    while (agenda.size() > 0) {
      AgendaItem ai = agenda.popBoth2();
      boolean y = getLabel(ai);
      // DO NOT COMMENT OUT: Need the side effects.
      thresh.decide2(ai);
      if (y)
        addEdgeToState(ai);
    }

    // These are the facts that the oracle chose for each decoder group
    Map<String, LinkedHashMap<List<Object>, Pair<HypEdge, Adjoints>>> rel2firstGoldByGroup = new HashMap<>();
    for (Entry<String, DecisionFunction> x : thresh.entries()) {
      DecisionFunction.ByGroup df = (DecisionFunction.ByGroup) x.getValue();
      assert df.getMode() == ByGroupMode.EXACTLY_ONE : "this doesn't work if isn't exactly one right answer per group";
      rel2firstGoldByGroup.put(x.getKey(), df.getFirstGoldInBucket());
    }

    // Reset to initial conditions, and save a memo which will let you do it one more timei
    thresh.clear();
    state = s0;
    agenda = a0;
    s0 = s0.duplicate();
    a0 = a0.duplicate();

    // LOSS AUGMENTED INFERENCE
    agenda.oneTimeRescore(RescoreMode.LOSS_AUGMENTED, goldEdges);
    while (agenda.size() > 0) {
      AgendaItem ai = agenda.popBoth2();
      @SuppressWarnings("unused")
      Pair<Boolean, Adjoints> p = thresh.decide2(ai);

      // This is the same issue as useGoldPredicate2InMV in VFP. If we
      // mis-predict a predicate2, there will be no correct argument4 facts We
      // still want LA inference to pick out the wrong answer within any given
      // bucket, but we can't allow that mistake to change the argument4
      // buckets.
      // NOTE: Since the classification update doesn't use global features,
      // there is no inconsistency problem with using gold information (eg via
      // global features) during training.
      boolean y = getLabel(ai);
      if (y)
        addEdgeToState(ai);
//      boolean yhat = p.get1();
//      if (yhat)
//        addEdgeToState(ai);
    }

    // These are the facts that LA inference chose for each decoder group
    Map<String, LinkedHashMap<List<Object>, Pair<HypEdge, Adjoints>>> rel2firstPredByGroup = new HashMap<>();
    for (Entry<String, DecisionFunction> x : thresh.entries()) {
      DecisionFunction.ByGroup df = (DecisionFunction.ByGroup) x.getValue();
      assert df.getMode() == ByGroupMode.EXACTLY_ONE : "this doesn't work if isn't exactly one right answer per group";
      rel2firstPredByGroup.put(x.getKey(), df.getFirstPredInBucket());
    }

    // Join the chosen facts on the group to produce the classification examples
    List<ClassEx> classEx = new ArrayList<>();
    assert rel2firstGoldByGroup.size() == rel2firstPredByGroup.size();
    for (String rel : rel2firstGoldByGroup.keySet()) {
      Map<List<Object>, Pair<HypEdge, Adjoints>> firstGoldByGroup = rel2firstGoldByGroup.get(rel);
      Map<List<Object>, Pair<HypEdge, Adjoints>> firstPredByGroup = rel2firstPredByGroup.get(rel);

      // There may be cases where the transition grammar cannot generate eg the
      // correct predicate2 fact. When this happens, the set of gold predicate2
      // buckets will be different from the predicted set. Here we just skip the
      // buckets where there is no gold prediction.
//      assert firstGoldByGroup.size() == firstPredByGroup.size()
//          : "firstGold=" + firstGoldByGroup.size() + " firstPred=" + firstPredByGroup.size();

      for (List<Object> key : firstGoldByGroup.keySet()) {
        Pair<HypEdge, Adjoints> a = firstGoldByGroup.get(key);
        Pair<HypEdge, Adjoints> b = firstPredByGroup.get(key);
        // Check if this is a mistake
        HashableHypEdge aa = new HashableHypEdge(a.get1());
        HashableHypEdge bb = new HashableHypEdge(b.get1());
        if (!aa.equals(bb)) {
          ClassEx c = new ClassEx(a, b, -1);
          classEx.add(c);
        }
      }
    }

    state = s0;
    agenda = a0;
    thresh.clear();
    disableGlobalFeats = dgOld;
    return classEx;
  }

  /**
   * Runs inference in loss augmented mode and records every pred=true step.
   * After inference is complete, it goes back to the ByGroup and asks for the
   * first gold in every bucket s.t. there is a pred in said bucket.
   *
   * Only works when using {@link DecisionFunction.ByGroup} with EXACTLY_ONE.
   *
   * This is distinct from max-violation updates in that the "oracle", or the
   * trajectory that you boost the score of, uses features computed from the
   * current model + loss augmentation rather than using oracle global features.
   * If there is a large difference between the oracle and the model, what
   * global features fire on oracle trajectories may not be informative as to
   * what action should be taken from states that the model will put itself in.
   * 
   * NOTE: For relations which use the default DecisionFunction (i.e. not a ByGroup
   * instance), a ClassEx is returned for every FP and FN with the gold and pred
   * fields respectively set to null.
   */
  public List<ClassEx> getLaso2Update(boolean classificationLoss, Set<String> oracleRollInRelations) {

    List<AgendaItem> defDfFP = new ArrayList<>();
    List<AgendaItem> defDfFN = new ArrayList<>();
    List<Step> trajForByGroupRelations = new ArrayList<>();

    // LOSS AUGMENTED INFERENCE
    assert agenda.getRescoreMode() == RescoreMode.NONE;
    assert !lossAugmentation;
    agenda.oneTimeRescore(RescoreMode.LOSS_AUGMENTED, goldEdges);
    lossAugmentation = true;
    while (agenda.size() > 0) {
      AgendaItem ai = agenda.popBoth2();
      Pair<Boolean, Adjoints> p = thresh.decide2(ai);
//      assert ai.score == p.get2();    // THEY'RE NOT, thresh hands out junk with (LearningRate 0 wrapped)
      boolean yhat = p.get1();
      boolean y = getLabel(ai);
      
      if (thresh.getDecisionFunction(ai.edge) == DecisionFunction.DEFAULT) {
        if (yhat && !y)
          defDfFP.add(ai);
        if (!yhat && y)
          defDfFN.add(ai);
      } else {
        if (yhat)
          trajForByGroupRelations.add(new Step(ai, y, yhat));
      }

      boolean addToState;
      if (oracleRollInRelations != null && oracleRollInRelations.contains(ai.edge.getRelation().getName()))
        addToState = y;
      else
        addToState = yhat;

      if (addToState)
        addEdgeToState(ai);
    }
    lossAugmentation = false;

    // Go collect the gold fact in every bucket that LA visited.
    List<ClassEx> updates = new ArrayList<>();
    for (Step s : trajForByGroupRelations) {
      assert s.pred;
      DecisionFunction.ByGroup t = (ByGroup) thresh.get(s.edge.getRelation().getName());
      if (t == null) {
        for (HypEdge f : goldEdges.getGoldEdges(false))
          System.out.println("gold: " + f);
        throw new RuntimeException("no group? " + s.edge);
      }
      List<Object> key = t.getKey(s.edge);
      Pair<HypEdge, Adjoints> g = t.getFirstGoldInBucket(key);

      if (g == null) {
        // This can also happen if frameTriage is missing some LU needed to recall the correct frame.
        if (g == null) {
          stats.increment("laso2/NO_UPDATE/lowRecall/" + s.edge.getRelation().getName());
          return Collections.emptyList();
        }

        // This can happen if you get the frame wrong, then all subsequent arg4 facts are wrong.
        // By the book, LOLS says that these should all receive negative update...
        // WAIT. What about nullSpans?
        // At the very least there should be a nullSpan which is the correct answer in every bucket.
        for (HypEdge f : goldEdges.getGoldEdges(false))
          System.out.println("gold: " + f);
        throw new RuntimeException("why is there no correct fact in this bucket: " + s.edge);
      }

      HashableHypEdge gg = new HashableHypEdge(g.get1());
      HashableHypEdge pp = new HashableHypEdge(s.edge);
      if (!gg.equals(pp)) {
        // Mistake!
        if (classificationLoss) {
          // Create a negative example for every wrong action/edge in this bucket
          for (Pair<HypEdge, Adjoints> wrong : t.getAllInBucket(key)) {
            if (!gg.equals(new HashableHypEdge(wrong.get1()))) {
              updates.add(new ClassEx(g, wrong, -1));
            }
          }
        } else {
          // Only maybe this step as a negative example
          updates.add(new ClassEx(g, new Pair<>(s.edge, s.score), -1));
        }
      }
    }
    
    // Handle all of the non-ByGroup errors
    for (AgendaItem ai : defDfFN)
      updates.add(new ClassEx(ai.edge, ai.score, null, null, -1));
    for (AgendaItem ai : defDfFP)
      updates.add(new ClassEx(null, null, ai.edge, ai.score, -1));
    
    return updates;
  }

  public static String dbgShrtStr(String x) {
    return x.replaceAll("propbank", "pb")
        .replaceAll("argument4", "arg4")
        .replaceAll("=false ", "=F ")
        .replace("=true ", "=T ");
  }

  /**
   * Roll in using oracle or model.
   * At each state, output set of correct and incorrect actions.
   *
   * Requires {@link NumArgsRoleCoocArgLoc#IMMUTABLE_FACTORS}=true
   */
  public List<AgendaSnapshot> daggerLike(double pOracleRollIn) {
    // Interpolate(oracle,model) Roll-in:
    // flip a coin for which score, then argmax w.r.t. that score.
    // Since we are making an entire run with that same score, we can just
    // flip the heap agenda once, no need to change scores mid-inference.

    // TODO Can try another way: argmax the interpolation.

    boolean oracleRollIn = rand.nextDouble() < pOracleRollIn;

    List<AgendaSnapshot> snaps = new ArrayList<>();
    while (agenda.size() > 0) {

      // Record possible actions
      List<LabledAgendaItem> a = new ArrayList<>();
      for (AgendaItem ai : agenda.getContentsInNoParticularOrder())
        a.add(ai.withLabel(getLabel(ai)));
      snaps.add(new AgendaSnapshot(a));

      // Take an action
      AgendaItem ai = agenda.popBoth2();
      boolean yhat = thresh.decide(ai);
      boolean y = getLabel(ai);
      if ((oracleRollIn && y) || (!oracleRollIn && yhat))
        addEdgeToState(ai);
    }

    return snaps;
  }

  /**
   * Holds a set of actions out of a state, where we know what all the right
   * actions are and we know what the wrong ones are, and this is used to hold
   * the needed information for an update (boost the prob/score of good actions
   * and lower the prob/score of bad ones).
   *
   * Lets say that we have a three stage pipeline, foo(x) -> bar(x) -> baz(x),
   * and we use pipeline agenda priority: stage(f) + tanh(score(f))
   * It seems like we care more about the correct and incorrect foo facts at
   * the top of the agenda more than the baz facts. Should we update those
   * more? We could flip a coin for whether we update each, where p(update)
   * trails off as you go down the heap (to facts which may not have had global
   * features fire).
   */
  public static class AgendaSnapshot {
    // In agenda order (by priority).
    // Each one is either Commit(f) or Prune(f) and either right or wrong.
    private List<LabledAgendaItem> agendaItems;

    // The hard part of this is going to be how to efficiently extract the
    // heap items and copy them in here without duplicating the entire heap.
    // I suppose I could have a non-destructive iterator for the agenda...
    // BFS traversal means almost sorted...
    // Could sort in here...

    public AgendaSnapshot(List<LabledAgendaItem> agendaContentsUnsorted) {
      this.agendaItems = agendaContentsUnsorted;
    }

    public void applyUpdate(double learningRate, boolean updateAccordingToPriority, Map<Relation, Double> costFP) {
      if (DEBUG > 1)
        Log.info("starting, learingRate=" + learningRate + " updateAccordingToPriority=" + updateAccordingToPriority + " costFP=" + costFP);
      if (updateAccordingToPriority) {
        /*
         * priority = stage(e) + tanh(score(e))
         *
         * score(e) will be <0 for most y=false edges
         * if we make the update proportional to the priority, this will mean we will be pushing down FPs more than pulling up FNs
         */
        agendaItems.sort(new Comparator<LabledAgendaItem>() {
          @Override
          public int compare(LabledAgendaItem o1, LabledAgendaItem o2) {
            if (o1.priority > o2.priority) return -1;
            if (o1.priority < o2.priority) return +1;
            return 0;
          }
        });

        int n = agendaItems.size();
        double maxP = agendaItems.get(0).priority;
        double minP = agendaItems.get(n - 1).priority;
        double r = (maxP - minP);
        assert r >= 0;
        if (r == 0)
          r = 1e-6;
//        double k = n / 5d;
        for (int i = 0; i < n; i++) {
//          double m = k / (k + i);
          LabledAgendaItem ai = agendaItems.get(i);
          double m = (1+(ai.priority - minP)) / (1+(maxP - minP));
          assert m > 0 && !Double.isNaN(m) && Double.isFinite(m) : "m=" + m;
          if (ai.label && ai.score.forwards() <= 0)
            ai.score.backwards(learningRate * -m);
          if (!ai.label && ai.score.forwards() > 0) {
            double cfp = costFP.get(ai.edge.getRelation());
            ai.score.backwards(learningRate * +m * cfp);
          }
        }
      } else {
        if (DEBUG > 2)
          Log.info("about to scan agenda snapshot of size=" + agendaItems.size());
        for (LabledAgendaItem ai : agendaItems) {
          if (ai.label && ai.score.forwards() <= 0) {
            ai.score.backwards(learningRate * -1);
            if (DEBUG > 2)
              Log.info("FN: backwards=" + (learningRate * -1) + " " + ai);
          } else if (!ai.label && ai.score.forwards() > 0) {
            double cfp = costFP.get(ai.edge.getRelation());
            ai.score.backwards(learningRate * +1 * cfp);
            if (DEBUG > 2)
              Log.info("FP: backwards=" + (learningRate * +1 * cfp) + " " + ai);
          } else {
            if (DEBUG > 2)
              Log.info("fine: " + ai);
          }
        }
      }
    }
  }

  /**
   * Runs inference, records the HypEdge popped at every step, and adds gold
   * HypEdges to the state graph as it goes (pruning other edges which are
   * popped but not gold).
   *
   * You must have added labels before calling this method.
   *
   * Note on duplicates: this method will add duplicate HypEdges on the
   * assumption that their *features* may not be the same. You may have two
   * ways of deriving a particular fact, e.g.
   *   srl2'(s2,...) & event2'(e2,...) & ... => srl3(s2,e2,...)
   *   event2'(e2,...) & srl2'(s2,...) & ... => srl3(s2,e2,...)
   * Both state minimum requirements for the state graph based on the LHS, but
   * the features could be global: they can reflect on all parts of the state
   * graph, unless you design them otherwise.
   * If you KNOW your features only look at the LHS of the rule (and not further
   * into the state graph), then you can remove duplicates yourself.
   * Though these duplicates are present, oracle recall is handled properly.
   */
  public List<Step> recordOracleTrajectory(boolean dedupEdges) {
    throw new RuntimeException("re-impelment me or use dbgRunInference");
//    if (COARSE_EVENT_LOGGING)
//      Log.info("starting " + dedupEdges);
//    if (goldEdges == null)
//      throw new IllegalStateException("you must add labels, goldEdges==null");
////    if (pNegKeep < 0 || pNegKeep > 1)
////      throw new IllegalArgumentException();
//    timer.start(REC_ORACLE_TRAJ);
//
//    Set<HashableHypEdge> uniqEdges = new HashSet<>();
//
//    Labels.Perf perf = goldEdges.new Perf();
//    List<Step> traj = new ArrayList<>();
//    while (agenda.size() > 0) {
//      Pair<HypEdge, Adjoints> p = agenda.popBoth();
//      HypEdge edge = p.get1();
//      boolean gold = perf.add(edge);
//      if (DEBUG)
//        System.out.println("[recOrcl] gold=" + gold + " " + edge);
//      traj.add(new Step(edge, p.get2(), gold));
//      if (gold) {
//        if (!dedupEdges || uniqEdges.add(new HashableHypEdge(edge)))
//          addEdgeToState(edge);
//      }
//    }
//
//    if (showTrajDiagnostics)
//      showTrajPerf(traj, perf);
//
//    timer.stop(REC_ORACLE_TRAJ);
//    return traj;
  }

  private void showTrajPerf(List<Step> traj, Labels.Perf perf) {
    Log.info("traj.size=" + traj.size());

//    Map<Relation, FPR> pbr = perf.perfByRel2();
//    for (Relation rel : goldEdges.getLabeledRelation()) {
//      Log.info("relation=" + rel.getName()
//          + " perf=" + pbr.get(rel)
//          + " n=" + goldEdges.getRelCount(rel.getName()));
//
//      for (HypEdge fn : perf.getFalseNegatives(rel)) {
//        System.out.println("\tfn: " + fn);
////          if ("srl1".equals(fn.getRelation().getName())) {
////            int i = Integer.parseInt((String) fn.getTail(0).getValue());
////            int j = Integer.parseInt((String) fn.getTail(1).getValue());
////            System.out.println("\tfn: " + fn + "\t" + dbgGetSpan(i, j));
////          } else {
////            System.out.println("\tfn: " + fn);
////          }
//      }
//    }

    Counts<String> c = new Counts<>();
    for (Step s : traj) {
      // Counts stuff
      if (c != null) {
        String r = s.edge.getRelation().getName();
        c.increment(r);
        c.increment(r + "/pred=" + s.pred);
        if (s.gold != null) {
          c.increment(r + "/gold=" + s.gold);
          if (s.gold && s.pred)
            c.increment(r + "/TP");
          else if (s.gold && !s.pred)
            c.increment(r + "/FN");
          else if (!s.gold && s.pred)
            c.increment(r + "/FP");
          else if (!s.gold && !s.pred)
            c.increment(r + "/TN");
        }
      }
    }
    System.out.println("[showTrajPerf] " + c);
  }

  /**
   * @param i start token index inclusive
   * @param j end token index exclusive
   */
  public List<String> dbgGetSpan(int i, int j) {
    assert j >= i;
    Relation word2 = getEdgeType("word2");
    NodeType tokenNT = lookupNodeType("tokenIndex", false);
    List<String> s = new ArrayList<>();
    for (int t = i; t < j; t++) {
      HypNode tokenN = lookupNode(tokenNT, String.valueOf(t), false, true);
      HypEdge wt = state.match1(0, word2, tokenN);
      String wordVal = (String) wt.getTail(1).getValue();
      s.add(wordVal);
    }
    return s;
  }

  public Random getRandom() {
    return rand;
  }

  public State getState() {
    return state;
  }

  /**
   * Try not to use this, since you may get bugs if you don't use methods on
   * this class like clearAgenda()!
   */
  public Agenda getAgenda() {
    return agenda;
  }

  public void clearAgenda() {
    agenda.clear();
    thresh.clear();
  }

  public static String stripComment(String line) {
    int c = line.indexOf('#');
    if (c >= 0)
      return line.substring(0, c);
    return line;
  }

  /** returns (line, comment) */
  public static Pair<String, String> stripComment2(String line) {
    int c = line.indexOf('#');
    if (c >= 0)
      return new Pair<>(line.substring(0, c).trim(), line.substring(c+1).trim());
    return new Pair<>(line, null);
  }

  public List<Relation> readRelData(File f) throws IOException {
    return readRelData(f, true);
  }
  public List<Relation> readRelData(File f, boolean noMatch) throws IOException {
    if (!f.isFile())
      throw new IllegalArgumentException("not a file: " + f.getPath());
    Log.info("reading rel data from: " + f.getPath());
    try (BufferedReader r = FileUtil.getReader(f)) {
      return readRelData(r, noMatch);
    }
  }

  /**
   * read in data like:
   * "def word2 <tokenIndx> <word>", "x word2 0 John", "y pos2 0 NNP", etc
   * and setup state/labels accordingly.
   *
   * TODO Use {@link RelationFileIterator} or at least {@link RelLine}
   *
   * @return a list of new {@link Relation}s defined in this file/reader.
   */
  public List<Relation> readRelData(BufferedReader r) throws IOException  {
    return readRelData(r, true);
  }
  public List<Relation> readRelData(BufferedReader r, boolean noMatch) throws IOException  {
    List<Relation> defs = new ArrayList<>();
    for (String line = r.readLine(); line != null; line = r.readLine()) { 
      Relation rr = readRelData(line, noMatch);
      if (rr != null)
        defs.add(rr);
    }
    return defs;
  }

  public void readRelData(RelDoc d) {
    readRelData(d.def);
    if (!d.facts.isEmpty())
      throw new IllegalArgumentException();
    for (RelLine l : d.items)
      readRelData(l);
  }
  public Relation readRelData(RelLine line) {
    return readRelData(line.toLine());
  }
  public Relation readRelData(String line) {
    return readRelData(line, false);
  }
  public Relation readRelData(String line, boolean noMatch) {
    String relName;
    Relation def = null;
    line = stripComment(line);
    if (line.isEmpty())
      return null;
    String[] toks = line.split("\\s+");
    String command = toks[0];
    switch (command) {
    case "startdoc":
//      Log.warn("skipping multi-doc line: " + line);
//      dbgMakeEdge(factString, isSchema)
//      this.addEdgeToStateNoMatch(e, Adjoints.Constant.ZERO);
      assert toks.length == 2;
      String docid = toks[1];
      NodeType docidNT = lookupNodeType("docid", false);
      Relation startDocRel = getEdgeType("startDoc");
      HypNode docidN = lookupNode(docidNT, docid, true /* addIfNotPresent */, false /* isSchema */);
      addEdgeToState(makeEdge(false /* isSchema */, startDocRel, docidN), Adjoints.Constant.ZERO);
      break;
    case "def":
      relName = toks[1];
      NodeType[] argTypes = new NodeType[toks.length - 2];
      for (int i = 0; i < argTypes.length; i++) {
        String argType = toks[i + 2];
        if (argType.charAt(0) == '<') {
          int n = argType.length();
          assert argType.charAt(n-1) == '>';
          argType = argType.substring(1, n-1);
        }
        argTypes[i] = this.lookupNodeType(argType, true);
      }
      def = new Relation(relName, argTypes);
      this.addEdgeType(def);
      break;
    case "schema":
    case "x":
    case "y":
      boolean schema = "schema".equals(command);
      relName = toks[1];
      Relation rel = this.getEdgeType(relName);
      HypNode[] args = new HypNode[toks.length-2];
      for (int i = 0; i < args.length; i++)
        args[i] = lookupNode(rel.getTypeForArg(i), toks[i+2], true, schema);
      HypEdge e = makeEdge(schema, rel, args);
      if (command.equals("y")) {
        this.addLabel(e);
      } else {
        if (noMatch)
          this.addEdgeToStateNoMatch(e, Adjoints.Constant.ZERO);
        else
          this.addEdgeToState(e, Adjoints.Constant.ZERO);
      }
      break;
    default:
      throw new RuntimeException("unknown-command: " + command);
    }
    return def;
  }

  /**
   * Use this rather than calling the {@link HypNode} constructor so that nodes
   * are guaranteed to be unique.
   */
  public HypNode lookupNode(NodeType nt, Object value) {
    return nodes3.lookupNode(nt, value, false);
  }
  public HypNode lookupNode(NodeType nt, Object value, boolean addIfNotPresent, boolean isSchema) {
    return nodes3.lookupNode(nt, value, addIfNotPresent, isSchema);
  }

  /**
   * Use this rather than calling the {@link NodeType} constructor so that nodes
   * types are gauranteed to be unique.
   */
  public NodeType lookupNodeType(String name, boolean allowNewNodeType) {
    NodeType nt = nodeTypes.get(name);
    if (nt == null) {
      if (!allowNewNodeType)
        throw new RuntimeException("there is no NodeType called " + name);
      if (DEBUG > 1)
        Log.info("adding NodeType for the first time: " + name);
      nt = new NodeType(name);
      nodeTypes.put(name, nt);
    }
    return nt;
  }

  public void addGlobalFactor(Term[] terms, GlobalFactor gf) {
    if (DEBUG > 0)
      Log.info("adding " + Arrays.toString(terms) + " => " + gf);
    Trigger trigger = lookupTrigger(terms);
    int i = trigger.getIndex();
    if (globalFactors[i] == null)
      globalFactors[i] = gf;
    else
      globalFactors[i] = new GlobalFactor.Composite(globalFactors[i], gf);
  }

  public Trigger lookupTrigger(Term[] terms) {
    if (DEBUG > 0)
      Log.info("terms=" + Arrays.toString(terms));
    Trigger t = new Trigger(terms, numTriggers);
    for (int i = 0; i < numTriggers; i++) {
      if (t.equivalent(triggers[i])) {
        if (DEBUG > 0)
          Log.info("equivalent triggers: " + t + " and " + triggers[i]);
        return triggers[i];
      }
    }
    if (numTriggers == triggers.length)
      growTriggers();
    trie3.add(t);
    triggers[t.getIndex()] = t;
    numTriggers++;
    return t;
  }

  private void growTriggers() {
    int newLength = (int) (1.6 * triggers.length + 1);
    triggers = Arrays.copyOf(triggers, newLength);
    globalFactors = Arrays.copyOf(globalFactors, newLength);
    transitionGenrators = Arrays.copyOf(transitionGenrators, newLength);
  }

  public void addTransitionGenerator(Rule r, LocalFactor s, boolean pruneFactsWithNullScore) {
    Trigger t = lookupTrigger(r.lhs);
    TransGen tg = new TransGen.Regular(r, s, pruneFactsWithNullScore);
    addTransitionGenerator(t, tg);
  }
  public void addTransitionGenerator(Term[] t, TransGen tg) {
    addTransitionGenerator(lookupTrigger(t), tg);
  }
  public void addTransitionGenerator(Trigger t, TransGen tg) {
    if (DEBUG > 0)
      Log.info("adding " + tg);
    int i = t.getIndex();
    if (transitionGenrators[i] == null)
      transitionGenrators[i] = tg;
    else
      transitionGenrators[i] = new TransGen.Composite(transitionGenrators[i], tg);
  }


  private boolean nodesContains(HypNode n) {
    return nodes3.contains(n);
  }
  private boolean nodesContains(HypEdge e) {
    if (e.getHead() != null && !nodesContains(e.getHead())) {
      Log.info("missing head=" + e.getHead());
      return false;
    }
    int n = e.getNumTails();
    for (int i = 0; i < n; i++) {
      if (!nodesContains(e.getTail(i))) {
        Log.info("missing: tail[" + i + "]=" + e.getTail(i));
        return false;
      }
    }
    return true;
  }

  public AgendaItem addEdgeToState(AgendaItem ai) {
    addEdgeToState(ai.edge, ai.score);
    return ai;
  }
  public HypEdge addEdgeToState(HypEdge e, Adjoints score) {
    if (DEBUG > 1)
      System.out.println("Uberts addEdgeToState: " + e.toString() + " " + score.forwards() + " " + score);
    if (stats != null) {
      stats.increment("state");
      stats.increment("state/" + e.getRelation().getName());
    }
    assert nodesContains(e);

    boolean nilFact = UbertsLearnPipeline.isNilFact(e);
    if (nilFact) {
      stats.increment("state/nilFact");
//      return null;
    } else {
      state.add(e, score);
    }

    HashableHypEdge he = new HashableHypEdge(e);
    Boolean y = goldEdges == null ? null : getLabel(he);
    if (newStateEdgeListeners.size() > 0)
      throw new RuntimeException("figure out if they should get nil facts or if I should remove NewStateEdgeListeners altogether");
    for (NewStateEdgeListener l : newStateEdgeListeners)
      l.addedToState(he, score, y);

    trie3.match(state, e, m -> {
      Trigger t = m.getTrigger();
      int ti = t.getIndex();
      HypEdge[] values = m.getValues();

      if (DEBUG > 2) {
        Log.info("just tripped: " + t
            + " with values=" + Arrays.toString(values)
            + " nilFact=" + nilFact
            + " triggersGlobalFactor=" + (globalFactors[ti] != null)
            + " triggersTransitionGenerator=" + (transitionGenrators[ti] != null));
      }

      if (globalFactors[ti] != null && !disableGlobalFeats)
        globalFactors[ti].rescore(this, values);

      if (!nilFact && transitionGenrators[ti] != null) {
        // TODO This could become a bug, or at least unexpected behavior.
        // Triggers for global features are not tripped when an edge is first
        // created.
        List<Pair<HypEdge, Adjoints>> edges = transitionGenrators[ti].match(values, this);
        for (Pair<HypEdge, Adjoints> se : edges)
          addEdgeToAgenda(se);
      }
    });
    return e;
  }

  /**
   * Adds an edge to the state WITHOUT checking if any rules fire for this new
   * fact. This is a useful optimization for setup, but make sure there is some
   * other trigger like doneAnno(docid) which ensures that you're rules fire.
   */
  public void addEdgeToStateNoMatch(HypEdge e, Adjoints score) {
    assert nodesContains(e) : e + " has some HypNodes which aren't in Uberts.nodes";
    if (DEBUG > 1)
      System.out.println("Uberts addEdgeToStateNoMatch: " + e.toString() + " " + score.forwards() + " " + score);

    if (UbertsLearnPipeline.isNilFact(e)) {
      stats.increment("state/nilFact");
      return;
    }

    stats.increment("state/noMatch");
    stats.increment("state/noMatch/" + e.getRelation().getName());
    state.add(e, score);
  }

  public void addEdgeToAgenda(Pair<HypEdge, Adjoints> p) {
    addEdgeToAgenda(p.get1(), p.get2());
  }
  public void addEdgeToAgenda(HypEdge e, Adjoints score) {
    HashableHypEdge hhe = new HashableHypEdge(e);
    boolean y = getLabel(hhe);
    if (DEBUG > 2) {
      if (DEBUG > 3)
        System.out.println("[Uberts addEdgeToAgenda] y=" + y + "\t" + e.toString() + "\t" + score.forwards() + "\t" + score);
      else
        System.out.println("[Uberts addEdgeToAgenda] y=" + y + "\t" + e.toString());
    }
    if (stats != null) {
      stats.increment("agenda");
      stats.increment("agenda/" + e.getRelation().getName());
    }
    assert nodesContains(e);
    if (agenda.contains(hhe)) {
      stats.increment("agenda/dup/agenda");
      stats.increment("agenda/dup/agenda/" + e.getRelation().getName());
    } else if (state.getScore(hhe) != null) {
      stats.increment("agenda/dup/state");
      stats.increment("agenda/dup/state/" + e.getRelation().getName());
    } else {
      if (lossAugmentation && !y)
        score = Adjoints.sum(score, Adjoints.Constant.ONE);
      agenda.add(hhe, score);
    }
  }

  /** returns its argument */
  public Relation addEdgeType(Relation r) {
    Log.info(r.toString());
    Relation old = relations.put(r.getName(), r);
    if (old != null)
      throw new IllegalStateException("already existed: " + r);
    return r;
  }
  public Relation getEdgeType(String name) {
    return getEdgeType(name, false);
  }
  public Relation getEdgeType(String name, boolean allowNull) {
    Relation r = relations.get(name);
    if (r == null && !allowNull)
      throw new IllegalArgumentException("no relation named: " + name);
    return r;
  }
  public List<Relation> getAllEdgeTypes() {
    List<Relation> l = new ArrayList<>();
    l.addAll(relations.values());
    return l;
  }

  public Counts<String> getStateFactCounts() {
    Counts<String> c = new Counts<>();
    for (HypEdge e : state.getEdges())
      c.increment(e.getRelation().getName());
    return c;
  }

  public NodeType getWitnessNodeType(String relationName) {
    return getWitnessNodeType(getEdgeType(relationName));
  }
  public NodeType getWitnessNodeType(Relation relation) {
    String wntName = getWitnessNodeTypeName(relation);
    return lookupNodeType(wntName, true);
  }
  public static String getWitnessNodeTypeName(Relation relation) {
    return getWitnessNodeTypeName(relation.getName());
  }
  public static String getWitnessNodeTypeName(String relationName) {
//    return ("witness-" + relationName).intern();
    return "witness-" + relationName;
  }

  /**
   * @param lookupHypNodes says whether {@link HypNode}s should be looked up and
   * de-dup'd (this is the case for normal inference where you want, e.g.
   * (tokenIndex 5) to mean the same exact node regardless of where tokenIndex
   * and 5 came from) OR if you just want to call new HypNode (as is the case if
   * you just want to write an edge to a file).
   */
  public HypEdge.WithProps makeEdge(RelLine line, boolean lookupHypNodes) {
    Relation r = getEdgeType(line.tokens[1]);
    assert r.getNumArgs() == line.tokens.length-2
        : "values=" + Arrays.toString(Arrays.copyOfRange(line.tokens, 2, line.tokens.length))
        + " does not match up with " + r.getDefinitionString()
        + " line=\"" + line.toLine() + "\""
        + " providence=" + line.providence;
    boolean isSchema = line.isSchema();
    HypNode[] tail = new HypNode[r.getNumArgs()];
    for (int i = 0; i < tail.length; i++) {
      NodeType nt = r.getTypeForArg(i);
      Object value = line.tokens[i+2];
      if (lookupHypNodes)
        tail[i] = lookupNode(nt, value, true, isSchema);
      else
        tail[i] = new HypNode(nt, value);
    }
    HypEdge.WithProps e;
    if (lookupHypNodes)
      e = makeEdge(isSchema, r, tail);
    else
      e = new HypEdge.WithProps(r, null, tail, 0);
    e.setProperty(HypEdge.IS_SCHEMA, isSchema);
    e.setProperty(HypEdge.IS_X, line.isX());
    e.setProperty(HypEdge.IS_Y, line.isY());
    return e;
  }
  public HypEdge.WithProps makeEdge(boolean isSchema, String relationName, HypNode... tail) {
    Relation r = getEdgeType(relationName);
    return makeEdge(isSchema, r, tail);
  }
  public HypEdge.WithProps makeEdge(boolean isSchema, Relation r, HypNode... tail) {
    NodeType headType = getWitnessNodeType(r);
    Object encoded = r.encodeTail(tail);
    HypNode head = lookupNode(headType, encoded, true, isSchema);
    long mask = isSchema ? HypEdge.IS_SCHEMA : 0;
    return new HypEdge.WithProps(r, head, tail, mask);
  }

  /**
   * Given the head of some fact, get back the fact.
   */
  public HypEdge getEdgeFromHeadVar(HypNode head) {
    // TODO this is a bit jenk, should I make encodeTail tuple up the Relation too?
    String prefix = "witness-";
    assert head.getNodeType().getName().startsWith(prefix);
    String relName = head.getNodeType().getName().substring(prefix.length());
    Relation rel = getEdgeType(relName, false);
    return state.match1(State.HEAD_ARG_POS, rel, head);
  }

  /**
   * Given a string like "pos2(4,NNS)" or "predicate2(3-4,propbank/kill-v-1)",
   * parse out the relation and create a new fact where every argument is
   * represented as a string. Should really only be used for debugging/testing.
   *
   * @param factString will be split on commas, so arguments cannot contain commas.
   */
  public HypEdge dbgMakeEdge(String factString, boolean isSchema) {
    factString = factString.trim();
    int lp = factString.indexOf('(');
    int rp = factString.indexOf(')');
    assert rp == factString.length() - 1;
    Relation r = getEdgeType(factString.substring(0, lp));
    String[] args = factString.substring(lp + 1, rp).split(",");
    HypNode[] tail = new HypNode[args.length];
    for (int i = 0; i < args.length; i++)
      tail[i] = lookupNode(r.getTypeForArg(i), args[i].trim(), true, isSchema);
    return makeEdge(isSchema, r, tail);
  }

}
