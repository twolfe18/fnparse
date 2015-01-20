package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.StateSequence;
import edu.jhu.hlt.fnparse.rl.TransitionFunction;
import edu.jhu.hlt.fnparse.rl.TransitionFunction.ActionDrivenTransitionFunction;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.params.HasUpdate;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.util.Beam;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.fnparse.util.MultiTimer;
import edu.jhu.hlt.fnparse.util.RandomSpan;

/**
 * Lets think about how this model compares to the previous one I have, which
 * roughly parameterizes the same set of variables (but just not sequences over
 * how to label them).
 * I could just use the previous model for p(s|t,k) and then just learn a decoder.
 * What would this look like?
 * Features would all look like "reranking" features, consider a lot of state
 * like what roles have been assigned where.
 * 
 * Sort all of the (t,k,s) by probability, indexed by rank as i
 * The set of items chosen so far and the parse so far is represented as state s
 * Each item therefore gets features f(i,s) which add to the original log-prob p(i)
 * Algorithm:
 * 1) sort list by s(i) = p(i) + theta * f(i,s)
 * 2) if s(0) < 0, the parse is done.
 * 2) else take top item and add it to the parse, s += i, goto 1
 * 
 * This is a nice proving-ground for my idea. We already have a model which is
 * decent (has some idea about what a good (t,k,s) is). We should be able to
 * improve it. Probably with just the decoder, but if not, we can start throwing
 * in more realistic scoring features (e.g. embedding stuff) and anneal p(i)
 * towards the uniform distribution.
 *
 * NOTE: ^^^I totally didn't do this^^^
 */
public class Reranker {
  public static final Logger LOG = Logger.getLogger(Reranker.class);
  public static boolean LOG_UPDATE = false;
  public static boolean LOG_ORACLE = false;
  public static boolean LOG_MOST_VIOLATED = false;

  public static final double COST_FN = 1d;

  // Higher score is better, this puts highest scores at the end of the list
  public static final Comparator<Item> byScore = new Comparator<Item>() {
    @Override
    public int compare(Item o1, Item o2) {
      if (o1.getScore() < o2.getScore())
        return -1;
      if (o1.getScore() > o2.getScore())
        return 1;
      return 0;
    }
  };

  private Params.Stateful thetaStateful;
  private Params.Stateless thetaStateless;

  private ActionType[] actionTypes;
  private int beamWidth;
  public boolean useItemsForPruning = false; // Otherwise use them as features
  // via Params, in e.g. PriorScoreParams

  private MultiTimer timer = new MultiTimer();
  private Random rand;

  public Reranker(Params.Stateful thetaStateful, Params.Stateless thetaStateless, int beamWidth, Random rand) {
    this.thetaStateful = thetaStateful;
    this.thetaStateless = thetaStateless;
    this.beamWidth = beamWidth;
    this.actionTypes = new ActionType[] {
        ActionType.COMMIT,
        //ActionType.COMMIT_AND_PRUNE,
    };
    this.rand = rand;
  }

  public String toString() {
    return "<Reranker beam=" + beamWidth + " " + thetaStateful + " " + thetaStateless + ">";
  }

  public Params.Stateless getStatelessParams() {
    return thetaStateless;
  }

  public Params.Stateful getStatefulParams() {
    return thetaStateful;
  }

  public void setStatelessParams(Params.Stateless theta) {
    this.thetaStateless = theta;
  }

  public void setStatefulParams(Params.Stateful theta) {
    this.thetaStateful = theta;
  }

  public static ItemProvider getItemProvider() {
    return getItemProvider(100, true);
  }

