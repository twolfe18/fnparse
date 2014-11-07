package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.DepParseFactorFactory;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.L2;

public class FrameIdStage
    extends AbstractStage<Sentence, FNTagging>
    implements Stage<Sentence, FNTagging>, Serializable {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG = Logger.getLogger(FrameIdStage.class);

  public static class Params implements Serializable {
    private static final long serialVersionUID = 1L;

    // If null, auto select learning rate
    public Double learningRate = 0.05d;
    public int batchSize = 4;
    public int passes = 2;
    public double propDev = 0.15d;
    public int maxDev = 50;
    public transient Regularizer regularizer = new L2(1_000_000d);
    public ApproxF1MbrDecoder decoder;
    public FactorFactory<FrameVars> factorsTemplate;

    public TargetPruningData getTargetPruningData() {
      return TargetPruningData.getInstance();
    }

    // If true, tuning the decoder will be done on training data. Generally
    // this is undesirable for risk of overfitting, but is good for
    // debugging (e.g. an overfitting test).
    public boolean tuneOnTrainingData = false;

    private transient TargetIndex targetIndex;
    public TargetIndex getTargetIndex() {
      if (targetIndex == null)
        targetIndex = new TargetIndex();
      return targetIndex;
    }

    public Params(ParserParams globalParams) {
      decoder = new ApproxF1MbrDecoder(globalParams.logDomain, 1.5d);
      factorsTemplate = new FrameFactorFactory(globalParams);
    }
  }

  public Params params;

  private static int frameVarsInstantiated = 0;
  public static void frameVarInstantiated() {
    frameVarsInstantiated++;
    if (frameVarsInstantiated % 1000 == 0)
      LOG.info(frameVarsInstantiated + " frame vars instantiated");
  }

  public FrameIdStage(
      ParserParams globalParams,
      HasFeatureAlphabet featureAlphabet) {
    super(globalParams, featureAlphabet);
    params = new Params(globalParams);
  }

  @Override
  public void configure(java.util.Map<String,String> configuration) {
    String key = "regularizer." + getName();
    String reg = configuration.get(key);
    if (reg != null)
      params.regularizer = new L2(Double.parseDouble(reg));

    key = "batchSize." + getName();
    String bs = configuration.get(key);
    if (bs != null)
      params.batchSize = Integer.parseInt(bs);

    key = "passes." + getName();
    String passes = configuration.get(key);
    if (passes != null)
      params.passes = Integer.parseInt(passes);
  }

  @Override
  public Serializable getParamters() {
    return params;
  }

  @Override
  public void setPameters(Serializable params) {
    this.params = (Params) params;
  }

  @Override
  public Double getLearningRate() {
    return params.learningRate;
  }

  @Override
  public Regularizer getRegularizer() {
    return params.regularizer;
  }

  @Override
  public int getBatchSize() {
    return params.batchSize;
  }

  @Override
  public int getNumTrainingPasses() {
    return params.passes;
  }

  public void train(List<FNParse> examples) {
    Collections.shuffle(examples, globalParams.rand);
    List<Sentence> x = new ArrayList<>();
    List<FNTagging> y = new ArrayList<>();
    for (FNTagging t : examples) {
      x.add(t.getSentence());
      y.add(t);
    }
    train(x, y, params.learningRate,
        params.regularizer, params.batchSize, params.passes);
  }

  @Override
  public TuningData getTuningData() {
    final List<Double> biases = new ArrayList<Double>();
    for(double b=0.5d; b<8d; b *= 1.1d) biases.add(b);
    LOG.debug("called getTuningData, trying " + biases.size() + " biases");
    return new TuningData() {
      @Override
      public ApproxF1MbrDecoder getDecoder() { return params.decoder; }
      @Override
      public EvalFunc getObjective() { return BasicEvaluation.targetMicroF1; }
      @Override
      public List<Double> getRecallBiasesToSweep() { return biases; }
      @Override
      public boolean tuneOnTrainingData() { return params.tuneOnTrainingData; }
    };
  }

  @Override
  public StageDatumExampleList<Sentence, FNTagging> setupInference(
      List<? extends Sentence> input,
      List<? extends FNTagging> labels) {
    List<StageDatum<Sentence, FNTagging>> data = new ArrayList<>();
    int n = input.size();
    assert labels == null || labels.size() == n;
    for (int i = 0; i < n; i++) {
      Sentence s = input.get(i);
      if(labels == null)
        data.add(new FrameIdDatum(s, this));
      else
        data.add(new FrameIdDatum(s, this, labels.get(i)));
    }
    return new StageDatumExampleList<Sentence, FNTagging>(data);
  }

  /**
   * Takes a sentence, and optionally a FNTagging, and can make either
   * {@link Decodable}<FNTagging>s (for prediction) or
   * {@link LabeledFgExample}s for training.
   * 
   * @author travis
   */
  public static class FrameIdDatum
      implements StageDatum<Sentence, FNTagging> {
    private final Sentence sentence;
    private final boolean hasGold;
    private final FNTagging gold;
    private final List<FrameVars> possibleFrames;
    private final FrameIdStage parent;

    public FrameIdDatum(Sentence s, FrameIdStage fid) {
      this.parent = fid;
      this.sentence = s;
      this.hasGold = false;
      this.gold = null;
      this.possibleFrames = new ArrayList<>();
      initHypotheses();
    }

    public FrameIdDatum(Sentence s, FrameIdStage fid, FNTagging gold) {
      assert gold != null;
      this.parent = fid;
      this.sentence = s;
      this.hasGold = true;
      this.gold = gold;
      this.possibleFrames = new ArrayList<>();
      initHypotheses();
      setGold(gold);
    }

    private void initHypotheses() {
      assert possibleFrames.size() == 0;
      TargetIndex ti = parent.params.getTargetIndex();
      boolean requireInParens = false;
      boolean requirePosMatchOneSingleWord = false;
      Map<Span, Set<Frame>> byTarget = ti.findFrames(
          sentence, requireInParens, requirePosMatchOneSingleWord);
      for (Map.Entry<Span, Set<Frame>> x : byTarget.entrySet()) {
        Span target = x.getKey();
        List<Frame> frames = new ArrayList<>();
        frames.addAll(x.getValue());
        FrameVars fv = new FrameVars(target, null, frames);
        possibleFrames.add(fv);
        frameVarInstantiated();
      }
    }

    private void setGold(FNTagging p) {
      if (p.getSentence() != sentence)
        throw new IllegalArgumentException();

      // Build an index from target to FrameVars
      Map<Span, FrameVars> byTarget = new HashMap<>();
      Set<FrameVars> haventSet = new HashSet<FrameVars>();
      for (FrameVars fHyp : this.possibleFrames) {
        FrameVars old = byTarget.put(fHyp.getTarget(), fHyp);
        assert old == null;
        haventSet.add(fHyp);
      }

      // Match up each FI to a FIHypothesis by the head word in the target
      for (FrameInstance fi : p.getFrameInstances()) {
        FrameVars fHyp = byTarget.get(fi.getTarget());
        if (fHyp == null) continue;	// nothing you can do here
        if (fHyp.goldIsSet()) {
          System.err.println("WARNING: " + p.getId() +
              " has at least two FrameInstances with the same "
              + "target head word, choosing the first one");
          continue;
        }
        fHyp.setGold(fi);
        boolean removed = haventSet.remove(fHyp);
        if (!removed) {
          throw new RuntimeException(
              "two FrameInstances with same target? "
                  + p.getSentence().getId());
        }
      }

      // The remaining hypotheses must be null because they didn't
      // correspond to a FI in the parse
      for (FrameVars fHyp : haventSet)
        fHyp.setGoldIsNull();
    }

    @Override
    public Sentence getInput() { return sentence; }

    @Override
    public boolean hasGold() { return hasGold; }

    @Override
    public LabeledFgExample getExample() {
      FactorGraph fg = getFactorGraph();
      VarConfig gold = new VarConfig();

      // Add the gold labels
      for(FrameVars hyp : possibleFrames) {
        assert hyp.goldIsSet();
        hyp.register(fg, gold);
      }

      return new LabeledFgExample(fg, gold);
    }

    private FactorGraph getFactorGraph() {
      FactorGraph fg = new FactorGraph();

      // Create factors
      List<Factor> factors = new ArrayList<Factor>();
      ProjDepTreeFactor depTree = null;
      ConstituencyTreeFactor consTree = null;
      if (parent.getGlobalParams().useLatentDepenencies) {
        depTree = new ProjDepTreeFactor(
            sentence.size(), VarType.LATENT);
        DepParseFactorFactory depParseFactorTemplate =
            new DepParseFactorFactory(parent.getGlobalParams());
        factors.addAll(depParseFactorTemplate.initFactorsFor(
            sentence, Collections.emptyList(), depTree, consTree));
      }
      factors.addAll(parent.params.factorsTemplate.initFactorsFor(
          sentence, possibleFrames, depTree, consTree));

      // Add factors to the factor graph
      for(Factor f : factors)
        fg.addFactor(f);

      return fg;
    }

    @Override
    public FrameIdDecodable getDecodable() {
      FgInferencerFactory infFact = parent.infFactory();
      FactorGraph fg = this.getFactorGraph();
      return new FrameIdDecodable(
          sentence, possibleFrames, fg, infFact, parent);
    }

    @Override
    public FNTagging getGold() {
      assert hasGold();
      return gold;
    }
  }

  /**
   * Stores beliefs about frameId variables (see {@link Decodable}) and
   * implements the decoding step.
   * 
   * @author travis
   */
  public static class FrameIdDecodable
      extends Decodable<FNTagging>
      implements Iterable<FrameVars> {

    private final FrameIdStage parent;
    private final Sentence sentence;
    private final List<FrameVars> possibleFrames;

    public FrameIdDecodable(Sentence sent, List<FrameVars> possibleFrames,
        FactorGraph fg, FgInferencerFactory infFact, FrameIdStage fid) {
      super(fg, infFact, fid);
      this.parent = fid;
      this.sentence = sent;
      this.possibleFrames = possibleFrames;
    }

    public Sentence getSentence() { return sentence; }

    @Override
    public Iterator<FrameVars> iterator() {
      return possibleFrames.iterator();
    }

    @Override
    public FNTagging decode() {
      FgInferencer hasMargins = this.getMargins();
      final boolean logDomain = this.getMargins().isLogDomain();
      List<FrameInstance> fis = new ArrayList<FrameInstance>();
      for (FrameVars fvars : possibleFrames) {
        final int T = fvars.numFrames();
        double[] beliefs = new double[T];
        for (int t=0; t<T; t++) {
          DenseFactor df =
              hasMargins.getMarginals(fvars.getVariable(t));
          // TODO Exactly1 factor removes the need for this
          if (logDomain) df.logNormalize();
          else df.normalize();
          beliefs[t] = df.getValue(BinaryVarUtil.boolToConfig(true));
        }
        parent.globalParams.normalize(beliefs);

        final int nullFrameIdx = fvars.getNullFrameIdx();
        int tHat = parent.params.decoder.decode(beliefs, nullFrameIdx);
        Frame fHat = fvars.getFrame(tHat);
        if (fHat != Frame.nullFrame) {
          fis.add(FrameInstance.frameMention(
              fHat, fvars.getTarget(), sentence));
        }
      }
      return new FNTagging(sentence, fis);
    }
  }

  @Override
  public void scanFeatures(List<FNParse> data) {
    List<Sentence> sentences = DataUtil.stripAnnotations(data);
    this.scanFeatures(sentences, data, 999, 999_999_999);
  }
}
