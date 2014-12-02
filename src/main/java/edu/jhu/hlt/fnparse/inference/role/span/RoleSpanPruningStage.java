package edu.jhu.hlt.fnparse.inference.role.span;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.ConstituencyTreeFactor.SpanVar;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.FPR;
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
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.parse.cky.ConstituencyTreeFactorParser;
import edu.jhu.parse.cky.chart.Chart;
import edu.jhu.parse.cky.data.BinaryTree;

/**
 * This identifies roles (span-valued) given some frames. It has two kinds of
 * variables, both of which are indexed by a span ij.
 * 1) a syntax variable, c_pq, which is a binary variable indicating whether
 *    there is a constituent from p to q.
 * 2) many role variables, r_{pq}^{itk}, which each indicate if span (p,q)
 *    fills role k from the frame t evoked at position i.
 *
 * We will add an AtMost1 factor to all the role variables for a given frameRole
 * (which says that a role cannot be filled by two different spans).
 *
 * With the AtMost1 factor, this is a loopy factor graph.
 *
 * TODO How to do join decoding? Probably the same way that 
 * 
 * ...unless I do observed feature conjoining, then this is too big of a model
 * to use. we will easily hit 750k-1m variables, each of which need to have
 * features extracted. conservatively:
 *   10ms/feature extraction * 750k vars = 125 minutes per example
 * even if that feature extraction speed is 10x too high, its still way too much
 *
 * I need to have a preliminary stage which filters the spans.
 * I would like to have a max-pooling step where there are features for every
 * role*span and we take the max of this across the roles that will be required
 * given the targets that have already been selected... but I think we will have
 * to skip this (would need to write more code to back-prop through the max).
 * We can however have our features know about what roles will be needed.
 *
 *
 *
 *
 * CHANGE:
 * We are going to pick out the spans that could be arguments for each target.
 * 1) have F*N^2 binary vars each of which says whether a span could be an arg
 *    to that frame (instance/target).
 * 2) have N^2 binary vars which say whether it can be an argument to any frame
 *    (can use the max_{frames} factor or features for this)
 * => I'm going to go with option 2 for now. The first implementation will not
 *    have the max factor in it, but will instead have features that are
 *    FNTagging-specific
 * => DON'T do the version where you take the max over a bunch of frames because
 *    this will mean that you're extracting just as many features as you would
 *    have been in the full model...
 *
 *
 * ANOTHER CHANGE:
 * I'm going to do option 1.
 * If the number of frames in a sentence is too high, I can prune the set of
 * frames that are relevant to a given span.
 * Constraints(s,fi):
 *   if s is more wider than the widest arg we've seen for fi.frame, prune
 *   if there is more than one fi between s and fi, prune
 *   if s covers fi.target + other tokens, prune
 *   if s.width=1 && argPruner.prune(fi.frame, role, s.start) forall role, prune
 *   etc.
 *
 * NOTE: This is replaced by {@link DeterministicRolePruning}
 *
 * @author travis
 */