  public static ItemProvider getItemProvider(int n, boolean addParses) {
    File cache = new File("data/rl/items." + n + ".train");
    FrameInstanceProvider fip = FileFrameInstanceProvider.dipanjantrainFIP;
    ItemProvider items;
    if (cache.isFile()) {
      LOG.info("[getItemProvider] getting cached items from " + cache.getPath());
      items = new ItemProvider.Caching(cache, fip);
    } else {
      LOG.info("[getItemProvider] parsing to get items...");
      List<FNParse> parses = DataUtil.iter2list(fip.getParsedSentences());
      parses = parses.subList(0, n);
      ItemProvider.Caching c = new ItemProvider.Caching(new ItemProvider.Slow(parses));
      LOG.info("[getItemProvider] saving cached items to " + cache.getPath());
      try { c.save(cache); }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
      items = c;
    }

    // Add parses
    if (addParses) {
      LOG.info("[getItemProvider] adding parses to " + items.size() + " sentences");
      ConcreteStanfordWrapper parser = ConcreteStanfordWrapper.getSingleton(true);
      for (int i = 0; i < items.size(); i++) {
        Sentence s = items.label(i).getSentence();
        if (s.getBasicDeps() == null)
          s.setBasicDeps(parser.getBasicDParse(s));
        if (s.getStanfordParse() == null)
          s.setStanfordParse(parser.getCParse(s));
      }
    }

    LOG.info("[getItemProvider] done");
    return items;
  }

  /**
   * Solves problem 1: \max_z s(y,z) = \max_z \sum_i s(y,z_i)
   * 
   * This is accomplished through beam search, starting at y and performing
   * "undo"s on potential actions that would lead to a starting point (empty
   * parse or initial decoder state).
   * 
   * NOTE: The reason this beam search works "backwards" (from the label with
   * undo operations) is to enforce the constraint that we're only considering
   * z that lead to y. However, this is not impossible to do in a forwards pass.
   * 
   * @param y
   */
  public StateSequence oracle(FNParse y) {
    boolean verbose = false;
    String desc = "[oracle]";
    Beam<StateSequence> beam = new Beam<>(beamWidth);
    Params.Stateful theta = getCachingParams();
    TransitionFunction transF =
        new ActionDrivenTransitionFunction(theta, actionTypes);
    State finalState = State.finalState(y);
    StateSequence init = new StateSequence(null, null, finalState, null);
    beam.push(init, 0d);
    for (int iter = 0; true; iter++) {
      // Choose an item to extend
      StateSequence frontier = beam.pop();
      boolean verboseThisIter = verbose || iter % 500000 == 0;
      if (LOG_ORACLE && verboseThisIter)
        logStateInfo("[oracle]", frontier.getCur());
      // For each of its extensions, check if they should be put on the beam.
      int added = 0;
      for (StateSequence ss : transF.previousStates(frontier)) {
        added++;
        double score = ss.getScore();
        boolean onBeam = beam.push(ss, score);
        if (LOG_ORACLE && verboseThisIter && onBeam )
          logAction(desc, iter, score, ss, y, false);
      }
      //LOG.debug(desc + " added=" + added);
      if (added == 0) {
        // There were no previous states, so frontier must be the empty parse,
        // or initial state.
        if (LOG_ORACLE)
          LOG.debug(desc + " done after " + iter + " iterations");
        logSolution(desc, frontier);
        return frontier;
      }
    }
  }

  public FNParse predict(FNTagging frames) {
    assert !useItemsForPruning : "need the items then!";
    if (frames.numFrameInstances() == 0)
      return new FNParse(frames.getSentence(), Collections.emptyList());
    StateSequence ss = mostViolated(frames, null);
    State st = ss.getCur();
    assert ss == ss.getLast();
    return st.decode();
  }
  public FNParse predict(FNTagging frames, List<Item> items) {
    assert useItemsForPruning : "probably should use the other one";
    if (frames.numFrameInstances() == 0)
      return new FNParse(frames.getSentence(), Collections.emptyList());
    StateSequence ss = mostViolated(items, frames, null);
    State st = ss.getCur();
    assert ss == ss.getLast();
    return st.decode();
  }

