package edu.jhu.hlt.fnparse.inference;

import java.util.*;

import edu.jhu.gm.data.*;
import edu.jhu.gm.data.FgExampleListBuilder.CacheType;
import edu.jhu.gm.data.FgExampleListBuilder.FgExamplesBuilderPrm;
import edu.jhu.gm.feat.*;
import edu.jhu.gm.model.*;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.features.BasicTargetFeatures;
import edu.jhu.hlt.fnparse.features.BasicTargetRoleFeatures;
import edu.jhu.hlt.fnparse.features.TargetFeatures;
import edu.jhu.hlt.fnparse.features.TargetRoleFeatures;
import edu.jhu.util.Alphabet;

/**
 ******************** Factor graph model that is similar to SEMAFOR ********************
 * 
 * For a sentence with length n, having s spans:
 * 
 * One "target" variable per word in a sentence which describes the frame that
 * is evoked by that target word:
 *   [f_1, f_2, ... f_n]
 * 
 * If the frame which with the most roles has k roles, then we will
 * have k * n "role" variables which describe what span is the realization
 * of this frame (given by the target) and role:
 *   r_{i,j} \in [1, 2, ... s] \forall
 *     i \in [1, 2, ... n]	# word index
 *     j \in [1, 2, ... k]	# role
 * 
 * There will be unary factors on each f_i variable which is comparable
 * to the target identification log-linear model in SEMAFOR.
 * 
 * There will be binary factors that connect each frame target with each of its role variables:
 *   for i in range(n):
 *     for j in range(k):
 *       yield Factor(f_i, r_{i,j})
 * 
 * We will do decoding by starting with sum-product BP, and slowly anneal towards
 * max-product BP (viterbi).
 * 
 * If we want to train (relatively) quickly, as they did with SEMAFOR, we will
 * mimic piecewise training by first learning the target unary factor weights
 * by _not adding the role variables_, and doing maximum conditional likelihood training.
 * When it is time to train the target-role binary factors, we will clamp the target
 * variables at the predicted values, and proceed with maximum conditional likelihood training.
 * 
 * Concerning the constraint that two roles for a given target may not map to the same span,
 * we can first ignore this during training, and do decoding using beam search as in SEMAFOR.
 * A slower way to do this is to add a MutexFactor(r_{i,j}, r_{i,k}) \forall j < k.
 * 
 * Another issue to address is how to do span identification.
 * -> can prune spans in a variety of ways (NER tagger, constituents of parse, up to a certain width, etc).
 *    perhaps these should, in the exact case, be unary factors on target-role variables (factor only considers the span)
 * -> can choose to parameterize a span as (headIdx, leftExpand, rightExpand).
 *    this may mean that we can train more robust (target, role-head) factors/features
 */
public class Semaforic implements FgExampleFactory {
	
	private Frame nullFrame;
	private List<Frame> frames;
	private List<String> frameNames;
	private List<SemaforicSentence> trainInstances;
	private TargetFeatures targetFeatures;
	private TargetRoleFeatures targetRoleFeatures;
	private FgModel model;
	private FactorTemplateList fts = new FactorTemplateList();	// holds factor cliques, just says that there is one factor
	
	/**
	 * train a model
	 */
	public void train(Map<Sentence, List<FrameInstance>> examples, List<Frame> frames, Frame nullFrame) {
		
		this.nullFrame = nullFrame;
		this.frames = frames;
		this.targetFeatures = new BasicTargetFeatures(nullFrame);
		this.targetRoleFeatures = new BasicTargetRoleFeatures(nullFrame);
		this.frameNames = new ArrayList<String>();
		int maxRoles = 0;
		for(Frame f : frames) {
			this.frameNames.add(f.getName());
			if(f.numRoles() > maxRoles)
				maxRoles = f.numRoles();
		}
		
		// construct the SemaforicSentenceFactorGraphs
		this.trainInstances = new ArrayList<SemaforicSentence>();
		for(Map.Entry<Sentence, List<FrameInstance>> x : examples.entrySet()) {
			Sentence s = x.getKey();
			List<FrameInstance> fis = x.getValue();
			trainInstances.add(new SemaforicSentence(s, fis, maxRoles, frameNames, nullFrame, targetFeatures, targetRoleFeatures));
		}
		
		CrfTrainer.CrfTrainerPrm trainerPrm = new CrfTrainer.CrfTrainerPrm();
		CrfTrainer trainer = new CrfTrainer(trainerPrm);
		
		FgExampleFactory exampleFactory = this;
		FgExamplesBuilderPrm prm = new FgExamplesBuilderPrm();
		prm.cacheType = CacheType.MEMORY_STORE;
		FgExampleListBuilder dataBuilder = new FgExampleListBuilder(prm);
		FgExampleList data = dataBuilder.getInstance(fts, exampleFactory);
		
		boolean includeUnsupportedFeatures = true;
		this.model = new FgModel(data, includeUnsupportedFeatures);
		this.model = trainer.train(this.model, data);
	}
	
	
	@Override	// FgExampleFactory
	public FgExample get(int i, FactorTemplateList fts) {
		SemaforicSentence fg = trainInstances.get(i);
		return new FgExample(fg.fg, fg.goldConf, fg, fts);
	}


