package edu.jhu.hlt.fnparse.inference.latentConstituents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.math3.util.FastMath;
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
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.features.Path;
import edu.jhu.hlt.fnparse.features.Path.EdgeType;
import edu.jhu.hlt.fnparse.features.Path.NodeType;
import edu.jhu.hlt.fnparse.features.Refinements;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.HasParserParams;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.Counts;
import edu.jhu.hlt.fnparse.util.HasFgModel;
import edu.jhu.hlt.fnparse.util.PosPatternGenerator;
import edu.jhu.hlt.fnparse.util.PosPatternGenerator.Mode;
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
 *    to that frame.
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
 * @author travis
 */
public class RoleSpanPruningStage
		extends AbstractStage<FNTagging, AlmostFNParse> {
	private static final long serialVersionUID = 1L;
	public static final Logger LOG = Logger.getLogger(RoleSpanPruningStage.class);

	// Features that say whether a span might be an arg to a frame.
	private FrameSpanRolePruningFeatures features;

	// Takes a bunch of JointRoleSpanStageDatum and decides which to prune
	private ApproxF1MbrDecoder decoder;

	private transient Regularizer regularizer;

	public RoleSpanPruningStage(ParserParams params) {
		super(params);
		features = new FrameSpanRolePruningFeatures(params);
		decoder = new ApproxF1MbrDecoder(params.logDomain, Math.exp(-8d));
		regularizer = new L2(1_000_000d);
	}

	@Override
	public Double getLearningRate() {
		return null;
	}

	@Override
	public int getNumTrainingPasses() {
		return 4;
	}

	@Override
	public Regularizer getRegularizer() {
		return regularizer;
	}

	@Override
	public StageDatumExampleList<FNTagging, AlmostFNParse> setupInference(
			List<? extends FNTagging> input,
			List<? extends AlmostFNParse> output) {
		List<StageDatum<FNTagging, AlmostFNParse>> data = new ArrayList<>();
		for (int i = 0; i < input.size(); i++) {
			AlmostFNParse g = output == null ? null : output.get(i);
			data.add(new JointRoleSpanStageDatum(input.get(i), g, this));
		}
		return new StageDatumExampleList<>(data);
	}

	/**
	 * A variable that indicates whether a particular span could potentially be
	 * an argument to a given frame.
	 * 
	 * @author travis
	 */
	static class RolePruningVar extends Var {
		private static final long serialVersionUID = 1L;
		public final Frame frame;
		public final Span target;
		public final Span arg;
		private boolean hasGold, gold;  // True if this span should be pruned
		public RolePruningVar(Span arg, FrameInstance fi) {
			this(arg, fi.getFrame(), fi.getTarget());
		}
		public RolePruningVar(Span arg, Frame frame, Span target) {
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
	}

	/**
	 * An example for this stage which holds a latent constituency tree and a
	 * bunch of pruning variables for the roles/args.
	 *
	 * This produces an AlmostFNParse which stores the frames that are allowable
	 * for every (frame,target).
	 *
	 * @author travis
	 */
	static class JointRoleSpanStageDatum
			implements StageDatum<FNTagging, AlmostFNParse> {
		private FNTagging input;
		private AlmostFNParse gold;
		private RoleSpanPruningStage parent;

		public JointRoleSpanStageDatum(
				FNTagging input,
				AlmostFNParse gold,
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
		public AlmostFNParse getGold() {
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

		private void build(FactorGraph fg, VarConfig goldConf, Collection<RolePruningVar> roleVars) {
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
						RolePruningVar p = new RolePruningVar(
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
				RolePruningVar p,
				ConstituencyTreeFactor cykPhi) {
			Var c = cykPhi.getSpanVar(p.arg.start, p.arg.end - 1);  // Matt's args are inclusive
			VarSet vs;
			if (c == null) {
				if (p.arg.width() != 1)
					LOG.info("null constituent var for: " + p.arg);
				vs = new VarSet(p);
			} else {
				vs = new VarSet(p, c);
			}
			ExplicitExpFamFactor phi = new ExplicitExpFamFactor(vs);
			for (int i = 0; i < vs.calcNumConfigs(); i++) {
				VarConfig conf = vs.getVarConfig(i);
				boolean prune = BinaryVarUtil.configToBool(conf.getState(p));
				boolean constituent = c == null
						? false : (conf.getState(c) == SpanVar.TRUE);
				FeatureVector fv = new FeatureVector();
				parent.features.featurize(fv, Refinements.noRefinements,
						p, prune, constituent, input.getSentence());
				phi.setFeatures(i, fv);
			}
			return phi;
		}

		@Override
		public IDecodable<AlmostFNParse> getDecodable() {
			FactorGraph fg = new FactorGraph();
			List<RolePruningVar> roleVars = new ArrayList<>();
			build(fg, null, roleVars);
			if (roleVars.size() == 0) {
				// If there are no frames, and thus no roles, then return an
				// empty decodable.
				final AlmostFNParse empty = new AlmostFNParse(
						input.getSentence(),
						Collections.<FrameInstance>emptyList(),
						Collections.<FrameInstance, List<Span>>emptyMap());
				return new IDecodable<AlmostFNParse>() {
					@Override
					public AlmostFNParse decode() {
						return empty;
					}
				};
			} else {
				return new FrameSpanRoleDecodable(fg, parent.infFactory(),
						parent, input, roleVars, parent.decoder);
			}
		}
	}

	static class FrameSpanRoleDecodable extends Decodable<AlmostFNParse> {
		// Each variable says whether to prune a particular span for a given
		// (frame,target).
		private List<RolePruningVar> roleVars;
		private FNTagging input;
		private ApproxF1MbrDecoder decoder;
		public FrameSpanRoleDecodable(
				FactorGraph fg,
				FgInferencerFactory infFact,
				HasFgModel weights,
				FNTagging input,
				List<RolePruningVar> roleVars,
				ApproxF1MbrDecoder decoder) {
			super(fg, infFact, weights);
			this.input = input;
			this.roleVars = roleVars;
			this.decoder = decoder;
			if (roleVars == null || roleVars.size() == 0)
				throw new IllegalArgumentException();
		}
		@Override
		public AlmostFNParse decode() {
			FgInferencer inf = this.getMargins();
			Map<FrameInstance, List<Span>> kept = new HashMap<>();
			int pruned = 0, considered = 0;
			for (RolePruningVar rpv : roleVars) {
				DenseFactor df = inf.getMarginals(rpv);
				int y = decoder.decode(
						df.getValues(), BinaryVarUtil.boolToConfig(false));
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

			return new AlmostFNParse(
					input.getSentence(), input.getFrameInstances(), kept);
		}
	}

	/**
	 * Features that describe if a particular span could be an argument to a
	 * given frame.
	 *
	 * @author travis
	 */
	static class FrameSpanRolePruningFeatures
			extends AbstractFeatures<FrameSpanRolePruningFeatures> {
		private static final long serialVersionUID = 1L;

		// TODO remove this and make sure that the recallBias in the decoder
		// is working in the correct direction
		boolean debugOverfit = false;

		public FrameSpanRolePruningFeatures(HasParserParams globalParams) {
			super(globalParams);
		}

		public void featurize(
				FeatureVector v,
				Refinements r,
				RolePruningVar p,
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

			if (debugOverfit) {
				b(v, r, d, s.getId(), p.arg.toString(), p.frame.getName(), p.target.toString());
			} else {
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

				String lPos = "lPos=" + AbstractFeatures.getLUSafe(p.arg.start-1, s).pos;
				String rPos = "rPos=" + AbstractFeatures.getLUSafe(p.arg.end, s).pos;
				String sPos = "sPos=" + s.getPos(p.arg.start);
				String ePos = "sPos=" + s.getPos(p.arg.end - 1);
				String hPos = "hPos=" + s.getPos(globalParams.getParserParams().headFinder.head(p.arg, s));
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
				String posPat = new PosPatternGenerator(1, 1, Mode.COARSE_POS).extract(p.arg, s);
				b(v, r, d, posPat);
				b(v, r, d, posPat, frame);
				String shapePat = new PosPatternGenerator(0, 0, Mode.WORD_SHAPE).extract(p.arg, s);
				b(v, r, d, shapePat);
				b(v, r, d, shapePat, frame);

				for (Path taPath : Arrays.asList(
						new Path(s, tHead, aHead, NodeType.POS, EdgeType.DIRECTION),
						new Path(s, tHead, aHead, NodeType.NONE, EdgeType.DEP))) {
					String l1 = "len(taPath)=" + (taPath.size() < 10 ? taPath.size() : "long");
					String l2 = "deltaLen(taPath)=" + (Math.abs(taPath.deltaDepth()) <= 5
							? taPath.deltaDepth() : (taPath.deltaDepth() < 0 ? "-long" : "+long"));
					b(v, r, d, l1);
					b(v, r, d, l1, frame);
					b(v, r, d, l2);
					b(v, r, d, l2, frame);
					if (taPath.size() <= 6) {
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
}
