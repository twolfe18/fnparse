package edu.jhu.hlt.fnparse.experiment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameArgInstance;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.features.precompute.FeatureSet;
import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.scoring.Adjoints;

/**
 * Stream (O(1) memory) over pre-extracted features {@link FeaturePrecomputation},
 * on-the-fly converting (unigram) templates to (n-gram) features according to
 * a feature set {@link FeatureSet} {@link BaseTemplates}, and learning a
 * classifier as you go.
 *
 * This only supports classification (e.g. no global features), so it can be
 * relatively light-weight (doesn't know about FNParses, just sees features).
 *
 * Right now this uses the progressive validation loss described in:
 * http://hunch.net/~jl/projects/prediction_bounds/progressive_validation/coltfinal.pdf
 *
 * TODO Switch from crappy shard-based dev/test set to proper one using sentence
 * ids file (should already be generated).
 *
 * @author travis
 */
public class FeatureSelectionClassificationExperiments {
  // if a given (target,arg) has more than one role, skip it
  public static boolean SKIP_MULTI_ROLE_INSTANCES = true;


  public static class Inst extends FrameArgInstance {
    private String sentId;
    private long hc;
    public Inst(String sentId, Frame f, Span t, int k, Span s) {
      super(f, t, k, s);
      this.sentId = sentId;
      int fr = f == null ? 31 : f.getId();
      this.hc = Hash.mix64(sentId.hashCode(), fr, Span.index(t), k, Span.index(s));
    }
    @Override
    public int hashCode() {
      return (int) hc;
    }
    @Override
    public boolean equals(Object other) {
      if (other instanceof Inst) {
        Inst i = (Inst) other;
        return hc == i.hc
            && sentId.equals(i.sentId)
            && super.equals(i);
      }
      return false;
    }
  }

  public static class SetEval<T> {
    private Set<T> gold, auto;

    public SetEval() {
      this(new HashSet<>(), new HashSet<>());
    }

    public SetEval(Set<T> gold, Set<T> auto) {
      this.gold = gold;
      this.auto = auto;
    }

    public void clear() {
      gold.clear();
      auto.clear();
    }

    public boolean addGold(T t) {
      return gold.add(t);
    }
    public boolean addAuto(T t) {
      return auto.add(t);
    }

    public double f1() {
      double r = recall();
      if (r == 0)
        return 0;
      double p = precision();
      if (p == 0)
        return 0;
      return 2 * p * r / (p + r);
    }

    public double precision() {
      double tp = tp();
      if (tp == 0)
        return 0;
      return tp / (tp + fp());
    }

    public double recall() {
      double tp = tp();
      if (tp == 0)
        return 0;
      return tp / (tp + fn());
    }

    public int fn() {
      int fn = 0;
      for (T i : gold)
        if (!auto.contains(i))
          fn++;
      return fn;
    }

    public int fp() {
      return auto.size() - tp();
    }

    public int tp() {
      int tp = 0;
      for (T i : auto) {
        if (gold.contains(i))
          tp++;
      }
      return tp;
    }
  }

  /**
   * Implements progressive validation as described in:
   * http://hunch.net/~jl/projects/prediction_bounds/progressive_validation/coltfinal.pdf
   *
   * With a stratified sampling technique: we maintain counts for each label
   * and stratify our accuracy estimate over labels (useful for cases of class-imbalance).
   * Each strata (chosen by true label) is split into k folds, and the (k-1)^th
   * fold is used for the test/validation set.
   * If you have a large training set and just want to track learning progress,
   * large k values like 1000 are appropriate.
   * In batch progressive validation, trainging is run to completion on the
   * train set before running validation on the test set, which is sort of like
   * k=1 with ignoring the advice of updateCount's return value on the train set.
   * Use however you like.
   */
  public static class ProgressiveValidation {
    private double smoothYCounts = 1;   // apply add-lambda (dir-mult MAP) smoothing to yCounts
    private int[] yCounts;
    private int[][] confusion;
    private int confusionSum;
    private int numFolds;

    /**
     * @param numClasses is the range of y (0..numClasses-1)
     * @param numFolds is how many pieces to split each strata (by gold label) into.
     */
    public ProgressiveValidation(int numClasses, int numFolds) {
      this.yCounts = new int[numClasses];
      this.confusion = new int[numClasses][numClasses];
      this.confusionSum = 0;
      this.numFolds = numFolds;
    }

    public void resize(int numClasses) {
      assert numClasses > yCounts.length;
      yCounts = Arrays.copyOf(yCounts, numClasses);
      confusion = Arrays.copyOf(confusion, numClasses);
      for (int i = 0; i < numClasses; i++) {
        if (confusion[i] == null)
          confusion[i] = new int[numClasses];
        else
          confusion[i] = Arrays.copyOf(confusion[i], numClasses);
      }
    }

