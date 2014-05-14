package edu.jhu.hlt.fnparse.inference.stages;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.features.BinaryBinaryFactorHelper;
import edu.jhu.hlt.fnparse.inference.ApproxF1MbrDecoder;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.dep.DepParseFactorFactory;
import edu.jhu.hlt.fnparse.inference.pruning.ArgPruner;
import edu.jhu.hlt.fnparse.inference.pruning.IArgPruner;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.inference.roleid.RoleFactorFactory;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars.RVar;
import edu.jhu.hlt.fnparse.util.Timer;

public class RoleIdStage extends AbstractStage<FNTagging, FNParse> implements Stage<FNTagging, FNParse> {
	
	public static class Params implements Serializable {
		private static final long serialVersionUID = 1L;

		public int batchSize = 4;
		public int passes = 1;
		public int threads = 2;
		public int maxSentenceLengthForTraining = 50;
		public IArgPruner argPruner;
		public ApproxF1MbrDecoder decoder;
		public FactorFactory<RoleVars> factorTemplate;
		
		public Params(ParserParams globalParams) {
			BinaryBinaryFactorHelper.Mode rDepMode = globalParams.useLatentDepenencies
					? BinaryBinaryFactorHelper.Mode.ISING : BinaryBinaryFactorHelper.Mode.NONE;
			this.factorTemplate = new RoleFactorFactory(globalParams, rDepMode, false);
			this.argPruner = new ArgPruner(TargetPruningData.getInstance(), globalParams.headFinder);
		}
	}
	
	public Params params;
	public ParserParams globalParams;

	public RoleIdStage(ParserParams globalParams) {
		super(globalParams);
		params = new Params(globalParams);
		this.globalParams = globalParams;
	}
	

	@Override
	public void train(List<FNTagging> x, List<FNParse> y) {

		List<FNTagging> xUse;
		List<FNParse> yUse;
		if(params.maxSentenceLengthForTraining > 0) {
			xUse = new ArrayList<>();
			yUse = new ArrayList<>();
			int n = x.size();
			for(int i=0; i<n; i++) {
				FNTagging xi = x.get(i);
				if(xi.getSentence().size() <= params.maxSentenceLengthForTraining) {
					xUse.add(xi);
					yUse.add(y.get(i));
				}
			}
			System.out.printf("[%s train] filtering out sentences longer than %d words, kept %d of %d\n",
					this.getName(), params.maxSentenceLengthForTraining, xUse.size(), x.size());
		}
		else {
			xUse = x;
			yUse = y;
		}
		
		super.train(xUse, yUse, null, params.batchSize, params.passes);
	}
	
	@Override
	public TuningData getTuningData() {
		final List<Double> biases = new ArrayList<Double>();
		for(double b=0.1d; b<1.5d; b *= 1.1d) biases.add(b);

		return new TuningData() {
			@Override
			public ApproxF1MbrDecoder getDecoder() { return params.decoder; }
			@Override
			public EvalFunc getObjective() { return BasicEvaluation.targetMicroF1; }
			@Override
			public List<Double> getRecallBiasesToSweep() { return biases; }
		};
	}


	@Override
	public StageDatumExampleList<FNTagging, FNParse> setupInference(List<? extends FNTagging> input, List<? extends FNParse> output) {
		List<StageDatum<FNTagging, FNParse>> data = new ArrayList<>();
		int n = input.size();
		assert output == null || output.size() == n;
		for(int i=0; i<n; i++) {
			FNTagging x = input.get(i);
			if(output == null)
				data.add(new RoleIdStageDatum(x, globalParams, params));
			else
				data.add(new RoleIdStageDatum(x, output.get(i), globalParams, params));
		}
		return new StageDatumExampleList<>(data);
	}



	/**
	 * 
	 * @author travis
	 */
	public static class RoleIdStageDatum implements StageDatum<FNTagging, FNParse> {
		
		private final List<RoleVars> roleVars;	// TODO this needs to have modes for roleId, roleExpansion, and joint
		private final FNTagging input;
		private final FNParse gold;
		private final Params params;
		private final ParserParams globalParams;

		/** you don't know gold */
		public RoleIdStageDatum(FNTagging frames, ParserParams globalParams, Params params) {
			this.roleVars = new  ArrayList<>();
			this.input = frames;
			this.gold = null;
			this.globalParams = globalParams;
			this.params = params;
			initHypotheses(frames, null, false);
		}

