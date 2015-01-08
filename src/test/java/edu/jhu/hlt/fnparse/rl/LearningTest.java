package edu.jhu.hlt.fnparse.rl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.StdEvalFunc;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.rl.TransitionFunction.ActionDrivenTransitionFunction;
import edu.jhu.hlt.fnparse.rl.params.CheatingParams;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.params.Params.Stateful;
import edu.jhu.hlt.fnparse.rl.params.PriorScoreParams;
import edu.jhu.hlt.fnparse.rl.rerank.ItemProvider;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer;
import edu.jhu.hlt.fnparse.util.FNDiff;

/**
 * If the algorithm is given an indicator feature for whether an item is in the
 * gold parse, it should be able to get 100% accuracy (quickly).
 *
 * @author travis
 */
public class LearningTest {
  public static final Logger LOG = Logger.getLogger(LearningTest.class);
  private final Random rand = new Random(9001);

  @Before
  public void turnOffPruning() {
    // TODO really really should put this in transition function...
    State.PRUNE_SPANS = false;
  }

  /**
   * Asserts that there is at least one Action that has a positive score out of
   * the given state.
   */
  public void assertStateHasAtLeastOneGoldAction(StateSequence ss, TransitionFunction oracleScoreTf, double probRecurse) {
    boolean showState = false;
    boolean showAction = false;
    boolean showDiff = false;
    String fid = ss.getCur().getFrames().getId();
    List<StateSequence> posScoreActions = new ArrayList<>();
    int n = 0;
    if (showState)
      LOG.info("[assertStateHasAtLeastOneGoldAction] out of: " + ss.getCur().show());
    for (StateSequence next : oracleScoreTf.nextStates(ss)) {
      n++;
      Assert.assertTrue(next.getPrev() != null);
      Assert.assertTrue(next.getNext() == null);

      // Note that I'm using the adjoint's score, which is the score of just
      // that action, as opposed to the StateSequence's score, which is the sum
      // of all of the action scores.
      //double s = next.getScore();
      double s = next.getAdjoints().getScore();
      Assert.assertTrue(Math.abs(s) > 1e-5);

      if (showAction) {
        LOG.info("[assertStateHasAtLeastOneGoldAction] at " + fid
            + " applying " + next.getAction() + " has score " + s);
      }
      if (showDiff)
        LOG.info(State.possibleDiff(next.getPrev().getCur(), next.getCur()));
      if (s > 0)
        posScoreActions.add(next);
    }

    if (n == 0)
      return; // Final state.

    if (posScoreActions.isEmpty())
      LOG.warn("about to blow up");
    Assert.assertTrue("n=" + n, posScoreActions.size() > 0);

    if (probRecurse > 1e-8) {
      for (StateSequence ns : posScoreActions) {
        if (rand.nextDouble() >= probRecurse)
          continue;
        assertStateHasAtLeastOneGoldAction(ns, oracleScoreTf, probRecurse * 0.5d);
      }
    }
  }

  /**
   * Ensures that every state has an optimal action according the gold parse.
   *
   * NOTE: Previously I had a bug in State.initial where I didn't allow nullSpans
   * in State.possible. When I fixed it the runtime of this test went from like
   * 1 second to like 17. This test passes before and after. Why the slowdown?
   */
  @Test
  public void noTrainPrereq() {
    List<FNParse> parses = testParses();
    CheatingParams thetaCheat = new CheatingParams(parses);
    thetaCheat.setWeightsByHand();
    Params.Stateful theta = Params.Stateful.lift(thetaCheat);
    for (double probRecurse : Arrays.asList(0d, 0.01d, 0.03d)) {
      for (FNParse y : parses) {
        LOG.info("[noTrainPrereq] testing " + y.getId() + " probRecurse=" + probRecurse);
        State init = State.initialState(y);
        StateSequence ss = new StateSequence(null, null, init, null);
        ActionDrivenTransitionFunction oracleScoreTf =
            new ActionDrivenTransitionFunction(theta, ActionType.COMMIT);

        // Initial state should have an Action that is consistent with the label
        // Recursively?
        // I believe this is true for COMMIT, as in no single COMMIT should step
        // on another COMMIT's toes. For COMMIT_AND_PRUNE, this wont be true if
        // there are spans in y that overlap.
        assertStateHasAtLeastOneGoldAction(ss, oracleScoreTf, probRecurse);
      }
    }
  }

  private List<FNParse> testParses() {
    int m = 10;
    List<FNParse> ys = new ArrayList<>();
    Iterator<FNParse> itr =
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences();
    for (int i = 0; i < m && itr.hasNext(); i++) {
      FNParse y = itr.next();
      if (y.numFrameInstances() == 0)
        continue; // No frameInstances => no states,actions
      ys.add(y);
    }
    return ys;
  }

