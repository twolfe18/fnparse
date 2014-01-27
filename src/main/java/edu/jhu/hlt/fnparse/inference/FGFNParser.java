package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.data.FgExampleMemoryStore;
import edu.jhu.gm.decode.MbrDecoder;
import edu.jhu.gm.decode.MbrDecoder.MbrDecoderPrm;
import edu.jhu.gm.feat.FeatureExtractor;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.ExplicitFactor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FeExpFamFactor;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.gm.train.CrfTrainer;
import edu.jhu.gm.util.IntIter;
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
public class FGFNParser {
	
	private FgModel model;
	
	private FrameFeatures frameFeatures = new BasicFrameFeatures();
	private FrameElementFeatures frameElemFeatures = new BasicFrameElemFeatures();
	
	private SpanExtractor targetIdentifier = new SingleWordSpanExtractor();
	private FrameHypothesisFactory frameHypFactory = new SemaforicFrameHypothesisFactory();
	private FrameElementHypothesisFactory frameElemHypFactory = new ExhaustiveFrameElementHypothesisFactory();
	
//	public String getName() {
//		StringBuilder sb = new StringBuilder("<FGFNParser_");
//		sb.append("targetId=" + targetIdentifier.getName());
//		sb.append("framesId=" + frameHypFactory.getName());
//		// TODO
//		sb.append(">");
//		return sb.toString();
//	}
	
	public FrameFeatures getFrameFeatures() { return frameFeatures; }
	public FrameElementFeatures getFrameElementFeatures() { return frameElemFeatures; }
	
	public SpanExtractor getTargetIdentifier() { return targetIdentifier; }
	public FrameHypothesisFactory getFrameIdentifier() { return frameHypFactory; }
	public FrameElementHypothesisFactory getFrameElementIdentifier() { return frameElemHypFactory; }
	
	public void train(List<Sentence> examples) {
	
		CrfTrainer.CrfTrainerPrm trainerPrm = new CrfTrainer.CrfTrainerPrm();
		CrfTrainer trainer = new CrfTrainer(trainerPrm);
		
		FgExampleMemoryStore exs = new FgExampleMemoryStore();
		for(Sentence s : examples) {
			FGFNParserSentence ps = new FGFNParserSentence(s);
			exs.add(new FgExample(ps.fg, ps.goldConf));
		}
		
		// Feature name tracking is done by me.
		int numParams = 1;
		this.model = new FgModel(numParams);
		this.model = trainer.train(this.model, exs);
	}

	public List<Sentence> parse(List<Sentence> sentences) {
		List<Sentence> ret = new ArrayList<Sentence>();
		for(Sentence inSent : sentences) {
			Sentence s = inSent.copy(false);
			FGFNParserSentence ps = new FGFNParserSentence(s);
			ps.decode(model);
			ret.add(s);
		}
		return ret;
	}

	static class FrameElemFeatureExtractor implements FeatureExtractor {
		private FrameElementFeatures features;
		private FrameElementHypothesis r_ij;
		private FrameHypothesis f_i;
		public FrameElemFeatureExtractor(FrameElementFeatures feats, FrameElementHypothesis r_ij, FrameHypothesis f_i) {
			this.features = feats;
			this.r_ij = r_ij;
			this.f_i = f_i;
		}
		@Override
		public void init(FgExample ex) {}
		@Override
		public FeatureVector calcFeatureVector(FeExpFamFactor factor, int configId) {
			VarConfig varConf = factor.getVars().getVarConfig(configId);
			int f_i_value = varConf.getState(f_i.getVar());
			Frame f = f_i.getPossibleFrame(f_i_value);
			int r_ij_value = varConf.getState(r_ij.getVar());
			Span argSpan = r_ij.getSpan(r_ij_value);
			return features.getFeatures(f, argSpan, f_i.getTargetSpan(), r_ij.getRoleIdx(), f_i.getSentence());
		}
	}
	