    public void clearCounts() {
      Arrays.fill(yCounts, 0);
    }
    public void clearAccuracy() {
      for (int i = 0; i < confusion.length; i++)
        Arrays.fill(confusion[i], 0);
      confusionSum = 0;
    }

    public int numClasses() {
      return yCounts.length;
    }
    public int numFolds() {
      return numFolds;
    }
    public int numAccuracy() {
      return confusionSum;
    }

    /**
     * You can call this on any instance, whether a part of the "test" set or
     * not. This is used to estimate p(y).
     * @return true if you call updateAccuracy using this instance
     */
    public boolean updateCount(int y) {
      if (y >= yCounts.length)
        resize(y + 1);
      int c = ++yCounts[y];
      return c % numFolds == 0;
    }

    /**
     * You should call this once per instance in the "test" set, BEFORE it has
     * been used for an update.
     */
    public void updateAccuracy(int y, int yhat) {
      int m = Math.max(y, yhat);
      if (m >= yCounts.length)
        resize(m + 1);
      confusion[y][yhat]++;
      confusionSum++;
    }

    public double loss() {
      int Y = confusion.length;
      double[] lossPerLabel = new double[Y];
      for (int i = 0; i < Y; i++) {
        double n = 0;
        for (int j = 0; j < Y; j++)
          n += confusion[i][j];
        if (n > 0) {
          lossPerLabel[i] = 1d - (confusion[i][i] / n);
          assert lossPerLabel[i] >= 0 && lossPerLabel[i] <= 1;
        }
      }
      double num = 0, denom = 0;
      for (int i = 0; i < Y; i++) {
        if (yCounts[i] == 0)
          continue;
        double yc  = yCounts[i] + smoothYCounts;
        num += lossPerLabel[i] * yc;
        denom += yc;
      }
      return num / denom;
    }
  }

  // Stuff related to features
  private List<File> featureFiles;
//  private File featureSetFile;

  // Derived
  private int[][] fs;
  private int[] template2cardinality;
  private BitSet templates;

  // Stuff related to learning
  private AveragedPerceptronWeights[] theta;    // indexed by role, 0th index is weights for no-role
  private int dimension = 1<<18;

  // Stuff related to evaluation
  private ProgressiveValidation acc;
  private ProgressiveValidation recentAcc;
  private SetEval<Inst> fpr;
  private SetEval<Inst> recentFpr;

  // Misc
  private Random rand;
  private TimeMarker tm;

  public FeatureSelectionClassificationExperiments(
      Random r,
      BiAlph featureCardinalities,
      File featureSetFile,
      Iterable<File> featureFiles) {

    this.rand = r;
    this.tm = new TimeMarker();
//    this.featureSetFile = featureSetFile;
    this.featureFiles = new ArrayList<>();
    for (File f : featureFiles)
      this.featureFiles.add(f);

    int numRoles = 30;
    this.theta = new AveragedPerceptronWeights[numRoles];
    int numFolds = 500;  // update accuracy every this many examples
    this.acc = new ProgressiveValidation(numRoles, numFolds);
    this.recentAcc = new ProgressiveValidation(numRoles, numFolds);
    this.fpr = new SetEval<>();
    this.recentFpr = new SetEval<>();

    this.fs = FeatureSet.getFeatureSet2(featureSetFile, featureCardinalities);
    this.template2cardinality = featureCardinalities.makeTemplate2Cardinality();
    this.templates = new BitSet();
    for (int[] f : fs)
      for (int ff : f)
        this.templates.set(ff);
  }

  /**
   * Multiple calls to this will screw up the progressive validation error
   * estimates because we get the validation set by taking an online sample.
   */
  public void run() {
    Collections.shuffle(featureFiles, rand);
    for (File f : featureFiles)
      learn(f);
    Log.info("done. "
        + " recentLoss=" + recentAcc.loss()
        + " nRecent=" + recentAcc.numAccuracy()
        + " loss=" + acc.loss()
        + " n=" + acc.numAccuracy()
        + " precision=" + fpr.precision()
        + " recall=" + fpr.recall()
        + " f1=" + fpr.f1());
  }

