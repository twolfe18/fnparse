package edu.jhu.hlt.fnparse.evaluation;

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

	private Sentence sentence;
	
	/*
	 * indexed with [gold][hyp]
	 * e.g. targets[1][0] = # false negatives
	 */
	private int[][] targets;
	
	public SentenceEval(Sentence s) {
		
		if(!s.hasGoldFrames())
			throw new IllegalArgumentException();
		if(!s.hasHypFrames())
			throw new IllegalArgumentException();
		
		this.sentence = s;
		
		// compute TP, FP, FN
		// part 1: build index
		int n = sentence.size();
		int[] g = new int[n];
		int[] h = new int[n];
		assert Frame.nullFrame.getId() == 0;
		for(FrameInstance fi : s.getGoldFrames())
			g[fi.getTargetIdx()] = fi.getFrame().getId();
		for(FrameInstance fi : s.getHypFrames())
			h[fi.getTargetIdx()] = fi.getFrame().getId();
		// part 2: fill out table
		targets = new int[2][2];
		for(int i=0; i<n; i++) {
			if(g[i] != 0) {
				if(h[i] == g[i]) targets[1][1]++;
				else targets[1][0]++;
			}
			else {
				if(h[i] == 0) targets[0][0]++;
				else targets[0][1]++;
			}
		}
	}
	
	public int targetTP() { return targets[1][1]; }
	public int targetFP() { return targets[0][1]; }
	public int targetFN() { return targets[1][0]; }
}
