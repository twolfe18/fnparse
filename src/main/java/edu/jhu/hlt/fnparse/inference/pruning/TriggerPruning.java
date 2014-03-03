
package edu.jhu.hlt.fnparse.inference.pruning;

import java.io.File;
import java.util.*;

import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.inference.newstuff.BinaryVarUtil;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.optimize.L2;
import edu.jhu.util.*;
import edu.jhu.gm.data.*;
import edu.jhu.gm.decode.MbrDecoder;
import edu.jhu.gm.decode.MbrDecoder.MbrDecoderPrm;
import edu.jhu.gm.feat.*;
import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.model.*;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.train.CrfTrainer;

/**
 * @deprecated as is this class is deprecated because its trying to
 * prune target spans. it no longer appears that attempting targets
 * that are wider than one word is worth it. we could revamp this to 
 * try to do filtering on single words, but for now I'm going to just
 * follow semafor/LTH and only take 1) targets that appear in the
 * lex/training examples, 2) are only one word, and 3) do not appear
 * on a short stop list (see SpanPruningExperiment).
 * 
 * @author travis
 */
public class TriggerPruning {

	// the goal here is to force certain f_i to be nullFrame
	// lets just do a CRF with a cutoff on prob(f_i == nullFrame)
	// (i could do a SVM with weighted hinge in for FP/FN instanecs,
	// but this is not worth it given how easily i can train with matts code)
	public static class TriggerPruningParams {
		public FgModel model;
		public Alphabet<String> featIdx;
		public double pruneThresh;
		public TargetPruningData data;
		
		public TriggerPruningParams(int numParams) {
			model = new FgModel(numParams);
			featIdx = new Alphabet<String>();
			pruneThresh = 0.6d;
			data = TargetPruningData.getInstance();
		}
		
		public void writeout(File f) {
			ModelIO.writeHumanReadable(model, featIdx, f);
		}
	}

	/**
	 * the alphabet given to this class is assumed to belong to
	 * only this factor. in other words, feature names are not
	 * prependend by a class-specific string to ensure that there
	 * are no global name collisions.
	 */
	static class TriggerPruningFactor extends ExpFamFactor {
		private static final long serialVersionUID = 1L;
		
		private TriggerPruningParams params;
		private int index;
		private Sentence sent;
		
		public static final int maxNgram = 5;
		public static final boolean simple = true;	// fewer features if true
		
		public TriggerPruningFactor(Var shouldPrune, TriggerPruningParams params, int idx, Sentence s) {
			super(new VarSet(shouldPrune));
			this.params = params;
			this.index = idx;
			this.sent = s;
		}
		
