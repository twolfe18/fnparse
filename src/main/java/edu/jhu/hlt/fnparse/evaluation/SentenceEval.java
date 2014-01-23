package edu.jhu.hlt.fnparse.evaluation;

import java.util.*;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

/**
 * Holds the data needed to evaluate parses.
 * Do not put evaluation code in this class,
 * it is better to leave this to a separate module
 * for easy extension.
 * @author travis
 */
public class SentenceEval {

	//private Sentence gold, hyp;
	
	/*
	 * indexed with [gold][hyp]
	 * e.g. targets[1][0] = # false negatives
	 */
	private int[][] targetConfusion;
	private int[][] fullConfusion;
	
	public SentenceEval(Sentence gold, Sentence hyp) {
		
		//this.gold = gold;
		//this.hyp = hyp;
		this.targetConfusion = new int[2][2];
		this.fullConfusion = new int[2][2];
	
		Set<Prediction> goldTargets = new HashSet<Prediction>();
		Set<Prediction> hypTargets = new HashSet<Prediction>();
		Set<Prediction> goldTargetRoles = new HashSet<Prediction>();
		Set<Prediction> hypTargetRoles = new HashSet<Prediction>();
		
		fillPredictions(gold.getFrameInstances(), goldTargets, goldTargetRoles);
		fillPredictions(hyp.getFrameInstances(), hypTargets, hypTargetRoles);
		
		fillConfusionTable(goldTargets, hypTargets, targetConfusion);
		fillConfusionTable(goldTargetRoles, hypTargetRoles, fullConfusion);
	}
	
	public void fillPredictions(List<FrameInstance> fis, Set<Prediction> targetPreds, Set<Prediction> targetRolePreds) {
		for(FrameInstance fi : fis) {
			Frame f = fi.getFrame();
			targetPreds.add(new Prediction(fi.getTarget(), f, -1));
			int n = fi.getFrame().numRoles();
			for(int i=0; i<n; i++)
				targetRolePreds.add(new Prediction(fi.getArgument(i), f, i));
		}
	}
	
	public void fillConfusionTable(Set<Prediction> gold, Set<Prediction> hyp, int[][] confusion) {
		
		Set<Prediction> s = new HashSet<Prediction>();
		
		// TP = G & H
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
	}
	
	public int targetTP() { return targetConfusion[1][1]; }
	public int targetFP() { return targetConfusion[0][1]; }
	public int targetFN() { return targetConfusion[1][0]; }
	
	public int fullTP() { return fullConfusion[1][1]; }
	public int fullFP() { return fullConfusion[0][1]; }
	public int fullFN() { return fullConfusion[1][0]; }
}