  public <T extends FNTagging> List<FNParse> predict(List<T> frames) {
    assert !useItemsForPruning : "need the items then!";
    List<FNParse> r = new ArrayList<>();
    for (T t : frames)
      r.add(predict(t));
    return r;
  }
  public List<FNParse> predict(ItemProvider ip) {
    assert useItemsForPruning : "probably should use the other one";
    List<FNParse> r = new ArrayList<>();
    int n = ip.size();
    for (int i = 0; i < n; i++) {
      FNTagging frames = DataUtil.convertParseToTagging(ip.label(i));
      r.add(predict(frames, ip.items(i)));
    }
    return r;
  }

  /** Shows some info about the given state */
  private void logStateInfo(String desc, State init) {
    LOG.info("");
    LOG.info(desc + " working on " + init.getFrames().getId());
    int n = init.getSentence().size();
    LOG.info(desc + " T=" + init.numFrameInstance()
        + " TK=" + init.numFrameRoleInstances()
        + " O(n^2)=" + (n*(n-1)/2));
    LOG.info(desc + " #committed=" + init.numCommitted()
        + " #unCommitted=" + init.numUncommitted());
    StringBuilder sb = new StringBuilder("action types:");
    for (ActionType at : actionTypes) sb.append(" " + at.getName());
    LOG.info(desc + " " + sb.toString());
    LOG.info(desc + " " + init.show());
  }

  /** Shows some info about the Action (wrapped in a StateSequence for ancillary info) */
  private void logAction(String desc, int iteration, double score, StateSequence ss, FNParse y, boolean showDeltaLoss) {
    StringBuilder sb = new StringBuilder(desc);
    if (iteration >= 0)
      sb.append(" iter=" + iteration);
    sb.append(" " + ss.getAction());
    sb.append(" score=" + score);
    sb.append(" actionScore=" + ss.getAdjoints().getScore());
    if (showDeltaLoss) {
      Action a = ss.getAction();
      ActionType at = a.getActionType();
      //State s = ss.getCur();          // State after applying a
      State s = ss.neighbor().getCur(); // State before applying a
      double dl = at.deltaLoss(s, a, y);
      sb.append(" deltaLoss=" + dl);
      //sb.append(" totalLoss=" + ss.getLoss(y));
    }
    sb.append(" totalScore=" + ss.getScore());
    LOG.info(sb.toString());
  }

  /** Shows the action sequence of a StateSequence given */
  private void logSolution(String desc, StateSequence frontier) {
    String actions = frontier.showActions();
    int k = 250;  // max characters to display of solution action sequence
    if (actions.length() > k)
      actions = actions.substring(0, k) + "...";
    LOG.info(desc + " solution: " + actions);
  }

  /**
   * Returns parameters which cache the stateless features, but not the stateful
   * ones (obviously...).
   *
   * Right now this only caches for a single FNTagging, and it will likely have
   * to stay this way, because otherwise it would need to cache for an entire
   * dataset, which is likley too much.
   */
  private Params.Stateful getCachingParams() {
    Params.Stateless thetaBaseCache = new Params.Stateless.Caching(thetaStateless);
    Params.Stateful theta = new Params.SumMixed(thetaStateful, thetaBaseCache);
    return theta;
  }

  /**
   * Special implementation of oracle for cases where you only have
   * Stateless params (Stateful == None).
   */
  public StateSequence simpleOracle(FNParse y) {
    // Used for all actions
    final int COMMIT = ActionType.COMMIT.getIndex();

    final int T = y.numFrameInstances();
    StateSequence cur = new StateSequence(null, null, State.finalState(y), null);
    // One COMMIT action for every (t,k)
    for (int t = 0; t < T; t++) {
      final FrameInstance fi = y.getFrameInstance(t);
      final int K = fi.getFrame().numRoles();
      for (int k = 0; k < K; k++) {
        Span arg = fi.getArgument(k);
        Action a = new Action(t, k, COMMIT, arg);
        State prev = cur.getCur().apply(a, false);
        Adjoints adj = thetaStateless.score(y, a);
        StateSequence prevSS = new StateSequence(null, cur, prev, adj);
        cur = prevSS;
      }
    }
    logSolution("[simpleOracle]", cur);
    return cur;
  }