		/** you know gold */
		public RoleIdStageDatum(FNTagging frames, FNParse gold, ParserParams globalParams, Params params) {
			if(gold == null)
				throw new IllegalArgumentException();
			this.roleVars = new  ArrayList<>();
			this.input = frames;
			this.gold = gold;
			this.globalParams = globalParams;
			this.params = params;
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

			Timer t = globalParams.timer.get("argId-initHypotheses");
			t.start();

			// make sure that we don't have overlapping targets
			frames = DataUtil.filterOutTargetCollisions(frames);

			// build an index keying off of the target head index
			FrameInstance[] fiByTarget = null;
			if(hasGold)
				fiByTarget = DataUtil.getFrameInstancesIndexByHeadword(gold.getFrameInstances(), getSentence(), globalParams.headFinder);

			for(FrameInstance fi : frames.getFrameInstances()) {
				Span target = fi.getTarget();
				int targetHead = globalParams.headFinder.head(target, fi.getSentence());

				RoleVars rv;
				if(hasGold) {	// train mode
					FrameInstance goldFI = fiByTarget[targetHead];
					rv = new RoleVars(goldFI, targetHead, fi.getFrame(), fi.getSentence(), globalParams, params);
				}
				else			// predict/decode mode
					rv = new RoleVars(targetHead, fi.getFrame(), fi.getSentence(), globalParams, params);

				this.roleVars.add(rv);
			}
			t.stop();
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
			if(globalParams.useLatentDepenencies) {
				depTree = new ProjDepTreeFactor(getSentence().size(), VarType.LATENT);
				DepParseFactorFactory depParseFactorTemplate = new DepParseFactorFactory(globalParams);
				factors.addAll(depParseFactorTemplate.initFactorsFor(getSentence(), Collections.emptyList(), depTree));
			}
			factors.addAll(params.factorTemplate.initFactorsFor(getSentence(), roleVars, depTree));

			// add factors to the factor graph
			for(Factor f : factors)
				fg.addFactor(f);
			
			return fg;
		}

		@Override
		public LabeledFgExample getExample() {
			FactorGraph fg = getFactorGraph();
			VarConfig goldConf = new VarConfig();
			
			// add the gold labels
			for(RoleVars hyp : roleVars) {
				hyp.register(fg, goldConf);
			}

			return new LabeledFgExample(fg, goldConf);
		}

		@Override
		public Decodable<FNParse> getDecodable(FgInferencerFactory infFact) {
			return new RoleIdDecodable(getFactorGraph(), infFact, getSentence(), roleVars, globalParams, params);
		}
	}
	
	/**
	 * decodes FNParses which have arguments represented by width-1 spans
	 * 
	 * @author travis
	 */
	public static class RoleIdDecodable extends Decodable<FNParse> {

		private Sentence sent;
		private List<RoleVars> hypotheses;
		private ApproxF1MbrDecoder decoder;
//		private Params params;
		private ParserParams globalParams;
		
		public RoleIdDecodable(FactorGraph fg, FgInferencerFactory infFact, Sentence sent, List<RoleVars> hypotheses, ParserParams globalParams, Params params) {
			super(fg, infFact);
			this.sent = sent;
			this.hypotheses = hypotheses;
			this.globalParams = globalParams;
//			this.params = params;
		}

		@Override
		public FNParse decode() {
			FgInferencer inf = getMargins();
			List<FrameInstance> fis = new ArrayList<FrameInstance>();
			for(RoleVars rv : hypotheses)
				fis.add(decodeRoleVars(rv, inf));
			return new FNParse(sent, fis);
		}
		
		public FrameInstance decodeRoleVars(RoleVars rv, FgInferencer inf) {

			Timer t = globalParams.timer.get("argId-decode", true);
			t.start();

			// max over j for every role
			final int n = sent.size();
			final int K = rv.getFrame().numRoles();
			Span[] arguments = new Span[K];
			Arrays.fill(arguments, Span.nullSpan);
			double[][] beliefs = new double[K][n+1];	// last inner index is "not realized"
			if(globalParams.logDomain) {
				for(int i=0; i<beliefs.length; i++)	// otherwise default is 0
					Arrays.fill(beliefs[i], Double.NEGATIVE_INFINITY);
			}

			Iterator<RVar> iter = rv.getVars();
			while(iter.hasNext()) {
				RVar rvar = iter.next();
				DenseFactor df = inf.getMarginals(rvar.roleVar);
				beliefs[rvar.k][rvar.j] = df.getValue(BinaryVarUtil.boolToConfig(true));
			}
			for(int k=0; k<K; k++) {

				// TODO add Exactly1 factor!
				globalParams.normalize(beliefs[k]);

				int jHat = decoder.decode(beliefs[k], n);
				if(jHat < n) {
					//				if(params.predictHeadValuedArguments)
					arguments[k] = Span.widthOne(jHat);
					//				else {
					//					Var expansionVar = rv.getExpansionVar(jHat, k);
					//					DenseFactor df = inf.getMarginals(expansionVar);
					//					arguments[k] = rv.getArgSpanFor(df.getArgmaxConfigId(), jHat, k);
					//		globalParams}
				}
			}

			if(t != null) t.stop();
			return FrameInstance.newFrameInstance(rv.getFrame(), Span.widthOne(rv.getTargetHead()), arguments, sent);
		}
	}

}
