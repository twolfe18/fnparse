package edu.jhu.hlt.fnparse.inference.latentConstituents;

import java.util.ArrayList;
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
import edu.jhu.hlt.fnparse.features.BasicRoleSpanFeatures;
import edu.jhu.hlt.fnparse.features.Refinements;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.HasFgModel;

/**
 * This stage takes a list of frame instances, each of which has a pruned set of
 * spans which could hold their arguments, and chooses the roles that are
 * realized for every given frame-role.
 * 
 * @author travis
 */
public class RoleSpanLabelingStage
		extends AbstractStage<AlmostFNParse, FNParse> {
	private static final long serialVersionUID = 1L;
	public static final Logger LOG =
			Logger.getLogger(RoleSpanLabelingStage.class);

	private BasicRoleSpanFeatures features;

	public RoleSpanLabelingStage(ParserParams params) {
		super(params);
		features = new BasicRoleSpanFeatures(params);
	}

	@Override
	public StageDatumExampleList<AlmostFNParse, FNParse> setupInference(
			List<? extends AlmostFNParse> input,
			List<? extends FNParse> output) {
		List<StageDatum<AlmostFNParse, FNParse>> data = new ArrayList<>();
		for (int i = 0; i < input.size(); i++) {
			FNParse gold = output == null ? null : output.get(i);
			data.add(new RoleSpanLabellingStageDatum(input.get(i), gold, this));
		}
		return new StageDatumExampleList<>(data);
	}

	/**
	 * FOR NOW: no latent syntax, just have one binary var for every
	 * (frame, role, span)
	 * NOTE: do not be tempted to make a k-ary variable for every (frame,role)
	 * because then i wont be able to hook up latent syntax binary factors later.
	 * NOTE: don't bother with an Exactly1 factor on (frame,role,*) because the
	 * decoder will enforce this.
	 * 
	 * @author travis
	 */
	// This is a conditional model, so we really should train this on the
	// pruned set of arg span options instead of the full set.
	// This means that if we prune the gold answer, we should not train on that
	// example.
	// DON'T DO THIS: ANOTHER OPTION: take all of the remaining negative spans
	// and still train with them as negative instances.
	static class RoleSpanLabellingStageDatum
			implements StageDatum<AlmostFNParse, FNParse> {
		private final AlmostFNParse input;
		private final FNParse gold;
		private final RoleSpanLabelingStage parent;

		public RoleSpanLabellingStageDatum(
				AlmostFNParse input,
				FNParse gold,
				RoleSpanLabelingStage parent) {
			this.input = input;
			this.gold = gold;
			this.parent = parent;
		}

		@Override
		public AlmostFNParse getInput() {
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

		private void getFactorGraph(
				FactorGraph fg,
				VarConfig goldConf,
				Collection<ArgVar> vars) {
			FeatureVector zero = new FeatureVector();
			Sentence s = input.getSentence();
			for (int i = 0; i < input.numFrameInstances(); i++) {
				Frame f = input.getFrame(i);
				Span target = input.getTarget(i);
				int targetHeadIdx = parent.globalParams.headFinder.head(
						target, input.getSentence());
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
							continue;
						}
					}
					for (Span arg : input.getPossibleArgs(i)) {
						ArgVar argVar = new ArgVar(arg, f, target, k);
						if (vars != null)
							vars.add(argVar);
						ExplicitExpFamFactor phi =
								new ExplicitExpFamFactor(new VarSet(argVar));
						FeatureVector fv = new FeatureVector();
						int argHeadIdx = -1;
						if (arg != Span.nullSpan) {
							argHeadIdx =
									parent.globalParams.headFinder.head(arg, s);
						}
						parent.features.featurize(fv,
								Refinements.noRefinements,
								targetHeadIdx, f, argHeadIdx, k, arg, s);
						phi.setFeatures(BinaryVarUtil.boolToConfig(true), fv);
						phi.setFeatures(BinaryVarUtil.boolToConfig(false), zero);
						fg.addFactor(phi);
						if (goldArg != null) {
							boolean g = goldArg.equals(argVar.arg);
							argVar.setGold(g);
							if (goldConf != null) {
								goldConf.put(argVar,
										BinaryVarUtil.boolToConfig(g));
							}
						}
					}
				}
			}
		}

		@Override
		public LabeledFgExample getExample() {
			VarConfig gold = new VarConfig();
			FactorGraph fg = new FactorGraph();
			getFactorGraph(fg, gold, null);
			return new LabeledFgExample(fg, gold);
		}

		@Override
		public IDecodable<FNParse> getDecodable() {
			Collection<ArgVar> vars = new ArrayList<>();
			FactorGraph fg = new FactorGraph();
			getFactorGraph(fg, null, vars);
			return new Decoder(
					fg, parent.infFactory(), parent, vars, input.getSentence());
		}
	}

	/**
	 * A variable that represents a binary question of whether a given span is
	 * an argument for a particular (frame,role).
	 * 
	 * @author travis
	 */
	static class ArgVar extends RoleSpanPruningStage.RolePruningVar {
		private static final long serialVersionUID = 1L;
		public final int role;
		public final Span arg;
		public ArgVar(Span arg, Frame frame, Span target, int role) {
			super(arg, frame, target);
			this.role = role;
			this.arg = arg;
		}
	}

	static class Decoder extends Decodable<FNParse> {
		private Map<FrameRoleInstance, List<ArgVar>> vars;
		private Sentence sentence;

		public Decoder(
				FactorGraph fg,
				FgInferencerFactory infFact,
				HasFgModel weights,
				Collection<ArgVar> vars,
				Sentence sentence) {
			super(fg, infFact, weights);
			this.sentence = sentence;

			// Index span variables by (frame,target,role)
			this.vars = new HashMap<>();
			for (ArgVar a : vars) {
				FrameRoleInstance key =
						new FrameRoleInstance(a.frame, a.target, a.role);
				List<ArgVar> x = this.vars.get(key);
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
			for (Map.Entry<FrameRoleInstance, List<ArgVar>> x : vars.entrySet()) {
				FrameRoleInstance ftr = x.getKey();
				FrameInstance key = FrameInstance.frameMention(
						ftr.frame, ftr.target, sentence);
				Span[] all = bestArgs.get(key);
				if (all == null) {
					all = new Span[ftr.frame.numRoles()];
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

		private Span argmax(Collection<ArgVar> vars) {
			FgInferencer inf = this.getMargins();
			Span best = null;
			double bestB = 0d;
			for (ArgVar a : vars) {
				DenseFactor df = inf.getMarginals(a);
				df.logNormalize();
				double b = df.getValue(BinaryVarUtil.boolToConfig(true));
				if (best == null || b > bestB) {
					best = a.arg;
					bestB = b;
				}
			}
			return best;
		}
	}
}
