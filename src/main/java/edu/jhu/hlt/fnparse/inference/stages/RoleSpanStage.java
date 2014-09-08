package edu.jhu.hlt.fnparse.inference.stages;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.BasicRoleSpanFeatures;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.features.Refinements;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.spans.ExpansionVar;
import edu.jhu.hlt.fnparse.util.HasFgModel;

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
			System.err.println("[RoleSpanStage] WARNING: this code does not "
					+ "implement latent syntax yet");
		}
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

		private final List<ExpansionVar> expansions;
		private final FNParse onlyHeads;
		private final FNParse gold;
		private final RoleSpanStage parent;

		/** constructor for when you don't have the labels */
		public RoleSpanStageDatum(FNParse onlyHeads, RoleSpanStage rss) {
			this(onlyHeads, null, rss);
		}

		/** constructor for when you have the labels */
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
			for(int fiIdx=0; fiIdx<F; fiIdx++) {
				FrameInstance fi = onlyHeads.getFrameInstance(fiIdx);
				assert gold == null || fi.getFrame() == gold.getFrameInstance(fiIdx).getFrame();
				assert gold == null || fi.getTarget() == gold.getFrameInstance(fiIdx).getTarget();
				//assert fi.getTarget().width() == 1;
				//int i = fi.getTarget().start;
				int i = parent.params.globalParams.headFinder.head(fi.getTarget(), fi.getSentence());
				int K = fi.getFrame().numRoles();
				for(int k=0; k<K; k++) {
					Span h = fi.getArgument(k);
					if(h == Span.nullSpan) continue;
					assert h.width() == 1;
					int j = h.start;
					Span goldSpan = gold == null
							? null
							: gold.getFrameInstance(fiIdx).getArgument(k);
					addExpansionVar(i, fiIdx, j, k, goldSpan);
				}
			}
		}

		private void addExpansionVar(
				int i,
				int fiIdx,
				int j,
				int k,
				Span goldSpan) {

			// make sure expanding right/left wouldn't overlap the target
			int maxLeft = parent.params.maxArgRoleExpandLeft;
			int maxRight = parent.params.maxArgRoleExpandRight;
			if(j > i && j - parent.params.maxArgRoleExpandLeft >= i)
				maxLeft = j - i;
			if(j < i && j + parent.params.maxArgRoleExpandRight > i)
				maxRight = i - j;

			int n = this.onlyHeads.getSentence().size();
			Expansion.Iter ei = new Expansion.Iter(j, n, maxLeft, maxRight);
			ExpansionVar ev = new ExpansionVar(i, fiIdx, j, k, this.onlyHeads, ei, goldSpan);
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


	
	/**
	 * 
	 * @author travis
	 *
	 */
	public static class RoleSpanDecodable extends Decodable<FNParse> {

		// indexing for these is the same as the loop order in which you would see non-null roles
		private FNParse onlyHeads;
		private List<ExpansionVar> vars;

		public RoleSpanDecodable(FactorGraph fg, FgInferencerFactory infFact, HasFgModel hasModel, FNParse onlyHeads, List<ExpansionVar> vars) {
			super(fg, infFact, hasModel);
			this.onlyHeads = onlyHeads;
			this.vars = vars;
		}

		@Override
		public FNParse decode() {
			
			// run inference
			FgInferencer margins = this.getMargins();
			
			// clone the FrameInstances
			List<FrameInstance> fis = new ArrayList<>();
			for(FrameInstance fi : onlyHeads.getFrameInstances())
				fis.add(fi.clone());

			// update the width-1 arguments as necessary
			for(ExpansionVar ev : this.vars) {
				Span s = ev.decodeSpan(margins);
				fis.get(ev.fiIdx).setArgument(ev.getRole(), s);
			}

			return new FNParse(onlyHeads.getSentence(), fis);
		}
	}


}
