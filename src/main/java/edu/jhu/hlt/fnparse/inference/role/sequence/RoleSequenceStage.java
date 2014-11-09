package edu.jhu.hlt.fnparse.inference.role.sequence;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
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
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.hlt.fnparse.util.HasFgModel;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.L2;

/**
 * Constructs a CRF tagging problem for every frame instance in a sentence.
 * The labels on tokens are the role that is evoked by that frame.
 * 
 * @author travis
 */
public class RoleSequenceStage extends AbstractStage<FNTagging, FNParse> {
  private static final long serialVersionUID = 1L;
  public static final Logger LOG = Logger.getLogger(RoleSequenceStage.class);
  private static boolean DEBUG = true;
  private static boolean SHOW_FEATURES = false;
  private static boolean SHOW_GOLD = true;

  /**
   * Role variables for one frame instance.
   * 
   * TODO Consider adding a B tag. Maybe one for every role, maybe just one for
   * all roles...
   * 
   * TODO Consider pre-computing observed features, manually conjoining them
   * with the labels, and caching them.
   */
  static class RoleSeqVars implements IDecodable<FrameInstance> {
    private Sentence sentence;
    private Frame frame;
    private Span target;
    private FrameInstance gold; // may be null

    // Co-indexed with tokens
    // Each Var ranges over 0..R where R is frame.numRoles
    // A value of R indicates that this token is not a part of any role/arg.
    private Var[] labels;

    /** Constructor for when doing prediction / gold label is not known */
    public RoleSeqVars(Frame f, Span target, Sentence s) {
      this.frame = f;
      this.target = target;
      this.sentence = s;
      this.gold = null;
      initVars();
    }

    /** Constructor for when you know the gold label */
    public RoleSeqVars(FrameInstance gold) {
      this.sentence = gold.getSentence();
      this.frame = gold.getFrame();
      this.target = gold.getTarget();
      this.gold = gold;
      initVars();
    }

    private void initVars() {
      int K = frame.numRoles();
      int n = sentence.size();
      labels = new Var[n];
      List<String> stateNames = null;
      if (DEBUG) {
        stateNames = new ArrayList<>();
        for (int k = 0; k < K; k++)
          stateNames.add(frame.getRole(k));
        stateNames.add("NO_ROLE");
      }
      for (int i = 0; i < n; i++) {
        String name = String.format("role[%s@%d-%d, %d]",
            frame.getName(), target.start, target.end, i);
        labels[i] = new Var(VarType.PREDICTED, K + 1, name, stateNames);
      }
    }

    /**
     * See {@link RoleSequenceDecoder}
     */
    @Override
    public FrameInstance decode() {
      assert hasMarginals != null;
      // Convert marginals int [word][role] index
      int K = frame.numRoles() + 1;
      double[][] probs = new double[labels.length][];
      for (int i = 0; i < labels.length; i++) {
        DenseFactor perWord = hasMarginals.getMarginals(labels[i]);
        probs[i] = perWord.getValues();
        assert probs[i].length == K;
      }
      RoleSequenceDecoder rsd = new RoleSequenceDecoder();
      return rsd.decode(probs, Double.NEGATIVE_INFINITY, frame, target, sentence);
    }

    public transient FgInferencer hasMarginals;

    public void addGold(VarConfig gold) {
      // Scan over every role, fill out the label for the labels in those spans
      int n = sentence.size();
      int K = frame.numRoles();
      int[] y = new int[n];
      Arrays.fill(y, K);
      roles: for (int k = 0; k < K; k++) {
        Span s = this.gold.getArgument(k);
        if (s == Span.nullSpan)
          continue;
        for (int i = s.start; i < s.end; i++) {
          if (y[i] != K) {
            LOG.warn("overlapping args in " + sentence.getId());
            continue roles;
          }
          y[i] = k;
        }
      }
      // Add the labels to the VarConfig
      for (int i = 0; i < n; i++) {
        if (SHOW_GOLD)
          LOG.info(labels[i].getName() + " has gold: " + labels[i].getStateNames().get(y[i]));
        gold.put(labels[i], y[i]);
      }
    }

    /**
     * @param i must be in [0,N) where N is the length of the sentence.
     */
    public ExplicitExpFamFactor getFactor(int i, TemplatedFeatures features) {
      if (i < 0 || i >= sentence.size() - 1)
        throw new IllegalArgumentException();
      VarSet vs = new VarSet(labels[i], labels[i+1]);
      ExplicitExpFamFactor phi = new ExplicitExpFamFactor(vs);
      TemplateContext context = new TemplateContext();
      context.setStage(RoleSequenceStage.class);
      context.setSentence(sentence);
      context.setFrame(frame);
      context.setHead1(i);
      int N = vs.calcNumConfigs();
      for (int c = 0; c < N; c++) {
        int[] cfg = vs.getVarConfigAsArray(c);
        int r1 = cfg[0];
        int r2 = cfg[1];
        FeatureVector fv = new FeatureVector();
        context.setRole(r1);
        context.setRole2(r2);
        if (SHOW_FEATURES) {
          String r1s = labels[i].getStateNames().get(r1);
          String r2s = labels[i].getStateNames().get(r2);
          String msg = String.format("[variables] i=%d K=%d c=%d/%d r1=%s r2=%s",
              i, labels[i].getNumStates(), c, N, r1s, r2s);
          features.featurizeDebug(fv, context, msg);
        } else {
          features.featurize(fv, context);
        }
        phi.setFeatures(c, fv);
      }
      return phi;
    }
  }