public class RoleSpanPruningStage
    extends AbstractStage<FNTagging, FNParseSpanPruning> {
  public static final Logger LOG = Logger.getLogger(RoleSpanPruningStage.class);

  public static boolean SHOW_FEATURES = false;

  private boolean allExamplesInMem = false;
  private boolean keepEverything = false;
	private boolean disallowArgWithoutConstituent = false;
	private double recallBias = 0.5d;

	private int tooBigForArg = 15;

	private FPR spanRecallAgainstStanford = new FPR(false);
	public void showSpanRecall() {
	  LOG.info("[showSpanRecall] micro recall: " + spanRecallAgainstStanford.recall());
	}

  public RoleSpanPruningStage(
      GlobalParameters globals,
      String featureTemplateString) {
    super(globals, featureTemplateString);
  }

  @Override
  public void saveModel(DataOutputStream dos, GlobalParameters globals) {
    super.saveModel(dos, globals);
    try {
      dos.writeBoolean(allExamplesInMem);
      dos.writeBoolean(keepEverything);
      dos.writeBoolean(disallowArgWithoutConstituent);
      dos.writeDouble(recallBias);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void loadModel(DataInputStream dis, GlobalParameters globals) {
    super.loadModel(dis, globals);
    try {
      allExamplesInMem = dis.readBoolean();
      keepEverything = dis.readBoolean();
      disallowArgWithoutConstituent = dis.readBoolean();
      recallBias = dis.readDouble();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void configure(java.util.Map<String,String> configuration) {
    super.configure(configuration);

    String key, value;

    key = "disallowArgWithoutConstituent." + getName();
    value = configuration.get(key);
    if (value != null) {
      LOG.info("[configure] set " + key + " = " + value);
      disallowArgWithoutConstituent = Boolean.valueOf(value);
    }

    key = "recallBias." + getName();
    value = configuration.get(key);
    if (value != null) {
      LOG.info("[configure] set " + key + " = " + value);
      recallBias = Double.parseDouble(value);
      assert recallBias > 0d;
    }
  }

  public void dontDoAnyPruning() {
    keepEverything = true;
  }

  @Override
  public StageDatumExampleList<FNTagging, FNParseSpanPruning> setupInference(
      List<? extends FNTagging> input,
      List<? extends FNParseSpanPruning> output) {
    return setupInference(input, output, false);
  }

  public StageDatumExampleList<FNTagging, FNParseSpanPruning> setupInference(
      List<? extends FNTagging> input,
      List<? extends FNParseSpanPruning> output,
      boolean showSpanRecall) {
    super.setupInferenceHook(input, output);
    List<StageDatum<FNTagging, FNParseSpanPruning>> data = new ArrayList<>();
    for (int i = 0; i < input.size(); i++) {
      FNParseSpanPruning g = output == null ? null : output.get(i);
      data.add(this.new RoleSpanPruningStageDatum(input.get(i), g, showSpanRecall));
    }
    return new StageDatumExampleList<>(data, allExamplesInMem);
  }

  @Override
  public void scanFeatures(List<FNParse> data) {
    List<FNTagging> frames = DataUtil.convertParsesToTaggings(data);
    List<FNParseSpanPruning> goldPrunes = FNParseSpanPruning.optimalPrune(data);
    this.scanFeatures(frames, goldPrunes, 999, 999_999_999);
  }

  /**
   * A variable that indicates whether a particular span could potentially be
   * an argument to a given frame (instance/target).
   */
  static class ArgSpanPruningVar extends Var {
    private static final long serialVersionUID = 1L;
    public final Frame frame;
    public final Span target;
    public final Span arg;
    private boolean hasGold, gold;  // True if this span should be pruned

    public ArgSpanPruningVar(Span arg, FrameInstance fi) {
      this(arg, fi.getFrame(), fi.getTarget());
    }
    public ArgSpanPruningVar(Span arg, Frame frame, Span target) {
      super(VarType.PREDICTED, 2,
          String.format("r_{%s @ %d-%d has arg @ %d-%d}",
              frame.getName(), target.start, target.end,
              arg.start, arg.end),
              BinaryVarUtil.stateNames);
      this.arg = arg;
      this.frame = frame;
      this.target = target;
      this.hasGold = false;
    }
    public void setGold(boolean shouldBePruned) {
      gold = shouldBePruned;
      hasGold = true;
    }
    public boolean hasGold() {
      return hasGold;
    }
    public boolean gold() {
      assert hasGold();
      return gold;
    }
    @Override
    public String toString() {
      return String.format(
          "<RolePruningVar %s @ %d-%d has some arg at %d-%d %s>",
          frame.getName(),
          target.start,
          target.end,
          arg.start,
          arg.end,
          "gold=" + (!hasGold ? "???" : (gold ? "prune" : "keep")));
    }
    private transient int targetHead = -1, argHead = -1;
    public int getTargetHead(HeadFinder hf, Sentence sent) {
      if (targetHead < 0)
        targetHead = hf.head(target, sent);
      return targetHead;
    }
    public int getArgHead(HeadFinder hf, Sentence sent) {
      if (argHead < 0)
        argHead = hf.head(arg, sent);
      return argHead;
    }
  }

  private int buildCtr = 0, buildCtrInterval = 100;
  private int bigSpansSkipped = 0;
  /**
   * An example for this stage which holds a latent constituency tree and a
   * bunch of pruning variables for the roles/args.
   *
   * This produces an AlmostFNParse which stores the frames that are allowable
   * for every (frame,target).
   */
  class RoleSpanPruningStageDatum
      implements StageDatum<FNTagging, FNParseSpanPruning> {
    private FNTagging input;
    private FNParseSpanPruning gold;
    private boolean showSpanRecall = false;

    public RoleSpanPruningStageDatum(
        FNTagging input,
        FNParseSpanPruning gold,
        boolean showSpanRecall) {
      this.input = input;
      this.gold = gold;
      this.showSpanRecall = showSpanRecall;
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
    public FNParseSpanPruning getGold() {
      assert hasGold();
      return gold;
    }

    @Override
    public LabeledFgExample getExample() {
      observeGetExample(input.getId());
      FactorGraph fg = new FactorGraph();
      VarConfig gold = new VarConfig();
      build(fg, gold, null);
      return new LabeledFgExample(fg, gold);
    }

    private void reportBigSpanSkipped(int start, int end) {
      if (bigSpansSkipped % 50000 == 0) {
        LOG.warn("[reportBigSpanSkipped] skipping " + bigSpansSkipped
            + "th long span (" + start +  "," + end + ")");
      }
      bigSpansSkipped++;
    }

    private void build(
        FactorGraph fg,
        VarConfig goldConf,
        PruningVars roleVars) {

      final int n = input.getSentence().size();
      ConstituencyTreeFactor cykPhi = null;
      if (useLatentConstituencies) {
        cykPhi = new ConstituencyTreeFactor(n, VarType.LATENT);
        fg.addFactor(cykPhi);
      }
      final int nFI = input.numFrameInstances();
      int numRoleVars = 0;
      for (int i = 0; i < nFI; i++) {
        FrameInstance fi = input.getFrameInstance(i);
        assert fi.getSentence().size() == n;
        int widestGold = 0;
        Set<Span> goldArgs = null;
        if (gold != null) {
          FrameInstance goldFi = gold.getFrameInstance(i);
          assert goldFi.getFrame().equals(fi.getFrame());
          assert goldFi.getTarget().equals(fi.getTarget());
          goldArgs = new HashSet<>();
          for (Span s : gold.getPossibleArgs(i)) {
            if (s.width() > widestGold)
              widestGold = s.width();
            goldArgs.add(s);
          }
        }
        for (int start = 0; start < n; start++) {
          for (int end = start + 1; end <= n; end++) {
            if (end - start > tooBigForArg
                && widestGold > 0 && end - start > widestGold) {
              reportBigSpanSkipped(start, end);
              continue;
            }
            ArgSpanPruningVar p = new ArgSpanPruningVar(
                Span.getSpan(start, end), fi);
            fg.addFactor(buildFactor(p, cykPhi));
            numRoleVars++;
            if (this.gold != null && goldConf != null) {
              boolean g = !goldArgs.contains(p.arg);
              p.setGold(g);
              //LOG.debug("[datum build] setting gold for: " + p);
              goldConf.put(p, BinaryVarUtil.boolToConfig(g));
            }
            if (roleVars != null)
              roleVars.add(p);
          }
        }
        // You can never prune the nullSpan, so the value is effectively
        // clamped to gold=keep. We do not include it here.
      }
      if (buildCtr++ % buildCtrInterval == 0) {
        if (cykPhi == null) {
          LOG.debug("[build] " + input.getSentence().getId()
              + " has " + numRoleVars + " role vars and"
              + " no span vars for a sentence of length " + n);
        } else {
          LOG.debug("[build] " + input.getSentence().getId()
              + " has " + numRoleVars + " role vars"
              + " and " + cykPhi.getVars().size()
              + " span vars for a sentence of length " + n);
        }
      }
      if (showSpanRecall && roleVars != null && cykPhi != null)
        roleVars.setCkyFactor(cykPhi);
    }

    /**
     * Creates either a binary factor for the role pruning var ~ constituency var
     * which includes the unary factor that would go on just the pruning var
     * or a unary factor the pruning var if cykPhi is null.
     */
    private ExplicitExpFamFactor buildFactor(
        ArgSpanPruningVar p,
        ConstituencyTreeFactor cykPhi) {  // can be null

      VarSet vs;
      Var c = null;    // Constituency variable
      if (cykPhi == null) {
        vs = new VarSet(p);
      } else {
        c = cykPhi.getSpanVar(p.arg.start, p.arg.end);
        if (c == null) {
          assert p.arg.width() == 1 :
            "figure out how to handle pruned constituents";
          vs = new VarSet(p);
        } else {
          vs = new VarSet(p, c);
        }
      }

      HeadFinder hf = SemaforicHeadFinder.getInstance();
      ExplicitExpFamFactorWithConstraint phi =
        new ExplicitExpFamFactorWithConstraint(vs, -1);
      TemplatedFeatures feats = RoleSpanPruningStage.this.getFeatures();
      TemplateContext context = new TemplateContext();
      context.setStage(RoleSpanPruningStage.class);
      context.setSentence(input.getSentence());
      context.setFrame(p.frame);
      context.setTarget(p.target);
      context.setTargetHead(p.getTargetHead(hf, input.getSentence()));
      context.setSpan2(p.target);
      context.setHead2(p.getTargetHead(hf, input.getSentence()));
      context.setArg(p.arg);
      context.setSpan1(p.arg);
      context.setHead1(p.getArgHead(hf, input.getSentence()));

      int N = vs.calcNumConfigs();
      for (int i = 0; i < N; i++) {
        VarConfig conf = vs.getVarConfig(i);
        boolean keep = !BinaryVarUtil.configToBool(conf.getState(p));
        boolean cons  = false;
        if (c != null)
          cons = p.arg.width() == 1 || BinaryVarUtil.configToBool(conf.getState(c));

        StringBuilder msg = null;
        if (SHOW_FEATURES) {
          msg = new StringBuilder("[variables]");
          msg.append(" keep=" + keep);
          if (c != null)
            msg.append(" cons=" + cons);
          msg.append(" ");
          msg.append(p.getName());
        }

        if (c != null   // only applies with latent c-parse
            && disallowArgWithoutConstituent && keep && !cons) {
          phi.setBadConfig(i);
          phi.setFeatures(i, AbstractFeatures.emptyFeatures);
          if (SHOW_FEATURES) {
            msg.append(" CONSTRAINED TO -INFINITY");
            LOG.info(msg);
            LOG.info("");
          }
        } else {
          context.setPrune(!keep);
          context.setSpan1IsConstituent(cons);
          FeatureVector fv = new FeatureVector();
          if (SHOW_FEATURES) {
            feats.featurizeDebug(fv, context, msg.toString());
          } else {
            feats.featurize(fv, context);
          }
          phi.setFeatures(i, fv);
        }
      }
      return phi;
    }

    @Override
    public IDecodable<FNParseSpanPruning> getDecodable() {
      if (input.getSentence().size() < 5)
        LOG.info("test");
      observeGetDecodable(input.getId());
      FactorGraph fg = new FactorGraph();
      PruningVars roleVars = new PruningVars(input.getSentence());
      build(fg, null, roleVars);
      if (roleVars.size() == 0) {
        // If there are no frames, and thus no roles, then return an
        // empty decodable.
        final FNParseSpanPruning empty = new FNParseSpanPruning(
            input.getSentence(),
            Collections.<FrameInstance>emptyList(),
            Collections.<FrameInstance, List<Span>>emptyMap());
        return new IDecodable<FNParseSpanPruning>() {
          @Override
          public FNParseSpanPruning decode() {
            return empty;
          }
        };
      } else {
        if (keepEverything) {
          ApproxF1MbrDecoder decoder = null;
          return new ThresholdDecodable(
              fg, infFactory(), input, roleVars, decoder);
        } else {
          RankDecodable rd = new RankDecodable(
              fg, infFactory(), input, roleVars, recallBias);
          return rd;
        }
      }
    }
  }

  class PruningVars extends ArrayList<ArgSpanPruningVar> {
    private static final long serialVersionUID = 1L;
    private Sentence sent;
    private ConstituencyTreeFactor ckyFactor = null;
    public PruningVars(Sentence sent) {
      this.sent = sent;
    }
    public void setCkyFactor(ConstituencyTreeFactor ckyFactor) {
      this.ckyFactor = ckyFactor;
    }
    public ConstituencyTreeFactor getCkyFactor() {
      return ckyFactor;
    }
    public void showSpanRecall(FgInferencer inf) {
      if (ckyFactor == null)
        return;

      // Get the spans from the marginals/beliefs
      List<SpanVar> sv = new ArrayList<>();
      List<DenseFactor> beliefs = new ArrayList<>();
      int n = sent.size();
      for (int i = 0; i < n; i++) {
        for (int j = i + 1; j <= n; j++) {
          SpanVar s = ckyFactor.getSpanVar(i, j);
          sv.add(s);
          beliefs.add(inf.getMarginals(s));
        }
      }
      ConstituencyTreeFactorParser parser = new ConstituencyTreeFactorParser();
      Chart chart = parser.parse(n, sv, beliefs);
      BinaryTree tree = chart.getViterbiParse().get1();
      Set<Span> hyp = new HashSet<>();
      getSpans(tree, hyp);

      // Get the spans from a constituency parser
      ConstituencyParse cparse = sent.getStanfordParse(false);
      if (cparse == null) {
        ConcreteStanfordWrapper stanford =
            ConcreteStanfordWrapper.getSingleton(false);
        cparse = stanford.getCParse(sent);
      }
      Set<Span> gold = new HashSet<>();
      cparse.getSpans(gold);

      // Evaluate recall
      int tp = 0, fn = 0;
      for (Span s : gold) {
        if (hyp.contains(s))
          tp++;
        else
          fn++;
      }
      double recall = ((double) tp) / (tp + fn);
      LOG.info("[showSpanRecall] on " + sent.getId() + " recall=" + recall
          + " tp=" + tp + " fn=" + fn + " sent.size=" + sent.size()
          + " hyp.size=" + hyp.size() + " gold.size=" + gold.size());
      spanRecallAgainstStanford.accum(tp, 0, fn);
    }
    private void getSpans(BinaryTree tree, Collection<Span> addTo) {
      if (tree == null)
        return;
      addTo.add(Span.getSpan(tree.getStart(), tree.getEnd()));
      getSpans(tree.getLeftChild(), addTo);
      getSpans(tree.getRightChild(), addTo);
    }
  }

  /**
   * Take the top scoring/most likely spans for every frame. Re-parameterizes
   * the simple score threshold by sorting by rank and taking the top K.
   */
  class RankDecodable extends Decodable<FNParseSpanPruning> {
    private PruningVars roleVars;
    private FNTagging input;
    private double recallBias;

    public RankDecodable(
        FactorGraph fg,
        FgInferencerFactory infFact,
        FNTagging input,
        PruningVars roleVars,
        double recallBias) {
      super(fg, infFact);
      this.input = input;
      this.roleVars = roleVars;
      this.recallBias = recallBias;
      if (roleVars == null || roleVars.size() == 0)
        throw new IllegalArgumentException();
    }

    @Override
    public FNParseSpanPruning decode() {
      final FgInferencer inf = this.getMargins();
      roleVars.showSpanRecall(inf);
      // Sort the roleVars by (frame,target) then probability
      Collections.sort(roleVars, new Comparator<ArgSpanPruningVar>() {
        @Override
        public int compare(ArgSpanPruningVar arg0, ArgSpanPruningVar arg1) {
          if (arg0.frame.getId() < arg1.frame.getId())
            return -1;
          if (arg0.frame.getId() > arg1.frame.getId())
            return 1;
          int tc = arg0.target.compareTo(arg1.target);
          if (tc != 0)
            return tc;
          DenseFactor df0 = inf.getMarginals(arg0);
          df0.logNormalize();
          double p0 = df0.getValue(BinaryVarUtil.boolToConfig(true));
          DenseFactor df1 = inf.getMarginals(arg1);
          df1.logNormalize();
          double p1 = df1.getValue(BinaryVarUtil.boolToConfig(true));
          if (p0 > p1)
            return 1;
          if (p0 < p1)
            return -1;
          return 0;
        }
      });
      // For each (frame,target) take the top K
      Map<FrameInstance, List<Span>> kept = new HashMap<>();
      List<Span> curKeep = null;
      FrameInstance cur = null;
      for (ArgSpanPruningVar rpv : roleVars) {
        FrameInstance c = FrameInstance.frameMention(
            rpv.frame, rpv.target, input.getSentence());
        if (!c.equals(cur)) {
          if (cur != null) {
            List<Span> old = kept.put(cur, curKeep);
            assert old == null;
          }
          cur = c;
          curKeep = new ArrayList<>();
          curKeep.add(Span.nullSpan);
        }
        //LOG.debug(rpv + " has p(prune)=" + inf.getMarginals(rpv).getValue(BinaryVarUtil.boolToConfig(true)));
        if (curKeep.size() < spansToTakeFor(cur.getFrame())) {
          //LOG.info("KEEP");
          curKeep.add(rpv.arg);
        } else {
          //LOG.info("DROP");
        }
      }
      List<Span> old = kept.put(cur, curKeep);
      assert old == null;
      return new FNParseSpanPruning(
          input.getSentence(), input.getFrameInstances(), kept);
    }

    public int spansToTakeFor(Frame f) {
      int n = input.getSentence().size();
      return (int) (recallBias * n + 0.5d);
    }
    @Override
    public FgModel getWeights() {
      return RoleSpanPruningStage.this.getWeights();
    }
    @Override
    public void setWeights(FgModel weights) {
      throw new UnsupportedOperationException();
    }
    @Override
    public boolean logDomain() {
      return RoleSpanPruningStage.this.logDomain();
    }
  }

  /**
   * Decode the set of spans as a classification problem with a probability
   * threshold
   * @deprecated because ranking is better
   */
  class ThresholdDecodable extends Decodable<FNParseSpanPruning> {
    // Each variable says whether to prune a particular span for a given
    // (frame,target).
    private List<ArgSpanPruningVar> roleVars;

    private FNTagging input;

    // If null, keep everything. Produce FNParseSpanPrunings that have
    // 100% recall.
    private ApproxF1MbrDecoder decoder;

    public ThresholdDecodable(
        FactorGraph fg,
        FgInferencerFactory infFact,
        FNTagging input,
        List<ArgSpanPruningVar> roleVars,
        ApproxF1MbrDecoder decoder) {
      super(fg, infFact);
      this.input = input;
      this.roleVars = roleVars;
      this.decoder = decoder;
      if (roleVars == null || roleVars.size() == 0)
        throw new IllegalArgumentException();
    }
    @Override
    public FNParseSpanPruning decode() {
      FgInferencer inf = this.getMargins();
      Map<FrameInstance, List<Span>> kept = new HashMap<>();
      int pruned = 0, considered = 0;
      for (ArgSpanPruningVar rpv : roleVars) {
        DenseFactor df = inf.getMarginals(rpv);
        int y = BinaryVarUtil.boolToConfig(false);
        if (decoder != null) {
          y = decoder.decode(
              df.getValues(), BinaryVarUtil.boolToConfig(false));
        }
        //LOG.debug("[decode] " + rpv + " has beliefs " + df
        //    + " and was decoded as " + y);
        considered++;
        if (y == BinaryVarUtil.boolToConfig(true)) {
          pruned++;
          continue;
        }
        FrameInstance key = FrameInstance.frameMention(
            rpv.frame, rpv.target, input.getSentence());
        List<Span> values = kept.get(key);
        if (values == null) {
          values = new ArrayList<>();
          kept.put(key, values);
        }
        values.add(rpv.arg);
      }
      LOG.info(String.format(
          "[decode] pruned %d of %d possible spans for %d frames in %s",
          pruned,
          considered,
          input.numFrameInstances(),
          input.getSentence().getId()));

      // Add nullSpan as an option for the next stage (role labeling).
      for (FrameInstance fi : input.getFrameInstances()) {
        FrameInstance key = FrameInstance.frameMention(
            fi.getFrame(), fi.getTarget(), fi.getSentence());
        List<Span> values = kept.get(key);
        if (values == null) {
          values = new ArrayList<>();
          kept.put(key, values);
        }
        values.add(Span.nullSpan);
      }

      return new FNParseSpanPruning(
          input.getSentence(), input.getFrameInstances(), kept);
    }
    @Override
    public FgModel getWeights() {
      return RoleSpanPruningStage.this.getWeights();
    }
    @Override
    public void setWeights(FgModel weights) {
      throw new UnsupportedOperationException();
    }
    @Override
    public boolean logDomain() {
      return RoleSpanPruningStage.this.logDomain();
    }
  }

  public static void main(String[] args) {
    Logger.getLogger(ConstituencyTreeFactor.class).setLevel(Level.FATAL);

    Random r = new Random(9001);
    List<FNParse> parses = DataUtil.iter2list(
        FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
    parses = DataUtil.reservoirSample(parses, 250, r);
    List<FNParse> train = new ArrayList<>(), test = new ArrayList<>();
    for (FNParse p : parses)
      (r.nextBoolean() ? train : test).add(p);

    GlobalParameters globals = new GlobalParameters();
    String featureTemplateString = "RoleSpanPruningStage-latent*frameRoleArg*head1WordWnSynset+RoleSpanPruningStage-latent*frameRoleArg*head1Lemma+RoleSpanPruningStage-latent*roleArg*head1WordWnSynset+RoleSpanPruningStage-latent*roleArg*head1Lemma+RoleSpanPruningStage-latent*arg*head1WordWnSynset+RoleSpanPruningStage-latent*arg*head1Lemma+RoleSpanPruningStage-latent*framePrune*1+RoleSpanPruningStage-latent*framePrune*head1Word+RoleSpanPruningStage-latent*framePrune*head1Pos2+RoleSpanPruningStage-latent*framePrune*head1WordWnSynset+RoleSpanPruningStage-latent*framePrune*head1Lemma+RoleSpanPruningStage-latent*framePrune*span1span2Overlap+RoleSpanPruningStage-latent*framePrune*span1LeftPos2+RoleSpanPruningStage-latent*framePrune*span1FirstPos2+RoleSpanPruningStage-latent*framePrune*span1LastPos2+RoleSpanPruningStage-latent*framePrune*span1RightPos2+RoleSpanPruningStage-latent*framePrune*span1Width/3+RoleSpanPruningStage-latent*framePrune*span1Width/5+RoleSpanPruningStage-latent*framePrune*Dist(SemaforPathLengths,Head1,Head2)+RoleSpanPruningStage-latent*framePrune*head1CollapsedLabel+RoleSpanPruningStage-latent*framePrune*Dist(Direction,Head1,Head2)*head1CollapsedParentDir+RoleSpanPruningStage-latent*prune*1+RoleSpanPruningStage-latent*prune*head1Word+RoleSpanPruningStage-latent*prune*head1Pos2+RoleSpanPruningStage-latent*prune*head1WordWnSynset+RoleSpanPruningStage-latent*prune*head1Lemma+RoleSpanPruningStage-latent*prune*span1span2Overlap+RoleSpanPruningStage-latent*prune*span1LeftPos2+RoleSpanPruningStage-latent*prune*span1FirstPos2+RoleSpanPruningStage-latent*prune*span1LastPos2+RoleSpanPruningStage-latent*prune*span1RightPos2+RoleSpanPruningStage-latent*prune*span1Width/3+RoleSpanPruningStage-latent*prune*span1Width/5+RoleSpanPruningStage-latent*prune*Dist(SemaforPathLengths,Head1,Head2)+RoleSpanPruningStage-latent*prune*head1CollapsedLabel+RoleSpanPruningStage-latent*prune*Dist(Direction,Head1,Head2)*head1CollapsedParentDir+RoleSpanPruningStage-latent*span1IsConstituent*1+RoleSpanPruningStage-latent*span1IsConstituent*frame+RoleSpanPruningStage-latent*span1IsConstituent*Dist(Direction,Head1,Head2)*frame+RoleSpanPruningStage-latent*span1IsConstituent*span1PosPat-COARSE_POS-1-1+RoleSpanPruningStage-latent*span1IsConstituent*span1PosPat-WORD_SHAPE-1-1+RoleSpanPruningStage-latent*span1IsConstituent*span1LeftPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1LeftPos2*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1LeftPos2*span1RightPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1LeftPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1LeftPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1LeftPos2*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1LeftPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1LastPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1LastPos2*span1LeftPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1LastPos2*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1LastPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1LastPos2*span1LastWord+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1RightPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1FirstWord+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1FirstWord*span1LeftPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1FirstWord*span1LastPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1FirstWord*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1FirstWord*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1LastWord+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1LastWord*span1LeftPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1LastWord*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstPos2*span1LastWord*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1LastPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1LastPos2*span1LeftPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1LastPos2*span1LeftPos2*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1LastPos2*span1LeftPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1LastPos2*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1LastPos2*span1RightPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1LastPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1LastPos2*span1LastWord+RoleSpanPruningStage-latent*span1IsConstituent*span1LastPos2*span1LastWord*span1LeftPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1LastPos2*span1LastWord*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1LastPos2*span1LastWord*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1RightPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstWord+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstWord*span1LeftPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstWord*span1LeftPos2*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstWord*span1LeftPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstWord*span1LastPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstWord*span1LastPos2*span1LeftPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstWord*span1LastPos2*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstWord*span1LastPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstWord*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstWord*span1RightPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1FirstWord*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1LastWord+RoleSpanPruningStage-latent*span1IsConstituent*span1LastWord*span1LeftPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1LastWord*span1LeftPos2*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1LastWord*span1LeftPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1LastWord*span1RightPos2+RoleSpanPruningStage-latent*span1IsConstituent*span1LastWord*span1RightPos2*span1Width/5+RoleSpanPruningStage-latent*span1IsConstituent*span1LastWord*span1Width/5+RoleSpanLabelingStage-latent*frameRoleArg*1+RoleSpanLabelingStage-latent*frameRoleArg*head1Word+RoleSpanLabelingStage-latent*frameRoleArg*head1Pos2+RoleSpanLabelingStage-latent*frameRoleArg*head1WordWnSynset+RoleSpanLabelingStage-latent*frameRoleArg*head1Lemma+RoleSpanLabelingStage-latent*frameRoleArg*span1span2Overlap+RoleSpanLabelingStage-latent*frameRoleArg*span1LeftPos2+RoleSpanLabelingStage-latent*frameRoleArg*span1FirstPos2+RoleSpanLabelingStage-latent*frameRoleArg*span1LastPos2+RoleSpanLabelingStage-latent*frameRoleArg*span1RightPos2+RoleSpanLabelingStage-latent*frameRoleArg*span1Width/3+RoleSpanLabelingStage-latent*frameRoleArg*span1Width/5+RoleSpanLabelingStage-latent*frameRoleArg*Dist(SemaforPathLengths,Head1,Head2)+RoleSpanLabelingStage-latent*frameRoleArg*head1CollapsedLabel+RoleSpanLabelingStage-latent*frameRoleArg*Dist(Direction,Head1,Head2)*head1CollapsedParentDir+RoleSpanLabelingStage-latent*roleArg*1+RoleSpanLabelingStage-latent*roleArg*head1Word+RoleSpanLabelingStage-latent*roleArg*head1Pos2+RoleSpanLabelingStage-latent*roleArg*head1WordWnSynset+RoleSpanLabelingStage-latent*roleArg*head1Lemma+RoleSpanLabelingStage-latent*roleArg*span1span2Overlap+RoleSpanLabelingStage-latent*roleArg*span1LeftPos2+RoleSpanLabelingStage-latent*roleArg*span1FirstPos2+RoleSpanLabelingStage-latent*roleArg*span1LastPos2+RoleSpanLabelingStage-latent*roleArg*span1RightPos2+RoleSpanLabelingStage-latent*roleArg*span1Width/3+RoleSpanLabelingStage-latent*roleArg*span1Width/5+RoleSpanLabelingStage-latent*roleArg*Dist(SemaforPathLengths,Head1,Head2)+RoleSpanLabelingStage-latent*roleArg*head1CollapsedLabel+RoleSpanLabelingStage-latent*roleArg*Dist(Direction,Head1,Head2)*head1CollapsedParentDir+RoleSpanLabelingStage-latent*arg*1+RoleSpanLabelingStage-latent*arg*head1Word+RoleSpanLabelingStage-latent*arg*head1Pos2+RoleSpanLabelingStage-latent*arg*head1WordWnSynset+RoleSpanLabelingStage-latent*arg*head1Lemma+RoleSpanLabelingStage-latent*arg*span1span2Overlap+RoleSpanLabelingStage-latent*arg*span1LeftPos2+RoleSpanLabelingStage-latent*arg*span1FirstPos2+RoleSpanLabelingStage-latent*arg*span1LastPos2+RoleSpanLabelingStage-latent*arg*span1RightPos2+RoleSpanLabelingStage-latent*arg*span1Width/3+RoleSpanLabelingStage-latent*arg*span1Width/5+RoleSpanLabelingStage-latent*arg*Dist(SemaforPathLengths,Head1,Head2)+RoleSpanLabelingStage-latent*arg*head1CollapsedLabel+RoleSpanLabelingStage-latent*arg*Dist(Direction,Head1,Head2)*head1CollapsedParentDir+RoleSpanLabelingStage-latent*framePrune*head1WordWnSynset+RoleSpanLabelingStage-latent*framePrune*head1Lemma+RoleSpanLabelingStage-latent*prune*head1WordWnSynset+RoleSpanLabelingStage-latent*prune*head1Lemma+RoleSpanLabelingStage-latent*span1IsConstituent*1+RoleSpanLabelingStage-latent*span1IsConstituent*frameRoleArg+RoleSpanLabelingStage-latent*span1IsConstituent*roleArg+RoleSpanLabelingStage-latent*span1IsConstituent*frame+RoleSpanLabelingStage-latent*span1IsConstituent*Dist(Direction,Head1,Head2)*frame+RoleSpanLabelingStage-latent*span1IsConstituent*span1PosPat-COARSE_POS-1-1+RoleSpanLabelingStage-latent*span1IsConstituent*span1PosPat-WORD_SHAPE-1-1+RoleSpanLabelingStage-latent*span1IsConstituent*span1LeftPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1LeftPos2*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1LeftPos2*span1RightPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1LeftPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1LeftPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1LeftPos2*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1LeftPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1LastPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1LastPos2*span1LeftPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1LastPos2*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1LastPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1LastPos2*span1LastWord+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1RightPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1FirstWord+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1FirstWord*span1LeftPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1FirstWord*span1LastPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1FirstWord*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1FirstWord*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1LastWord+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1LastWord*span1LeftPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1LastWord*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstPos2*span1LastWord*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastPos2*span1LeftPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastPos2*span1LeftPos2*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastPos2*span1LeftPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastPos2*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastPos2*span1RightPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastPos2*span1LastWord+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastPos2*span1LastWord*span1LeftPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastPos2*span1LastWord*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastPos2*span1LastWord*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1RightPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstWord+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstWord*span1LeftPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstWord*span1LeftPos2*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstWord*span1LeftPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstWord*span1LastPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstWord*span1LastPos2*span1LeftPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstWord*span1LastPos2*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstWord*span1LastPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstWord*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstWord*span1RightPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1FirstWord*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastWord+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastWord*span1LeftPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastWord*span1LeftPos2*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastWord*span1LeftPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastWord*span1RightPos2+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastWord*span1RightPos2*span1Width/5+RoleSpanLabelingStage-latent*span1IsConstituent*span1LastWord*span1Width/5";
    RoleSpanPruningStage rsp = new RoleSpanPruningStage(globals, featureTemplateString);
    rsp.setSyntaxMode("latent");
    rsp.configure("passes", "3");
    rsp.configure("bpIters", "1");
    rsp.scanFeatures(train);
    List<FNTagging> trainX = DataUtil.convertParsesToTaggings(train);
    List<FNParseSpanPruning> trainY =
        FNParseSpanPruning.optimalPrune(train);
    rsp.train(trainX, trainY);
    List<FNParseSpanPruning> yhat =
        rsp.setupInference(DataUtil.convertParsesToTaggings(test), null).decodeAll();
    FPR perf = new FPR(false);
    int argsLetThrough = 0, nFI = 0;
    for (int i = 0; i < yhat.size(); i++) {
      FNParseSpanPruning prune = yhat.get(i);
      FNParse p = test.get(i);
      nFI += p.numFrameInstances();
      argsLetThrough += prune.numPossibleArgs();
      prune.perf(p, perf);
    }
    double ns = ((double) argsLetThrough) / yhat.size();
    double nf = ((double) argsLetThrough) / nFI;
    LOG.info("recall=" + perf.recall() + " n/s=" + ns + " n/FI=" + nf);
  }
}