  /**
   * Special implementation of mostViolated for cases where you only have
   * Stateless params (Stateful == None).
   */
  public StateSequence simpleMostViolated(State init, FNParse y) {
    // The key here is that order doesn't matter for solving the max over z
    // when you don't have any Stateful params.

    // Sane implementations:
    // 1) Only use COMMIT, choose nullSpan for every realized arg, choose a random span for every unrealized arg
    // 2) Like 1 but instead take the argmax by the current score function of every span for each unrealized arg
    boolean maxOverSpans = rand.nextInt(30) == 0;

    // Used for all actions
    final int COMMIT = ActionType.COMMIT.getIndex();

    final int T = y.numFrameInstances();
    StateSequence cur = new StateSequence(null, null, init, null);
    // One COMMIT action for every (t,k)
    for (int t = 0; t < T; t++) {
      final FrameInstance fi = y.getFrameInstance(t);
      final int K = fi.getFrame().numRoles();
      for (int k = 0; k < K; k++) {
        Span arg = fi.getArgument(k);
        Action a;
        if (arg == Span.nullSpan) {
          // Choose a span other than nullSpan
          if (!maxOverSpans) {
            // be lazy: choose a random span
            Iterable<Span> possible = cur.getCur().naiveAllowableSpans(t, k);
            Span randArg = RandomSpan.draw(possible, rand);
            //Span randArg = RandomSpan.draw(n, rand);
            a = new Action(t, k, COMMIT, randArg);
          } else {
            // correct(-ish) training: take the highest scoring span.
            // (not fully-correct because we're only considering COMMIT)
            Span bestSpan = null;
            double bestScore = 0d;
            for (Span s : cur.getCur().naiveAllowableSpans(t, k)) {
              Action as = new Action(t, k, COMMIT, s);
              Adjoints adj = thetaStateless.score(y, as);
              double score = adj.getScore();
              if (bestSpan == null || score > bestScore) {
                bestSpan = s;
                bestScore = score;
              }
            }
            assert bestSpan != null;
            a = new Action(t, k, COMMIT, bestSpan);
          }
        } else {
          // Choose nullSpan
          a = new Action(t, k, COMMIT, Span.nullSpan);
        }
        // Move forward one state and set a backpointer
        State next = cur.getCur().apply(a, true);
        Adjoints adj = thetaStateless.score(y, a);
        StateSequence nextSS = new StateSequence(cur, null, next, adj);
        cur = nextSS;
      }
    }
    assert 0 == cur.getCur().numUncommitted();
    logSolution("[simpleMostViolated]", cur);
    return cur;
  }