		public FeatureVector getFeatures(int config) {
			boolean prune = BinaryVarUtil.configToBool(config);
			if(!prune) return AbstractFeatures.emptyFeatures;
			
			LexicalUnit x;
			FeatureVector fv = new FeatureVector();
			b(fv, "intercept");
			
			x = sent.getLU(index);
			b(fv, "word=" + x.word, 2d);
			b(fv, "pos=" + x.pos);
			b(fv, "lu=" + x.getFullString(), 2d);
			
			// check for match to known LU
			List<Frame> frameMatchesByLU = params.data.getLU2Frames().get(sent.getLU(index));
			if(frameMatchesByLU == null)
				b(fv, "doesnt-match-LU-for-any-frame", 2d);
			else {
				b(fv, "#LU-match=" + frameMatchesByLU.size());
				if(!simple) {
					for(Frame f : frameMatchesByLU) {
						b(fv, "LU-matches-" + f.getName());
						b(fv, "LU-matches-" + f.getName() + "-using-" + x.word);
					}
				}
			}
			
			// check for similarity to an existing lex FI example
//			List<FrameInstance> lexFIs = params.data.getWord2FrameInstances().get(sent.getWord(index));
//			if(lexFIs == null)
//				b(fv, "doesnt-match-any-lexFI", 2d);
//			else {
//				int n = lexFIs.size();
//				b(fv, "#lexFI-matches=" + n);
//				if(n >= 2) b(fv, "#lexFI-matches>=2");
//				if(n >= 3) b(fv, "#lexFI-matches>=3");
//				if(n >= 4) b(fv, "#lexFI-matches>=4");
//				if(n >= 5) b(fv, "#lexFI-matches>=5");
//				if(n >= 10) b(fv, "#lexFI-matches>=10");
//				if(n >= 20) b(fv, "#lexFI-matches>=20");
//				if(n >= 40) b(fv, "#lexFI-matches>=40");
//				if(!simple) {
//					for(FrameInstance fi : lexFIs) {
//						b(fv, "#lexFI-match-for-" + fi.getFrame().getName());
//						// try to expand l/r
//						// TODO i want LCS, but that seems too slow
//						// how about intersection of ngrams from the target?
//						boolean takeWord = true;
//						boolean takePos = false;
//						Set<String> fiW = allNgrams(fi.getTarget(), fi.getSentence(), maxNgram, takeWord, takePos);
//						int left = index, right = index+1;
//						while(true) {
//							if(left >= 0 &&
//									fiW.contains(getNgramStr(left-1, right, sent, takeWord, takePos))) {
//								left--;
//								continue;
//							}
//							if(right <= sent.size() &&
//									fiW.contains(getNgramStr(left, right+1, sent, takeWord, takePos))) {
//								right++;
//								continue;
//							}
//							break;
//						}
//						Set<String> fiWintersected = allNgrams(Span.getSpan(left, right), sent, maxNgram, takeWord, takePos);
//						if(fiWintersected.size() >= 2) b(fv, "#lexFI-matchin-ngrams>=2");
//						if(fiWintersected.size() >= 4) b(fv, "#lexFI-matchin-ngrams>=4");
//						if(fiWintersected.size() >= 6) b(fv, "#lexFI-matchin-ngrams>=6");
//						if(fiWintersected.size() >= 8) b(fv, "#lexFI-matchin-ngrams>=8");
//						if(fiWintersected.size() >= 12) b(fv, "#lexFI-matchin-ngrams>=12");
//						for(String ngram : fiWintersected)
//							b(fv, "lexFI-matchin-ngram=" + ngram);
//					}
//				}
//			}
			
			
			// parents and grandparents in the dep tree
			int parentIdx = sent.governor(index);
			LexicalUnit parent = AbstractFeatures.getLUSafe(parentIdx, sent);
			LexicalUnit gparent = parentIdx >= 0
					? AbstractFeatures.getLUSafe(sent.governor(parentIdx), sent)
					: AbstractFeatures.luStart;
			LexicalUnit me = sent.getLU(index);
			b(fv, "parent=" + parent.getFullString());
			b(fv, "parent=" + parent.word);
			b(fv, "parent=" + parent.pos);
			b(fv, "parent=" + parent.getFullString() + "_word=" + me.word);
			b(fv, "parent=" + parent.word + "_word=" + me.word);
			b(fv, "parent=" + parent.word + "_word=" + me.pos);
			b(fv, "parent=" + parent.pos + "_word=" + me.getFullString());
			b(fv, "parent=" + parent.pos + "_word=" + me.word);
			b(fv, "parent=" + parent.pos + "_word=" + me.pos);
			if(!simple) {
				b(fv, "parent=" + parent.getFullString() + "_word=" + me.word + "_gparent=" + gparent.pos);
				b(fv, "parent=" + parent.word + "_word=" + me.word + "_gparent=" + gparent.word);
				b(fv, "parent=" + parent.word + "_word=" + me.word + "_gparent=" + gparent.pos);
				b(fv, "parent=" + parent.word + "_word=" + me.pos + "_gparent=" + gparent.word);
				b(fv, "parent=" + parent.word + "_word=" + me.pos + "_gparent=" + gparent.pos);
				b(fv, "parent=" + parent.pos + "_word=" + me.getFullString() + "_gparent=" + gparent.pos);
				b(fv, "parent=" + parent.pos + "_word=" + me.word + "_gparent=" + gparent.pos);
				b(fv, "parent=" + parent.pos + "_word=" + me.pos + "_gparent=" + gparent.pos);
			}
			
			// children in the dependency tree
			int n = sent.size();
			for(int i=0; i<n; i++) {
				if(sent.governor(i) == index) {
					LexicalUnit child = sent.getLU(i);
					b(fv, "child=" + child.getFullString());
					b(fv, "child=" + child.word);
					b(fv, "child=" + child.pos);
					b(fv, "child=" + child.getFullString() + "_word=" + me.word);
					b(fv, "child=" + child.getFullString() + "_word=" + me.pos);
					b(fv, "child=" + child.word + "_word=" + me.word);
					b(fv, "child=" + child.word + "_word=" + me.pos);
					b(fv, "child=" + child.pos + "_word=" + me.word);
					b(fv, "child=" + child.pos + "_word=" + me.pos);
				}
			}
			
			// ngrams
			int k = simple ? 2 : 4;
			for(int width=1; width<k; width++) {
				for(int offset=-k; offset<=k; offset++) {
					StringBuilder wordFeatName = new StringBuilder();
					wordFeatName.append("n="+width);
					wordFeatName.append("_d="+offset);
					StringBuilder posFeatName = new StringBuilder();
					posFeatName.append("n="+width);
					posFeatName.append("_d="+offset);
					StringBuilder mixFeatName = new StringBuilder();
					mixFeatName.append("n="+width);
					mixFeatName.append("_d="+offset);
					int center = index + offset;
					int left = center - (width/2);
					int right = left + width;
					for(int i=left; i<right; i++) {
						x = AbstractFeatures.getLUSafe(i, sent);
						wordFeatName.append("_" + x.word);
						posFeatName.append("_" + x.pos);
						mixFeatName.append("_" + (i == center ? x.word : x.pos));
					}
					double weight = (2d / (2+width)) + (4d / (4d + Math.abs(offset)));
					b(fv, wordFeatName.toString(), weight);
					b(fv, posFeatName.toString(), weight);
					b(fv, mixFeatName.toString(), weight);
				}
			}
			
			// from left abs
			b(fv, "i=" + index);
			b(fv, "i/2=" + (index/2));
			b(fv, "i/3=" + (index/3));
			b(fv, "i/5=" + (index/5));
			
			// from left prop
			b(fv, "i*2/n=" + ((index*2)/sent.size()));
			b(fv, "i*3/n=" + ((index*3)/sent.size()));
			b(fv, "i*5/n=" + ((index*5)/sent.size()));
			b(fv, "i*7/n=" + ((index*7)/sent.size()));
			
			// from right abs
			b(fv, "n-i="  + (sent.size()-index));
			b(fv, "(n-i)/2="  + (sent.size()-index)/2);
			b(fv, "(n-i)/3="  + (sent.size()-index)/3);
			b(fv, "(n-i)/5="  + (sent.size()-index)/5);
			
			// from right prop
			b(fv, "((n-i)*2)/n=" + ((index*2)/sent.size()));
			b(fv, "((n-i)*3)/n=" + ((index*3)/sent.size()));
			b(fv, "((n-i)*5)/n=" + ((index*5)/sent.size()));
			b(fv, "((n-i)*7)/n=" + ((index*7)/sent.size()));
			
			return fv;
		}
		