	static class FrameFeatureExtractor implements FeatureExtractor {
		private FrameFeatures features;
		private FrameHypothesis f_i;
		public FrameFeatureExtractor(FrameFeatures feats, FrameHypothesis f_i) {
			this.features = feats;
			this.f_i = f_i;
		}
		@Override
		public void init(FgExample ex) {}
		@Override
		public FeatureVector calcFeatureVector(FeExpFamFactor factor, int configId) {
			Frame f = f_i.getPossibleFrame(configId);
			Span extent = f_i.getTargetSpan();
			Sentence s = f_i.getSentence();
			return features.getFeatures(f, extent, s);
		}
	}
		
	/**
	 * Represents the factor graph for a sentence, most of the meat is here.
	 * 
	 * For now, this implements ObsFeatureExtractor.
	 * Matt is changing the code so that Factors will be responsible for providing
	 * features, rather than a FeatureExtractor, so I will have to change this.
	 */
	public class FGFNParserSentence {
		
		public List<FrameHypothesis> frameVars;						// f_i
		public List<List<FrameElementHypothesis>> frameElemVars;	// r_ij
		public List<FeExpFamFactor> frameFactors;					// phi(f_i)
		public List<List<FeExpFamFactor>> frameElemFactors;			// phi(f_i, r_ij)
		public Sentence sentence;
		public FactorGraph fg;
		public VarConfig goldConf;
		
		public static final boolean verbose = false;
		
		public FGFNParserSentence(Sentence s) {
			
			this.goldConf = new VarConfig();
			this.sentence = s;
			this.fg = new FactorGraph();
			
			// build an index so you can look up if there is a Frame evoked at a particular Span
			Map<Span, FrameInstance> goldFrames = new HashMap<Span, FrameInstance>();
			for(FrameInstance fi : s.getFrameInstances())
				goldFrames.put(fi.getTarget(), fi);
			
			// targets and frameHyps
			frameVars = new ArrayList<FrameHypothesis>();
			frameFactors = new ArrayList<FeExpFamFactor>();
			for(Span sp : targetIdentifier.computeSpans(s)) {
				FrameHypothesis f_i = frameHypFactory.make(sp, goldFrames.get(sp), sentence);
				if(f_i.numPossibleFrames() == 1) {
					assert f_i.getPossibleFrame(0) == Frame.nullFrame;
					continue;
				}
				assert f_i.numPossibleFrames() > 1;
				FeExpFamFactor ff_i = new FeExpFamFactor(
						new VarSet(f_i.getVar()),
						new FrameFeatureExtractor(frameFeatures, f_i));
				frameVars.add(f_i);
				frameFactors.add(ff_i);
				fg.addFactor(ff_i);
				
				Integer goldFrameIdx = f_i.getGoldFrameIndex();
				if(goldFrameIdx != null) {
					if(verbose)
						System.out.printf("[goldConfig.put] F: %s -> %s\n", f_i.getVar(), goldFrameIdx);
					goldConf.put(f_i.getVar(), goldFrameIdx);
				}
				else
					System.out.println("WTF1");
			}
			assert goldConf.size() == frameVars.size() :
				String.format("goldConf.size=%d frameVars.size=%d", goldConf.size(), frameVars.size());
			
			// arguments
			frameElemVars = new ArrayList<List<FrameElementHypothesis>>();
			frameElemFactors = new ArrayList<List<FeExpFamFactor>>();
			for(int i=0; i<frameVars.size(); i++) {
				
				FrameHypothesis f_i = frameVars.get(i);
				int numRoles = f_i.maxRoles();
				frameElemVars.add(new ArrayList<FrameElementHypothesis>());
				frameElemFactors.add(new ArrayList<FeExpFamFactor>());
				
				for(int j=0; j<numRoles; j++) {
					
					// f_i.getGoldFrameInstance is null
					// why?
					// we make an f_i for every possible target span, some of which won't be represented as positive frames in Sentence
					// what do when f_i.getGoldFrameInstance is null?
					// if the goldFrameInstance is null, then the gold is null, so are all of the arguments
					// what happens if we do clamping @ gold targets/frames?
					// then these role variables won't exist (when goldFrame==nullFrame)
					
					// we want to push down the probability of p(frame != null) at the null positions,
					// so we need to count these instantiations of variables.
					// if we clamped at the gold frames, then these variables should have 0 prob due to the hard factors
					// (which rule out r_ij != nullSpan when f_i = nullFrame by way of
					//  nullFrame.numRoles = 0 and constraint on r_ij and j < f_i.numRoles)
					
					
					
					FrameElementHypothesis r_ij = frameElemHypFactory.make(f_i, j, sentence);
					FeExpFamFactor fr_ij = new FeExpFamFactor(
							new VarSet(r_ij.getVar(), f_i.getVar()),
							new FrameElemFeatureExtractor(frameElemFeatures, r_ij, f_i));
					frameElemVars.get(i).add(r_ij);
					frameElemFactors.get(i).add(fr_ij);
					fg.addFactor(fr_ij);
					
					// need to get gold Span for
					Integer gold_r_ij = r_ij.getGoldSpanIdx();
					if(gold_r_ij != null) {
						if(verbose)
							System.out.printf("[goldConfig.put] FE: %s -> %s\n", r_ij.getVar(), gold_r_ij);
						goldConf.put(r_ij.getVar(), gold_r_ij);
					}
					else
						System.out.println("WTF2");
				}
			}
			
			// add hard-factors/constraints that say that r_ij = nullSpan \forall i, j \ge f_i.numRoles
			for(int i=0; i<frameVars.size(); i++) {
				FrameHypothesis f_i = frameVars.get(i);
				List<FrameElementHypothesis> r_i = frameElemVars.get(i);
				for(int j=0; j<r_i.size(); j++) {
					FrameElementHypothesis r_ij = r_i.get(j);
					VarSet vs = new VarSet(f_i.getVar(), r_ij.getVar());
					DenseFactor df = new DenseFactor(vs);
					IntIter iiter = vs.getConfigIter(vs);
					while(iiter.hasNext()) {
						int cfgIdx = iiter.next();
						VarConfig cfg = vs.getVarConfig(cfgIdx);
						int spanIdx = cfg.getState(r_ij.getVar());
						Span span_ij = r_ij.getSpan(spanIdx);
						int frameIdx = cfg.getState(f_i.getVar());
						Frame f = f_i.getPossibleFrame(frameIdx);
						df.setValue(cfgIdx, j >= f.numRoles() && span_ij != Span.nullSpan ? 0d : 1d);
					}
					fg.addFactor(new ExplicitFactor(df));
				}
			}
			
		}
		