  /**
   * if y is null, then do decoding.
   */
  public StateSequence mostViolated(FNTagging frames, FNParse y) {
    if (LOG_MOST_VIOLATED)
      LOG.warn("[mostViolated] using exhaustive version (not reranking)");
    State init = State.initialState(y != null ? y : frames);
    return mostViolatedHelper(init, y);
  }
  public StateSequence mostViolated(List<Item> rerank, FNTagging frames, FNParse y) {
    if (useItemsForPruning) {
      if (LOG_MOST_VIOLATED)
        LOG.warn("[mostViolated] using reranking version");
      State init = State.initialState(y != null ? y : frames, rerank);
      return mostViolatedHelper(init, y);
    } else {
      return mostViolated(y, y);
    }
  }
  private StateSequence mostViolatedHelper(State init, FNParse y) {
    String desc = (y == null) ? "[decode]" : "[mostViolated]";
    boolean verbose = true;
    Params.Stateful theta = getCachingParams();
    TransitionFunction transF =
        new ActionDrivenTransitionFunction(theta, actionTypes);
    StateSequence frontier = new StateSequence(null, null, init, null);
    Beam<StateSequence> beam = new Beam<>(beamWidth);
    beam.push(frontier, 0d);
    for (int iter = 0; true; iter++) {  // while (true)
      frontier = beam.pop();
      if (verbose && LOG_MOST_VIOLATED)
        logStateInfo(desc, frontier.getCur());
      int added = 0;
      for (StateSequence ss : transF.nextStates(frontier)) {
        added++;

        // Model score
        double score = ss.getScore();

        // Add in a reward for increasing the loss
        if (y != null) {
          Action a = ss.getAction();
          ActionType at = a.getActionType();
          //State s = ss.getCur();        // State after applying a (slow)
          State s = frontier.getCur();    // State before applying a (fast)
          score += at.deltaLoss(s, a, y);
        }

        // Add to the beam
        boolean onBeam = beam.push(ss, score);
        if (verbose && onBeam && LOG_MOST_VIOLATED)
          logAction(desc, iter, score, ss, y, y != null);
      }
      //LOG.debug("[mostViolated] added=" + added);
      if (added == 0) {
        if (LOG_MOST_VIOLATED) {
          LOG.info("[mostViolated] done on iteration " + iter);
          if (verbose) {
            StateSequence cur = frontier;
            while (cur != null) {
              Adjoints a = cur.getAdjoints();
              if (a != null)
                LOG.info("[mostViolated] " + a.getAction() + " with score " + a.getScore());
              cur = cur.getPrev();
            }
          }
        }
        assert 0 == frontier.getCur().numUncommitted();
        logSolution(desc, frontier);
        return frontier;
      }
    }
  }

  public State randomDecodingState(FNTagging frames, Random rand) {
    Params.Stateful theta = getCachingParams();
    TransitionFunction transF =
        new ActionDrivenTransitionFunction(theta, actionTypes);
    State init = State.initialState(frames);
    StateSequence frontier = new StateSequence(null, null, init, null);
    int TK = init.numFrameRoleInstances();
    if (TK == 0)
      throw new IllegalArgumentException("only works when there are frameInstances");
    int tkStop = rand.nextInt(TK);
    for (int i = 0; i < tkStop; i++)
      frontier = DataUtil.reservoirSampleOne(transF.nextStates(frontier), rand);
    return frontier.getCur();
  }

  /**
   * subgradient step on loss of:
   * max(0, s(y,z) - [s(y',z') + loss(y,y')])
   * 
   * where (y,z) = oracle
   *       (y',z') = mostViolated
   *       max(0, s(y,z) - [s(y',z') + loss(y,y')]) = loss
   *
   * @return the hinge loss
   */
  public class Update implements HasUpdate {
    private final Logger LOG = Logger.getLogger(Update.class);

    public final StateSequence oracle;
    public final StateSequence mostViolated;
    public final double hinge;
    public final boolean onlyStateless;

    private StateSequence oracle(FNParse y, List<Item> rerank) {
      StateSequence oracle;
      if (onlyStateless) {
        timer.start("udpate.simpleOracle");
        oracle = Reranker.this.simpleOracle(y);
        timer.stop("udpate.simpleOracle");
      } else {
        timer.start("udpate.oracle");
        oracle = Reranker.this.oracle(y);
        timer.stop("udpate.oracle");
      }
      return oracle;
    }

    private StateSequence mostViolated(FNParse y, List<Item> rerank) {
      StateSequence mv;
      if (onlyStateless) {
        timer.start("udpate.simpleMostViolated");
        mv = simpleMostViolated(State.initialState(y, rerank), y);
        timer.stop("udpate.simpleMostViolated");
      } else {
        timer.start("udpate.mostViolated");
        mv = Reranker.this.mostViolated(rerank, y, y);
        timer.stop("udpate.mostViolated");
      }
      return mv;
    }

