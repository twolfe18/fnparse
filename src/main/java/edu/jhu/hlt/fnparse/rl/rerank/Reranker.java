package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.Adjoints;
import edu.jhu.hlt.fnparse.rl.CompositeParams;
import edu.jhu.hlt.fnparse.rl.DenseFastFeatures;
import edu.jhu.hlt.fnparse.rl.Params;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.StateSequence;
import edu.jhu.hlt.fnparse.rl.TransitionFunction;
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
  private int beamWidth;
  private Random rand;

  public Reranker() {
    this(new CompositeParams(
          new DenseFastFeatures(),
          new PriorScoreParams(getItemProvider(), true)),
        100,
        new Random(9001));
  }

  public Reranker(Params theta, int beamWidth, Random rand) {
    this.theta = theta;
    this.beamWidth = beamWidth;
    this.rand = rand;
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
    LOG.info("[oracle] starting " + y.getId());

    // TODO this can be used to bias the search to find certain paths which you
    // a priori think are good.
    //Params p = new NumCommittedParamsWrapper(0d, theta);
    Params p = theta;
    TransitionFunction trans = new TransitionFunction.Simple(y, p);

    Beam<StateSequence> beam = new Beam<>(beamWidth);
    State finalState = State.finalState(y);
    StateSequence init = new StateSequence(null, null, finalState, null);
    beam.push(init, 0d);
    while (true) {
      // Choose an item to extend
      StateSequence frontier = beam.pop();
      // For each of its extensions, check if they should be put on the beam.
      int added = 0;
      for (StateSequence ss : trans.previousStates(frontier)) {
        added++;
        beam.push(ss, ss.getScore());
      }
      //LOG.debug("[oracle] added=" + added);
      if (added == 0) {
        // There were no previous states, so frontier must be the empty parse,
        // or initial state.
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

  /**
   * if y is null, then do decoding.
   */
  public StateSequence mostViolated(FNTagging frames, FNParse y) {
    LOG.warn("[mostViolated] don't use this version");
    State init = State.initialState(frames);
    return mostViolatedHelper(init, y);
  }
  public StateSequence mostViolated(List<Item> rerank, FNParse y) {
    State init = State.initialState(y, rerank);
    return mostViolatedHelper(init, y);
  }
  private StateSequence mostViolatedHelper(State init, FNParse y) {
    LOG.info("[mostViolated] starting " + y.getId());
    TransitionFunction trans = new TransitionFunction.Simple(null, theta);
    StateSequence frontier = new StateSequence(null, null, init, null);
    Beam<StateSequence> beam = new Beam<>(beamWidth);
    beam.push(frontier, 0d);
    while (true) {
      frontier = beam.pop();
      int added = 0;
      for (StateSequence ss : trans.nextStates(frontier)) {
        added++;
        double score = ss.getScore();
        if (y != null)
          score += deltaLoss(ss, y);
        beam.push(ss, score);
      }
      //LOG.debug("[mostViolated] added=" + added);
      if (added == 0) {
        LOG.info("[mostViolated] done");
        return frontier;
      }
    }
  }

  private static double deltaLoss(StateSequence next, FNParse y) {
    final double costFP = 1d;
    final double costFN = 1d;
    double cost = 0;
    Action a = next.getAction();
    if (a.mode == Action.COMMIT || a.mode == Action.COMMIT_AND_PRUNE_OVERLAPPING) {
      // Check for false positives (proposing a bad argument)
      if (a.hasSpan()) {
        Span hyp = a.getSpan();
        Span gold = y.getFrameInstance(a.t).getArgument(a.k);
        if (hyp != gold)
          cost += costFP;
      }

      // Check for false negatives (pruning a gold argument)
      if (!a.hasSpan()) {
        assert a.start == Span.nullSpan.start && a.end == Span.nullSpan.end;
        Span gold = y.getFrameInstance(a.t).getArgument(a.k);
        if (gold != Span.nullSpan)
          cost += costFN;
      }
    } else {
      throw new RuntimeException("not supported yet");
    }
    return cost;
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
  private double updateParams(FNParse y, List<Item> rerank, StdEvalFunc eval) {
    if (y.numFrameInstances() == 0)
      return 0d;
    if (rerank == null || rerank.size() == 0)
      throw new IllegalArgumentException();
    StateSequence oracle = oracle(y);
    StateSequence mv = mostViolated(rerank, y);
    double l = eval.evaluate(new SentenceEval(y, mv.getCur().decode()));
    assert l >= 0d && l <= 1d;
    l = 1d - l; // Convert from score => loss
    double negHinge = oracle.getScore() - (mv.getScore() + l);
    LOG.info(String.format(
        "[updateParams] items.size=%d -hinge(%s) = %.3f = oracle.score=%.3f - [mv.score=%.3f + loss=%.3f]",
        rerank.size(), y.getId(), negHinge, oracle.getScore(), mv.getScore(), l));
    assert Double.isFinite(negHinge) && !Double.isNaN(negHinge);
    if (negHinge < 0) {
      StateSequence cur;
      cur = oracle;
      while (cur != null) {
        Adjoints a = cur.getAdjoints();
        if (a != null)
          theta.update(a, l);
        else
          LOG.warn("null adjoints in oracle");
        cur = cur.neighbor();
      }
      cur = mv;
      while (cur != null) {
        Adjoints a = cur.getAdjoints();
        if (a != null)
          theta.update(a, -l);
        else
          LOG.warn("null adjoints in mv");
        cur = cur.neighbor();
      }
    }
    return negHinge;
  }

  public void train(ItemProvider ip) {
    StdEvalFunc eval = BasicEvaluation.argOnlyMicroF1;
    int n = ip.size();
    for (int epoch = 0; epoch < 10; epoch++) {
      int updated = 0;
      for (int i = 0; i < n; i++) {
        int idx = rand.nextInt(n);
        FNParse y = ip.label(idx);
        List<Item> items = ip.items(idx);
        if (updateParams(y, items, eval) > 0)
          updated++;
      }
      LOG.info("[train] updated " + updated);
      if (updated == 0)
        break;  // unlikely...
    }
  }

  public static void main(String[] args) {
    Logger.getLogger(ConstituencyTreeFactor.class).setLevel(Level.FATAL);
    Reranker r = new Reranker();
    ItemProvider ip = getItemProvider();
    r.train(ip);
  }
}