  /** Set CheatingParams weights by hand */
  @Test
  public void getsItRightWithNoTraining() {
    ItemProvider ip = Reranker.getItemProvider();
    List<FNParse> gold = ItemProvider.allLabels(ip, new ArrayList<>());

    CheatingParams theta = new CheatingParams(gold);
    theta.setWeightsByHand();

    int beamWidth = 10;
    Reranker model = new Reranker(Stateful.NONE, theta, beamWidth);
    Reranker.LOG_MOST_VIOLATED = false;

    StdEvalFunc eval = BasicEvaluation.argOnlyMicroF1;
    for (FNParse g : gold) {
      FNParse h = model.predict(g);
      double perf = eval.evaluate(new SentenceEval(g, h));
      LOG.info("perf=" + perf);
      if (perf < 0.99d)
         LOG.info("diff:\n" + FNDiff.diffArgs(g, h, true));
      Assert.assertTrue(perf > 0.99d);
    }
  }

  @Test
  public void getsItRightQuickly() {
    getsItRight(1);
  }

  public void getsItRight(int iters) {
    Reranker.LOG_UPDATE = true;
    CheatingParams.SHOW_ON_UPDATE = true;
    int beamWidth = 10;
    RerankerTrainer trainer = new RerankerTrainer(rand, iters, 1, 1);
    ItemProvider ip = Reranker.getItemProvider();
    List<FNParse> gold = ItemProvider.allLabels(ip, new ArrayList<>());
    CheatingParams theta = new CheatingParams(gold);
    LOG.info("[getsItRight] before: " + theta.showWeights());
    Reranker model = trainer.train(theta, beamWidth, ip);
    LOG.info("[getsItRight] after " + iters + ": " + theta.showWeights());

    StdEvalFunc eval = BasicEvaluation.argOnlyMicroF1;
    for (FNParse g : gold) {
      FNTagging frames = DataUtil.convertParseToTagging(g);
      FNParse h = model.predict(frames);
      double perf = eval.evaluate(new SentenceEval(g, h));
      LOG.info("[getsItRight] after " + iters + ": perf=" + perf);
      if (perf < 0.99d)
        LOG.info("diff:\n" + FNDiff.diffArgs(g, h, true));
      Assert.assertTrue(perf > 0.99d);
    }
  }

  private void evaluate(Reranker model, ItemProvider ip) {
    // Show the parses one by one
    for (int i = 0; i < ip.size(); i++) {
      FNParse y = ip.label(i);
      FNParse yhat;
      if (model.useItemsForPruning) {
        FNTagging frames = DataUtil.convertParseToTagging(ip.label(i));
        yhat = model.predict(frames, ip.items(i));
      } else {
        yhat = model.predict(DataUtil.convertParseToTagging(y));
      }
      SentenceEval se = new SentenceEval(y, yhat);
      double f1 = BasicEvaluation.argOnlyMicroF1.evaluate(se);
      double prec = BasicEvaluation.argOnlyMicroPrecision.evaluate(se);
      double rec = BasicEvaluation.argOnlyMicroRecall.evaluate(se);
      LOG.info("f1=" + f1 + " p=" + prec + " r=" + rec
          + "  diff:\n" + FNDiff.diffArgs(y, yhat, true));
    }

    // Evaluate performance on all parses
    List<FNParse> gold = ItemProvider.allLabels(ip, new ArrayList<>());
    List<FNTagging> frames = DataUtil.convertParsesToTaggings(gold);
    List<FNParse> hyp = model.useItemsForPruning
        ? model.predict(ip) : model.predict(frames);
    Map<String, Double> perf = BasicEvaluation.evaluate(gold, hyp);
    BasicEvaluation.showResults("[using PriorScoreParams]", perf);
  }

  public void fromPriorScores() {
    // Train a model using PriorScoreParams
    Reranker.LOG_UPDATE = true;
    Reranker.LOG_ORACLE = true;
    Reranker.LOG_MOST_VIOLATED = false;
    ItemProvider ip = Reranker.getItemProvider();
    int beamWidth = 1;
    boolean train = true;
    Reranker model;

    // Train a model based on the items
    if (train) {
      boolean byHand = false;
      PriorScoreParams theta = new PriorScoreParams(ip, true);
      if (byHand) {
        theta.setParamsByHand();
        model = new Reranker(Stateful.NONE, theta, beamWidth);
      } else {
        int iters = 2;
        int batchSize = 20;
        int threads = 1;
        RerankerTrainer trainer = new RerankerTrainer(rand, iters, threads, batchSize);
        model = trainer.train(theta, beamWidth, ip);
      }
    } else {
      // Intersect the items dumped to disk with oracle parameters,
      // check how good the score can be.
      CheatingParams oracle = new CheatingParams(ItemProvider.allLabels(ip));
      oracle.setWeightsByHand();
      model = new Reranker(Stateful.NONE, oracle, beamWidth);
      model.useItemsForPruning = true;
    }

    evaluate(model, ip);
  }

  public static void main(String[] args) {
    LearningTest lt = new LearningTest();
    lt.turnOffPruning();
    //lt.getsItRight(1);
    lt.fromPriorScores();
  }
}
