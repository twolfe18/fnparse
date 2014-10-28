package edu.jhu.hlt.fnparse.inference.role.span;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.hlt.fnparse.util.HasFgModel;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.L2;

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
  private static final long serialVersionUID = 1L;
  public static final Logger LOG = Logger.getLogger(RoleSpanPruningStage.class);

  public static boolean SHOW_FEATURES = false;

  // If true, do not prune anything, produce FNParseSpanPrunings that have
  // 100% recall.
  private boolean keepEverything = false;
  private TemplatedFeatures features;
  private transient Regularizer regularizer;

  public RoleSpanPruningStage(
      ParserParams params,
      HasFeatureAlphabet featureNames) {
    super(params, featureNames);
    features = new TemplatedFeatures("roleSpanPruning",
        params.getParserParams().getFeatureTemplateDescription(),
        params.getParserParams().getAlphabet());
    regularizer = new L2(100_000d);
  }

  @Override
  public Serializable getParamters() {
    return keepEverything;
  }

  @Override
  public void setPameters(Serializable params) {
    keepEverything = (Boolean) params;
  }

  public void dontDoAnyPruning() {
    keepEverything = true;
  }

  @Override
  public Double getLearningRate() {
    return 1d;
  }

  @Override
  public int getNumTrainingPasses() {
    return 3;
  }

  @Override
  public Regularizer getRegularizer() {
    return regularizer;
  }

  @Override
  public StageDatumExampleList<FNTagging, FNParseSpanPruning> setupInference(
      List<? extends FNTagging> input,
      List<? extends FNParseSpanPruning> output) {
    List<StageDatum<FNTagging, FNParseSpanPruning>> data = new ArrayList<>();
    for (int i = 0; i < input.size(); i++) {
      FNParseSpanPruning g = output == null ? null : output.get(i);
      data.add(new JointRoleSpanStageDatum(input.get(i), g, this));
    }
    return new StageDatumExampleList<>(data);
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

  /**
   * An example for this stage which holds a latent constituency tree and a
   * bunch of pruning variables for the roles/args.
   *
   * This produces an AlmostFNParse which stores the frames that are allowable
   * for every (frame,target).
   */
  static class JointRoleSpanStageDatum
      implements StageDatum<FNTagging, FNParseSpanPruning> {
    private FNTagging input;
    private FNParseSpanPruning gold;
    private RoleSpanPruningStage parent;

    public JointRoleSpanStageDatum(
        FNTagging input,
        FNParseSpanPruning gold,
        RoleSpanPruningStage parent) {
      this.input = input;
      this.gold = gold;
      this.parent = parent;
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
      FactorGraph fg = new FactorGraph();
      VarConfig gold = new VarConfig();
      build(fg, gold, null);
      return new LabeledFgExample(fg, gold);
    }

    private void build(FactorGraph fg, VarConfig goldConf, Collection<ArgSpanPruningVar> roleVars) {
      // Build the variables
      final int n = input.getSentence().size();
      ConstituencyTreeFactor cykPhi =
          new ConstituencyTreeFactor(n, VarType.LATENT);
      fg.addFactor(cykPhi);
      final int nFI = input.numFrameInstances();
      int numRoleVars = 0;
      for (int i = 0; i < nFI; i++) {
        FrameInstance fi = input.getFrameInstance(i);
        Set<Span> goldArgs = null;
        if (gold != null) {
          FrameInstance goldFi = gold.getFrameInstance(i);
          assert goldFi.getFrame().equals(fi.getFrame());
          assert goldFi.getTarget().equals(fi.getTarget());
          goldArgs = new HashSet<>();
          goldArgs.addAll(gold.getPossibleArgs(i));
        }
        for (int start = 0; start < n; start++) {
          for (int end = start + 1; end <= n; end++) {
            ArgSpanPruningVar p = new ArgSpanPruningVar(
                Span.getSpan(start, end), fi);
            fg.addFactor(buildBinaryFactor(p, cykPhi));
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
      LOG.debug(input.getSentence().getId() + " has " + numRoleVars
          + " role vars and " + cykPhi.getVars().size()
          + " span vars for a sententence of length " + n);
    }

    /**
     * Create a binary factor for the role pruning var ~ constituency var
     * which includes the unary factor that would go on just the pruning var
     */
    private ExplicitExpFamFactor buildBinaryFactor(
        ArgSpanPruningVar p,
        ConstituencyTreeFactor cykPhi) {
      // -1 because Matt's args are inclusive
      Var c = cykPhi.getSpanVar(p.arg.start, p.arg.end - 1);
      VarSet vs;
      if (c == null) {
        assert p.arg.width() == 1 :
          "figure out how to handle pruned constituents";
        vs = new VarSet(p);
      } else {
        vs = new VarSet(p, c);
      }
      HeadFinder hf = parent.getGlobalParams().headFinder;
      ExplicitExpFamFactor phi = new ExplicitExpFamFactor(vs);
      TemplateContext context = parent.features.getContext();
      context.clear();
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
        context.setPrune(BinaryVarUtil.configToBool(conf.getState(p)));
        context.setSpan1IsConstituent(
            p.arg.width() == 1 || BinaryVarUtil.configToBool(conf.getState(c)));
        FeatureVector fv = new FeatureVector();
        if (SHOW_FEATURES) {
          StringBuilder msg = new StringBuilder("[variables]");
          msg.append(" prune=" + BinaryVarUtil.configToBool(conf.getState(p)));
          if (c != null)
            msg.append(" constit=" + BinaryVarUtil.configToBool(conf.getState(c)));
          parent.features.featurizeDebug(fv, msg.toString());
        } else {
          parent.features.featurize(fv);
        }
        phi.setFeatures(i, fv);
      }
      return phi;
    }

    @Override
    public IDecodable<FNParseSpanPruning> getDecodable() {
      FactorGraph fg = new FactorGraph();
      List<ArgSpanPruningVar> roleVars = new ArrayList<>();
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
        //return new ThresholdDecodable(fg, parent.infFactory(),
        //		parent, input, roleVars, parent.decoder);
        if (parent.keepEverything) {
          ApproxF1MbrDecoder decoder = null;
          return new ThresholdDecodable(fg, parent.infFactory(),
              parent, input, roleVars, decoder);
        } else {
          return new RankDecodable(fg, parent.infFactory(),
              parent, input, roleVars);
        }
      }
    }
  }

  /**
   * Take the top scoring/most likely spans for every frame. Re-parameterizes
   * the simple score threshold by sorting by rank and taking the top K.
   */
  static class RankDecodable extends Decodable<FNParseSpanPruning> {
    private List<ArgSpanPruningVar> roleVars;
    private FNTagging input;
    private double recallBias = 2d;

    public RankDecodable(
        FactorGraph fg,
        FgInferencerFactory infFact,
        HasFgModel weights,
        FNTagging input,
        List<ArgSpanPruningVar> roleVars) {
      super(fg, infFact, weights);
      this.input = input;
      this.roleVars = roleVars;
      if (roleVars == null || roleVars.size() == 0)
        throw new IllegalArgumentException();
    }

    @Override
    public FNParseSpanPruning decode() {
      final FgInferencer inf = this.getMargins();
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
      int numCore = 0;
      for (int k = 0; k < f.numRoles(); k++)
        if (f.getRole(k).toLowerCase().indexOf("core") >= 0)
          numCore++;
      return (int) (recallBias * (input.getSentence().size() + numCore + 5d));
    }
  }

  /**
   * Decode the set of spans as a classification problem with a probability
   * threshold
   * @deprecated because ranking is better
   */
  static class ThresholdDecodable extends Decodable<FNParseSpanPruning> {
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
        HasFgModel weights,
        FNTagging input,
        List<ArgSpanPruningVar> roleVars,
        ApproxF1MbrDecoder decoder) {
      super(fg, infFact, weights);
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
        //		+ " and was decoded as " + y);
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
  }

  /**
   * Features that describe if a particular span could be an argument to a
   * given frame.
   *
   * @deprecated going to make this work through templated features
	static class Features extends AbstractFeatures<Features> {
		private static final long serialVersionUID = 1L;

		public Features(HasParserParams globalParams) {
			super(globalParams);
		}

		public void featurize(
				FeatureVector v,
				Refinements r,
				ArgSpanPruningVar p,
				boolean prune,
				boolean constituent,
				Sentence s) {

			// Don't need to parameterize everything
			String d;
			if (!prune && constituent)
				return;
			else if (!prune && !constituent)
				d = "keepNonConstituent";
			else if (prune && constituent)
				d = "pruneConstituent";
			else
				d = "pruneNonConstituent";

			String width = "width=" + discretizeDist(p.arg.width());
			String frame = "frame=" + p.frame.getName();
			final int tHead = globalParams
					.getParserParams()
					.headFinder.head(p.target, s);
			final int aHead = globalParams
					.getParserParams()
					.headFinder.head(p.arg, s);

			b(v, r, 10d, d);
			b(v, r, d, width);
			b(v, r, d, width, frame);

			String lPos = "lPos=" + AbstractFeatures
					.getLUSafe(p.arg.start-1, s).pos;
			String rPos = "rPos=" + AbstractFeatures
					.getLUSafe(p.arg.end, s).pos;
			String sPos = "sPos=" + s.getPos(p.arg.start);
			String ePos = "ePos=" + s.getPos(p.arg.end - 1);
			String hPos = "hPos=" + s.getPos(
					globalParams.getParserParams().headFinder.head(p.arg, s));
			List<String> allPos = Arrays.asList(lPos, rPos, sPos, ePos, hPos);
			for (int i = 0; i < allPos.size() - 1; i++) {
				b(v, r, d, allPos.get(i), frame);
				for (int j = i + 1; j < allPos.size(); j++)
					b(v, r, d, allPos.get(i), allPos.get(j), frame);
			}

			if (p.arg.equals(p.target)) {
				String w = p.arg.width() <= 4 ? ("w=" + p.arg.width()) : "w>4";
				b(v, r, d, "equals");
				b(v, r, d, "equals", w);
				b(v, r, d, "equals", frame);
				b(v, r, d, "equals", frame, w);
			}

			String dist = "overlap";
			if (p.arg.after(p.target)) {
				dist = "afterBy" + discretizeDist(p.arg.start - p.target.end);
				b(v, r, d, dist);
				b(v, r, d, dist, frame);
				dist = "hh-afterBy" + discretizeDist(aHead - tHead);
				b(v, r, d, dist);
				b(v, r, d, dist, frame);
			} else if (p.arg.before(p.target)) {
				dist = "beforeBy" + discretizeDist(p.target.start - p.arg.end);
				b(v, r, d, dist);
				b(v, r, d, dist, frame);
				dist = "hh-beforeBy" + discretizeDist(tHead - aHead);
				b(v, r, d, dist);
				b(v, r, d, dist, frame);
			}
			b(v, r, d, dist);
			b(v, r, d, dist, frame);

			// Counts of POS between arg and target
			Counts<String> between = null;
			if (p.arg.end <= p.target.start) {
				between = between(p.arg.end, p.target.start, s);
			} else if (p.target.end <= p.arg.start) {
				between = between(p.target.end, p.arg.start, s);
			}
			if (between != null) {
				for (Map.Entry<String, Integer> x : between.entrySet()) {
					String f = "count(" + x.getKey() + ")=" + x.getValue();
					b(v, r, d, f);
					b(v, r, d, f, frame);
				}
			}

			// POS pattern and word shapes for the arguments
			if (p.arg.width() <= 6) {
				String posPat = new PosPatternGenerator(
						1, 1, Mode.COARSE_POS).extract(p.arg, s);
				b(v, r, d, posPat);
				b(v, r, d, posPat, frame);
				String shapePat = new PosPatternGenerator(
						0, 0, Mode.WORD_SHAPE).extract(p.arg, s);
				b(v, r, d, shapePat);
				b(v, r, d, shapePat, frame);
			} else {
				b(v, r, d, "posPatTooBig");
				b(v, r, d, "posPatTooBig", frame);
			}

			if (globalParams.getParserParams().useSyntaxFeatures) {
				for (Path taPath : Arrays.asList(
						new Path(s, s.getCollapsedDeps(), tHead, aHead,
								NodeType.POS, EdgeType.DIRECTION),
						new Path(s, s.getCollapsedDeps(), tHead, aHead,
						    NodeType.NONE, EdgeType.DEP))) {
					String l1 = "len(taPath)=" + (taPath.size() < 10
							? taPath.size() : "long");
					String l2 = "deltaLen(taPath)="
							+ (Math.abs(taPath.deltaDepth()) <= 5
							? taPath.deltaDepth()
									: (taPath.deltaDepth() < 0
											? "-long" : "+long"));
					b(v, r, d, l1);
					b(v, r, d, l1, frame);
					b(v, r, d, l2);
					b(v, r, d, l2, frame);
					if (taPath.size() <= 5) {
						b(v, r, d, "taPath=" + taPath.getPath());
						b(v, r, d, "taPath=" + taPath.getPath(), frame);
					}
				}
			}
		}

		private static Counts<String> between(int i, int j, Sentence s) {
			Counts<String> c = new Counts<>();
			for (int idx = i; idx <= j; idx++) {
				String pos = s.getPos(idx);
				c.increment(pos);
				c.increment(pos.substring(0, 1));
			}
			return c;
		}

		private static String discretizeDist(int w) {
			if (w > 100)
				return ">100";
			return String.valueOf((int) FastMath.pow(w, 0.6f));
		}
	}
   */
}