		public String getNgramStr(int start, int end, Sentence sent, boolean takeWord, boolean takePos) {
			assert takeWord || takePos;
			assert start < end;
			StringBuilder sb = new StringBuilder();
			for(int i=start; i<end; i++) {
				sb.append("_");
				LexicalUnit x = AbstractFeatures.getLUSafe(i, sent);
				if(takeWord && takePos)
					sb.append(x.getFullString());
				else if(takeWord)
					sb.append(x.word);
				else //if(takePos)
					sb.append(x.pos);
			}
			return sb.toString();
		}
		
		public Set<String> allNgrams(Span s, Sentence sent, int maxNgram, boolean takeWord, boolean takePos) {
			Set<String> set = new HashSet<String>();
			for(int start=s.start; start<s.end; start++)
				for(int end=start+1; end<=s.end && end - start <= maxNgram; end++)
					set.add(getNgramStr(start, end, sent, takeWord, takePos));
			return set;
		}
		
		private final void b(FeatureVector fv, String featureName) {
			b(fv, featureName, 1d);
		}
		private final void b(FeatureVector fv, String featureName, double weight) {
			if(params.featIdx.isGrowing())
				fv.add(params.featIdx.lookupIndex(featureName, true), weight);
			else {
				int idx = params.featIdx.lookupIndex(featureName, false);
				if(idx >= 0) fv.add(idx, weight);
			}
		}
	}
	
	public TriggerPruningParams params;
	
	public TriggerPruning() {
		params = new TriggerPruningParams(2 * 1000 * 1000);
	}

