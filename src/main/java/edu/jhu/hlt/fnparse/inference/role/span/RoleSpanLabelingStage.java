package edu.jhu.hlt.fnparse.inference.role.span;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameRoleInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.hlt.fnparse.util.HasFgModel;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.L2;
import edu.jhu.prim.arrays.Multinomials;

/**
 * This stage takes a list of frame instances, each of which has a pruned set of
 * spans which could hold their arguments, and chooses the roles that are
 * realized for every given frame-role.
 * 
 * @author travis
 */
public class RoleSpanLabelingStage
    extends AbstractStage<FNParseSpanPruning, FNParse> {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG =
      Logger.getLogger(RoleSpanLabelingStage.class);

  private TemplatedFeatures features;
  private ApproxF1MbrDecoder decoder;
  private transient Regularizer regularizer = new L2(1_000_000d);

  public RoleSpanLabelingStage(
      ParserParams params, HasFeatureAlphabet featureNames) {
    super(params, featureNames);
    features = new TemplatedFeatures("roleSpanLabeling",
        params.getFeatureTemplateDescription(),
        params.getAlphabet());
    decoder = new ApproxF1MbrDecoder(params.logDomain, 1d);
  }

  @Override
  public Serializable getParamters() {
    return decoder;
  }

  @Override
  public void setPameters(Serializable params) {
    decoder = (ApproxF1MbrDecoder) params;
  }

  @Override
  public Double getLearningRate() {
    return 1d;
  }

  @Override
  public int getNumTrainingPasses() {
    return 2;
  }

  @Override
  public Regularizer getRegularizer() {
    return regularizer;
  }

  @Override
  public TuningData getTuningData() {
    return new TuningData() {
      @Override
      public ApproxF1MbrDecoder getDecoder() {
        return decoder;
      }
      @Override
      public EvalFunc getObjective() {
        return BasicEvaluation.fullMicroF1;
      }
      @Override
      public List<Double> getRecallBiasesToSweep() {
        List<Double> biases = new ArrayList<>();
        for (double b = 0.5d; b < 12d; b *= 1.25d)
          biases.add(b);
        return biases;
      }
      @Override
      public boolean tuneOnTrainingData() {
        return false;
      }
    };
  }

  @Override
  public StageDatumExampleList<FNParseSpanPruning, FNParse> setupInference(
      List<? extends FNParseSpanPruning> input,
      List<? extends FNParse> output) {
    List<StageDatum<FNParseSpanPruning, FNParse>> data = new ArrayList<>();
    for (int i = 0; i < input.size(); i++) {
      FNParse gold = output == null ? null : output.get(i);
      data.add(new RoleSpanLabellingStageDatum(input.get(i), gold, this));
    }
    return new StageDatumExampleList<>(data);
  }

  /**
   * Represents all of the variables needed to choose from a pruned set of
   * spans for every role. In cases where we've pruned the gold span, we do
   * not add a training variable/instance for that role.
   *
   * TODO: Implement latent syntax version of this factor. Right now it only
   * adds a binary factor for the labels.
   *
   * NOTE: do not be tempted to make a k-ary variable for every (frame,role)
   * because then i wont be able to hook up latent syntax binary factors later
   *
   * @author travis
   */
  static class RoleSpanLabellingStageDatum
      implements StageDatum<FNParseSpanPruning, FNParse> {
    private static final FeatureVector zero = new FeatureVector();
    private final FNParseSpanPruning input;
    private final FNParse gold;
    private final RoleSpanLabelingStage parent;

    public RoleSpanLabellingStageDatum(
        FNParseSpanPruning input,
        FNParse gold,
        RoleSpanLabelingStage parent) {
      this.input = input;
      this.gold = gold;
      this.parent = parent;
    }

    @Override
    public FNParseSpanPruning getInput() {
      return input;
    }

    @Override
    public boolean hasGold() {
      return gold != null;
    }

    @Override
    public FNParse getGold() {
      assert hasGold();
      return gold;
    }

    private void build(
        FactorGraph fg,
        VarConfig goldConf,
        Collection<ArgSpanLabelVar> vars) {
      int prunedGold = 0, total = 0, totalRealized = 0;
      for (int i = 0; i < input.numFrameInstances(); i++) {
        Frame f = input.getFrame(i);
        Span target = input.getTarget(i);
        FrameInstance goldFi = null;
        if (gold != null) {
          goldFi = gold.getFrameInstance(i);
          assert goldFi.getFrame().equals(f);
          assert goldFi.getTarget().equals(target);
        }
        for (int k = 0; k < f.numRoles(); k++) {
          Span goldArg = null;
          if (goldFi != null) {
            goldArg = goldFi.getArgument(k);
            int goldArgIdx = input.getPossibleArgs(i).indexOf(goldArg);
            if (goldArgIdx < 0) {
              LOG.warn("pruned the gold label for "
                  + f.getName() + "." + f.getRole(k));
              LOG.warn("not including this as a training example");
              assert goldArg != Span.nullSpan :
                "did you include nullSpan as an possible span?";
              prunedGold++;
              continue;
            }
          }
          total++;
          if (goldArg != null && goldArg != Span.nullSpan)
            totalRealized++;
          boolean foundNullSpan = false;
          for (Span arg : input.getPossibleArgs(i)) {
            // Non-null span variables
            Boolean spanIsGold = null;
            if (goldArg != null)
              spanIsGold = (goldArg == arg);
            buildSpanVar(f, target, k, arg, spanIsGold,
                fg, goldConf, vars);
            if (arg == Span.nullSpan) {
              assert !foundNullSpan;
              foundNullSpan = true;
            }
          }
          assert foundNullSpan;
        }
      }
      if (gold != null) {
        LOG.info(String.format(
            "[build] pruned the gold span in %d of %d cases (%d realized) in %s",
            prunedGold, total, totalRealized, input.getSentence().getId()));
      } else {
        LOG.info("[build] setup " + total
            + " arg-span label variables for prediction in "
            + input.getSentence().getId());
      }
    }

    private void buildSpanVar(
        Frame frame,
        Span target,
        int role,
        Span arg,
        Boolean isGold,
        FactorGraph fg,
        VarConfig goldConf,
        Collection<ArgSpanLabelVar> vars) {
      // Make the variable
      ArgSpanLabelVar argVar = new ArgSpanLabelVar(
          arg, frame, target, role);
      if (vars != null) vars.add(argVar);

      // Make a binary factor
      ExplicitExpFamFactor phi =
          new ExplicitExpFamFactor(new VarSet(argVar));

      Sentence s = input.getSentence();
      int targetHeadIdx = parent.globalParams.headFinder.head(target, s);

      // Compute features for the binary factor
      TemplateContext ctx = parent.features.getContext();
      ctx.clear();
      ctx.setSentence(s);
      ctx.setFrame(frame);
      ctx.setRole(role);
      if (arg != null && arg != Span.nullSpan) {
        ctx.setTarget(target);
        ctx.setTargetHead(targetHeadIdx);
        ctx.setSpan2(target);
        ctx.setHead2(targetHeadIdx);
        int argHeadIdx = parent.globalParams.headFinder.head(arg, s);
        ctx.setArg(arg);
        ctx.setArgHead(argHeadIdx);
        ctx.setSpan1(arg);
        ctx.setHead1(argHeadIdx);
      }
      FeatureVector fv = new FeatureVector();
      parent.features.featurize(fv);
      phi.setFeatures(BinaryVarUtil.boolToConfig(true), fv);
      phi.setFeatures(BinaryVarUtil.boolToConfig(false), zero);

      // Add the factor to the graph (this adds the var too)
      fg.addFactor(phi);

      // Set the gold if it is known
      if (isGold != null) {
        argVar.setGold(isGold);
        if (goldConf != null)
          goldConf.put(argVar, BinaryVarUtil.boolToConfig(isGold));
      }
    }

    @Override
    public LabeledFgExample getExample() {
      VarConfig gold = new VarConfig();
      FactorGraph fg = new FactorGraph();
      build(fg, gold, null);
      return new LabeledFgExample(fg, gold);
    }

    @Override
    public IDecodable<FNParse> getDecodable() {
      Collection<ArgSpanLabelVar> vars = new ArrayList<>();
      FactorGraph fg = new FactorGraph();
      build(fg, null, vars);
      return new Decoder(
          fg, parent.infFactory(),
          parent,
          vars, input.getSentence(),
          parent);
    }
  }

  /**
   * A variable that represents a binary question of whether a given span is
   * an argument for a particular (frame,role).
   */
  static class ArgSpanLabelVar
      extends RoleSpanPruningStage.ArgSpanPruningVar {
    private static final long serialVersionUID = 1L;
    public final int role;
    public final Span arg;
    public ArgSpanLabelVar(Span arg, Frame frame, Span target, int role) {
      super(arg, frame, target);
      this.role = role;
      this.arg = arg;
    }
  }

  static class Decoder extends Decodable<FNParse> {
    private Map<FrameRoleInstance, List<ArgSpanLabelVar>> vars;
    private Sentence sentence;
    private RoleSpanLabelingStage parent;
    public Decoder(
        FactorGraph fg,
        FgInferencerFactory infFact,
        HasFgModel weights,
        Collection<ArgSpanLabelVar> vars,
        Sentence sentence,
        RoleSpanLabelingStage parent) {
      super(fg, infFact, weights);
      this.sentence = sentence;
      this.parent = parent;

      // Index span variables by (frame,target,role)
      this.vars = new HashMap<>();
      for (ArgSpanLabelVar a : vars) {
        FrameRoleInstance key =
            new FrameRoleInstance(a.frame, a.target, a.role);
        List<ArgSpanLabelVar> x = this.vars.get(key);
        if (x == null) {
          x = new ArrayList<>();
          this.vars.put(key, x);
        }
        x.add(a);
      }
    }

    @Override
    public FNParse decode() {
      // Decode the best argument for every (frame,target,role)
      Map<FrameInstance, Span[]> bestArgs = new HashMap<>();
      for (Map.Entry<FrameRoleInstance, List<ArgSpanLabelVar>> x : vars.entrySet()) {
        FrameRoleInstance ftr = x.getKey();
        FrameInstance key = FrameInstance.frameMention(
            ftr.frame, ftr.target, sentence);
        Span[] all = bestArgs.get(key);
        if (all == null) {
          all = new Span[ftr.frame.numRoles()];
          Arrays.fill(all, Span.nullSpan);
          bestArgs.put(key, all);
        } else {
          assert ftr.frame.numRoles() == all.length;
        }
        all[ftr.role] = argmax(x.getValue());
      }
      // Aggregate all roles for each (frame,target)
      List<FrameInstance> fis = new ArrayList<>();
      for (Map.Entry<FrameInstance, Span[]> x : bestArgs.entrySet()) {
        FrameInstance fi = x.getKey();
        fis.add(FrameInstance.newFrameInstance(
            fi.getFrame(), fi.getTarget(), x.getValue(), sentence));
      }
      return new FNParse(sentence, fis);
    }

    private Span argmax(List<ArgSpanLabelVar> vars) {
      FgInferencer inf = this.getMargins();
      double[] posterior = new double[vars.size()];
      int nullSpanIdx = -1;
      for (int i = 0; i < posterior.length; i++) {
        if (vars.get(i).arg == Span.nullSpan) {
          assert nullSpanIdx < 0;
          nullSpanIdx = i;
        }
        DenseFactor df = inf.getMarginals(vars.get(i));
        if (parent.globalParams.logDomain)
          df.logNormalize();
        else
          df.normalize();
        posterior[i] = df.getValue(BinaryVarUtil.boolToConfig(true));
        //LOG.debug("[labeling argmax] " + posterior[i] + "\t" + vars.get(i));
      }
      assert nullSpanIdx >= 0;
      if (parent.globalParams.logDomain)
        Multinomials.normalizeLogProps(posterior);
      else
        Multinomials.normalizeProps(posterior);
      int y = parent.decoder.decode(posterior, nullSpanIdx);
      return vars.get(y).arg;
    }
  }

  @Override
  public void scanFeatures(List<FNParse> data) {
    List<FNParseSpanPruning> goldPrunes = FNParseSpanPruning.optimalPrune(data);
    this.scanFeatures(goldPrunes, data, 999, 999_999_999);
  }
}
