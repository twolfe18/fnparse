package edu.jhu.hlt.fnparse.inference.newstuff;

import java.io.Serializable;
import java.util.*;

import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Var;

public class ApproxF1MbrDecoder implements Serializable {

	private static final long serialVersionUID = 1L;
	
	private double falsePosPenalty;
	private double falseNegPenalty;
    
    // TODO do decoding over (f_i, r_ijk)!
    // if you're going to bother with joint training you better do joint decoding!
	
	public ApproxF1MbrDecoder() {
		setRecallBias(1d);
	}
	
	public ApproxF1MbrDecoder(double recallBias) {
		setRecallBias(recallBias);
	}
	
	/**
	 * @param recallBias higher values will penalize false negatives
	 * more than false positives, and thus increase recall. If you give
	 * 1, then false positives and false negatives will have the same
	 * loss.
	 */
	public void setRecallBias(double recallBias) {
		if(recallBias < 1e-3 || recallBias > 1e3)
			throw new IllegalArgumentException();		
		if(recallBias >= 1) {
			double rb = Math.sqrt(recallBias);
			this.falseNegPenalty = rb;
			this.falsePosPenalty = 1d / rb;
		}
		else {
			double rb = Math.sqrt(1d / recallBias);
			this.falseNegPenalty = rb;
			this.falsePosPenalty = 1d / rb;
		}
	}
	
	public double getFalsePosPenalty() {
		return falsePosPenalty;
	}
	
	public double getFalseNegPenalty() {
		return falseNegPenalty;
	}
	
	public Map<Var, Integer> decode(Map<Var, DenseFactor> margins, int nullIndex) {
		Map<Var, Integer> m = new HashMap<Var, Integer>();
		for(Map.Entry<Var, DenseFactor> x : margins.entrySet()) {
			int i = decode(x.getValue().getValues(), nullIndex);
			m.put(x.getKey(), i);
		}
		return m;
	}
    
	/**
	 * MBR:
	 * argmin_{y.hat} E_{y ~ posterior} loss(y, y.hat)
	 * 
	 * We are approximating F1 loss by a linear function of type I and type II errors. 
	 * 
	 * The states in the domain of the posterior distribution are
	 * partitioned into two set: nullIndex and not-nullIndex.
	 * 
	 * TP = predicting a label that is not-nullIndex and is also correct
	 * FP = predicting a label that is not-nullIndex and is wrong
	 * FN = predicting nullIndex when the answer is in not-nullIndex
	 * TN = predicting nullIndex when the answer is nullIndex
	 * 
	 * @param posterior should be probabilities (not log-probs)!
	 * @param nullIndex is, e.g., the index of nullFrame
	 * @param risks can be null, but otherwise will be filled with the risk estimates.
	 */
    public int decode(double[] posterior, int nullIndex, double[] risks) {
    	if(risks != null && risks.length != posterior.length)
    		throw new IllegalArgumentException();
    	final int n = posterior.length;
    	int minI = -1;
    	double minR = 0d;
    	double Z = 0d;
    	for(int i=0; i<n; i++)  {		// i indexes predictions
    		Z += posterior[i];
    		double pFP = 0d;
    		double pFN = 0d;
    		for(int j=0; j<n; j++) {	// j indexes the correct answer under the posterior
    			if(i == nullIndex && j != nullIndex)
    				pFN += posterior[j];
    			if(i != nullIndex && j != i)
    				pFP += posterior[j];
    		}
    		double risk = pFP * falsePosPenalty + pFN * falseNegPenalty;
    		if(risk < minR || minI < 0) {
    			minR = risk;
    			minI = i;
    		}
    		if(risks != null)
    			risks[i] = risk;
    	}
    	if(Math.abs(Z - 1d) > 1e-5)
    		throw new IllegalArgumentException("that posterior ain't a distribution! " + Arrays.toString(posterior));
    	return minI;
    }
    public int decode(double[] posterior, int nullIndex) {
    	return decode(posterior, nullIndex, null);
    }
    
}

