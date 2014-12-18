package edu.jhu.hlt.fnparse.inference.role.span;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.ConstituencyTreeFactor.SpanVar;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameRoleInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.datatypes.WeightedFrameInstance;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadToSpanStage.ExplicitExpFamFactorWithConstraint;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.hlt.fnparse.util.HasFgModel;

/**
 * This stage takes a list of frame instances, each of which has a pruned set of
 * spans which could hold their arguments, and chooses the roles that are
 * realized for every given frame-role.
 * 
 * The latent version will roll out a latent constituency tree and the regular
 * version will not.
 * 
 * @author travis
 */
public class RoleSpanLabelingStage
    extends AbstractStage<FNParseSpanPruning, FNParse> {
  public static final Logger LOG =
      Logger.getLogger(RoleSpanLabelingStage.class);
  public static boolean SHOW_FEATURES = false;

  private ApproxF1MbrDecoder decoder;
  private boolean allExamplesInMem = false;
  private boolean disallowArgWithoutConstituent = true;

  // If 1, perform regular max_{frame,target,role} decoding
  // If >1, take the top-K roles per {frame,target,role}, ignoring decoder
  // TODO add configure/saveModel/loadModel support
  public int maxSpansPerArg = 1;

  public RoleSpanLabelingStage(
      GlobalParameters globals,
      String featureTemplateString) {
    super(globals, featureTemplateString);
    this.decoder = new ApproxF1MbrDecoder(logDomain(), 1d);
  }

  @Override
  public void saveModel(DataOutputStream dos, GlobalParameters globals) {
    super.saveModel(dos, globals);
    try {
      dos.writeDouble(decoder.getRecallBias());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void loadModel(DataInputStream dis, GlobalParameters globals) {
    super.loadModel(dis, globals);
    try {
      this.decoder = new ApproxF1MbrDecoder(logDomain(), dis.readDouble());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void configure(java.util.Map<String,String> configuration) {
    super.configure(configuration);
    String key = "disallowArgWithoutConstituent." + getName();
    String value = configuration.get(key);
    if (value != null) {
      disallowArgWithoutConstituent = Boolean.valueOf(value);
      LOG.info("setting disallowArgWithoutConstituent to "
          + disallowArgWithoutConstituent);
    }
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
        return BasicEvaluation.argOnlyMicroF1;
      }
      @Override
      public List<Double> getRecallBiasesToSweep() {
        List<Double> biases = new ArrayList<>();
        for (double b = 0.5d; b < 25d; b *= 1.2d)
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
    super.setupInferenceHook(input, output);
    log.info("[setupInference] maxSpansPerArg=" + maxSpansPerArg);
    log.info("[setupInference] disallowArgWithoutConstituent=" + disallowArgWithoutConstituent);
    List<StageDatum<FNParseSpanPruning, FNParse>> data = new ArrayList<>();
    for (int i = 0; i < input.size(); i++) {
      FNParse gold = output == null ? null : output.get(i);
      data.add(this.new RoleSpanLabelingStageDatum(input.get(i), gold));
    }
    return new StageDatumExampleList<>(data, allExamplesInMem);
  }

  @Override
  public void scanFeatures(List<FNParse> data) {
    List<FNParseSpanPruning> goldPrunes = FNParseSpanPruning.optimalPrune(data);
    this.scanFeatures(goldPrunes, data, 999, 999_999_999);
  }

  private int buildCtr = 0, buildCtrInterval = 500;
  /**
   * Represents all of the variables needed to choose from a pruned set of
   * spans for every role. In cases where we've pruned the gold span, we do
   * not add a training variable/instance for that role.
   *
   * NOTE: do not be tempted to make a k-ary variable for every (frame,role)
   * because then i wont be able to hook up latent syntax binary factors later
   */
  class RoleSpanLabelingStageDatum
      implements StageDatum<FNParseSpanPruning, FNParse> {
    private final FNParseSpanPruning input;
    private final FNParse gold;

    public RoleSpanLabelingStageDatum(FNParseSpanPruning input, FNParse gold) {
      this.input = input;
      this.gold = gold;
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

      boolean training = goldConf != null;

      ConstituencyTreeFactor consTree = null;
      if (useLatentConstituencies) {
        int n = input.getSentence().size();
        consTree = new ConstituencyTreeFactor(n, VarType.LATENT);
        fg.addFactor(consTree);
      }

      Map<FrameRoleInstance, Span> goldSpans = null;
      if (training)
        goldSpans = gold.getMapRepresentation();

      Map<FrameRoleInstance, Set<Span>> inputSpans =
          input.getMapRepresentation();

      int prunedGold = 0, total = 0, totalRealized = 0;
      for (FrameRoleInstance key : inputSpans.keySet()) {
        Set<Span> reachable = inputSpans.get(key);
        Span g = null;
        if (training) {
          g = goldSpans.get(key);
          if (g == null) {
            // Interestingly, the MLE thing to do would not be to skip this case
            // but rather have all of the variables have a gold value of nullSpan.

            // This however is twice as fast and produce better solutions!

            // All I can think of is that this is sub-sampling negative training
            // examples, which helps the model not be overly pessimistic...

            // Maybe I need an ApproxF1MbrDecoder step here to compensate for
            // over abundance of negative examples?

            // TODO implement an ab-test for this (continue statement)
            //continue;
          } else {
            if (!reachable.contains(g))
              prunedGold++;
            totalRealized++;
          }
        }
        total++;
        for (Span s : reachable) {
          if (s.end > input.getSentence().size()) {
            LOG.warn("reachable span outside sentence!"
              + " span: " + s
              + " sentence size: " + input.getSentence().size()
              + " sentence id: " + input.getSentence().getId()
              + " input id: " + input.getId());
            continue;
          }
          Boolean isGold = null;
          if (training)
            isGold = (s == g);
          SpanVar spanVar = null;
          if (consTree != null && s.width() > 1)
            spanVar = consTree.getSpanVar(s.start, s.end - 1);
          buildSpanVar(key.frame, key.target, key.role,
              s, spanVar, isGold, fg, goldConf, vars);
        }
      }
      if (buildCtr++ % buildCtrInterval == 0) {
        if (training) {
          LOG.info(String.format(
              "[build] pruned the gold span in %d of %d cases (%d realized) in %s",
              prunedGold, total, totalRealized, input.getSentence().getId()));
        } else {
          LOG.info("[build] setup " + total
              + " arg-span label variables for prediction in "
              + input.getSentence().getId());
        }
      }
    }

    private void buildSpanVar(
        Frame frame,
        Span target,
        int role,
        Span arg,
        SpanVar spanVar,  // may be null
        Boolean isGold,   // may be null
        FactorGraph fg,
        VarConfig goldConf,
        Collection<ArgSpanLabelVar> vars) {

      ArgSpanLabelVar argVar = new ArgSpanLabelVar(
          arg, frame, target, role);
      if (vars != null) vars.add(argVar);

      VarSet vs = spanVar == null
          ? new VarSet(argVar) : new VarSet(argVar, spanVar);

      ExplicitExpFamFactorWithConstraint phi =
          new ExplicitExpFamFactorWithConstraint(vs, -1);

      Sentence s = input.getSentence();
      HeadFinder hf = SemaforicHeadFinder.getInstance();
      int targetHeadIdx = hf.head(target, s);

      // Compute features for the binary factor
      TemplatedFeatures feats = getFeatures();
      TemplateContext context = new TemplateContext();
      context.clear();

      int n = vs.calcNumConfigs();
      for (int c = 0; c < n; c++) {
        context.setStage(RoleSpanLabelingStage.class);
        context.setSentence(s);
        context.setFrame(frame);

        VarConfig conf = vs.getVarConfig(c);
        boolean roleIsRealized = arg != Span.nullSpan && BinaryVarUtil.configToBool(conf.getState(argVar));
        boolean spanIsConstit = arg.width() == 1
            || (spanVar != null && BinaryVarUtil.configToBool(conf.getState(spanVar)));

        if (roleIsRealized || spanIsConstit) {
          int argHeadIdx = hf.head(arg, s);
          context.setHead1(argHeadIdx);
          context.setHead2(targetHeadIdx);
          context.setSpan1(arg);
          context.setSpan2(target);
          if (roleIsRealized) {
            context.setRole(role);
            context.setTarget(target);
            context.setTargetHead(targetHeadIdx);
            context.setArg(arg);
            context.setArgHead(argHeadIdx);
          }
          if (spanVar != null) {
            context.setSpan1IsConstituent(spanIsConstit);
          }
        }

        String msg = null;
        if (SHOW_FEATURES) {
          msg = "[variables] roleIsRealized=" + roleIsRealized
              + " spanIsConstit=" + spanIsConstit + "\t" + frame.getName()
              + "." + frame.getRole(role) + " arg=" + arg;
        }
        if (spanVar != null // only applies with latent c-parse
            && disallowArgWithoutConstituent && roleIsRealized && !spanIsConstit) {
          phi.setBadConfig(c);
          phi.setFeatures(c, AbstractFeatures.emptyFeatures);
          if (SHOW_FEATURES) {
            LOG.info(msg + " CONSTRAINED TO -INFINITY");
            LOG.info("");
          }
        } else {
          FeatureVector fv = new FeatureVector();
          if (SHOW_FEATURES) {
            feats.featurizeDebug(fv, context, msg);
          } else {
            feats.featurize(fv, context);
          }
          phi.setFeatures(c, fv);
        }
      }

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
      observeGetExample(input.getId());
      VarConfig gold = new VarConfig();
      FactorGraph fg = new FactorGraph();
      build(fg, gold, null);
      return new LabeledFgExample(fg, gold);
    }

    @Override
    public IDecodable<FNParse> getDecodable() {
      observeGetDecodable(input.getId());
      Collection<ArgSpanLabelVar> vars = new ArrayList<>();
      FactorGraph fg = new FactorGraph();
      build(fg, null, vars);
      return new Decoder(fg, infFactory(), null, vars, input);
    }
  }

  /**
   * A variable that represents a binary question of whether a given span is
   * an argument for a particular (frame,role).
   */
  public static class ArgSpanLabelVar
      extends RoleSpanPruningStage.ArgSpanPruningVar {
    private static final long serialVersionUID = 1L;
    public final int role;
    public ArgSpanLabelVar(Span arg, Frame frame, Span target, int role) {
      super(arg, frame, target);
      this.role = role;
    }
  }

  class Decoder extends Decodable<FNParse> {
    private Map<FrameRoleInstance, List<ArgSpanLabelVar>> vars;
    private FNParseSpanPruning pruneMask;
    public Decoder(
        FactorGraph fg,
        FgInferencerFactory infFact,
        HasFgModel weights,
        Collection<ArgSpanLabelVar> vars,
        FNParseSpanPruning pruneMask) {
      super(fg, infFact);
      this.pruneMask = pruneMask;

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
      List<FrameInstance> fis = new ArrayList<>();
      FgInferencer inf = this.getMargins();
      Map<FrameInstance, WeightedFrameInstance> best = new HashMap<>();
      for (FrameRoleInstance ftr : pruneMask.getMapRepresentation().keySet()) {
        List<ArgSpanLabelVar> argVars = vars.get(ftr);
        FrameInstance key = FrameInstance.frameMention(
            ftr.frame, ftr.target, pruneMask.getSentence());
        WeightedFrameInstance value = best.get(key);
        if (value == null) {
          value = WeightedFrameInstance.newWeightedFrameInstance(
              ftr.frame, ftr.target, pruneMask.getSentence());
          value.setTargetWeight(pruneMask.getTargetWeight(
              key.getFrame(), key.getTarget()));;
          best.put(key, value);
        }
        for (ArgSpanLabelVar aslv : argVars) {
          DenseFactor df = inf.getMarginals(aslv);
          if (logDomain()) df.logNormalize();
          else df.normalize();
          double w = df.getValue(BinaryVarUtil.boolToConfig(true));
          value.addArgumentTheory(aslv.role, aslv.arg, w);
        }
      }
      int totalTargets = 0, totalArgs = 0, totalTheories = 0;
      for (WeightedFrameInstance wfi : best.values()) {
        if (maxSpansPerArg == 1) {
          wfi.decode(decoder);
        } else {
          wfi.sortArgumentTheories();
          wfi.pruneArgumentTheories(maxSpansPerArg);
          wfi.setArgumentTheoriesAsValues();
        }
        fis.add(wfi);
        totalTargets++;
        totalArgs += wfi.numRealizedArguments();
        totalTheories += wfi.numRealizedTheories();
      }
      //LOG.debug(String.format("[decode] realized %d theories and %d arguments on %d targets",
      //    totalTheories, totalArgs, totalTargets));
      return new FNParse(pruneMask.getSentence(), fis);
    }

    @Override
    public FgModel getWeights() {
      return RoleSpanLabelingStage.this.getWeights();
    }

    @Override
    public void setWeights(FgModel weights) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean logDomain() {
      return RoleSpanLabelingStage.this.logDomain();
    }
  }
}
