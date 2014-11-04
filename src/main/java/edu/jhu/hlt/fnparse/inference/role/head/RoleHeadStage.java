package edu.jhu.hlt.fnparse.inference.role.head;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.evaluation.FPR;
import edu.jhu.hlt.fnparse.evaluation.GenerousEvaluation;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.DepParseFactorFactory;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.pruning.ArgPruner;
import edu.jhu.hlt.fnparse.inference.pruning.IArgPruner;
import edu.jhu.hlt.fnparse.inference.pruning.NoArgPruner;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadVars.RVar;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.L2;

/**
 * Predicts the heads of roles for frames that appear in a sentence. Also serves
 * as a pruning step for predicting spans (some roles will have no head
 * predicted as being true).
 * 
 * @author travis
 */
public class RoleHeadStage
    extends AbstractStage<FNTagging, FNParse>
    implements Stage<FNTagging, FNParse>, Serializable {
  private static final long serialVersionUID = 1L;

  public static class Params implements Serializable {
    private static final long serialVersionUID = 1L;

    public int batchSize = 1;
    public int passes = 10;
    public int maxSentenceLengthForTraining = -1;
    public Double learningRate = null;//0.05;    // null means auto-select
    public transient Regularizer regularizer = new L2(1_000_000d);
    public IArgPruner argPruner;
    public ApproxF1MbrDecoder decoder;
    public RoleFactorFactory factorTemplate;

    // If true, tuning the decoder will be done on training data. Generally
    // this is undesirable for risk of overfitting, but is good for
    // debugging (e.g. an overfitting test).
    public boolean tuneOnTrainingData = false;

    public Params(ParserParams globalParams) {
      this.factorTemplate = new RoleFactorFactory(globalParams);
      this.argPruner = new ArgPruner(
          TargetPruningData.getInstance(), globalParams.headFinder);
      //this.argPruner = new NoArgPruner();
      this.decoder = new ApproxF1MbrDecoder(globalParams.logDomain, 1d);
    }
  }

  public Params params;

  public RoleHeadStage(ParserParams globalParams, HasFeatureAlphabet featureNames) {
    super(globalParams, featureNames);
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

  /** can't undo this for now */
  public void disablePruning() {
    params.argPruner = new NoArgPruner();
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

  @Override
  public void scanFeatures(List<FNParse> data) {
    List<FNTagging> frames = DataUtil.convertParsesToTaggings(data);
    List<FNParse> argHeads = DataUtil.convertArgumenSpansToHeads(data, globalParams.headFinder);
    this.scanFeatures(frames, argHeads, 999, 999_999_999);
  }

  @Override
  public void train(List<FNTagging> x, List<FNParse> y) {
    List<FNTagging> xUse;
    List<FNParse> yUse;
    if (params.maxSentenceLengthForTraining > 0) {
      xUse = new ArrayList<>();
      yUse = new ArrayList<>();
      int n = x.size();
      for (int i=0; i<n; i++) {
        FNTagging xi = x.get(i);
        if(xi.getSentence().size() <= params.maxSentenceLengthForTraining) {
          xUse.add(xi);
          yUse.add(y.get(i));
        }
      }
      log.info(String.format("[train] Filtering out sentences longer than"
          + " %d words, kept %d of %d",
          params.maxSentenceLengthForTraining,
          xUse.size(), x.size()));
      if (xUse.size() == 0)
        log.warn("[train] Filtered out all training examples, not training!");
    }
    else {
      xUse = x;
      yUse = y;
    }
    super.train(xUse, yUse, params.learningRate,
        params.regularizer, params.batchSize, params.passes);
  }

  @Override
  public TuningData getTuningData() {
    final List<Double> biases = new ArrayList<Double>();
    for (double b = 0.2d; b < 15d; b *= 1.2d) biases.add(b);
    return new TuningData() {
      @Override
      public ApproxF1MbrDecoder getDecoder() { return params.decoder; }
      @Override
      public EvalFunc getObjective() {
        double timeCost = 1e-6; // anything below 1e-4 is just breaking ties
        return GenerousEvaluation.generousPlusTime(FPR.Mode.F1, false, timeCost);
      }
      @Override
      public List<Double> getRecallBiasesToSweep() { return biases; }
      @Override
      public boolean tuneOnTrainingData() { return params.tuneOnTrainingData; }
    };
  }

  /** Must have initialized weights before calling this */
  @Override
  public StageDatumExampleList<FNTagging, FNParse> setupInference(
      List<? extends FNTagging> input,
      List<? extends FNParse> output) {
    List<StageDatum<FNTagging, FNParse>> data = new ArrayList<>();
    int n = input.size();
    assert output == null || output.size() == n;
    for (int i=0; i<n; i++) {
      FNTagging x = input.get(i);
      if (output == null)
        data.add(new RoleIdStageDatum(x, this));
      else
        data.add(new RoleIdStageDatum(x, output.get(i), this));
    }
    return new StageDatumExampleList<>(data);
  }

  /**
   * 
   * @author travis
   */
  public static class RoleIdStageDatum implements StageDatum<FNTagging, FNParse> {
    private final List<RoleHeadVars> roleVars;
    private final FNTagging input;
    private final FNParse gold;
    private final RoleHeadStage parent;

    /** you don't know gold */
    public RoleIdStageDatum(FNTagging frames, RoleHeadStage parent) {
      this.roleVars = new  ArrayList<>();
      this.input = frames;
      this.gold = null;
      this.parent = parent;
      initHypotheses(frames, null, false);
    }

    /** you know gold */
    public RoleIdStageDatum(FNTagging frames, FNParse gold, RoleHeadStage parent) {
      if(gold == null)
        throw new IllegalArgumentException();
      this.roleVars = new  ArrayList<>();
      this.input = frames;
      this.gold = gold;
      this.parent = parent;
      initHypotheses(frames, gold, true);
    }

    public Sentence getSentence() { return input.getSentence(); }

    /**
     * Creates the needed variables and puts them in super.hypotheses.
     * 
     * @param frames
     * @param gold can be null if !hasGold
     * @param hasGold
     */
    private void initHypotheses(FNTagging frames, FNParse gold, boolean hasGold) {
      if(hasGold && gold.getSentence() != frames.getSentence())
        throw new IllegalArgumentException();

      Timer t = parent.globalParams.getTimer("argId-initHypotheses");
      t.start();

      // Make sure that we don't have overlapping targets (see next step)
      frames = DataUtil.filterOutTargetCollisions(frames);

      // Build an index keying off of the target head index.
      // This assumes that:
      // 1) We are using a headword to describe a target
      // 2) A given headword can evoke at most 1 frame.
      Map<Span, FrameInstance> fiByTarget = null;
      if (hasGold)
        fiByTarget = DataUtil.getFrameInstanceByTarget(gold);

      for (FrameInstance fi : frames.getFrameInstances()) {
        Span target = fi.getTarget();
        RoleHeadVars rv;
        if (hasGold) {  // Train mode
          // goldFI may be null, meaning that we predicted a frame
          // that was not actually present in the sentence.
          FrameInstance goldFI = fiByTarget.get(target);
          rv = new RoleHeadVars(goldFI, target, fi.getFrame(),
              fi.getSentence(), parent.globalParams, parent.params);
        } else {        // Predict/decode mode
          rv = new RoleHeadVars(target, fi.getFrame(),
              fi.getSentence(), parent.globalParams, parent.params);
        }

        this.roleVars.add(rv);
      }
      t.stop();
    }

    @Override
    public FNTagging getInput() { return input; }

    @Override
    public boolean hasGold() {
      return gold != null;
    }

    @Override
    public FNParse getGold() {
      assert hasGold();
      return gold;
    }

    protected FactorGraph getFactorGraph() {
      FactorGraph fg = new FactorGraph();

      // create factors
      List<Factor> factors = new ArrayList<>();
      ProjDepTreeFactor depTree = null;
      ConstituencyTreeFactor consTree = null;	// used in RoleSpanStage, not here
      if(parent.globalParams.useLatentDepenencies) {
        depTree = new ProjDepTreeFactor(getSentence().size(), VarType.LATENT);
        DepParseFactorFactory depParseFactorTemplate =
            new DepParseFactorFactory(parent.globalParams);
        factors.addAll(depParseFactorTemplate.initFactorsFor(
            getSentence(), Collections.emptyList(), depTree, consTree));
      }
      factors.addAll(parent.params.factorTemplate.initFactorsFor(
          getSentence(), roleVars, depTree, consTree));

      // add factors to the factor graph
      for(Factor f : factors)
        fg.addFactor(f);

      return fg;
    }

    @Override
    public LabeledFgExample getExample() {
      FactorGraph fg = getFactorGraph();
      VarConfig goldConf = new VarConfig();

      // add the gold labels
      for(RoleHeadVars hyp : roleVars) {
        hyp.register(fg, goldConf);
      }

      return new LabeledFgExample(fg, goldConf);
    }

    @Override
    public Decodable<FNParse> getDecodable() {
      FgInferencerFactory infFact = parent.infFactory();
      return new RoleIdDecodable(
          getFactorGraph(), infFact, getSentence(), roleVars, parent);
    }
  }

  /**
   * decodes FNParses which have arguments represented by width-1 spans
   * 
   * @author travis
   */
  public static class RoleIdDecodable extends Decodable<FNParse> {
    public static final Logger LOG = Logger.getLogger(RoleIdDecodable.class);
    public static boolean debug = false;

    private final Sentence sent;
    private final List<RoleHeadVars> hypotheses;
    private final ApproxF1MbrDecoder decoder;
    private final RoleHeadStage parent;

    public RoleIdDecodable(
        FactorGraph fg,
        FgInferencerFactory infFact,
        Sentence sent,
        List<RoleHeadVars> hypotheses,
        RoleHeadStage parent) {
      super(fg, infFact, parent);
      this.sent = sent;
      this.hypotheses = hypotheses;
      this.decoder = parent.params.decoder;
      this.parent = parent;
    }

    @Override
    public FNParse decode() {
      FgInferencer inf = getMargins();
      List<FrameInstance> fis = new ArrayList<FrameInstance>();
      for(RoleHeadVars rv : hypotheses)
        fis.add(decodeRoleVars(rv, inf));
      return new FNParse(sent, fis);
    }

    public FrameInstance decodeRoleVars(RoleHeadVars rv, FgInferencer inf) {

      Timer t = parent.globalParams.getTimer("argId-decode");
      t.start();

      // Max over j for every role
      final int n = sent.size();
      final int K = rv.getFrame().numRoles();
      Span[] arguments = new Span[K];
      Arrays.fill(arguments, Span.nullSpan);
      // Last inner index is "not realized"
      double[][] beliefs = new double[K][n+1];
      if (parent.globalParams.logDomain) {
        for (int i = 0; i < beliefs.length; i++) // otherwise default is 0
          Arrays.fill(beliefs[i], Double.NEGATIVE_INFINITY);
      }

      boolean[] considered = new boolean[K];
      Iterator<RVar> iter = rv.getVars();
      while (iter.hasNext()) {
        RVar rvar = iter.next();
        DenseFactor df = inf.getMarginals(rvar.roleVar);
        beliefs[rvar.k][rvar.j] = df.getValue(
            BinaryVarUtil.boolToConfig(true));
        considered[rvar.k] = true; 
        if (debug)
          LOG.debug(rvar.roleVar.getName() + "\t" + df);
      }

      for (int k = 0; k < K; k++) {
        if (!considered[k]) continue;
        if (debug) {
          System.out.println();
          for (int i = 0; i < beliefs[k].length; i++) {
            LOG.debug(String.format("%-15s %-15s % 5d %-15s %.3f",
                rv.getFrame().getName() + "@" + rv.getTarget(),
                rv.getFrame().getRole(k) + "/" + k,
                i,
                i < sent.size() ? sent.getWord(i) : "NONE",
                    beliefs[k][i]));
          }
        }

        // TODO add Exactly1 factor!
        parent.globalParams.normalize(beliefs[k]);

        int jHat = decoder.decode(beliefs[k], n);
        if (jHat < n)
          arguments[k] = Span.widthOne(jHat);
      }
      if (t != null) t.stop();
      return FrameInstance.newFrameInstance(
        rv.getFrame(), rv.getTarget(), arguments, sent);
    }
  }
}
