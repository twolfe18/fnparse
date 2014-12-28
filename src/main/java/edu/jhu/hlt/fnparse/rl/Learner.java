package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.rl.State.Action;
import edu.jhu.hlt.fnparse.util.DataSplitter;

public class Learner {
  public static final Logger LOG = Logger.getLogger(Learner.class);

  /**
   * wraps the result of a forward pass, which is often needed for computing a
   * gradient.
   */
  public interface Adjoints {
    public double getScore();
    public Action getAction();
  }

  /**
   * parameterizes a score function on (state,action) pairs
   */
  public interface Params {
    /**
     * Q-function, score of a action from a state
     */
    public Adjoints score(State s, Action a);

    /**
     * updates the parameters for taking a in s and receiving a reward
     */
    public void update(Adjoints a, double reward);
  }

  /**
   * TODO This can look at the gold answer!
   * Can compute recall, not precision really.
   * 
   * OK, I'm sufficiently confused to know that I'm out of my depths. I'm going
   * to do a very simple reward function that I can understand the semantics of
   * the hack for.
   */
  static class RewardFunction {
    /*
    double lam0 = 0.01d;  // absolute
    double lam1 = 1.0d;   // relative to previous num completions
    double lam2 = 1.0d;   // relative to length of sentence
    double lamD = 0.5d;   // penalty for moves applied
    */
    double rFP = -1d;
    double rFN = -2d;
    double rMiss = -5d;
    double rHit = 500d;
    double rPrune = 1d;
    public double reward(State s, Action a, FNParse p) {
      State next = new State(s);
      next.apply(a);
      return reward(s, a, next, p);
    }
    public double reward(State s, Action a, State s_after_a, FNParse p) {
      FrameInstance fi = p.getFrameInstance(a.t);
      Span arg = fi.getArgument(a.k);
      if (a.mode == Action.COMMIT) {
        if (arg == Span.nullSpan && a.aspan.isNullSpan())
          return rPrune;
        if (arg == Span.nullSpan && !a.aspan.isNullSpan())
          return rFP;
        if (arg != Span.nullSpan && a.aspan.isNullSpan())
          return rFN;
        if (arg != Span.nullSpan && a.matches(arg))
          return rHit;
        else
          return rMiss;
      } else {
        throw new RuntimeException("implement me");
      }
      /*
      double x = s.possibleCompletions();
      double y = s_after_a.possibleCompletions();
      int n = p.getSentence().size();
      return lam0 * (x - y)
           + lam1 * (x - y) / x
           + lam2 * (x - y) / (n * (n-1) / 2)
           - lamD * s_after_a.movesApplied();
      */
    }
  }

  private Params theta;
  private RewardFunction rewardFunc;
  private Random rand;
  private double epsilon = 0.5d;

  public Learner(Random r) {
    this.rand = r;
    //this.theta = new FeatureParams();
    //this.theta = new EmbeddingParams(2);
    this.theta = new CompositeParams(new EmbeddingParams(1), new FeatureParams());
    this.rewardFunc = new RewardFunction();
  }

  public void train(List<FNParse> parses, int epochs) {
    int n = parses.size();
    long t = System.currentTimeMillis();
    for (int e = 0; e < epochs; e++) {
      LOG.info(String.format("[train] starting epoch %d @ %.1f iters/sec",
          e, (1000d * e * n) / (System.currentTimeMillis() - t)));
      for (int i = 0; i < n ; i++) {
        FNParse p = parses.get(rand.nextInt(n));
        State start = new State(p);
        update(p, start);
        //System.out.print("*");
      }
      //System.out.println();
    }
    LOG.info("[train] done");
  }

  /**
   * epsilon-greedy action selection.
   */
  public void update(FNParse p, State s) {

    Set<Span> limitSpans = new HashSet<>();
    for (FrameInstance fi : p.getFrameInstances())
      fi.getRealizedArgs(limitSpans);
    double pSkipIfNotRealSpan = 0.75d;

    Adjoints a_best = null;
    Adjoints a_rand = null;
    double a_score = 0d;
    int seen = 0;
    //LOG.info("[update] starting");
    for (Action a : s.actions()) {
      if (a.aspan.isNormalSpan() && !limitSpans.contains(a.aspan.getSpan()) && rand.nextDouble() < pSkipIfNotRealSpan)
        continue;
      Adjoints adj = theta.score(s, a);
      double score = adj.getScore();
      //LOG.info("[update] action " + a.toString(s) + " has score " + score);
      if (a_best == null || score > a_score) {
        //LOG.info("[update] NEW BEST!");
        a_best = adj;
        a_score = score;
      }
      seen++;
      if (rand.nextInt(seen) == 0)
        a_rand = adj;
    }
    if (a_best == null) {
      assert seen == 0;
      return;
    }

    Adjoints a = (rand.nextDouble() > epsilon) ? a_best : a_rand;
    //LOG.info("[update] saw " + seen + " actions and chose " + a);
    double reward = rewardFunc.reward(s, a.getAction(), p);
    theta.update(a, reward);
  }

  public FNParse predict(FNTagging frames) {
    State state = new State(frames);
    while (true) {
      Action a_best = null;
      double a_score = 0d;
      for (Action a : state.actions()) {
        double score = theta.score(state, a).getScore();
        if (a_best == null || score > a_score) {
          a_best = a;
          a_score = score;
        }
      }
      if (a_best == null)
        break;
      state.apply(a_best);
    }
    return state.decode();
  }

  public List<FNParse> predict(List<FNTagging> frames) {
    List<FNParse> parses = new ArrayList<>();
    for (FNTagging t : frames)
      parses.add(predict(t));
    return parses;
  }

  public static void main(String[] args) {
    Random r = new Random(9001);
    List<FNParse> parses = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    //parses = parses.subList(0, 100);
    DataSplitter ds = new DataSplitter(r);
    List<FNParse> train = new ArrayList<>();
    List<FNParse> test = new ArrayList<>();
    ds.split(parses, train, test, 0.4d, false, "foo");

    int k = 1;
    train = train.subList(0, k * 100);
    test = train.subList(0, k * 10);

    Learner l = new Learner(r);
    for (int i = 0; i < 1; i++) {
      LOG.info("[main] training @ " + i);
      l.train(train, 2);
      LOG.info("[main] predicting @ " + i);
      List<FNParse> hyp = l.predict(DataUtil.convertParsesToTaggings(test));
      BasicEvaluation.showResults("[test@" + i + "]", BasicEvaluation.evaluate(test, hyp));
    }
  }
}
