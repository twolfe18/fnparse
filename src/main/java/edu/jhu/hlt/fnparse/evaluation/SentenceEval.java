package edu.jhu.hlt.fnparse.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * Holds the data needed to evaluate parses.
 * Do not put evaluation code in this class,
 * it is better to leave this to a separate module
 * for easy extension.
 * @author travis
 */
public class SentenceEval {

	/*
	 * indexed with [gold][hyp]
	 * e.g. targets[1][0] = # false negatives
	 */
	private int[][] targetConfusion;
	private int[][] fullConfusion;
	private int size;
	
	public SentenceEval(FNParse gold, FNParse hyp) {
		
		if(!gold.getSentence().getId().equals(hyp.getSentence().getId()))
			throw new IllegalArgumentException();
		
		//this.gold = gold;
		//this.hyp = hyp;
		this.targetConfusion = new int[2][2];
		this.fullConfusion = new int[2][2];
		this.size = gold.getSentence().size();
	
		Set<Prediction> goldTargets = new HashSet<Prediction>();
		Set<Prediction> hypTargets = new HashSet<Prediction>();
		Set<Prediction> goldTargetRoles = new HashSet<Prediction>();
		Set<Prediction> hypTargetRoles = new HashSet<Prediction>();
		
		fillPredictions(gold.getFrameInstances(), goldTargets, goldTargetRoles);
		fillPredictions(hyp.getFrameInstances(), hypTargets, hypTargetRoles);
		
		fillConfusionTable(goldTargets, hypTargets, targetConfusion);
		fillConfusionTable(goldTargetRoles, hypTargetRoles, fullConfusion);
	}
	
	public int size() { return size; }
	
	public void fillPredictions(List<FrameInstance> fis, Set<Prediction> targetPreds, Set<Prediction> targetRolePreds) {
		for(FrameInstance fi : fis) {
			Frame f = fi.getFrame();
			targetPreds.add(new Prediction(fi.getTarget(), f, -1));
			targetRolePreds.add(new Prediction(fi.getTarget(), f, -1));
			int n = fi.getFrame().numRoles();
			for(int i=0; i<n; i++) {
				Span arg = fi.getArgument(i);
				if(arg != Span.nullSpan)
					targetRolePreds.add(new Prediction(arg, f, i));
			}
		}
	}
	
	public void fillConfusionTable(Set<Prediction> gold, Set<Prediction> hyp, int[][] confusion) {
		
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
		
		// FN = G -- H
		s.clear();
		s.addAll(gold);
		s.removeAll(hyp);
		confusion[1][0] = s.size();
		
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
	
	public double getFrameAccuracy() {
		int right = targetConfusion[0][0] + targetConfusion[1][1];
		int wrong = targetConfusion[0][1] + targetConfusion[1][0];
		return ((double) right) / (right + wrong);
	}
	
	// TODO arg accuracy (both predictions? just args?)
}
