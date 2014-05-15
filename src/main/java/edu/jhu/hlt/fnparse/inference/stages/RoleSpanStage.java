package edu.jhu.hlt.fnparse.inference.stages;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var;
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
import edu.jhu.util.Alphabet;

public class RoleSpanStage extends AbstractStage<FNParse, FNParse> implements Stage<FNParse, FNParse> {
	
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
			factorTemplate = new RoleSpanFactorFactory(params.featAlph);
			this.globalParams = params;
		}
	}
	
	public Params params;
	
	public RoleSpanStage(ParserParams globalParams) {
		super(globalParams);
		params = new Params(globalParams);
	}


	@Override
	public StageDatumExampleList<FNParse, FNParse> setupInference(List<? extends FNParse> onlyHeads, List<? extends FNParse> labels) {
		List<StageDatum<FNParse, FNParse>> data = new ArrayList<>();
		int n = onlyHeads.size();
		assert labels == null || labels.size() == n;
		for(int i=0; i<n; i++) {
			FNParse x = onlyHeads.get(i);
			if(labels == null)
				data.add(new RoleSpanStageDatum(x, params, weights));
			else
				data.add(new RoleSpanStageDatum(x, labels.get(i), params, weights));
		}
		return new StageDatumExampleList<>(data);
	}

	

	/**
	 * 
	 * @author travis
	 *
	 */
	public static class RoleSpanFactorFactory implements FactorFactory<ExpansionVar> {

		private static final long serialVersionUID = 1L;
		
		private Features.RE features;
		private Refinements refs;
		
		public RoleSpanFactorFactory(Alphabet<String> featAlph) {
			features = new BasicRoleSpanFeatures(featAlph);
			refs = new Refinements("r_itjk^e~1");
		}

		@Override
		public List<Features> getFeatures() {
			return Arrays.asList((Features) features);
		}

		@Override
		public List<Factor> initFactorsFor(Sentence s, List<ExpansionVar> inThisSentence, ProjDepTreeFactor l) {
			List<Factor> factors = new ArrayList<>();
			for(ExpansionVar ev : inThisSentence) {
				
				// r_itjk^e ~ 1
				int n = ev.values.size();
				ExplicitExpFamFactor phi = new ExplicitExpFamFactor(new VarSet(ev.var));
				for(int i=0; i<n; i++) {
					Span a = ev.getSpan(i);
					FeatureVector fv = new FeatureVector();
					features.featurize(fv, refs, ev.i, ev.getFrame(), ev.j, ev.k, a, s);
					phi.setFeatures(i, fv);
				}
				factors.add(phi);
			}
			return factors;
		}
	}
	
	
	
	/**
	 * 
	 * @author travis
	 */
	public static class RoleSpanStageDatum implements StageDatum<FNParse, FNParse> {
		
		private List<ExpansionVar> expansions;
		private FNParse onlyHeads;
		private FNParse gold;
		private final Params params;
		private final FgModel weights;

		/** constructor for when you don't have the labels */
		public RoleSpanStageDatum(FNParse onlyHeads, Params params, FgModel weights) {
			this(onlyHeads, null, params, weights);
		}

		/** constructor for when you have the labels */
		public RoleSpanStageDatum(FNParse onlyHeads, FNParse gold, Params params, FgModel weights) {
			this.params = params;
			this.gold = gold;
			this.onlyHeads = onlyHeads;
			this.weights = weights;
			this.expansions = new ArrayList<>();
			int F = onlyHeads.getFrameInstances().size();
			assert gold == null || F == gold.getFrameInstances().size();
			for(int fiIdx=0; fiIdx<F; fiIdx++) {
				FrameInstance fi = onlyHeads.getFrameInstance(fiIdx);
				assert gold == null || fi.getFrame() == gold.getFrameInstance(fiIdx).getFrame();
				assert gold == null || fi.getTarget() == gold.getFrameInstance(fiIdx).getTarget();
				//assert fi.getTarget().width() == 1;
				//int i = fi.getTarget().start;
				int i = params.globalParams.headFinder.head(fi.getTarget(), fi.getSentence());
				int K = fi.getFrame().numRoles();
				for(int k=0; k<K; k++) {
					Span h = fi.getArgument(k);
					if(h == Span.nullSpan) continue;
					assert h.width() == 1;
					int j = h.start;
					Span goldSpan = gold == null ? null : gold.getFrameInstance(fiIdx).getArgument(k);
					addExpansionVar(i, fiIdx, j, k, goldSpan);
				}
			}
		}

		private void addExpansionVar(int i, int fiIdx, int j, int k, Span goldSpan) {

			// make sure expanding right/left wouldn't overlap the target
			int maxLeft = params.maxArgRoleExpandLeft;
			int maxRight = params.maxArgRoleExpandRight;
			if(j > i && j - params.maxArgRoleExpandLeft >= i)
				maxLeft = j - i;
			if(j < i && j + params.maxArgRoleExpandRight > i)
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
			for(Factor f : params.factorTemplate.initFactorsFor(getSentence(), expansions, null))
				fg.addFactor(f);
			// TODO constituency tree factors
			return fg;
		}

		@Override
		public Decodable<FNParse> getDecodable(FgInferencerFactory infFact) {
			FactorGraph fg = this.getFactorGraph();
			return new RoleSpanDecodable(fg, infFact, weights, params.globalParams.logDomain, onlyHeads, expansions);
		}
	}


	
	/**
	 * 
	 * @author travis
	 *
	 */
	public static class ExpansionVar {

		private static final int UNLABELED = -1;
		private static final int PRUNED_EXPANSION = -2;
		private static final int FAlSE_POS_ARG = -3;

		public final int i;
		public final int fiIdx;
		public final int j;
		public final int k;
		public final FNParse onlyHeads;
		private Var var;
		private Expansion.Iter values;
		private int goldIdx;
		
		public ExpansionVar(int i, int fiIdx, int j, int k, FNParse onlyHeads, Expansion.Iter values, Span goldSpan) {
			this.i = i;
			this.fiIdx = fiIdx;
			this.j = j;
			this.k = k;
			this.onlyHeads = onlyHeads;
			this.values = values;
			if(goldSpan == null)
				this.goldIdx = UNLABELED;
			else if(goldSpan == Span.nullSpan)
				this.goldIdx = FAlSE_POS_ARG;
			else {
				Expansion e = Expansion.headToSpan(j, goldSpan);
				int ei = values.indexOf(e);
				if(ei < 0)
					this.goldIdx = PRUNED_EXPANSION;
				else
					this.goldIdx = ei;
			}
			String name = String.format("r_{i=%d,t=%s,j=%d,k=%d}^e", i, getFrame().getName(), j, k);
			this.var = new Var(VarType.PREDICTED, values.size(), name, null);
		}
		
		public int getTargetHeadIdx() { return i; }
		public Frame getFrame() { return onlyHeads.getFrameInstance(fiIdx).getFrame(); }
		public int getArgHeadIdx() { return j; }
		public int getRole() { return k; }
		
		public boolean hasGold() {
			return goldIdx != UNLABELED;
		}
		
		public void addToGoldConfig(VarConfig goldConf) {
			assert hasGold();
			int i = this.goldIdx;
			// TODO
			// if i == PRUNED_EXPANSION, don't train on this example
			// if i == FALSE_POS_ARG, don't train on this example
			if(i < 0)
				i = values.indexOf(Expansion.headToSpan(j, Span.widthOne(j)));
			goldConf.put(this.var, i);
		}
		
		public Span getSpan(int configIdx) {
			Expansion e = values.get(configIdx);
			return e.upon(j);
		}

		public Span getGoldSpan() {
			assert goldIdx >= 0;
			Expansion e = values.get(goldIdx);
			return e.upon(j);
		}
		
		public Span decodeSpan(FgInferencer hasMargins) {
			DenseFactor df = hasMargins.getMarginals(var);
			return getSpan(df.getArgmaxConfigId());
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

		public RoleSpanDecodable(FactorGraph fg, FgInferencerFactory infFact, FgModel weights, boolean logDomain, FNParse onlyHeads, List<ExpansionVar> vars) {
			super(fg, infFact, weights, logDomain);
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
