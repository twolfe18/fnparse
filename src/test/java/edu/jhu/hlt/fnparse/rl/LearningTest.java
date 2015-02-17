package edu.jhu.hlt.fnparse.rl;

import java.io.File;
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
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
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
 * gold parse (CheatingParams), it should be able to get 100% accuracy (quickly)
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
  public void assertStateHasAtLeastOneGoldAction(StateSequence ss, Params.Stateful theta, TransitionFunction oracleScoreTf, double probRecurse) {
    boolean showState = false;
    boolean showAction = false;
    boolean showDiff = false;
    String fid = ss.getCur().getFrames().getId();
    List<StateSequence> posScoreActions = new ArrayList<>();
    int n = 0;
    if (showState)
      LOG.info("[assertStateHasAtLeastOneGoldAction] out of: " + ss.getCur().show());
    for (Action a : oracleScoreTf.nextStates(ss)) {
    //for (StateSequence next : oracleScoreTf.nextStates(ss)) {
      Adjoints adj = theta.score(ss.getCur(), null, a);
      StateSequence next = new StateSequence(ss, null, null, adj);
      n++;
      Assert.assertTrue(next.getPrev() != null);
      Assert.assertTrue(next.getNext() == null);

      // Note that I'm using the adjoint's score, which is the score of just
      // that action, as opposed to the StateSequence's score, which is the sum
      // of all of the action scores.
      //double s = next.getScore();
      double s = next.getAdjoints().forwards();
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
        assertStateHasAtLeastOneGoldAction(ns, theta, oracleScoreTf, probRecurse * 0.5d);
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
    Params.PruneThreshold tau = Params.PruneThreshold.Const.ZERO;
    CheatingParams thetaCheat = new CheatingParams(parses);
    thetaCheat.setWeightsByHand();
    Params.Stateful theta = Params.Stateful.lift(thetaCheat);
    for (double probRecurse : Arrays.asList(0d, 0.01d, 0.03d)) {
      for (FNParse y : parses) {
        LOG.info("[noTrainPrereq] testing " + y.getId() + " probRecurse=" + probRecurse);
        State init = State.initialState(y);
        StateSequence ss = new StateSequence(null, null, init, null);
        TransitionFunction oracleScoreTf =
            new TransitionFunction.Tricky(theta, tau);

        // Initial state should have an Action that is consistent with the label
        // Recursively?
        // I believe this is true for COMMIT, as in no single COMMIT should step
        // on another COMMIT's toes. For COMMIT_AND_PRUNE, this wont be true if
        // there are spans in y that overlap.
        assertStateHasAtLeastOneGoldAction(ss, theta, oracleScoreTf, probRecurse);
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
    ItemProvider ip = Reranker.getItemProvider(100, false);
    List<FNParse> gold = ItemProvider.allLabels(ip, new ArrayList<>());

    CheatingParams theta = new CheatingParams(gold);
    theta.setWeightsByHand();

    int beamWidth = 10;
    Params.PruneThreshold tau = Params.PruneThreshold.Const.ZERO;
    Reranker model = new Reranker(Stateful.NONE, theta, tau, beamWidth, rand);
    Reranker.LOG_FORWARD_SEARCH = false;

    evaluate(model, ip, 0.99d, 0.99d);
  }

  @Test
  public void getsItRightQuickly() {
    getsItRight(1);
  }

  public void getsItRight(int iters) {
    Reranker.LOG_UPDATE = true;
    CheatingParams.SHOW_ON_UPDATE = true;
    RerankerTrainer trainer = new RerankerTrainer(rand, new File("/tmp/fnparse-test"));
    trainer.pretrainConf.beamSize = 10;
    ItemProvider ip = Reranker.getItemProvider(100, false);
    List<FNParse> gold = ItemProvider.allLabels(ip, new ArrayList<>());
    CheatingParams theta = new CheatingParams(gold);
    trainer.statelessParams = theta;
    LOG.info("[getsItRight] before: ");
    theta.showWeights();
    Reranker model = trainer.train1(ip);
    LOG.info("[getsItRight] after " + iters + ": ");
    theta.showWeights();
    evaluate(model, ip, 0.99d, 0.99d);
  }

  private void evaluate(Reranker model, ItemProvider ip, double f1ThreshForEachParse, double f1ThreshAggregate) {
    // Show the parses one by one
    for (int i = 0; i < ip.size(); i++) {
      FNParse y = ip.label(i);
      FNParse yhat = model.predict(DataUtil.convertParseToTagging(y));
      SentenceEval se = new SentenceEval(y, yhat);
      double f1 = BasicEvaluation.argOnlyMicroF1.evaluate(se);
      double prec = BasicEvaluation.argOnlyMicroPrecision.evaluate(se);
      double rec = BasicEvaluation.argOnlyMicroRecall.evaluate(se);
      LOG.info("f1=" + f1 + " p=" + prec + " r=" + rec
          + "  diff:\n" + FNDiff.diffArgs(y, yhat, true));
      Assert.assertTrue(f1 >= f1ThreshForEachParse);
    }

    // Evaluate performance on all parses
    Map<String, Double> results =
        RerankerTrainer.eval(model, ip, "[using PriorScoreParams]");
    Assert.assertTrue(results.get(BasicEvaluation.argOnlyMicroF1.getName())
        >= f1ThreshAggregate);
  }

  public void fromPriorScores() {
    // Train a model using PriorScoreParams
    Reranker.LOG_UPDATE = true;
    Reranker.LOG_FORWARD_SEARCH = false;
    ItemProvider ip = Reranker.getItemProvider(100, false);
    int beamWidth = 1;
    boolean train = true;
    Reranker model;

    // Train a model based on the items
    Params.PruneThreshold tau = Params.PruneThreshold.Const.ZERO;
    if (train) {
      boolean byHand = false;
      PriorScoreParams theta = new PriorScoreParams(ip, true);
      if (byHand) {
        theta.setParamsByHand();
        model = new Reranker(Stateful.NONE, theta, tau, beamWidth, rand);
      } else {
        RerankerTrainer trainer = new RerankerTrainer(rand, new File("/tmp/fnparse-test"));
        trainer.statelessParams = theta;
        trainer.pretrainConf.batchSize = 20;
        model = trainer.train1(ip);
      }
    } else {
      // Intersect the items dumped to disk with oracle parameters,
      // check how good the score can be.
      CheatingParams oracle = new CheatingParams(ItemProvider.allLabels(ip));
      oracle.setWeightsByHand();
      model = new Reranker(Stateful.NONE, oracle, tau, beamWidth, rand);
      Assert.assertTrue("I dumped support for using items for pruning", false);
    }

    evaluate(model, ip, 0d, 0d);
  }

  public static void main(String[] args) {
    LearningTest lt = new LearningTest();
    lt.turnOffPruning();
    //lt.getsItRight(1);
    lt.fromPriorScores();
  }
}