	@Override	// FgExampleFactory
	public int size() { return trainInstances.size(); }
	
	
	/**
	 * runs inference after you've trained a model
	 */
	public Map<Sentence, List<FrameInstance>> predict(List<Sentence> sentences) {
		throw new RuntimeException("implement me");
	}
	

	/**
	 * Factor for each f_i variable (it observes the word at index i,
	 * as well as all of the other information in the sentence, but
	 * I don't use observed variables because they're not really needed).
	 * @author travis
	 */
	public static class TargetUnaryFactor extends ExpFamFactor {
		private static final long serialVersionUID = 8681081343059187337L;
		public final int targetIdx;
		public TargetUnaryFactor(Var f_i, int i) {
			super(new VarSet(f_i), SemaforicSentence.FactorTemplate.TARGET);
			targetIdx = i;
		}
		// TODO getFeatures will go here when Matt updates his code
	}
	
	/**
	 * Factor that goes between a f_i variable and an r_ij variable
	 * @author travis
	 */
	public static class TargetRoleFactor extends ExpFamFactor {
		private static final long serialVersionUID = 7822812450436221032L;
		public final int targetIdx;
		public final int roleIdx;
		public TargetRoleFactor(Var f_i, Var r_ij, int i, int j) {
			super(new VarSet(f_i, r_ij), SemaforicSentence.FactorTemplate.TARGET_ROLE);
			targetIdx = i;
			roleIdx = j;
		}
		// TODO getFeatures will go here when Matt updates his code
	}
	
	/**
	 * Represents the factor graph for a sentence, most of the meat is here.
	 * 
	 * For now, this implements ObsFeatureExtractor.
	 * Matt is changing the code so that Factors will be responsible for providing
	 * features, rather than a FeatureExtractor, so I will have to change this.
	 */
	protected static class SemaforicSentence implements ObsFeatureExtractor {
		
		public TargetFeatures targetFeatureFunc;
		public TargetRoleFeatures targetRoleFeatureFunc;
		public Sentence sentence;
		public List<FrameInstance> gold;
		public Frame nullFrame;	// TODO remove this once Matt changes his code
		
		public List<Span> spans;
		public List<String> spanNames;
		public int[][] spanIds;	// given a span, what is its state id for the categorical variable 
		
		public Var[] targetVars;
		public Var[][] roleVars;
		public TargetUnaryFactor[] targetFactors;
		public TargetRoleFactor[][] targetRoleFactors;
		public FactorGraph fg;
		public VarConfig goldConf;
		
		public static enum FactorTemplate {
			TARGET,			// unary factors on f_i variables saying whether they evoke a frame
			TARGET_ROLE		// binary factors between f_i and r_{ij} variables
		}
		