		public Iterable<Var> getAllVars() {
			List<Var> all = new ArrayList<Var>();
			int n = frameVars.size();
			for(int i=0; i<n; i++) {
				all.add(frameVars.get(i).getVar());
				for(FrameElementHypothesis feh : frameElemVars.get(i))
					all.add(feh.getVar());
			}
			return all;
		}

		/**
		 * adds FrameInstances to the Sentence provided in the constructor
		 */
		public void decode(FgModel model) {
			
			if(sentence.numFrameInstances() > 0)
				throw new IllegalStateException("did you add an already annotated Sentence?");
			
			MbrDecoderPrm decPrm = new MbrDecoderPrm();
			MbrDecoder dec = new MbrDecoder(decPrm);
			
			// for now, pass goldConf. will blow up?
			dec.decode(model, new FgExample(this.fg, this.goldConf));
			
			VarConfig conf = dec.getMbrVarConfig();
			for(int i=0; i<frameElemVars.size(); i++) {
				
				// read the frame that is evoked
				FrameHypothesis f_i = frameVars.get(i);
				int f_i_value = conf.getState(f_i.getVar());
				Frame f_i_hyp = f_i.getPossibleFrame(f_i_value);
				if(f_i_hyp == Frame.nullFrame)
					continue;
				
				int numRoles = f_i_hyp.numRoles();
				Span[] roles = new Span[numRoles];
				for(int j=0; j<numRoles; j++) {
					FrameElementHypothesis r_ij = frameElemVars.get(i).get(j);
					int r_ij_value = conf.getState(r_ij.getVar());
					roles[j] = r_ij.getSpan(r_ij_value);
				}
				
				FrameInstance fi = FrameInstance.newFrameInstance(f_i_hyp, f_i.getTargetSpan(), roles, sentence);
				sentence.addFrameInstance(fi);
			}
		}

	}
	
}