  /**
   * Represents everything needed for a sentence.
   */
  class RoleSeqDatum implements StageDatum<FNTagging, FNParse> {
    private HasFgModel weights;
    private FNTagging input;
    private FNParse gold;
    private RoleSeqVars[] vars;   // Co-indexed with i in input.getFrameIndex(i)

    /** Constructor for when doing prediction / gold label is not known */
    public RoleSeqDatum(FNTagging input, HasFgModel weights) {
      this.input = input;
      this.gold = null;
      this.weights = weights;
      initVars();
    }

    /** Constructor for when you know the gold label */
    public RoleSeqDatum(FNTagging input, FNParse gold, HasFgModel weights) {
      this.input = input;
      this.gold = gold;
      this.weights = weights;
      initVars();
    }

    private void initVars() {
      int F = input.numFrameInstances();
      vars = new RoleSeqVars[F];
      for (int f = 0; f < F; f++) {
        if (gold != null) {
          vars[f] = new RoleSeqVars(gold.getFrameInstance(f));
        } else {
          FrameInstance fi = input.getFrameInstance(f);
          vars[f] = new RoleSeqVars(
              fi.getFrame(), fi.getTarget(), input.getSentence());
        }
      }
    }

    public int numFrameInstnaces() {
      return input.numFrameInstances();
    }

    public RoleSeqVars getVars(int frameInstanceIdx) {
      return vars[frameInstanceIdx];
    }

    public Sentence getSentence() {
      return input.getSentence();
    }

    @Override
    public FNTagging getInput() {
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

    public FactorGraph getFG() {
      FactorGraph fg = new FactorGraph();
      // Binary factors between labels (which have unary factors on labels
      // folded-in).
      int F = input.numFrameInstances();
      int N = input.getSentence().size();
      for (int f = 0; f < F; f++)
        for (int i = 0; i < N - 1; i++)
          fg.addFactor(vars[f].getFactor(i, getFeatures()));
      // TODO Connect to dependency vars? Make a binary factor that connects the
      // i^{th} label variable to link[targetHead, i]
      return fg;
    }

    @Override
    public LabeledFgExample getExample() {
      FactorGraph fg = getFG();
      VarConfig gold = new VarConfig();
      int F = input.numFrameInstances();
      for (int f = 0; f < F; f++)
        vars[f].addGold(gold);
      return new LabeledFgExample(fg, gold);
    }

    @Override
    public IDecodable<FNParse> getDecodable() {
      return new RoleSeqDecodable(this, infFactory(), weights);
    }
  }

  static class RoleSeqDecodable extends Decodable<FNParse> {
    private RoleSeqDatum datum;
    public RoleSeqDecodable(
        RoleSeqDatum datum,
        FgInferencerFactory infFact,
        HasFgModel weights) {
      super(datum.getFG(), infFact, weights);
      this.datum = datum;
    }
    @Override
    public FNParse decode() {
      FgInferencer inf = getMargins();
      List<FrameInstance> fis = new ArrayList<>();
      int F = datum.numFrameInstnaces();
      for (int f = 0; f < F; f++) {
        RoleSeqVars rsv = datum.getVars(f);
        rsv.hasMarginals = inf;
        fis.add(rsv.decode());
      }
      return new FNParse(datum.getSentence(), fis);
    }
  }

  /* START of RoleSequenceStage ***********************************************/

  private TemplatedFeatures features;

  public RoleSequenceStage(ParserParams params, HasFeatureAlphabet featureNames) {
    super(params, featureNames);
  }

  TemplatedFeatures getFeatures() {
    if (features == null) {
      features = new TemplatedFeatures("RoleSequenceStage",
          globalParams.getFeatureTemplateDescription(),
          globalParams.getAlphabet());
    }
    return features;
  }

  @Override
  public Double getLearningRate() {
    return 0.1d;
  }

  @Override
	public Regularizer getRegularizer() {
		return new L2(10_000_000d);
	}

	public int getNumTrainingPasses() {
		return 10;
	}

  @Override
  public void configure(Map<String, String> configuration) {
    throw new RuntimeException("implement me");
  }

  @Override
  public void scanFeatures(List<FNParse> data) {
    List<FNTagging> frames = DataUtil.convertParsesToTaggings(data);
    scanFeatures(frames, data, 999, 999_999_999);
  }

  @Override
  public StageDatumExampleList<FNTagging, FNParse> setupInference(
      List<? extends FNTagging> input, List<? extends FNParse> output) {
    List<StageDatum<FNTagging, FNParse>> data = new ArrayList<>();
    for (int i = 0; i < input.size(); i++) {
      if (output == null)
        data.add(this.new RoleSeqDatum(input.get(i), this));
      else
        data.add(this.new RoleSeqDatum(input.get(i), output.get(i), this));
    }
    return new StageDatumExampleList<>(data);
  }

  @Override
  public Serializable getParamters() {
    LOG.info("[getParameters] not actually doing anything");
    return null;
  }

  @Override
  public void setPameters(Serializable params) {
    LOG.info("[setParameters] not actually doing anything");
  }
}
