package edu.jhu.hlt.fnparse.inference;

import java.util.*;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleFactory;
import edu.jhu.gm.data.FgExampleList;
import edu.jhu.gm.data.FgExampleListBuilder;
import edu.jhu.gm.data.FgExampleListBuilder.CacheType;
import edu.jhu.gm.data.FgExampleListBuilder.FgExamplesBuilderPrm;
import edu.jhu.gm.feat.FactorTemplate;
import edu.jhu.gm.feat.FactorTemplateList;
import edu.jhu.gm.feat.Feature;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.feat.ObsFeatureExtractor;
import edu.jhu.gm.model.*;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.features.BasicTargetFeatures;
import edu.jhu.hlt.fnparse.features.TargetFeature;
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
	private TargetFeature targetFeatures;
	private FgModel model;
	private FactorTemplateList fts = new FactorTemplateList();	// holds factor cliques, just says that there is one factor
	
	/**
	 * train a model
	 */
	public void train(Map<Sentence, List<FrameInstance>> examples, List<Frame> frames, Frame nullFrame) {
		
		this.nullFrame = nullFrame;
		this.frames = frames;
		this.targetFeatures = new BasicTargetFeatures(nullFrame);
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
			trainInstances.add(new SemaforicSentence(s, fis, maxRoles, frameNames, nullFrame, targetFeatures));
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
	 * Represents the factor graph for a sentence, most of the meat is here.
	 * 
	 * For now, this implements ObsFeatureExtractor.
	 * Matt is changing the code so that Factors will be responsible for providing
	 * features, rather than a FeatureExtractor, so I will have to change this.
	 */
	private static class SemaforicSentence implements ObsFeatureExtractor {
		
		public TargetFeature targetFeatureFunc;
		public Sentence sentence;
		public List<FrameInstance> gold;
		public List<Span> spans;
		public List<String> spanNames;
		public Var[] targetVars;
		public Var[][] roleVars;
		public FactorGraph fg;
		public VarConfig goldConf;
		
		public static enum FactorTemplate {
			TARGET,			// unary factors on f_i variables saying whether they evoke a frame
			TARGET_ROLE		// binary factors between f_i and r_{ij} variables
		}
		
		public SemaforicSentence(Sentence s, List<FrameInstance> gold, int maxRoles,
				List<String> frameNames, Frame nullFrame, TargetFeature targetFeatureFunc) {
			
			assert s != null;
			this.targetFeatureFunc = targetFeatureFunc;
			this.sentence = s;
			this.fg = new FactorGraph();
			this.gold = gold;
			
			computeSpans(s);
			
			int numFrames = frameNames.size();
			int n = sentence.size();
			targetVars = new Var[n];
			roleVars = new Var[n][maxRoles];
			
			// target
			for(int i=0; i<n; i++) {
				Var f_i = new Var(VarType.PREDICTED, numFrames, "f_" + i, frameNames);
				targetVars[i] = f_i;
				fg.addFactor(new ExpFamFactor(new VarSet(f_i), FactorTemplate.TARGET));
			}
			
			// arguments
			for(int i=0; i<n; i++) {
				for(int j=0; j<maxRoles; j++) {
					Var r_ij = new Var(VarType.PREDICTED, spans.size(), "r_{"+i+","+j+"}", spanNames);
					roleVars[i][j] = r_ij;
					fg.addFactor(new ExpFamFactor(new VarSet(targetVars[i], r_ij), FactorTemplate.TARGET_ROLE));
					// TODO add a unary factor for whether this span looks good syntactically
				}
			}
			
			// gold labels
			if(gold != null) {
				goldConf = new VarConfig();
				for(int i=0; i<n; i++)
					goldConf.put(targetVars[i], nullFrame.getId());
				for(FrameInstance fi : gold) {
					int i = fi.getTargetIdx();
					Frame evoked = fi.getFrame();
					goldConf.put(targetVars[i], evoked.getId());
					for(int j=0; j<evoked.numRoles(); j++)
						goldConf.put(roleVars[i][j], spanConfigId(fi.getArgument(j)));
				}
			}
		}
	
		public void computeSpans(Sentence s) {
			spans = new ArrayList<Span>();
			spanNames = new ArrayList<String>();
			spans.add(Span.nullSpan);
			spanNames.add("nullSpan");
			int n = s.size();
			for(int i=0; i<n; i++) {
				for(int j=i+1; j<n; j++) {
					spans.add(new Span(i, j));
					spanNames.add("span[" + i + "," + j + "]");
				}
			}
		}
		
		public int spanConfigId(Span s) {
			assert Span.nullSpan.start == 0 && Span.nullSpan.end == 0;	// this gives 0
			assert s == Span.nullSpan ^ s.start < s.end;
			return s.start * sentence.size() + s.end;
		}

		@Override
		public FeatureVector calcObsFeatureVector(int factorId) {
			// TODO look up Frame and targetIdx by factorId (how?)
			Frame evoked = null;
			int targetIdx = 0;
			FeatureVector fv = targetFeatureFunc.getFeatures(evoked, targetIdx, this.sentence);
			// TODO target-role features
			return fv;
		}

		@Override
		public void init(FactorGraph fg, FactorGraph fgLat,
				FactorGraph fgLatPred, VarConfig goldConfig,
				FactorTemplateList fts) {
			
			// ==== TELL FactorTemplateList WHAT THE FEATURES ARE ====
			// target features
			Alphabet<Feature> alphabet = fts.getTemplateByKey(FactorTemplate.TARGET).getAlphabet();
			//VarSet vars = null;
			//fts.add(new FactorTemplate(vars, alphabet, "targetModel"));
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