		public SemaforicSentence(Sentence s, List<FrameInstance> gold, int maxRoles, List<String> frameNames,
				Frame nullFrame, TargetFeatures targetFeatureFunc, TargetRoleFeatures targetRoleFeatureFunc) {
			
			assert s != null;
			this.targetFeatureFunc = targetFeatureFunc;
			this.targetRoleFeatureFunc = targetRoleFeatureFunc;
			this.sentence = s;
			this.fg = new FactorGraph();
			this.gold = gold;
			this.nullFrame = nullFrame;
			
			computeSpans(s);
			
			int numFrames = frameNames.size();
			int n = sentence.size();
			
			// target
			targetVars = new Var[n];
			targetFactors = new TargetUnaryFactor[n];
			for(int i=0; i<n; i++) {
				Var f_i = new Var(VarType.PREDICTED, numFrames, "f_" + i, frameNames);
				TargetUnaryFactor ff_i = new TargetUnaryFactor(f_i, i);
				targetVars[i] = f_i;
				targetFactors[i] = ff_i;
				fg.addFactor(ff_i);
			}
			
			// arguments
			roleVars = new Var[n][maxRoles];
			targetRoleFactors = new TargetRoleFactor[n][maxRoles];
			for(int i=0; i<n; i++) {
				for(int j=0; j<maxRoles; j++) {
					Var r_ij = new Var(VarType.PREDICTED, spans.size(), "r_{"+i+","+j+"}", spanNames);
					TargetRoleFactor fr_ij = new TargetRoleFactor(targetVars[i], r_ij, i, j);
					roleVars[i][j] = r_ij;
					targetRoleFactors[i][j] = fr_ij;
					fg.addFactor(fr_ij);
					// TODO add a unary factor for whether spans look good syntactically
				}
			}
			
			// gold labels
			if(gold != null) {
				goldConf = new VarConfig();
				for(int i=0; i<n; i++)
					goldConf.put(targetVars[i], nullFrame.getId());
				for(FrameInstance fi : gold) {
					int target = fi.getTargetIdx();
					Frame evoked = fi.getFrame();
					goldConf.put(targetVars[target], evoked.getId());
					for(int role=0; role<maxRoles; role++) {
						// if the frame evoked is nullFrame, #roles=0, and the span is nullSpan
						Span sp = role < evoked.numRoles() ? fi.getArgument(role) : Span.nullSpan;
						int state = spanIds[sp.start][sp.end];
						System.out.printf("[SemaforicSentence init] roleVars[%d][%d].#states=%d state=%d span=%s\n",
								target, role, roleVars[target][role].getNumStates(), state, sp);
						goldConf.put(roleVars[target][role], state);
					}
				}
			}
		}
	
		public void computeSpans(Sentence s) {
			System.out.println("[SemaforicSentence computeSpans] sentence.size=" + s.size());
			int n = s.size();
			int id = 0;
			spans = new ArrayList<Span>();
			spanNames = new ArrayList<String>();
			spanIds = new int[n][n+1];	// yes this a waste of O(n^2) space...
			spans.add(Span.nullSpan);
			spanNames.add("nullSpan");
			spanIds[0][0] = id++;
			for(int i=0; i<n; i++) {
				for(int j=i+1; j<=n; j++) {
					spans.add(new Span(i, j));
					spanNames.add("span[" + i + "," + j + "]");
					spanIds[i][j] = id++;
					System.out.println("[SemaforicSentence computeSpans] " + new Span(i,j));
				}
			}
			System.out.println("[SemaforicSentence computeSpans] #spans=" + spans.size());
		}
		

		@Override
		public FeatureVector calcObsFeatureVector(int factorId) {
			Factor f = fg.getFactor(factorId);
			if(f instanceof TargetUnaryFactor) {
				Frame evoked = nullFrame;	// TODO can't get this yet
				int targetIdx = ((TargetUnaryFactor) f).targetIdx;
				FeatureVector fv = targetFeatureFunc.getFeatures(evoked, targetIdx, this.sentence);
				return fv;
			}
			else if(f instanceof TargetRoleFactor) {
				Frame evoked = nullFrame;	// TODO can't get this yet
				Span s = Span.nullSpan;		// TODO can't get this yet
				int targetIdx = ((TargetRoleFactor) f).targetIdx;
				int roleIdx = ((TargetRoleFactor) f).roleIdx;
				FeatureVector fv = targetRoleFeatureFunc.getFeatures(evoked, s, targetIdx, roleIdx, this.sentence);
				return fv;
			}
			else throw new RuntimeException("implement me");
		}

		@Override
		public void init(FactorGraph fg, FactorGraph fgLat,
				FactorGraph fgLatPred, VarConfig goldConfig,
				FactorTemplateList fts) {
			
			// ==== TELL FactorTemplateList WHAT THE FEATURES ARE ====
			// target features
			Alphabet<Feature> alphabet = fts.getTemplateByKey(FactorTemplate.TARGET).getAlphabet();
			for(int i=0; i<this.targetFeatureFunc.cardinality(); i++) {
				String featName = FactorTemplate.TARGET + "_" + i;
				alphabet.lookupIndex(new Feature(featName));
			}
			// TODO target-role features
		}

		@Override
		public void clear() {}
	}
	
}


