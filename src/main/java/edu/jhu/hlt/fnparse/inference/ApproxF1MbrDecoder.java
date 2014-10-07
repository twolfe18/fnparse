package edu.jhu.hlt.fnparse.inference;

import java.io.Serializable;
import java.util.*;

import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.prim.util.math.FastMath;

public class ApproxF1MbrDecoder implements Serializable {
	private static final long serialVersionUID = 1L;

	private boolean logSpace;
	private double falsePosPenalty;
	private double falseNegPenalty;
	private double recallBias;

	public ApproxF1MbrDecoder(boolean logSpace) {
		setRecallBias(1d);
		this.logSpace = logSpace;
	}

	public ApproxF1MbrDecoder(boolean logSpace, double recallBias) {
		setRecallBias(recallBias);
		this.logSpace = logSpace;
	}

	/**
	 * @param recallBias higher values will penalize false negatives
	 * more than false positives, and thus increase recall. If you give
	 * 1, then false positives and false negatives will have the same
	 * loss.
	 */
	public void setRecallBias(double recallBias) {
		this.recallBias = recallBias;
		double rb = Math.sqrt(recallBias);
		this.falseNegPenalty = rb;
		this.falsePosPenalty = 1d / rb;
	}

	public double getRecallBias() {
		return recallBias;
	}

	public double getFalsePosPenalty() {
		return falsePosPenalty;
	}

	public double getFalseNegPenalty() {
		return falseNegPenalty;
	}

	public boolean isLogSpace() { return logSpace; }

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

    	int minI = -1;
    	double minR = 0d;
    	double Z = zero();

    	final int n = posterior.length;
    	for(int i=0; i<n; i++)  {		// i indexes predictions
    		if(!check(posterior[i])) throw new RuntimeException();
    		Z = plus(Z, posterior[i]);
    		double pFP = zero();
    		double pFN = zero();
    		// TODO this can be made more efficient by keeping a sum and subtracting out
    		for(int j=0; j<n; j++) {	// j indexes the correct answer under the posterior
    			if(i == nullIndex && j != nullIndex)
    				pFN = plus(pFN, posterior[j]);
    			if(i != nullIndex && j != i)
    				pFP = plus(pFP, posterior[j]);
    		}
    		double risk = risk(pFP, falsePosPenalty, pFN, falseNegPenalty);
    		if(risk < minR || minI < 0) {
    			minR = risk;
    			minI = i;
    		}
    		if(risks != null)
    			risks[i] = risk;
    	}

    	if(Math.abs(Z - one()) > 1e-5)
    		throw new IllegalArgumentException("that posterior isn't a distribution! " + Arrays.toString(posterior));

    	return minI;
    }

    public int decode(double[] posterior, int nullIndex) {
    	return decode(posterior, nullIndex, null);
    }

    public double risk(double probA, double lossA, double probB, double lossB) {
    	if(this.logSpace) return Math.exp(probA) * lossA + Math.exp(probB) * lossB;
    	else return probA * lossA + probB * lossB;
    }

    public double plus(double a, double b) {
    	if(this.logSpace) return FastMath.logAdd(a, b);
    	else return a + b;
    }

    public double one() {
    	return this.logSpace ? 0d : 1d;
    }

    public double zero() {
    	return this.logSpace ? Double.NEGATIVE_INFINITY : 0d;
    }

    public boolean check(double prob) {
    	if(Double.isNaN(prob)) return false;
    	if(this.logSpace) return prob <= 1d;
    	else return prob >= 0d && prob <= 1d;
    }
}
