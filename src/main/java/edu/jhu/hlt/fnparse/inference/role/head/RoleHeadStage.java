package edu.jhu.hlt.fnparse.inference.role.head;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.tutils.ExperimentProperties;
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
import edu.jhu.gm.model.globalfac.LinkVar;
import edu.jhu.gm.model.globalfac.ProjDepTreeFactor;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.evaluation.GenerousEvaluation;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.DepParseFactorFactory;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.inference.pruning.ArgPruner;
import edu.jhu.hlt.fnparse.inference.pruning.IArgPruner;
import edu.jhu.hlt.fnparse.inference.pruning.NoArgPruner;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadVars.RVar;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.nlp.depparse.DepParseDecoder;
import edu.jhu.parse.dep.EdgeScores;

/**
 * Predicts the heads of roles for frames that appear in a sentence. Also serves
 * as a pruning step for predicting spans (some roles will have no head
 * predicted as being true).
 * 
 * @author travis
 */
public class RoleHeadStage
    extends AbstractStage<FNTagging, FNParse> {
  public static final Logger LOG = Logger.getLogger(RoleHeadStage.class);
  public static boolean SHOW_FEATURES = false;
  public static boolean SHOW_DEPENDENCY_RECALL = true;

  private int maxSentenceLengthForTraining = -1;
  private boolean useArgPruner = ExperimentProperties.getInstance().getBoolean("roleHeadStage.useArgPruner", true);
  private IArgPruner argPruner;
  private ApproxF1MbrDecoder decoder;

  // If true, set the span fields in TemplateContext so that all the span
  // features fire. If you change this, re-compute feature cardinality with
  // BasicFeatureTemplates.main.
  private boolean allowSpanFeatures = true;

  // TODO If true, create a ternary factor that fires -infinity if there isn't
  // a syntactic dependency between the arg/role head and the target head.
  // This needs to be ternary because you want to consider cases where the arg
  // is the syntactic parent of the target.
  // As measured with Stanford collapsed dependencies:
  // (57.7%) arguments are local
  // (47.8%) arguments are a child of the target
  // ( 9.9%) arguments are the parent of the target
  //private boolean disallowArgWithoutDependency = false;

  private FPR headBasicRecall = new FPR(false);
  private FPR headCollapsedRecall = new FPR(false);
  public void showHeadRecall() {
    LOG.info("[showHeadRecall] basic     micro recall/accuracy: " + headBasicRecall.recall());
    LOG.info("[showHeadRecall] collapsed micro recall/accuracy: " + headCollapsedRecall.recall());
  }

  public RoleHeadStage(GlobalParameters globals, String featureTemplateString) {
    super(globals, featureTemplateString);
    if (useArgPruner)
      argPruner = ArgPruner.getInstance();
    else
      argPruner = new NoArgPruner();
    decoder = new ApproxF1MbrDecoder(logDomain(), 1d);
  }

  @Override
  public void saveModel(DataOutputStream dos, GlobalParameters globals) {
    super.saveModel(dos, globals);
    try {
      dos.writeInt(maxSentenceLengthForTraining);
      dos.writeBoolean(useArgPruner);
      dos.writeBoolean(allowSpanFeatures);
      dos.writeDouble(decoder.getRecallBias());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void loadModel(DataInputStream dis, GlobalParameters globals) {
    super.loadModel(dis, globals);
    try {
      maxSentenceLengthForTraining = dis.readInt();
      useArgPruner = dis.readBoolean();
      if (useArgPruner)
        argPruner = ArgPruner.getInstance();
      else
        argPruner = new NoArgPruner();
      allowSpanFeatures = dis.readBoolean();
      decoder = new ApproxF1MbrDecoder(logDomain(), dis.readDouble());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void configure(java.util.Map<String,String> configuration) {
    super.configure(configuration);
    // TODO add config options
  }

  /** can't undo this for now */
  public void disablePruning() {
    argPruner = new NoArgPruner();
  }

  @Override
  public void scanFeatures(List<FNParse> data) {
    List<FNTagging> frames = DataUtil.convertParsesToTaggings(data);
    List<FNParse> argHeads = DataUtil.convertArgumenSpansToHeads(
        data, SemaforicHeadFinder.getInstance());
    this.scanFeatures(frames, argHeads, 999, 999_999_999);
  }

  @Override
  public void train(List<FNTagging> x, List<FNParse> y) {
    List<FNTagging> xUse;
    List<FNParse> yUse;
    if (maxSentenceLengthForTraining > 0) {
      xUse = new ArrayList<>();
      yUse = new ArrayList<>();
      int n = x.size();
      for (int i=0; i<n; i++) {
        FNTagging xi = x.get(i);
        if(xi.getSentence().size() <= maxSentenceLengthForTraining) {
          xUse.add(xi);
          yUse.add(y.get(i));
        }
      }
      log.info(String.format("[train] Filtering out sentences longer than"
          + " %d words, kept %d of %d",
          maxSentenceLengthForTraining,
          xUse.size(), x.size()));
      if (xUse.size() == 0)
        log.warn("[train] Filtered out all training examples, not training!");
    }
    else {
      xUse = x;
      yUse = y;
    }
    super.train(xUse, yUse, learningRate, regularizer, batchSize, passes);
  }

  @Override
  public TuningData getTuningData() {
    final List<Double> biases = new ArrayList<Double>();
    for (double b = 0.2d; b < 15d; b *= 1.2d) biases.add(b);
    return new TuningData() {
      @Override
      public ApproxF1MbrDecoder getDecoder() { return decoder; }
      @Override
      public EvalFunc getObjective() {
        double timeCost = 1e-6; // anything below 1e-4 is just breaking ties
        return GenerousEvaluation.generousPlusTime(FPR.Mode.F1, false, timeCost);
      }
      @Override
      public List<Double> getRecallBiasesToSweep() { return biases; }
      @Override
      public boolean tuneOnTrainingData() { return tuneOnTrainingData; }
    };
  }

  /** Must have initialized weights before calling this */
  @Override
  public StageDatumExampleList<FNTagging, FNParse> setupInference(
      List<? extends FNTagging> input,
      List<? extends FNParse> output) {
    return setupInference(input, output, false);
  }

  public StageDatumExampleList<FNTagging, FNParse> setupInference(
      List<? extends FNTagging> input,
      List<? extends FNParse> output,
      boolean showHeadRecall) {
    super.setupInferenceHook(input, output);
    log.info("[setupInfernece] maxSentenceLengthForTraining=" + maxSentenceLengthForTraining);
    log.info("[setupInfernece] useArgPruner=" + useArgPruner);
    log.info("[setupInfernece] allowSpanFeatures=" + allowSpanFeatures);
    List<StageDatum<FNTagging, FNParse>> data = new ArrayList<>();
    int n = input.size();
    assert output == null || output.size() == n;
    for (int i=0; i<n; i++) {
      FNTagging x = input.get(i);
      if (output == null)
        data.add(new RoleIdStageDatum(x, showHeadRecall));
      else
        data.add(new RoleIdStageDatum(x, output.get(i), showHeadRecall));
    }
    return new StageDatumExampleList<>(data);
  }

  public List<Factor> initFactorsFor(
      Sentence s,
      List<RoleHeadVars> fr,
      ProjDepTreeFactor l,
      ConstituencyTreeFactor c) {
    System.out.println("[RoleHeadStage initFactorsFor] starting! fr=" + fr);
    HeadFinder hf = SemaforicHeadFinder.getInstance();
    TemplateContext context = new TemplateContext();
    List<Factor> factors = new ArrayList<Factor>();
    for (RoleHeadVars rv : fr) {
      Span target = rv.getTarget();
      int targetHead = hf.head(target, s);
      Iterator<RVar> it = rv.getVars();
      while (it.hasNext()) {
        RVar rvar = it.next();
        LinkVar link = null;
        boolean argRealized = rvar.j < s.size();
        assert rvar.j >= 0;
        VarSet vs;
        if (argRealized && useLatentDependencies) {
          link = l.getLinkVar(targetHead, rvar.j);
          if (link == null) {
            assert targetHead == rvar.j;
            vs = new VarSet(rvar.roleVar);
          } else {
            vs = new VarSet(rvar.roleVar, link);
          }
        } else {
          vs = new VarSet(rvar.roleVar);
        }
        ExplicitExpFamFactor phi = new ExplicitExpFamFactor(vs);
        TemplatedFeatures feats = getFeatures();
        int n = vs.calcNumConfigs();
        System.out.println("[RoleHeadStage initFactorsFor] rvar=" + rvar + " n=" + n);
        for (int i = 0; i < n; i++) {
          VarConfig vc = vs.getVarConfig(i);
          boolean role = argRealized && BinaryVarUtil.configToBool(vc.getState(rvar.roleVar));
          boolean dep = link != null && BinaryVarUtil.configToBool(vc.getState(link));
          context.clear();
          context.setStage(RoleHeadStage.class);
          context.setSentence(s);
          context.setFrame(rv.getFrame());
          context.setTargetHead(targetHead);
          if (allowSpanFeatures) {
            context.setTarget(target);
            context.setSpan2(target);
          }
          if (role || dep) {
            context.setHead2(targetHead);
            if (allowSpanFeatures)
              context.setSpan2(target);
            if (role) {
              context.setRole(rvar.k);
              context.setArgHead(rvar.j);
              context.setHead1(rvar.j);
              if (allowSpanFeatures) {
                context.setSpan1(Span.widthOne(rvar.j));
                context.setArg(Span.widthOne(rvar.j));
              }
            }
            if (dep) {
              context.setHead1_parent(targetHead);
            }
          }
          FeatureVector fv = new FeatureVector();
          if (RoleHeadStage.SHOW_FEATURES) {
            String msg = String.format("[variables] rvar[%d,%d]=%s dep=%s",
                rvar.j, rvar.k, role, dep);
            feats.featurizeDebug(fv, context, msg);
          } else {
            feats.featurize(fv, context);
          }
          phi.setFeatures(i, fv);
        }
        factors.add(phi);
      }
    }
    return factors;
  }

  class RoleVars extends ArrayList<RoleHeadVars> {
    private static final long serialVersionUID = 1L;
    private Sentence sent;
    private ProjDepTreeFactor deps;

    public RoleVars(Sentence sent) {
      this.sent = sent;
    }

    public void setDeps(ProjDepTreeFactor deps) {
      this.deps = deps;
    }

    public void showDependencyRecall(FgInferencer inf) {
      if (deps == null)
        return;

      // Get the most likely latent parse
      final int n = sent.size();
      double[] rootBel = new double[n];
      double[][] childBel = new double[n][n];
      for (int i = 0; i < n; i++) {
        Var v = deps.getRootVars()[i];
        assert v != null;
        if (logDomain()) {
          Tensor t = inf.getLogMarginals(v);
          rootBel[i] = t.get(LinkVar.TRUE) - t.get(LinkVar.FALSE);
        } else {
          Tensor t = inf.getMarginals(v);
          rootBel[i] = t.get(LinkVar.TRUE) / t.get(LinkVar.FALSE);
        }
      }
      for (int i = 0; i < n; i++) {
        for (int j = 0; j < n; j++) {
          Var v = deps.getLinkVar(i, j);
          if (v == null)
            continue;
          if (logDomain()) {
            Tensor t = inf.getLogMarginals(v);
            childBel[i][j] = t.get(LinkVar.TRUE) - t.get(LinkVar.FALSE);
          } else {
            Tensor t = inf.getMarginals(v);
            childBel[i][j] = t.get(LinkVar.TRUE) / t.get(LinkVar.FALSE);
          }
        }
      }
      EdgeScores edgeScores = new EdgeScores(rootBel, childBel);
      int[] heads = DepParseDecoder.getParents(edgeScores);

      // Measure agreement/recall
      DependencyParse basic = sent.getBasicDeps(false);
      if (basic == null) {
        ConcreteStanfordWrapper stanford =
            ConcreteStanfordWrapper.getSingleton(false);
        basic = stanford.getBasicDParse(sent);
      }
      DependencyParse collapsed = sent.getCollapsedDeps(false);
      assert collapsed != null;
      showAgreement(heads, basic, "basicDeps", headBasicRecall);
      showAgreement(heads, collapsed, "collapsedDeps", headCollapsedRecall);
    }

    private void showAgreement(int[] parents, DependencyParse deps, String name, FPR accum) {
      if (parents.length != deps.size())
        throw new IllegalArgumentException();
      final int n = sent.size();
      int right = 0, wrong = 0;
      for (int i = 0; i < n; i++) {
        if (parents[i] == deps.getHead(i)
            || parents[i] < 0 && deps.isRoot(i)) {
          right++;
        } else {
          wrong++;
        }
      }
      double a = ((double) right) / (right + wrong);
      LOG.info("[showHeadRecall] " + sent.getId() + " accuracy=" + a
          + " n=" + n + " right=" + right + " wrong=" + wrong);
      accum.accum(right, 0, wrong);
    }
  }

  class RoleIdStageDatum implements StageDatum<FNTagging, FNParse> {
    //private final List<RoleHeadVars> roleVars;
    private final RoleVars roleVars;
    private final FNTagging input;
    private final FNParse gold;
    private boolean showHeadRecall;

    /** you don't know gold */
    public RoleIdStageDatum(FNTagging frames, boolean showHeadRecall) {
      this.roleVars = new RoleVars(frames.getSentence());
      this.showHeadRecall = showHeadRecall;
      this.input = frames;
      this.gold = null;
      initHypotheses(frames, null, false);
    }

    /** you know gold */
    public RoleIdStageDatum(FNTagging frames, FNParse gold, boolean showHeadRecall) {
      if(gold == null)
        throw new IllegalArgumentException();
      this.roleVars = new RoleVars(frames.getSentence());
      this.showHeadRecall = showHeadRecall;
      this.input = frames;
      this.gold = gold;
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

      // Make sure that we don't have overlapping targets (see next step)
      frames = DataUtil.filterOutTargetCollisions(frames);

      // Build an index keying off of the target head index.
      // This assumes that:
      // 1) We are using a headword to describe a target
      // 2) A given headword can evoke at most 1 frame.
      Map<Span, FrameInstance> fiByTarget = null;
      if (hasGold)
        fiByTarget = DataUtil.getFrameInstanceByTarget(gold);

      HeadFinder hf = SemaforicHeadFinder.getInstance();
      for (FrameInstance fi : frames.getFrameInstances()) {
        Span target = fi.getTarget();
        RoleHeadVars rv;
        if (hasGold) {  // Train mode
          // goldFI may be null, meaning that we predicted a frame
          // that was not actually present in the sentence.
          FrameInstance goldFI = fiByTarget.get(target);
          rv = new RoleHeadVars(
              goldFI, target, fi.getFrame(), fi.getSentence(), hf, argPruner);
        } else {        // Predict/decode mode
          rv = new RoleHeadVars(
              target, fi.getFrame(), fi.getSentence(), hf, argPruner);
        }

        this.roleVars.add(rv);
      }
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
      ConstituencyTreeFactor consTree = null; // used in RoleSpanStage, not here
      if (useLatentDependencies) {
        depTree = new ProjDepTreeFactor(getSentence().size(), VarType.LATENT);
        DepParseFactorFactory depParseFactorTemplate =
            new DepParseFactorFactory(globals);
        factors.addAll(depParseFactorTemplate.initFactorsFor(
            getSentence(), Collections.emptyList(), depTree, consTree));
        if (showHeadRecall)
          roleVars.setDeps(depTree);
      }
      factors.addAll(
          initFactorsFor(getSentence(), roleVars, depTree, consTree));

      // add factors to the factor graph
      for(Factor f : factors)
        fg.addFactor(f);

      return fg;
    }

    @Override
    public LabeledFgExample getExample() {
      observeGetExample(input.getId());
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
      observeGetDecodable(input.getId());
      FgInferencerFactory infFact = infFactory();
      return new RoleIdDecodable(
          getFactorGraph(), infFact, getSentence(), roleVars);
    }
  }

  /**
   * decodes FNParses which have arguments represented by width-1 spans
   */
  class RoleIdDecodable extends Decodable<FNParse> {
    public boolean debug = false;

    private final Sentence sent;
    //private final List<RoleHeadVars> hypotheses;
    private final RoleVars hypotheses;

    public RoleIdDecodable(
        FactorGraph fg,
        FgInferencerFactory infFact,
        Sentence sent,
        //List<RoleHeadVars> hypotheses) {
        RoleVars hypotheses) {
      super(fg, infFact);
      this.sent = sent;
      this.hypotheses = hypotheses;
    }

    @Override
    public FNParse decode() {
      FgInferencer inf = getMargins();
      hypotheses.showDependencyRecall(inf);
      List<FrameInstance> fis = new ArrayList<FrameInstance>();
      for(RoleHeadVars rv : hypotheses)
        fis.add(decodeRoleVars(rv, inf));
      return new FNParse(sent, fis);
    }

    public FrameInstance decodeRoleVars(RoleHeadVars rv, FgInferencer inf) {

      // Max over j for every role
      final int n = sent.size();
      final int K = rv.getFrame().numRoles();
      Span[] arguments = new Span[K];
      Arrays.fill(arguments, Span.nullSpan);
      // Last inner index is "not realized"
      double[][] beliefs = new double[K][n+1];
      if (logDomain()) {
        for (int i = 0; i < beliefs.length; i++) // otherwise default is 0
          Arrays.fill(beliefs[i], Double.NEGATIVE_INFINITY);
      }

      boolean[] considered = new boolean[K];
      Iterator<RVar> iter = rv.getVars();
      while (iter.hasNext()) {
        RVar rvar = iter.next();
        Tensor df = logDomain()
            ? inf.getLogMarginals(rvar.roleVar)
            : inf.getMarginals(rvar.roleVar);
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
        normalize(beliefs[k]);

        int jHat = decoder.decode(beliefs[k], n);
        if (jHat < n)
          arguments[k] = Span.widthOne(jHat);
      }
      return FrameInstance.newFrameInstance(
        rv.getFrame(), rv.getTarget(), arguments, sent);
    }
    @Override
    public FgModel getWeights() {
      return RoleHeadStage.this.getWeights();
    }
    @Override
    public void setWeights(FgModel weights) {
      throw new UnsupportedOperationException();
    }
    @Override
    public boolean logDomain() {
      return RoleHeadStage.this.logDomain();
    }
  }
}
