package edu.jhu.hlt.uberts;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda.AgendaItem;
import edu.jhu.hlt.uberts.Agenda.LabledAgendaItem;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.auto.Rule;
import edu.jhu.hlt.uberts.auto.Term;
import edu.jhu.hlt.uberts.auto.Trigger;
import edu.jhu.hlt.uberts.factor.GlobalFactor;
import edu.jhu.hlt.uberts.factor.LocalFactor;
import edu.jhu.hlt.uberts.factor.NumArgsRoleCoocArgLoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;
import edu.jhu.hlt.uberts.rules.Env.Trie3;
import edu.jhu.hlt.uberts.transition.TransGen;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.HPair;

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

  // Log things like when an edge is added to the state or a rule fires
  /* deprecated */
  public static boolean COARSE_EVENT_LOGGING = false;

  public static final String REC_ORACLE_TRAJ = "recordOracleTrajectory";

  private State state;
  private Agenda agenda;

  // TNode holds TransGen (which holds LocalFeatures) and GlobalFactors
  private Trie3 trie3;

  // All of these are co-indexed
  private Trigger[] triggers;     // first numTriggers values are non-null
  private TransGen[] transitionGenrators; // may contain nulls
  private GlobalFactor[] globalFactors;   // may contain nulls
  private int numTriggers;

  // What happens if we have multiple factors with the same trigger?
  // Every time you add a Trigger->TG|GF, then we check if its a new trigger.
  // If no, then we merge the TG|GF (they must be "summable/mergable").

  private Random rand;
  private MultiTimer timer;

  // So you can ask for Relations by name and keep them unique
  private Map<String, Relation> relations;

  // Alphabet of HypNodes which appear in either state or agenda.
