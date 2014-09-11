package edu.jhu.hlt.fnparse.inference.stages;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.BasicRoleSpanFeatures;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.features.Refinements;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.spans.ExpansionVar;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.HasFgModel;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.L2;

public class RoleSpanStage
		extends AbstractStage<FNParse, FNParse>
		implements Stage<FNParse, FNParse>, Serializable {
	
	public static class Params implements Serializable {
		private static final long serialVersionUID = 1L;

		// if you check the pareto frontier in ExpansionPruningExperiment:
		// (6,0) gives 77.9 % recall
		// (6,3) gives 86.7 % recall
		// (8,3) gives 88.4 % recall
		// (8,4) gives 90.0 % recall
		// (10,5) gives 92.3 % recall
		// (12,5) gives 93.2 % recall
		public int maxArgRoleExpandLeft = 10;
		public int maxArgRoleExpandRight = 5;

		public Double learningRate = 0.05;	// null means auto-select
		public Regularizer regularizer = new L2(1000d);
		public int batchSize = 4;
		public int passes = 2;

		// TODO Move this to RoleIdStage
		// If true, when training the expansion stage, targets which do not
		// correspond to a target in the gold label will have their argument
		// gold labels set to nullSpan (as if the frames were present with no
		// arguments realized in the sentence). In principle this allows this
		// stage of the pipeline to adapt to errors made in the frameId stage,
		// but this may also distort the model to get the wrong answer when the
		// frameId stage was correct.
		// If false, then targets that are not present in the gold label are
		// simply dropped, and argument variables are not instantianted/added to
		// the training set.
		// NOTE: If you use gold frameId to train this stage, then this option
		// has no effect one way or the other because every target will be in
		// the gold label.
		//public boolean useNullSpanForArgumentsToIncorrectTarget = false;
		
		public FactorFactory<ExpansionVar> factorTemplate;
		public ParserParams globalParams;

		public Params(ParserParams params) {
			factorTemplate = new RoleSpanFactorFactory(params);
			this.globalParams = params;
		}
	}

	private static final long serialVersionUID = 1L;
	public Params params;

	public RoleSpanStage(ParserParams globalParams) {
		super(globalParams);
		params = new Params(globalParams);
		if(globalParams.useLatentDepenencies
				|| globalParams.useLatentConstituencies) {
			log.warn("This code does not implement latent syntax yet");
		}
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
	public StageDatumExampleList<FNParse, FNParse> setupInference(
			List<? extends FNParse> onlyHeads,
			List<? extends FNParse> labels) {
		List<StageDatum<FNParse, FNParse>> data = new ArrayList<>();
		int n = onlyHeads.size();
		assert labels == null || labels.size() == n;
		for(int i=0; i<n; i++) {
			FNParse x = onlyHeads.get(i);
			if(labels == null)
				data.add(new RoleSpanStageDatum(x, this));
			else
				data.add(new RoleSpanStageDatum(x, labels.get(i), this));
		}
		return new StageDatumExampleList<>(data);
	}

	/**
	 * 
	 * @author travis
	 */
	public static class RoleSpanFactorFactory
			implements FactorFactory<ExpansionVar> {

		private static final long serialVersionUID = 1L;

		private final Features.RE features;
		private final Refinements refs;
		private final ParserParams params;

		public RoleSpanFactorFactory(ParserParams params) {
			features = new BasicRoleSpanFeatures(params);
			refs = new Refinements("r_itjk^e~1");
			this.params = params;
		}

		@Override
		public List<Features> getFeatures() {
			return Arrays.asList((Features) features);
		}

		@Override
		public List<Factor> initFactorsFor(
				Sentence s,
				List<ExpansionVar> inThisSentence,
				ProjDepTreeFactor d,
				ConstituencyTreeFactor c) {
			List<Factor> factors = new ArrayList<>();
			for(ExpansionVar ev : inThisSentence) {

				// r_itjk^e ~ 1
				int n = ev.values.size();
				ExplicitExpFamFactor phi =
						new ExplicitExpFamFactor(new VarSet(ev.var));
				for(int i=0; i<n; i++) {
					Span a = ev.getSpan(i);
					FeatureVector fv = new FeatureVector();
					features.featurize(
							fv, refs, ev.i, ev.getFrame(), ev.j, ev.k, a, s);
					phi.setFeatures(i, fv);
				}
				factors.add(phi);

				// r_itjk^e ~ l_mn
				if(params.useLatentConstituencies) {
					assert c != null;
					throw new RuntimeException(
							"go get code from PairedExactly1");	// TODO
				}

			}
			return factors;
		}
	}
	
	
	/**
	 * 
	 * @author travis
	 */
	public static class RoleSpanStageDatum
			implements StageDatum<FNParse, FNParse> {
		public static final Logger LOG =
				Logger.getLogger(RoleSpanStageDatum.class);
		static { LOG.setLevel(Level.INFO); }

		private final List<ExpansionVar> expansions;
		private final FNParse onlyHeads;
		private final FNParse gold;
		private final RoleSpanStage parent;

		/** Constructor for when you don't have the labels. */
		public RoleSpanStageDatum(FNParse onlyHeads, RoleSpanStage rss) {
			this(onlyHeads, null, rss);
		}

		/**
		 * Constructor for when you have the labels.
		 * 
		 * This rolls out argument variables for the roles of every
		 * FrameInstance in onlyHeads. If gold is null (i.e. we are doing
		 * prediction), this statement is unqualified. If gold is not null
		 * (i.e. we are doing training), then the set of argument variables
		 * created depends on the overlap of FrameInstances in onlyHeads and
		 * gold.
		 * 
		 * For FrameInstances that are common to both onlyHeads and gold, it is
		 * clear that the gold labels for the role variables should be the
		 * values of the role variables from the corresponding FrameInstance
		 * in gold.
		 * 
		 * For FrameInstances in onlyHeads that are not present in gold, there
		 * are two options:
		 * 1) don't roll out argument variables for these FrameInstances
		 * 2) roll them out with gold values of nullSpan (which typically
		 *    results in higher precision awarded by the evaluation function) 
		 * 
		 * Note that if you train with gold frameId, then onlyHeads == gold, and
		 * these problems go away.
		 * 
		 * TODO This needs to be re-written. It was written thinking that I was
		 * doing argId rather than argExpansion. The general notion that is
		 * described is "is there a viable theory in the input so far that
		 * matches the gold label?". In the argId case, the "viable theory" is a
		 * FrameInstance (target). In the case of argExpansion, the "viable
		 * theory" is (it=FrameInstance,k=role,j=head). To check whether it
		 * matches the gold label requires checking if there exists and span for
		 * itk and if j is in that span.
		 */
		public RoleSpanStageDatum(
				FNParse onlyHeads,
				FNParse gold,
				RoleSpanStage rss) {
			this.parent = rss;
			this.gold = gold;
			this.onlyHeads = onlyHeads;
			this.expansions = new ArrayList<>();
			int F = onlyHeads.getFrameInstances().size();
			assert gold == null || F == gold.getFrameInstances().size();

			// Build a map of the correct expansions for all arguments that are
			// present in the gold parse.
			Map<FrameRoleInstance, Span> goldSpans = new HashMap<>();
			if (gold != null) {
				for (FrameInstance fi : gold.getFrameInstances()) {
					int K = fi.getFrame().numRoles();
					for (int k = 0; k < K; k++) {
						Span arg = fi.getArgument(k);
						if (arg == Span.nullSpan) continue;
						FrameRoleInstance fri = new FrameRoleInstance(
								fi.getFrame(), fi.getTarget(), k);
						Span old = goldSpans.put(fri, arg);
						assert old == null;
					}
				}
			}

			// Go over all the arguments in onlyHeads and roll out expansion
			// variables for them.
			for (int fiIdx = 0; fiIdx < onlyHeads.numFrameInstances(); fiIdx++) {
				FrameInstance fi = onlyHeads.getFrameInstance(fiIdx);
				LOG.debug("roles for " + Describe.frameInstance(fi));
				Frame f = fi.getFrame();
				Span t = fi.getTarget();
				int ti = parent.params.globalParams.headFinder.head(
						t, fi.getSentence());
				int K = fi.getFrame().numRoles();
				for (int k = 0; k < K; k++) {
					Span arg = fi.getArgument(k);
					if (arg == Span.nullSpan) continue;
					assert arg.width() == 1;	// head only, we're expanding
					int j = arg.start;
					Span goldArg = goldSpans.get(new FrameRoleInstance(f, t, k));
					// Here, unlike argId, we have no way to "recover" from a
					// bad prediction made earlier in the pipeline. At this
					// point, we are assuming that there is an argument, and the
					// only question is how wide is the constituent (i.e. you
					// can't "expand" an argument out of existence like you).
					// So, if we can't find a viable theory (i.e. argument head)
					// to expand upon, then we should not train on it at all.
					// If we're in prediction mode then this doesn't matter and
					// we're going to roll out the variables anyway.
					boolean makeExpansionVar =
							(gold == null)
							|| (goldArg != null && goldArg.includes(j));
					if (!makeExpansionVar) continue;
					addExpansionVar(ti, fiIdx, j, k, goldArg);
				}
			}
		}

		private static class FrameRoleInstance {
			public final Frame frame;
			public final Span target;
			public final int role;
			public FrameRoleInstance(Frame frame, Span target, int role) {
				if (role >= frame.numRoles())
					throw new IllegalArgumentException();
				this.frame = frame;
				this.target = target;
				this.role = role;
			}
			@Override
			public int hashCode() {
				return 197 * frame.hashCode()
						+ 199 * target.hashCode()
						+ 211 * role;
			}
			@Override
			public boolean equals(Object other) {
				if (other instanceof FrameRoleInstance) {
					FrameRoleInstance fri = (FrameRoleInstance) other;
					return role == fri.role
							&& target.equals(fri.target)
							&& frame.equals(fri.frame);
				}
				return false;
			}
		}

		private void addExpansionVar(
				int i,
				int fiIdx,
				int j,
				int k,
				Span goldSpan) {
			// Make sure expanding right/left wouldn't overlap the target
			int maxLeft = parent.params.maxArgRoleExpandLeft;
			int maxRight = parent.params.maxArgRoleExpandRight;
			if(j > i && j - parent.params.maxArgRoleExpandLeft >= i)
				maxLeft = j - i;
			if(j < i && j + parent.params.maxArgRoleExpandRight > i)
				maxRight = i - j;
			int n = this.onlyHeads.getSentence().size();
			Expansion.Iter ei = new Expansion.Iter(j, n, maxLeft, maxRight);
			int goldExpIdx = -1;
			if (goldSpan != null) {
				Expansion goldExp = Expansion.headToSpan(j, goldSpan);
				goldExpIdx = ei.indexOf(goldExp);
				if (goldExpIdx < 0) return;
			}
			ExpansionVar ev = new ExpansionVar(
					i, fiIdx, j, k, this.onlyHeads, ei, goldExpIdx);
			this.expansions.add(ev);
		}

		@Override
		public FNParse getInput() { return onlyHeads; }

		@Override
		public boolean hasGold() { return gold != null; }

		@Override
		public FNParse getGold() {
			assert hasGold();
			return gold;
		}
		
		public Sentence getSentence() { return onlyHeads.getSentence(); }

		@Override
		public LabeledFgExample getExample() {
			FactorGraph fg = this.getFactorGraph();
			VarConfig goldConf = new VarConfig();
			for(ExpansionVar ev : this.expansions) {
				assert ev.hasGold();
				ev.addToGoldConfig(goldConf);
			}
			return new LabeledFgExample(fg, goldConf);
		}

		private FactorGraph getFactorGraph() {
			FactorGraph fg = new FactorGraph();
			ProjDepTreeFactor depTree = null;
			ConstituencyTreeFactor consTree = null;
			if(parent.params.globalParams.useLatentConstituencies) {
				consTree = new ConstituencyTreeFactor(getSentence().size(), VarType.LATENT);
				fg.addFactor(consTree);
				// TODO add unary factors on constituency vars
			}
			for(Factor f : parent.params.factorTemplate.initFactorsFor(getSentence(), expansions, depTree, consTree))
				fg.addFactor(f);
			return fg;
		}

		@Override
		public Decodable<FNParse> getDecodable() {
			FgInferencerFactory infFact = parent.infFactory();
			return new RoleSpanDecodable(
					getFactorGraph(), infFact, parent, onlyHeads, expansions);
		}
	}

	public static class RoleSpanDecodable extends Decodable<FNParse> {

		// Indexing for these is the same as the loop order in which you would
		// see non-null roles.
		private FNParse onlyHeads;
		private List<ExpansionVar> vars;

		public RoleSpanDecodable(
				FactorGraph fg,
				FgInferencerFactory infFact,
				HasFgModel hasModel,
				FNParse onlyHeads,
				List<ExpansionVar> vars) {
			super(fg, infFact, hasModel);
			this.onlyHeads = onlyHeads;
			this.vars = vars;
		}

		@Override
		public FNParse decode() {
			// Run inference
			FgInferencer margins = this.getMargins();

			// Clone the FrameInstances
			List<FrameInstance> fis = new ArrayList<>();
			for(FrameInstance fi : onlyHeads.getFrameInstances())
				fis.add(fi.clone());

			// Update the width-1 arguments as necessary
			for(ExpansionVar ev : this.vars) {
				Span s = ev.decodeSpan(margins);
				fis.get(ev.fiIdx).setArgument(ev.getRole(), s);
			}

			return new FNParse(onlyHeads.getSentence(), fis);
		}
	}


}