	/**
	 * should f_i be fixed to nullFrame for this index (i.e. pruned)?
	 */
	public boolean prune(int headIdx, Sentence s) {
		return probPrune(headIdx, s) >= params.pruneThresh;
	}
	
	public double probPrune(int headIdx, Sentence s) {
		FgExample fge = setupExample(headIdx, s, false);
		MbrDecoderPrm prm = new MbrDecoderPrm();
		prm.infFactory = infFactory();
		MbrDecoder decoder = new MbrDecoder(prm);
		decoder.decode(params.model, fge);
		Map<Var, DenseFactor> margins = decoder.getVarMarginalsIndexed();
		assert margins.size() == 1;
		DenseFactor beliefs = margins.values().iterator().next();
		return beliefs.getValue(BinaryVarUtil.boolToConfig(true));
	}
	
	private FgInferencerFactory infFactory() {
		BeliefPropagation.BeliefPropagationPrm bpif = new BeliefPropagation.BeliefPropagationPrm();
		bpif.logDomain = false;
		bpif.cacheFactorBeliefs = false;
		bpif.normalizeMessages = true;
		return bpif;
	}

	/**
	 * even if you don't know the label, you have to provide a value for
	 * matt's code, so just say false.
	 */
	protected FgExample setupExample(int headIdx, Sentence s, boolean label) {
		String name = String.format("y_{%s,%d}", s.getId(), headIdx);
		Var y = new Var(VarType.PREDICTED, 2, name, BinaryVarUtil.stateNames);
		Factor f = new TriggerPruningFactor(y, params, headIdx, s);
		FactorGraph fg = new FactorGraph();
		fg.addVar(y);
		fg.addFactor(f);
		VarConfig gold = new VarConfig();
		gold.put(y, BinaryVarUtil.boolToConfig(label));
		return new FgExample(fg, gold);
	}
	
	/**
	 * now i'm training a classifier for whether a given index is
	 * in a target span or not. this is more conservative than it
	 * really should be, which is whether it is the head of a target
	 * (according to the model structure).
	 * another way to make this better is to do a proper sequence model CRF.
	 * right now its just N independent logistic regressions.
	 */
	public <T extends FNTagging> void train(List<T> examples, double priorVariance) {
		if(priorVariance <= 0) throw new IllegalArgumentException();
		long start = System.currentTimeMillis();
		params.featIdx.startGrowth();
		FgExampleMemoryStore exs = new FgExampleMemoryStore();
		int nPrune = 0;
		for(FNTagging tagging : examples) {
			Sentence s = tagging.getSentence();
			int n = s.size();
			
			// get the labels
			boolean[] target = new boolean[n];
			for(FrameInstance fi : tagging.getFrameInstances()) {
				Span trigger = fi.getTarget();
				for(int i=trigger.start; i<trigger.end; i++)
					target[i] = true;
			}
			
			// add the instances
			for(int i=0; i<n; i++) {
				if(target[i]) nPrune++;
				exs.add(setupExample(i, s, target[i]));
			}
		}
		System.out.printf("[TriggerPruning train] %d of %d examples should be pruned\n", nPrune, exs.size());
		CrfTrainer.CrfTrainerPrm trainerParams = new CrfTrainer.CrfTrainerPrm();
		
		// mallet is doing a really terrible job of optimizing this function... like usual
		// maybe LBFGS is getting too smart for its own good, ill conditioned 2nd order approx?
		// => fewer corrections? (default is 4)
//		MalletLBFGS.MalletLBFGSPrm mLBFGSparams = new MalletLBFGS.MalletLBFGSPrm();
//		mLBFGSparams.numberOfCorrections = 2;
//		trainerParams.maximizer = new MalletLBFGS(mLBFGSparams);
		
		trainerParams.infFactory = infFactory();
		trainerParams.regularizer = new L2(priorVariance);
		CrfTrainer trainer = new CrfTrainer(trainerParams);
		try { trainer.train(params.model, exs); }
		catch(cc.mallet.optimize.OptimizationException e) {
			e.printStackTrace();
		}
		params.featIdx.stopGrowth();
		System.out.printf("[TriggerPruning train] done training in %.1f seconds\n",
				(System.currentTimeMillis()-start)/1000d);
	}

}

