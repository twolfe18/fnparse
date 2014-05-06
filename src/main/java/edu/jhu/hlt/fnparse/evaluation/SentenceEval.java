package edu.jhu.hlt.fnparse.evaluation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * Holds the data needed to evaluate parses.
 * @author travis
 */
public class SentenceEval {

	/*
	 * indexed with [gold][hyp]
	 * e.g. targets[1][0] = # false negatives
	 * 
	 * NOTE: true negatives is a lower bound rather than exact,
	 * it assumes that the only possible set of predictions in the
	 * universe are the ones that were either predicted or in the gold
	 * labels (real set of true negatives would include all possible
	 * negatives, which is much larger).
	 */
	private final int[][] targetConfusion;
	private final int[][] fullConfusion;
	private final int[][] argOnlyConfusion;	// like fullConfusion, but doesn't include frame-targets as a prediction, should only be used for argId evaluation (gold frameId)
	private final int size;

	private final FNParse gold, hyp;
	private List<Prediction> targetFalsePos, targetFalseNeg;
	private List<Prediction> fullFalsePos, fullFalseNeg;
	
	public SentenceEval(FNParse gold, FNParse hyp) { this(gold, hyp, true); }

	public SentenceEval(FNParse gold, FNParse hyp, boolean storeDebugInfo) {
		
		if(!gold.getSentence().getId().equals(hyp.getSentence().getId()))
			throw new IllegalArgumentException();
		
		//this.gold = gold;
		//this.hyp = hyp;
		this.targetConfusion = new int[2][2];
		this.fullConfusion = new int[2][2];
		this.argOnlyConfusion = new int[2][2];
		this.size = gold.getSentence().size();
		this.gold = gold;
		this.hyp = hyp;
		
		if(storeDebugInfo) {
			targetFalsePos = new ArrayList<Prediction>();
			targetFalseNeg = new ArrayList<Prediction>();
			fullFalsePos = new ArrayList<Prediction>();
			fullFalseNeg = new ArrayList<Prediction>();
		}
	
		Set<Prediction> goldTargets = new HashSet<Prediction>();
		Set<Prediction> hypTargets = new HashSet<Prediction>();
		Set<Prediction> goldTargetRoles = new HashSet<Prediction>();
		Set<Prediction> hypTargetRoles = new HashSet<Prediction>();
		Set<Prediction> goldRoles = new HashSet<Prediction>();
		Set<Prediction> hypRoles = new HashSet<Prediction>();
		
		fillPredictions(gold.getFrameInstances(), goldTargets, goldTargetRoles, goldRoles);
		fillPredictions(hyp.getFrameInstances(), hypTargets, hypTargetRoles, hypRoles);
		
		fillConfusionTable(goldTargets, hypTargets, targetConfusion, targetFalsePos, targetFalseNeg);
		fillConfusionTable(goldTargetRoles, hypTargetRoles, fullConfusion, fullFalsePos, targetFalseNeg);
		fillConfusionTable(goldRoles, hypRoles, argOnlyConfusion, null, null);
	}
	
	// only work if storeDebugInfo was true
	public FNParse getGold() { return gold; }
	public FNParse getHypothesis() { return hyp; }
	public List<Prediction> getTargetFalsePos() { return targetFalsePos; }
	public List<Prediction> getTargetFalseNeg() { return targetFalseNeg; }
	public List<Prediction> getFullFalsePos() { return fullFalsePos; }
	public List<Prediction> getFullFalseNeg() { return fullFalseNeg; }
	
	@Override
	public String toString() {
		return gold.toString() + "\n" + hyp.toString();
	}
	
	public String longString() {
		throw new RuntimeException("implement me");
	}
	
	public int size() { return size; }
	
	public void fillPredictions(List<FrameInstance> fis, Collection<Prediction> targetPreds, Collection<Prediction> targetRolePreds, Collection<Prediction> onlyArgPreds) {
		for(FrameInstance fi : fis) {
			Frame f = fi.getFrame();
			targetPreds.add(new Prediction(fi.getTarget(), f, -1));
			targetRolePreds.add(new Prediction(fi.getTarget(), f, -1));
			int n = fi.getFrame().numRoles();
			for(int i=0; i<n; i++) {
				Span arg = fi.getArgument(i);
				if(arg != Span.nullSpan) {
					Prediction p = new Prediction(arg, f, i);
					targetRolePreds.add(p);
					onlyArgPreds.add(p);
				}
			}
		}
	}
	
	public void fillConfusionTable(Collection<Prediction> gold, Collection<Prediction> hyp, int[][] confusion, List<Prediction> fpStore, List<Prediction> fnStore) {
		
		Set<Prediction> s = new HashSet<Prediction>();
		
		// TP = G & H
		s.clear();
		s.addAll(gold);
		s.retainAll(hyp);
		confusion[1][1] = s.size();
		
		// FP = H -- G
		s.clear();
		s.addAll(hyp);
		s.removeAll(gold);
		confusion[0][1] = s.size();
		if(fpStore != null)
			fpStore.addAll(s);
		
		// FN = G -- H
		s.clear();
		s.addAll(gold);
		s.removeAll(hyp);
		confusion[1][0] = s.size();
		if(fnStore != null)
			fnStore.addAll(s);
		
		// TN
		s.clear();
		s.addAll(gold);
		s.addAll(hyp);
		confusion[0][0] = s.size() - (confusion[1][1] + confusion[0][1] + confusion[1][0]);
		
		assert confusion[1][1] >= 0;
		assert confusion[0][1] >= 0;
		assert confusion[1][0] >= 0;
		assert confusion[0][0] >= 0;
	}
	
	public int targetTP() { return targetConfusion[1][1]; }
	public int targetFP() { return targetConfusion[0][1]; }
	public int targetFN() { return targetConfusion[1][0]; }
	
	public int fullTP() { return fullConfusion[1][1]; }
	public int fullFP() { return fullConfusion[0][1]; }
	public int fullFN() { return fullConfusion[1][0]; }
	
	public int argOnlyTP() { return argOnlyConfusion[1][1]; }
	public int argOnlyFP() { return argOnlyConfusion[0][1]; }
	public int argOnlyFN() { return argOnlyConfusion[1][0]; }

	// TODO arg accuracy (both predictions? just args?)
}
