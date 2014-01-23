package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleFactory;
import edu.jhu.gm.data.FgExampleList;
import edu.jhu.gm.data.FgExampleListBuilder;
import edu.jhu.gm.data.FgExampleListBuilder.CacheType;
import edu.jhu.gm.data.FgExampleListBuilder.FgExamplesBuilderPrm;
import edu.jhu.gm.feat.FactorTemplateList;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.feat.ObsFeatureExtractor;
import edu.jhu.gm.model.ExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.BasicFrameElemFeatures;
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.FrameElementFeatures;
import edu.jhu.hlt.fnparse.features.FrameFeatures;
import edu.jhu.hlt.fnparse.inference.spans.SingleWordSpanExtractor;
import edu.jhu.hlt.fnparse.inference.spans.SpanExtractor;

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
 * 
 * 
 * We might also want to try to break up the frame variables for the binary factors that connect
 * f_i and r_ij variables. Right now we need to loop over at least 2,000 frames times the number of spans (e.g. 500),
 * which will be very slow. We may be able to essentially do dimensionality reduction on the frames.
 * This would work by replacing the f_i variables with ones that range over "meta frames", with say 30 values.
 * These "meta frames" would then connect to all frames, but the 2,000*500 loop is broken down into 30*500 + 30*2000.
 * Choosing the "meta frame" to frame mapping could be done by looking at the frame relations, e.g.
 * if the frames formed a tree, the "meta frames" could constitute frames high up in the tree.
 * It would be interesting to look into clustering (dimensionality reduction?) on trees of items to
 * see if there is a good way to do this.
 * Note that this doesn't help if we do piecewise training and clamp the f_i variables (it only helps for the
 * f_i to r_ij factors). Also, the number of "meta frames" must be consistently smaller than then number of
 * spans in a sentence, otherwise it is cheaper to do it the naive way. In the context of good span pruning,
 * this may end up not being worth it.
 *   
 * TODO: add latent variable for LU, as in SEMAFOR
 */
public class FGFNParser implements FgExampleFactory {
	
	private List<FGFNParserSentence> trainInstances;
	
	private FrameFeatures frameFeatures = new BasicFrameFeatures();
	private FrameElementFeatures frameElemFeatures = new BasicFrameElemFeatures();
	private SpanExtractor targetIdentifier = new SingleWordSpanExtractor();
	private FrameHypothesisFactory frameHypFactory = new SemaforicFrameHypothesisFactory();
	private FrameElementHypothesisFactory frameElemHypFactory = new ExhaustiveFrameElementHypothesisFactory();
	
	private FgModel model;
	private FactorTemplateList fts = new FactorTemplateList();	// holds factor cliques, just says that there is one factor
	
//	public String getName() {
//		StringBuilder sb = new StringBuilder("<FGFNParser_");
//		sb.append("targetId=" + targetIdentifier.getName());
//		sb.append("framesId=" + frameHypFactory.getName());
//		// TODO
//		sb.append(">");
//		return sb.toString();
//	}
	
	public void train(List<Sentence> examples) {
	
		// construct the SemaforicSentenceFactorGraphs
		this.trainInstances = new ArrayList<FGFNParserSentence>();
		for(Sentence s : examples)
			trainInstances.add(new FGFNParserSentence(s, frameFeatures, frameElemFeatures));
		
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
		FGFNParserSentence fg = trainInstances.get(i);
		return new FgExample(fg.fg, fg.goldConf, fg, fts);
	}

	@Override	// FgExampleFactory
	public int size() { return trainInstances.size(); }
	
	public List<Sentence> parse(List<Sentence> sentences) {
		throw new RuntimeException("implement me");
	}
	
	public static class FrameFactor extends ExpFamFactor {
		private static final long serialVersionUID = 8681081343059187337L;
		public final FrameHypothesis f_i;
		public FrameFactor(FrameHypothesis f_i) {
			super(new VarSet(f_i.getVar()), FactorTemplate.TARGET);
			this.f_i = f_i;
		}
		// TODO getFeatures will go here when Matt updates his code
	}
	
	public static class FrameElemFactor extends ExpFamFactor {
		private static final long serialVersionUID = 7822812450436221032L;
		public final FrameHypothesis f_i;
		public final FrameElementHypothesis r_ij;
		public FrameElemFactor(FrameHypothesis f_i, FrameElementHypothesis r_ij) {
			super(new VarSet(f_i.getVar(), r_ij.getVar()), FactorTemplate.TARGET_ROLE);
			this.f_i = f_i;
			this.r_ij = r_ij;
		}
		// TODO getFeatures will go here when Matt updates his code
	}
	
