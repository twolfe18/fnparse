package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.autodiff.Tensor;
import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.inf.FgInferencerFactory;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.gm.model.globalfac.ConstituencyTreeFactor;
import edu.jhu.gm.model.globalfac.ProjDepTreeFactor;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.WeightedFrameInstance;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.DepParseFactorFactory;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.Span;

public class FrameIdStage extends AbstractStage<Sentence, FNTagging> {
  public static final Logger LOG = Logger.getLogger(FrameIdStage.class);
  public static boolean SHOW_FEATURES = false;

  private ApproxF1MbrDecoder decoder = new ApproxF1MbrDecoder(true, 1.0d);
  private FPR triage = new FPR(false);
  private boolean ignorePosForFrameTriage = false;
  private boolean addGoldFrameIfNotInTriage = true;

  public FrameIdStage(GlobalParameters globals, String featureTemplateString) {
    super(globals, featureTemplateString);
  }

  public ApproxF1MbrDecoder getDecoder() {
    return decoder;
  }

  @Override
  public void loadModel(DataInputStream dis, GlobalParameters globals) {
    super.loadModel(dis, globals);
    try {
      decoder = new ApproxF1MbrDecoder(logDomain(), dis.readDouble());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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

  private static int frameVarsInstantiated = 0;
  public static void frameVarInstantiated() {
    frameVarsInstantiated++;
    if (frameVarsInstantiated % 1000 == 0)
      LOG.info(frameVarsInstantiated + " frame vars instantiated");
  }

  @Override
  public void configure(java.util.Map<String,String> configuration) {
    super.configure(configuration);

    String key, value;

    key = "recallBias." + getName();
    value = configuration.get(key);
    if (value != null) {
      LOG.info("[configure] set " + key + " = " + value);
      decoder.setRecallBias(Double.parseDouble(value));
    }
  }

  public void train(List<FNParse> examples) {
    Collections.shuffle(examples, globals.getRandom());
    List<Sentence> x = new ArrayList<>();
    List<FNTagging> y = new ArrayList<>();
    for (FNTagging t : examples) {
      x.add(t.getSentence());
      y.add(t);
    }
    train(x, y, learningRate, regularizer, batchSize, passes);
  }

  @Override
  public TuningData getTuningData() {
    final List<Double> biases = new ArrayList<Double>();
    for(double b=0.5d; b<8d; b *= 1.1d) biases.add(b);
    LOG.debug("called getTuningData, trying " + biases.size() + " biases");
    return new TuningData() {
      @Override
      public ApproxF1MbrDecoder getDecoder() { return decoder; }
      @Override
      public EvalFunc getObjective() { return BasicEvaluation.targetMicroF1; }
      @Override
      public List<Double> getRecallBiasesToSweep() { return biases; }
      @Override
      public boolean tuneOnTrainingData() { return tuneOnTrainingData; }
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
//      if (s.getWord(2).equalsIgnoreCase("landed"))
//        LOG.info("debug me");
      if(labels == null)
        data.add(this.new FrameIdDatum(s));//, this));
      else
        data.add(this.new FrameIdDatum(s, /*this,*/ labels.get(i)));
    }
    LOG.info("[FrameIdStage setupInference] triage: " + triage);
    return new StageDatumExampleList<Sentence, FNTagging>(data);
  }

  @Override
  public void scanFeatures(List<FNParse> data) {
    List<Sentence> sentences = DataUtil.stripAnnotations(data);
    this.scanFeatures(sentences, data, 999, 999_999_999);
  }

  // TODO need to add an Exactly1 factor to each FrameVars
  // ^^^^ do i really need this if i'm not doing joint inference?
  public List<Factor> initFactorsFor(
      Sentence s,
      List<FrameVars> fr,
      ProjDepTreeFactor l,
      ConstituencyTreeFactor c) {

    final int ROOT = -1;
    HeadFinder hf = SemaforicHeadFinder.getInstance();
    TemplatedFeatures feats = FrameIdStage.this.getFeatures();
    TemplateContext context = new TemplateContext();
    List<Factor> factors = new ArrayList<Factor>();
    for (FrameVars fhyp : fr) {
      final int T = fhyp.numFrames();
      int targetHead = hf.head(fhyp.getTarget(), s);
      context.clear();
      context.setStage(FrameIdStage.class);
      context.setSentence(s);
      context.setTarget(fhyp.getTarget());
      context.setSpan1(fhyp.getTarget());
      context.setTargetHead(targetHead);
      context.setHead1(targetHead);
      for (int tIdx = 0; tIdx < T; tIdx++) {
        Frame t = fhyp.getFrame(tIdx);
        Var frameVar = fhyp.getVariable(tIdx);
        ExplicitExpFamFactor phi;
        if (l != null) {  // Latent syntax
          Var linkVar = l.getLinkVar(-1, targetHead);
          VarSet vs = new VarSet(linkVar, frameVar);
          phi = new ExplicitExpFamFactor(vs);
          for (int config = 0; config < 4; config++) {
            VarConfig vc = vs.getVarConfig(config);
            boolean link = BinaryVarUtil.configToBool(vc.getState(linkVar));
            boolean frame = BinaryVarUtil.configToBool(vc.getState(frameVar));
            context.setHead1_parent(link ? ROOT : TemplateContext.UNSET);
            context.setFrame(frame ? t : null);
            FeatureVector fv = new FeatureVector();
            if (SHOW_FEATURES) {
              String msg = "[variables] " + vs.getVarConfig(config);
              feats.featurizeDebug(fv, context, msg);
            } else {
              feats.featurize(fv, context);
            }
            phi.setFeatures(config, fv);
          }
        } else {          // No latent syntax
          VarSet vs = new VarSet(frameVar);
          phi = new ExplicitExpFamFactor(vs);
          FeatureVector fv1 = new FeatureVector();
          context.setFrame(t);
          //context.blankOutIllegalInfo(params.getParserParams());
          if (SHOW_FEATURES) {
            feats.featurizeDebug(fv1, context, "[variables] (contained in context)");
          } else {
            feats.featurize(fv1, context);
          }
          phi.setFeatures(BinaryVarUtil.boolToConfig(true), fv1);
          FeatureVector fv2 = new FeatureVector();
          context.setFrame(null);
          //context.blankOutIllegalInfo(params.getParserParams());
          if (SHOW_FEATURES) {
            feats.featurizeDebug(fv2, context, "[variables] (contained in context)");
          } else {
            feats.featurize(fv2, context);
          }
          phi.setFeatures(BinaryVarUtil.boolToConfig(false), fv2);
        }
        factors.add(phi);
      }
    }
    return factors;
  }

  /**
   * Takes a sentence, and optionally a FNTagging, and can make either
   * {@link Decodable}<FNTagging>s (for prediction) or
   * {@link LabeledFgExample}s for training.
   * 
   * @author travis
   */
  public class FrameIdDatum
      implements StageDatum<Sentence, FNTagging> {
    private final Sentence sentence;
    private final boolean hasGold;
    private final FNTagging gold;
    private final List<FrameVars> possibleFrames;

    public FrameIdDatum(Sentence s) {
      this.sentence = s;
      this.hasGold = false;
      this.gold = null;
      this.possibleFrames = new ArrayList<>();
      initHypotheses();
    }

    public FrameIdDatum(Sentence s, FNTagging gold) {
      assert gold != null;
      this.sentence = s;
      this.hasGold = true;
      this.gold = gold;
      this.possibleFrames = new ArrayList<>();
      initHypotheses();
      setGold(gold);
    }

    private void initHypotheses() {
      assert possibleFrames.size() == 0;
      TargetIndex ti = TargetIndex.getInstance();
      boolean requireInParens = false;
      Map<Span, Set<Frame>> byTarget = ti.findFrames(
          sentence, requireInParens, ignorePosForFrameTriage);
      Map<Span, FrameInstance> goldFIs = null;
      if (addGoldFrameIfNotInTriage && gold != null)
        goldFIs = gold.getFrameLocations();
      for (Map.Entry<Span, Set<Frame>> x : byTarget.entrySet()) {
        Span target = x.getKey();
        List<Frame> frames = new ArrayList<>();
        frames.addAll(x.getValue());
        if (addGoldFrameIfNotInTriage && gold != null) {
          FrameInstance g = goldFIs.get(target);
          if (g != null && !frames.contains(g.getFrame()))
            frames.add(g.getFrame());
        }
        FrameVars fv = new FrameVars(target, frames);
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
        boolean triageWorked = fHyp.setGold(fi);
        if (triageWorked)
          triage.accumTP();
        else
          triage.accumFN();
        boolean removed = haventSet.remove(fHyp);
        if (!removed) {
          throw new RuntimeException(
              "two FrameInstances with same target? "
                  + p.getSentence().getId());
        }
      }

      // The remaining hypotheses must be null because they didn't
      // correspond to a FI in the parse
      for (FrameVars fHyp : haventSet) {
        triage.accumFP();
        fHyp.setGoldIsNull();
      }
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
      if (useLatentDependencies) {
        depTree = new ProjDepTreeFactor(
            sentence.size(), VarType.LATENT);
        DepParseFactorFactory depParseFactorTemplate =
            new DepParseFactorFactory(globals);
        factors.addAll(depParseFactorTemplate.initFactorsFor(
            sentence, Collections.emptyList(), depTree, consTree));
      }
      factors.addAll(initFactorsFor(
          sentence, possibleFrames, depTree, consTree));

      // Add factors to the factor graph
      for(Factor f : factors)
        fg.addFactor(f);

      return fg;
    }

    @Override
    public FrameIdDecodable getDecodable() {
      FgInferencerFactory infFact = infFactory();
      FactorGraph fg = this.getFactorGraph();
      return new FrameIdDecodable(sentence, possibleFrames, fg, infFact);
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
  public class FrameIdDecodable
      extends Decodable<FNTagging>
      implements Iterable<FrameVars> {

    private final Sentence sentence;
    private final List<FrameVars> possibleFrames;

    public FrameIdDecodable(Sentence sent, List<FrameVars> possibleFrames,
        FactorGraph fg, FgInferencerFactory infFact) {
      super(fg, infFactory());
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
      List<FrameInstance> fis = new ArrayList<FrameInstance>();
      for (FrameVars fvars : possibleFrames) {
        final int T = fvars.numFrames();
        double[] beliefs = new double[T];
        for (int t = 0; t < T; t++) {
          Tensor df = logDomain()
              ? hasMargins.getLogMarginals(fvars.getVariable(t))
              : hasMargins.getMarginals(fvars.getVariable(t));
          // TODO Exactly1 factor removes the need for this
          df.normalize();
          beliefs[t] = df.getValue(BinaryVarUtil.boolToConfig(true));
        }
        normalize(beliefs);

        final int nullFrameIdx = fvars.getNullFrameIdx();
        int tHat = decoder.decode(beliefs, nullFrameIdx);
        Frame fHat = fvars.getFrame(tHat);
        if (fHat != Frame.nullFrame) {
          WeightedFrameInstance wfi =
              WeightedFrameInstance.newWeightedFrameInstance(
                  fHat, fvars.getTarget(), sentence);
          wfi.setTargetWeight(beliefs[tHat]);
          fis.add(wfi);
        }
      }
      return new FNTagging(sentence, fis);
    }
    @Override
    public FgModel getWeights() {
      return FrameIdStage.this.getWeights();
    }
    @Override
    public void setWeights(FgModel weights) {
      throw new UnsupportedOperationException();
    }
    @Override
    public boolean logDomain() {
      return FrameIdStage.this.logDomain();
    }
  }
}
