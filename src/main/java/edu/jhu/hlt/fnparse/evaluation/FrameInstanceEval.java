package edu.jhu.hlt.fnparse.evaluation;

import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;


public class FrameInstanceEval {
	
	public FrameInstance gold;
	public FrameInstance hyp;
	
	public FrameInstanceEval(FrameInstance gold, FrameInstance hyp) {
		this.gold = gold;
		this.hyp = hyp;
	}
	
	public boolean triggerCorrect() {
		return gold.getTriggerIdx() == hyp.getTriggerIdx();
	}
	
	public double argPrecision() {
		int tp = argTruePositives();
		int fp = argFalsePositives();
		if(tp + fp == 0) return 1d;
		return ((double) tp) / (tp+fp);
	}
	
	public double argRecall() {
		int tp = argTruePositives();
		int fn = argFalseNegatives();
		if(tp + fn == 0) return 1d;
		return ((double) tp) / (tp+fn);
	}
	
	public double argF1() {
		double p = argPrecision();
		double r = argRecall();
		if(p + r == 0d) return 0d;
		return 2d * p * r / (p + r);
	}
	
	public int argTruePositives() {
		int n = gold.getFrame().numRoles();
		int right = 0;
		for(int i=0; i<n; i++) {
			Span h = hyp.getArgument(i);
			if(h != null && h.equals(gold.getArgument(i)))
				right++;
		}
		return right;
	}
	
	public int argFalsePositives() {
		int n = gold.getFrame().numRoles();
		int fp = 0;
		for(int i=0; i<n; i++) {
			Span h = hyp.getArgument(i);
			if(h != null && !h.equals(gold.getArgument(i)))
				fp++;
		}
		return fp;
	}
	
	public int argFalseNegatives() {
		int n = gold.getFrame().numRoles();
		int fn = 0;
		for(int i=0; i<n; i++) {
			Span h = hyp.getArgument(i);
			if(h == null && gold.getArgument(i) != null)
				fn++;
		}
		return fn;
	}
}