	public static enum FactorTemplate {
		TARGET,			// unary factors on f_i variables saying whether/what they evoke a frame
		TARGET_ROLE		// binary factors between f_i and r_{ij} variables
	}
	
	/**
	 * Represents the factor graph for a sentence, most of the meat is here.
	 * 
	 * For now, this implements ObsFeatureExtractor.
	 * Matt is changing the code so that Factors will be responsible for providing
	 * features, rather than a FeatureExtractor, so I will have to change this.
	 */
	class FGFNParserSentence implements ObsFeatureExtractor {
		
		public FrameHypothesis[] frameVars;					// f_i
		public FrameElementHypothesis[][] frameElemVars;	// r_ij
		public FrameFactor[] frameFactors;					// phi(f_i)
		public FrameElemFactor[][] frameElemFactors;		// phi(f_i, r_ij)
		public Sentence sentence;
		public FactorGraph fg;
		public VarConfig goldConf;
		
		public FGFNParserSentence(Sentence s, FrameFeatures targetFeatureFunc, FrameElementFeatures targetRoleFeatureFunc) {
			
			this.goldConf = new VarConfig();
			this.sentence = s;
			this.fg = new FactorGraph();
			
			// TODO
			Map<Span, FrameInstance> goldFrames = new HashMap<Span, FrameInstance>();
			for(FrameInstance fi : s.getFrameInstances())
				goldFrames.put(fi.getTarget(), fi);
			
			// targets and frameHyps
			List<Span> targets = targetIdentifier.computeSpans(s);
			int numTargets = targets.size();
			frameVars = new FrameHypothesis[numTargets];
			frameFactors = new FrameFactor[numTargets];
			for(int i=0; i<numTargets; i++) {
				Span sp = targets.get(i);
				FrameHypothesis f_i = frameHypFactory.make(sp, goldFrames.get(sp), sentence);
				FrameFactor ff_i = new FrameFactor(f_i);
				frameVars[i] = f_i;
				frameFactors[i] = ff_i;
				fg.addFactor(ff_i);
				
				Integer goldFrameIdx = f_i.getGoldFrameIndex();
				if(goldFrameIdx != null)
					goldConf.put(f_i.getVar(), goldFrameIdx);
			}
			assert goldConf.size() == s.getFrameInstances().size();
			
			// arguments
			frameElemVars = new FrameElementHypothesis[numTargets][];
			frameElemFactors = new FrameElemFactor[numTargets][];
			for(int i=0; i<numTargets; i++) {
				FrameHypothesis f_i = frameVars[i];
				for(int j=0; j<f_i.maxRoles(); j++) {
					FrameElementHypothesis r_ij = frameElemHypFactory.make(f_i, j, sentence);
					FrameElemFactor fr_ij = new FrameElemFactor(frameVars[i], r_ij);
					frameElemVars[i][j] = r_ij;
					frameElemFactors[i][j] = fr_ij;
					fg.addFactor(fr_ij);
					
					// need to get gold Span for
					Integer gold_r_ij = r_ij.getGoldSpanIdx();
					if(gold_r_ij != null)
						goldConf.put(r_ij.getVar(), gold_r_ij);
				}
			}

		}
		
		@Override
		public FeatureVector calcObsFeatureVector(int factorId) {
			Factor f = fg.getFactor(factorId);
			if(f instanceof FrameFactor) {
				Frame evoked = Frame.nullFrame;	// TODO can't get this yet
				Span target = ((FrameFactor) f).f_i.getTargetSpan();
				FeatureVector fv = frameFeatures.getFeatures(evoked, target, this.sentence);
				return fv;
			}
			else if(f instanceof FrameElemFactor) {
				Frame evoked = Frame.nullFrame;	// TODO can't get this yet
				Span s = Span.nullSpan;			// TODO can't get this yet
				Span target = ((FrameElemFactor) f).f_i.getTargetSpan();
				int roleIdx = ((FrameElemFactor) f).r_ij.getRoleIdx();
				FeatureVector fv = frameElemFeatures.getFeatures(evoked, s, target, roleIdx, this.sentence);
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
//			Alphabet<Feature> alphabet = fts.getTemplateByKey(FactorTemplate.TARGET).getAlphabet();
//			for(int i=0; i<this.targetFeatureFunc.cardinality(); i++) {
//				String featName = FactorTemplate.TARGET + "_" + i;
//				alphabet.lookupIndex(new Feature(featName));
//			}
			// TODO target-role features
		}

		@Override
		public void clear() {}
	}
	
}


