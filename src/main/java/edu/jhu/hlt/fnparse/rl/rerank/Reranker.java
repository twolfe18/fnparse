package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.StateSequence;
import edu.jhu.hlt.fnparse.rl.TransitionFunction;
import edu.jhu.hlt.fnparse.rl.TransitionFunction.ActionDrivenTransitionFunction;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.util.Beam;

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

  private Params theta;
  private ActionType[] actionTypes;
  private int beamWidth;
  public boolean logOracle = false;
  public boolean logMostViolated = false;

  public Reranker(Params theta, int beamWidth) {
    this.theta = theta;
    this.beamWidth = beamWidth;
    this.actionTypes = new ActionType[] {
        //ActionType.COMMIT,
        ActionType.COMMIT_AND_PRUNE,
    };
  }

  public String toString() {
    return "<Reranker beam=" + beamWidth + " theta=" + theta.toString() + ">";
  }

  public static ItemProvider getItemProvider() {
    int n = 100;
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
   * TODO this doesn't need to work backwards at all. Perhaps it even should be
   * a forward pass to mimic how the decoder would work at test time. IDK though,
   * backwards might find better solutions due to the strong global features.
   * 
   * @param y
   */
  public StateSequence oracle(FNParse y) {
    if (logOracle)
      LOG.info("[oracle] starting " + y.getId());
    Beam<StateSequence> beam = new Beam<>(beamWidth);
    TransitionFunction transF =
        new ActionDrivenTransitionFunction(theta, y, actionTypes);
    State finalState = State.finalState(y);
    StateSequence init = new StateSequence(null, null, finalState, null);
    beam.push(init, 0d);
    while (true) {
      // Choose an item to extend
      StateSequence frontier = beam.pop();
      // For each of its extensions, check if they should be put on the beam.
      int added = 0;
      for (StateSequence ss : transF.previousStates(frontier)) {
        added++;
        beam.push(ss, ss.getScore());
      }
      //LOG.debug("[oracle] added=" + added);
      if (added == 0) {
        // There were no previous states, so frontier must be the empty parse,
        // or initial state.
        if (logOracle)
          LOG.debug("[oracle] done");
        return frontier;
      }
    }
  }

  public FNParse predict(FNTagging frames) {
    StateSequence ss = mostViolated(frames, null);
    State st = ss.getCur();
    assert ss == ss.getLast();
    return st.decode();
  }

  public <T extends FNTagging> List<FNParse> predict(List<T> frames) {
    List<FNParse> r = new ArrayList<>();
    for (T t : frames)
      r.add(predict(t));
    return r;
  }

  /**
   * if y is null, then do decoding.
   */
  public StateSequence mostViolated(FNTagging frames, FNParse y) {
    if (logMostViolated)
      LOG.warn("[mostViolated] using exhaustive version (not reranking)");
    State init = State.initialState(frames);
    return mostViolatedHelper(init, y);
  }
  public StateSequence mostViolated(List<Item> rerank, FNParse y) {
    if (logMostViolated)
      LOG.warn("[mostViolated] using reranking version");
    State init = State.initialState(y, rerank);
    return mostViolatedHelper(init, y);
  }
  private StateSequence mostViolatedHelper(State init, FNParse y) {
    if (logMostViolated) {
      if (y == null)
        LOG.info("[mostViolated] decoding " + init.getFrames().getId());
      else
        LOG.info("[mostViolated] starting " + y.getId());
      int n = init.getSentence().size();
      LOG.info("[mostViolated] T=" + init.numFrameInstance()
          + " TK=" + init.numFrameRoleInstances()
          + " O(n^2)=" + (n*(n-1)/2));
    }
    TransitionFunction transF =
        new ActionDrivenTransitionFunction(theta, y, actionTypes);
    StateSequence frontier = new StateSequence(null, null, init, null);
    Beam<StateSequence> beam = new Beam<>(beamWidth);
    beam.push(frontier, 0d);
    while (true) {
      frontier = beam.pop();
      int added = 0;
      for (StateSequence ss : transF.nextStates(frontier)) {
        added++;
        // Model score
        double score = ss.getScore();
        if (y != null) {
          // Add in a reward for increasing the loss
          Action a = ss.getAction();
          ActionType at = a.getActionType();
          State s = ss.getCur();
          score += at.deltaLoss(s, a, y);
        }
        beam.push(ss, score);
      }
      //LOG.debug("[mostViolated] added=" + added);
      if (added == 0) {
        if (logMostViolated)
          LOG.info("[mostViolated] done");
        return frontier;
      }
    }
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
  public class Update {

    public final StateSequence oracle;
    public final StateSequence mostViolated;
    public final double negHinge;

    public Update(FNParse y, List<Item> rerank, StdEvalFunc eval) {
      if (y.numFrameInstances() == 0) {
        oracle = null;
        mostViolated = null;
        negHinge = 0d;
      } else {
        if (rerank == null || rerank.size() == 0)
          throw new IllegalArgumentException();
        oracle = oracle(y);
        mostViolated = mostViolated(rerank, y);
        double l = eval.evaluate(new SentenceEval(y, mostViolated.getCur().decode()));
        assert l >= 0d && l <= 1d;
        l = 1d - l; // Convert from score => loss
        negHinge = oracle.getScore() - (mostViolated.getScore() + l);
      }
    }

    public boolean apply() {
      if (negHinge >= 0d)
        return false;
      StateSequence cur;
      cur = oracle;
      while (cur != null) {
        Adjoints a = cur.getAdjoints();
        if (a != null)
          theta.update(a, negHinge);
//        else
//          LOG.warn("null adjoints in oracle");
        cur = cur.neighbor();
      }
      cur = mostViolated;
      while (cur != null) {
        Adjoints a = cur.getAdjoints();
        if (a != null)
          theta.update(a, -negHinge);
//        else
//          LOG.warn("null adjoints in mv");
        cur = cur.neighbor();
      }
      return true;
    }
  }

  public State randomDecodingState(FNTagging frames, Random rand) {
    TransitionFunction transF =
        new ActionDrivenTransitionFunction(theta, null, actionTypes);
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
}