//  private Map<Pair<NodeType, Object>, HypNode> nodes;
  private Map<HPair<NodeType, Object>, HypNode> nodes;

  // Never call `new NodeType` outside of Uberts, use lookupNodeType
  private Map<String, NodeType> nodeTypes;

  // Supervision (set of gold HypEdges)
  private Labels goldEdges;

  public boolean showTrajDiagnostics = false;

  // Tracks things like number of pushes and pops, can be externally read/reset
  public Counts<String> stats = new Counts<>();
  // i^th value is the size of the agenda after applying i actions.
  // This must be cleared after each round of inference by the user.
  public IntArrayList statsAgendaSizePerStep = new IntArrayList();

  // Ancillary data for features which don't look at the State graph.
  // New data backend (used to be fnparse.Sentence and FNParse)
  // TODO Update TemplateContext and everything in BasicFeatureTemplates.
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
      goldEdges = new Labels();
    goldEdges.add(e);;
  }
  public boolean getLabel(HypEdge e) {
    return goldEdges.contains(e);
  }
  public boolean getLabel(HashableHypEdge e) {
    return goldEdges.contains(e);
  }
  /**
   * Sets the set of gold edges to the empty set.
   */
  public void initLabels() {
    goldEdges = new Labels();
  }

  /**
   * You need to do this to release memory if you are calling lookupNode on all
   * the data you're processing.
   */
  public void clearNodes() {
    nodes.clear();
  }

  public Uberts(Random rand) {
    this(rand, (edge, score) -> score.forwards());
  }
  public Uberts(Random rand, BiFunction<HypEdge, Adjoints, Double> agendaPriority) {
    this.rand = rand;
    this.relations = new HashMap<>();
    this.agenda = new Agenda(agendaPriority);
    this.state = new State.Split();
//    this.trie = new TNode(null, null);
    this.trie3 = Trie3.makeRoot();
    this.nodes = new HashMap<>();
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

  // TODO This is an ugly hack, fixme.
  public Relation addSuccTok(int n) {
    NodeType tokenIndex = lookupNodeType("tokenIndex", true);
    Relation succTok = addEdgeType(new Relation("succTok", tokenIndex, tokenIndex));
    HypNode prev = lookupNode(tokenIndex, String.valueOf(-1), true);
    for (int i = 0; i < n; i++) {   // TODO figure out a better way to handle this...
      HypNode cur = lookupNode(tokenIndex, String.valueOf(i), true);
      HypEdge e = makeEdge(succTok, prev, cur);
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
  public Pair<Labels.Perf, List<Step>> dbgRunInference(
      boolean oracle,
      double minScore,
      int actionLimit) {
    if (DEBUG > 0)
      Log.info("starting, oracle=" + oracle + " minScore=" + minScore + " actionLimit=" + actionLimit);
    statsAgendaSizePerStep.clear();

    Labels.Perf perf = null;
    if (goldEdges != null)
      perf = goldEdges.new Perf();

    List<Step> steps = new ArrayList<>();
    for (int i = 0; agenda.size() > 0; i++) {// && (actionLimit <= 0 || i < actionLimit); i++) {
      statsAgendaSizePerStep.add(agenda.size());
      AgendaItem ai = agenda.popBoth2();
      Boolean y = perf == null ? null : getLabel(ai.edge);
      if (DEBUG > 1)
        System.out.println("[dbgRunInference] popped=" + ai);

      // Always record the action
      boolean hitLim = actionLimit > 0 && i >= actionLimit;
      boolean pred = !hitLim && ai.score.forwards() > minScore;
      steps.add(new Step(ai, y, pred));

      // But maybe don't add apply it (add it to state)
      if (hitLim)
        continue;
      if ((oracle && y) || pred) {
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
    return dbgRunInference(false, Double.NEGATIVE_INFINITY, 0);
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
      boolean y = getLabel(ai.edge);
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
          totalLoss = Labels.combinePerfByRel(prev.totalLoss, actionLoss);
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
   * This is sort of a pun on the real max-violation algorithm. The original
   * version assumes a transition system where states can be said to fall into
   * a statically known (depending only on x, not actions) indexable sequence.
   * Meaning you can talk about "the i^th action" and mean something comparable
   * on any trajectory. This is fine if you are doing something like tagging
   * tokens from left to right ("i^th action" means "tag of token i"), but it is
   * not fine if some actions are pruning actions which affect the space of
   * future actions.
   *
   * The pun that we use is to count up from i=0. The first actions are
   * comparable (they add some fact starting from the initial state). After that
   * they may not be comparable. E.g. At i=10, the oracle might be applying
   * foo(x) & bar(y) => baz(z), but the predictor may have never built foo(x) or
   * bar(y) based on earlier choices/mistakes, so asking the predictor to apply
   * the baz(z) rule may be a bad idea.
   *
   * If the oracle produces a length N trajectory, and the predictor length M,
   * we take the max-violator over length min(N,M) prefixes.
   *
   * See http://www.aclweb.org/anthology/N12-1015
   */
  public Pair<Traj, Traj> maxViolationPerceptron(Map<Relation, Double> costFP) {

    timer.start("duplicate-state");
    State s0 = state.duplicate();
    timer.stop("duplicate-state");

    timer.start("duplicate-agenda");
    Agenda a0 = agenda.duplicate();
    timer.stop("duplicate-agenda");

    // Oracle
    Traj t1 = null;
    {
      while (agenda.size() > 0) {
        AgendaItem ai = agenda.popBoth2();
        boolean y = getLabel(ai.edge);
        boolean yhat = ai.score.forwards() > 0;
        if (DEBUG > 1)
          System.out.println("[dbgRunInference] popped=" + ai);

        // Always record the action
        Step s = new Step(ai, y, yhat);
        t1 = new Traj(s, t1);
//        System.out.println("MV oracle, y=" + s.gold + " yhat=" + s.pred + " " + s.edge);

        // But maybe don't add apply it (add it to state)
        if (y)
          addEdgeToState(ai);
      }
    }
    assert t1 != null : "oracle took no steps?";

    // Predictions
    state = s0;
    agenda = a0;
    Traj t2 = null;
    {
      // We can stop the trajectory earlier if it goes longer than the oracle,
      // which is likely to happen.
      statsAgendaSizePerStep.clear();
      while (agenda.size() > 0 && (t2 == null || t2.length < t1.length)) {
        statsAgendaSizePerStep.add(agenda.size());
        AgendaItem ai = agenda.popBoth2();
        boolean y = getLabel(ai.edge);
        boolean yhat = ai.score.forwards() > 0;
        if (DEBUG > 1)
          System.out.println("[dbgRunInference] popped=" + ai);

        // Always record the action
        Step s = new Step(ai, y, yhat);
        t2 = new Traj(s, t2);
//        System.out.println("MV pred, y=" + s.gold + " yhat=" + s.pred + " " + s.edge);

        // But maybe don't add apply it (add it to state)
        if (yhat)
          addEdgeToState(ai);
      }
    }

    // TODO Return (state,agenda) back to how they were?
    // We kept a copy around anyway...

    // Compute the max-violator
    Pair<Traj, Traj> best = null;
    double bestViolation = 0;
    Deque<Traj> t1r = t1.reverse();
    Deque<Traj> t2r = t2.reverse();
    while (!t1r.isEmpty() && !t2r.isEmpty()) {
      Traj t1cur = t1r.pop();
      Traj t2cur = t2r.pop();
      double violation =  t2cur.getScorePlusLoss(costFP) - t1cur.totalScore;
      if (violation > bestViolation) {
        bestViolation = violation;
        best = new Pair<>(t1cur, t2cur);
      }
    }

    System.out.println("bestViolation=" + bestViolation);

    return best;
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
        a.add(ai.withLabel(getLabel(ai.edge)));
      snaps.add(new AgendaSnapshot(a));

      // Take an action
      AgendaItem ai = agenda.popBoth2();
      boolean y = getLabel(ai.edge);
      boolean yhat = ai.score.forwards() > 0;
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
        for (LabledAgendaItem ai : agendaItems) {
          if (ai.label && ai.score.forwards() <= 0)
            ai.score.backwards(learningRate * -1);
          if (!ai.label && ai.score.forwards() > 0) {
            double cfp = costFP.get(ai.edge.getRelation());
            ai.score.backwards(learningRate * +1 * cfp);
          }
        }
      }
    }
  }

  /**
   * Use this when you don't have any global factors (the scores of actions
   * don't affect one another).
   *
   * Works by using the agenda as an argmax at every step. The best action is
   * taken and all the others are removed.
   *
   * WARNING: This is not compatible with schemes where you push a bunch of
   * gold edges on to the agenda (with some oracle feature which knows that
   * they're gold). Since the agenda is emptied at every step, all but one of
   * these gold edges will be removed at the first step, limiting recall.
   *
   * NOTE: This should be removed once you implement a beam Agenda (which has a
   * maximum size) -- just use a max size of 1.
   */
  public Pair<Labels.Perf, List<Step>> runLocalInference(boolean oracle, double minScore, int actionLimit) {
    throw new RuntimeException("re-impelment me or use dbgRunInference");
//    if (COARSE_EVENT_LOGGING)
//      Log.info("starting, oracle=" + oracle + " minScore=" + minScore + " actionLimit=" + actionLimit);
//    Labels.Perf perf = goldEdges.new Perf();
//    List<Step> steps = new ArrayList<>();
//    for (int i = 0; agenda.size() > 0 && (actionLimit <= 0 || i < actionLimit); i++) {
//      Pair<HypEdge, Adjoints> p = agenda.popBoth();
//      HypEdge best = p.get1();
//      boolean y = getLabel(best);
//      if (!y && oracle)
//        continue;
//      if (p.get2().forwards() < minScore)
//        break;
//      agenda.clear();
//      perf.add(best);
//      steps.add(new Step(p, y));
//      addEdgeToState(best);
//    }
//    return new Pair<>(perf, steps);
  }

//  /**
//   * Normally, the oracle takes the best action you give it, and says "yes, add
//   * that edge to the state" or "no, throw that edge out". A better oracle could
//   * have their own beam, with edges ordered differently, and pick whatever edge
//   * they want. I have some temporal ordering in the dependencies of my edges
//   * which makes the first oracle kind of sucky. E.g.:
//   *
//   * a => b
//   * c => d
//   * b & d => y
//   *
//   * Suppose we put the a's and c's on the agenda which are needed to get to y.
//   * If the oracle adds the a nodes to the state AFTER the c nodes, then the
//   * d will have been there and the b will trigger the last rule. If the oracle
//   * chooses the A nodes first, there are y that we will never derive because
//   * the rule doesn't fire.
//   *
//   * This oracle will find the first action which produces a gold edge.
//   *
//   * NOTE: I have been confused on what the state/action space is, thinking that
//   * I only have one action available to me: the one at the top of the agenda.
//   * That isn't true for this model (with some given params), but not this
//   * transition system per se (if the parameters were different, the order of
//   * the agenda would be different, and thus the action would be different).
//   * The proper way to think about is that all actions on the agenda are possible
//   * next states (which an oracle could choose instead of the highest scoring
//   * one). Each action has a loss (not cost, as the distinction is made in the
//   * dagger paper) of 1 if that HypEdge is not in the gold set and 0 otherwise.
//   * Could even add a "how many edges got added to the agenda as a result of
//   * adding this edge to the state" penalty term.
//   */
//  public void dbgRunAgressiveOracleStep(List<Step> addTo) {
//    for (HypEdge e : agenda.getContentsInNoParticularOrder()) {
//      if (getLabel(e)) {
//        addTo.add(new Step(e, agenda.getScore(e), true));
//        break;
//      }
//    }
//  }

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
    Map<Relation, FPR> pbr = perf.perfByRel2();
    for (Relation rel : goldEdges.getLabeledRelation()) {
      Log.info("relation=" + rel.getName()
          + " perf=" + pbr.get(rel)
          + " n=" + goldEdges.getRelCount(rel.getName()));
      for (HypEdge fn : perf.getFalseNegatives(rel)) {
        System.out.println("\tfn: " + fn);
//          if ("srl1".equals(fn.getRelation().getName())) {
//            int i = Integer.parseInt((String) fn.getTail(0).getValue());
//            int j = Integer.parseInt((String) fn.getTail(1).getValue());
//            System.out.println("\tfn: " + fn + "\t" + dbgGetSpan(i, j));
//          } else {
//            System.out.println("\tfn: " + fn);
//          }
      }
    }
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
      HypNode tokenN = lookupNode(tokenNT, String.valueOf(t), false);
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
  public Agenda getAgenda() {
    return agenda;
  }
//  public TNode getGraphFragments() {
////    return trie;
//    throw new RuntimeException("leaky abstraction!");
//  }

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
      return new Pair<>(line.substring(0, c), line.substring(c+1).trim());
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
      Log.warn("skipping multi-doc line: " + line);
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
      relName = toks[1];
      Relation rel = this.getEdgeType(relName);
      HypNode[] args = new HypNode[toks.length-2];
      for (int i = 0; i < args.length; i++)
        args[i] = this.lookupNode(rel.getTypeForArg(i), toks[i+2], true);
      HypEdge e = this.makeEdge(rel, args);
      if (command.equals("schema"))
        e = new HypEdge.WithProps(e, HypEdge.IS_SCHEMA);
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
  public HypNode lookupNode(NodeType nt, Object value, boolean addIfNotPresent) {
//    Pair<NodeType, Object> key = new Pair<>(nt, value);
    HPair<NodeType, Object> key = new HPair<>(nt, value);
    HypNode v = nodes.get(key);
    if (v == null && addIfNotPresent) {
      v = new HypNode(nt, value);
      nodes.put(key, v);
    }
    return v;
  }

  /**
   * Prefer lookupNode if you can. Throws exception if this node exists.
   */
  public void putNode(HypNode n) {
//    Pair<NodeType, Object> key = new Pair<>(n.getNodeType(), n.getValue());
    HPair<NodeType, Object> key = new HPair<>(n.getNodeType(), n.getValue());
    HypNode old = nodes.put(key, n);
    if (old != null)
      throw new RuntimeException("duplicate: " + key);
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

  public void addTransitionGenerator(Rule r, LocalFactor s) {
    Trigger t = lookupTrigger(r.lhs);
    addTransitionGenerator(t, new TransGen.Regular(r, s));
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
//    HypNode n2 = nodes.get(new Pair<>(n.getNodeType(), n.getValue()));
    HypNode n2 = nodes.get(new HPair<>(n.getNodeType(), n.getValue()));
    return n2 == n;
  }
  private boolean nodesContains(HypEdge e) {
    if (e.getHead() != null && !nodesContains(e.getHead())) {
      Log.warn("missing head=" + e.getHead());
      return false;
    }
    int n = e.getNumTails();
    for (int i = 0; i < n; i++) {
      if (!nodesContains(e.getTail(i))) {
        if (DEBUG > 1)
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
      stats.increment("pop");
      stats.increment("pop/" + e.getRelation().getName());
    }
    assert nodesContains(e);
    state.add(e, score);
    trie3.match(state, e, m -> {
      Trigger t = m.getTrigger();
      int ti = t.getIndex();
      HypEdge[] values = m.getValues();
      if (globalFactors[ti] != null)
        globalFactors[ti].rescore3(agenda, state, values);
      if (transitionGenrators[ti] != null) {
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
    assert nodesContains(e);
    state.add(e, score);
  }

  public void addEdgeToAgenda(Pair<HypEdge, Adjoints> p) {
    addEdgeToAgenda(p.get1(), p.get2());
  }
  public void addEdgeToAgenda(HypEdge e, Adjoints score) {
    if (DEBUG > 2) {
      if (DEBUG > 3)
        System.out.println("Uberts addEdgeToAgenda: " + e.toString() + " " + score);
      else
        System.out.println("Uberts addEdgeToAgenda: " + e.toString());
    }
    if (stats != null) {
      stats.increment("push");
      stats.increment("push/" + e.getRelation().getName());
    }
    assert nodesContains(e);
    agenda.add(e, score);
  }

  /** returns its argument */
  public Relation addEdgeType(Relation r) {
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
  public HypEdge makeEdge(RelLine line, boolean lookupHypNodes) {
    Relation r = getEdgeType(line.tokens[1]);
    assert r.getNumArgs() == line.tokens.length-2
        : "values=" + Arrays.toString(Arrays.copyOfRange(line.tokens, 2, line.tokens.length))
        + " does not match up with " + r.getDefinitionString()
        + " line=\"" + line.toLine() + "\""
        + " providence=" + line.providence;
    HypNode[] tail = new HypNode[r.getNumArgs()];
    for (int i = 0; i < tail.length; i++) {
      NodeType nt = r.getTypeForArg(i);
      Object value = line.tokens[i+2];
      if (lookupHypNodes)
        tail[i] = this.lookupNode(nt, value, true);
      else
        tail[i] = new HypNode(nt, value);
    }
    if (lookupHypNodes)
      return makeEdge(r, tail);
    return new HypEdge(r, null, tail);
  }
  public HypEdge makeEdge(String relationName, HypNode... tail) {
    Relation r = getEdgeType(relationName);
    return makeEdge(r, tail);
  }
  public HypEdge makeEdge(Relation r, HypNode... tail) {
    NodeType headType = getWitnessNodeType(r);
    Object encoded = r.encodeTail(tail);
    HypNode head = lookupNode(headType, encoded, true);
    return new HypEdge(r, head, tail);
  }

  /**
   * Given a string like "pos2(4,NNS)" or "predicate2(3-4,propbank/kill-v-1)",
   * parse out the relation and create a new fact where every argument is
   * represented as a string. Should really only be used for debugging/testing.
   *
   * @param factString will be split on commas, so arguments cannot contain commas.
   */
  public HypEdge dbgMakeEdge(String factString) {
    factString = factString.trim();
    int lp = factString.indexOf('(');
    int rp = factString.indexOf(')');
    assert rp == factString.length() - 1;
    Relation r = getEdgeType(factString.substring(0, lp));
    String[] args = factString.substring(lp + 1, rp).split(",");
    HypNode[] tail = new HypNode[args.length];
    for (int i = 0; i < args.length; i++)
      tail[i] = lookupNode(r.getTypeForArg(i), args[i].trim(), true);
    return makeEdge(r, tail);
  }

}