    public Update(FNParse y, List<Item> rerank, boolean onlyStateless) {
      this.onlyStateless = onlyStateless;
      if (y.numFrameInstances() == 0) {
        oracle = null;
        mostViolated = null;
        hinge = 0d;
        if (LOG_UPDATE)
          LOG.info("[init] no frameInstances, 0 update");
      } else {
        if (rerank == null || rerank.size() == 0)
          throw new IllegalArgumentException();

        if (LOG_UPDATE) LOG.info("[init] solving oracle for " + y.getId());
        oracle = oracle(y, rerank);

        if (LOG_UPDATE) LOG.info("[init] solving mostViolated for " + y.getId());
        mostViolated = mostViolated(y, rerank);

        if (LOG_UPDATE) LOG.info("[init] decoding for " + y.getId());
        timer.start("udpate.decode");
        FNParse yHat = mostViolated.getCur().decode();
        timer.stop("udpate.decode");

        assert yHat != null : "mostViolated returned non-terminal state?";
        SentenceEval se = new SentenceEval(y, yHat);
        double l = se.argOnlyFP() + se.argOnlyFN() * COST_FN;
        assert l >= 0d;
        hinge = oracle.getScore() - (mostViolated.getScore() + l);
        if (LOG_UPDATE) {
          LOG.info(String.format(
              "[init] y=%s hinge=%.2f = s(oracle)=%.2f - [s(mv)=%.2f + loss=%.2f]",
              y.getId(), hinge, oracle.getScore(), mostViolated.getScore(), l));
          LOG.info("[init] in MV solution:     fp=" + se.argOnlyFP() + " fn=" + se.argOnlyFN() + " tp=" + se.argOnlyTP());

          // See if the oracle is finding the perfect answer
          FNParse oracleDecode = oracle.getLast().getCur().decode();
          assert oracleDecode != null : "oracle didn't even find a parse?";
          SentenceEval se2 = new SentenceEval(y, oracleDecode);
          LOG.info("[init] in oracle solution: fp=" + se2.argOnlyFP() + " fn=" + se2.argOnlyFN() + " tp=" + se2.argOnlyTP());

          LOG.info("[init] timer: " + timer);
        }
      }
    }

    public boolean isViolated() {
      return hinge < 0d;
    }

    public double violation() {
      if (hinge < 0d)
        return -hinge;
      else
        return 0d;
    }

    @Override
    public void getUpdate(double[] addTo, double scale) {
      timer.start("Update.getUpdate");

      if (!isViolated()) {
        timer.stop("Update.getUpdate");
        return;
      }

      int skipped;

      skipped = 0;
      final double upOracle = scale * -hinge / oracle.length();
      for (StateSequence cur = oracle; cur != null; cur = cur.neighbor()) {
        Adjoints a = cur.getAdjoints();
        if (a != null)
          a.getUpdate(addTo, upOracle);
        else
          skipped++;
      }
      assert skipped <= 1;

      skipped = 0;
      final double upMV = scale * hinge / mostViolated.length();
      for (StateSequence cur = mostViolated; cur != null; cur = cur.neighbor()) {
        Adjoints a = cur.getAdjoints();
        if (a != null)
          a.getUpdate(addTo, upMV);
        else
          skipped++;
      }
      assert skipped <= 1;

      timer.stop("Update.getUpdate");
    }
  }

  /**
   * Averages the updates in the given batch.
   */
  public static class UpdateBatch implements HasUpdate {
    private Collection<Update> elements;
    public UpdateBatch(Update... elements) {
      this.elements = Arrays.asList(elements);
    }
    public UpdateBatch(Collection<Update> elements) {
      this.elements = elements;
    }
    @Override
    public void getUpdate(double[] addTo, double scale) {
      double s = scale / elements.size();
      for (Update u : elements)
        u.getUpdate(addTo, s);
    }
    public double violation() {
      double v = 0d;
      for (Update u : elements)
        v += u.violation();
      return v;
    }
  }
}
