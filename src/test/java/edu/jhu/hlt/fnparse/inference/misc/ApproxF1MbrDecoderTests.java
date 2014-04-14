package edu.jhu.hlt.fnparse.inference.misc;

import static org.junit.Assert.*;

import java.util.Arrays;

import org.junit.Test;

public class ApproxF1MbrDecoderTests {
	
	@Test
	public void basic() {
		double recallBias = 2d;
		double[] probs = new double[] { 0.1d, 0.2d, 0.3d, 0.4d };
		double[] risks = new double[probs.length];
		int nullIdx = probs.length - 1;
		ApproxF1MbrDecoder dec = new ApproxF1MbrDecoder(false, recallBias);
		assertEquals(recallBias, dec.getFalseNegPenalty() / dec.getFalsePosPenalty(), 1e-6);
		int best = dec.decode(probs, nullIdx, risks);
		System.out.printf("recallBias=%.1f probs=%s risks=%s logSpace=%s\n", recallBias, Arrays.toString(probs), Arrays.toString(risks), dec.isLogSpace());
		assertEquals(probs.length - 2, best);
		
		for(int i=0; i<probs.length; i++)
			probs[i] = Math.log(probs[i]);
		ApproxF1MbrDecoder logDec = new ApproxF1MbrDecoder(true, recallBias);
		int logBest = logDec.decode(probs, nullIdx, risks);
		System.out.printf("recallBias=%.1f probs=%s risks=%s logSpace=%s\n", recallBias, Arrays.toString(probs), Arrays.toString(risks), logDec.isLogSpace());
		assertEquals(best, logBest);

		recallBias = 0.001;
		dec.setRecallBias(recallBias);
		logDec.setRecallBias(recallBias);
		assertEquals(recallBias, dec.getFalseNegPenalty() / dec.getFalsePosPenalty(), 1e-6);
		int shouldBeNullIdx = logDec.decode(probs, nullIdx, risks);
		System.out.printf("recallBias=%.1f probs=%s risks=%s logSpace=%s\n", recallBias, Arrays.toString(probs), Arrays.toString(risks), logDec.isLogSpace());
		assertEquals("risks = " + Arrays.toString(risks), nullIdx, shouldBeNullIdx);

		for(int i=0; i<probs.length; i++)
			probs[i] = Math.exp(probs[i]);
		shouldBeNullIdx = dec.decode(probs, nullIdx, risks);
		System.out.printf("recallBias=%.1f probs=%s risks=%s logSpace=%s\n", recallBias, Arrays.toString(probs), Arrays.toString(risks), dec.isLogSpace());
		assertEquals("risks = " + Arrays.toString(risks), nullIdx, shouldBeNullIdx);
	}
}