  private void learn(File f) {

    // Shuffling instances (t,s) within a file improves learning
    List<FeatureFile.Line> lines = new ArrayList<>();
    boolean featuresSorted = true;
    for (FeatureFile.Line l : FeatureFile.getLines(f, featuresSorted))
      lines.add(l);
    Collections.shuffle(lines, rand);

    for (FeatureFile.Line line : lines) {
      CachedFeatures.Item i = new CachedFeatures.Item(null);
      Span t = line.getTarget();
      Span s = line.getArgSpan();
      i.setFeatures(t, s, line);
      i.convertToFlattenedRepresentation(fs, template2cardinality);
      List<ProductIndex> features = i.getFlattenedCachedFeatures(t, s);

      // Extract the label
      int yIdx;
      int[] roles = line.getRoles(false);
      if (roles.length == 1) {
        assert roles[0] == -1;
        yIdx = 0;
      } else {
        assert roles.length % 3 == 0 : "see FeaturePrecomputation";
        // role, frameRole, frame
        if (roles.length > 3) {
          assert SKIP_MULTI_ROLE_INSTANCES;
          continue;
        }
        yIdx = roles[0] + 1;
      }
      AveragedPerceptronWeights yWeights = getWeights(yIdx, true);
      Adjoints yScore = yWeights.score(features, false);
      Inf y = new Inf(yIdx, yScore, yWeights);

      // Update progressive validation
      // [AVERAGE WEIGHTS]
      Inf yhatAvg = predict(features, true);
      updateLoss(y, yhatAvg, line);

      // Make a prediction/update
      // [NON-AVERAGE WEIGHTS]
      Inf yhat = predict(features, false);
      updateWeights(y, yhat);
    }
  }

  private void updateLoss(Inf y, Inf yhatAvg, FeatureFile.Line line) {
    double secBtwLoss = 10;
    if (acc.updateCount(y.label) && yhatAvg.label >= 0)
      acc.updateAccuracy(y.label, yhatAvg.label);
    recentAcc.updateCount(y.label);
    if (yhatAvg.label >= 0)
      recentAcc.updateAccuracy(y.label, yhatAvg.label);
    if (y.label > 0) {
      Inst g = new Inst(line.getSentenceId(), null, line.getTarget(), y.label, line.getArgSpan());
      fpr.addGold(g);
      recentFpr.addGold(g);
    }
    if (yhatAvg.label > 0) {
      Inst a = new Inst(line.getSentenceId(), null, line.getTarget(), yhatAvg.label, line.getArgSpan());
      fpr.addAuto(a);
      recentFpr.addAuto(a);
    }
    if (tm.enoughTimePassed(secBtwLoss)) {
      Log.info("recentLoss=" + recentAcc.loss()
      + " nRecent=" + recentAcc.numAccuracy()
      + " loss=" + acc.loss()
      + " n=" + acc.numAccuracy()
      + " recentPrecision=" + recentFpr.precision()
      + " recentRecall=" + recentFpr.recall()
      + " recentF1=" + recentFpr.f1()
      + " precision=" + fpr.precision()
      + " recall=" + fpr.recall()
      + " f1=" + fpr.f1());
      recentAcc.clearAccuracy();
      recentFpr.clear();
    }
  }

  private static class Inf {
    int label;
    Adjoints score;
    AveragedPerceptronWeights weights;
    public Inf(int label, Adjoints score, AveragedPerceptronWeights weights) {
      this.label = label;
      this.score = score;
      this.weights = weights;
    }
  }

  private void updateWeights(Inf y, Inf yhat) {
    if (y.label != yhat.label) {
      y.score.backwards(-1);
      if (yhat.score != null)
        yhat.score.backwards(+1);
    }
    y.weights.completedObservation();
    if (yhat.weights != y.weights)
      yhat.weights.completedObservation();
  }

  public Inf predict(List<ProductIndex> features, boolean averageWeights) {
    Inf yhat = new Inf(-1, null, null);
    for (int k = 0; k < theta.length; k++) {
      AveragedPerceptronWeights w = getWeights(k, false);
      if (w == null)
        continue;
      Adjoints score = w.score(features, false);
      score = Adjoints.cacheIfNeeded(score);
      if (yhat.label < 0 || score.forwards() > yhat.score.forwards())
        yhat = new Inf(k, score, w);
    }
    return yhat;
  }

  /** may return null if !addIfNecessary */
  private AveragedPerceptronWeights getWeights(int role, boolean addIfNecessary) {
    if (role >= theta.length) {
      int newSize = Math.max(role + 1, (int) (1.6 * theta.length + 1));
      this.theta = Arrays.copyOf(theta, newSize);
      if (newSize >= acc.numClasses())
        acc.resize(newSize);
    }
    AveragedPerceptronWeights w = theta[role];
    if (w == null && addIfNecessary) {
      int numIntercept = 0;
      w = theta[role] = new AveragedPerceptronWeights(dimension, numIntercept);
    }
    return w;
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);

    LineMode lm = LineMode.valueOf(
        config.getString("bialphLineMode", LineMode.ALPH.name()));
    BiAlph featureCardinalities = new BiAlph(
        config.getExistingFile("bialph"), lm);

    FeatureSelectionClassificationExperiments fsce =
        new FeatureSelectionClassificationExperiments(
            new Random(config.getInt("seed", 9001)),
            featureCardinalities,
            config.getExistingFile("featureSetFile"),
            config.getFileGlob("featureFiles"));
    fsce.run();
  }
}
