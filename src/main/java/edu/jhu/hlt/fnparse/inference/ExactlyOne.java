package edu.jhu.hlt.fnparse.inference;

import java.util.Iterator;

/**
 * This is a hard factor that touches N binary variables,
 * which fires 1 iff there is exactly one binary variable set to 1,
 * and 0 otherwise. Though this factor sums over 2^N configurations,
 * it computes the margins of this table in O(N) time.
 * 
 * Implementation described here:
 * http://cs.jhu.edu/~jason/papers/smith+eisner.emnlp08.pdf
 * 
 * @author travis
 */
public class ExactlyOne {

	// beliefs of N binary variables touching this factor
	private double[] beliefs;
	private boolean logDomain;
	private boolean normalize;
	
	public ExactlyOne(double[] bel, boolean log, boolean normalize) {
		this.beliefs = bel;
		this.logDomain = log;
		this.normalize = normalize;
		
		for(int i=0; i<bel.length; i++) {
			if(log) assert bel[i] <= 0d;
			else assert 0d <= bel[i]; // && bel[i] <= 1d;
		}
	}
	
	public double[] computeMarginals() {
		
		int n = beliefs.length;
		double[] margins = new double[n];
		
		if(!logDomain) {
			
			double pi = 1d;
			for(int i=0; i<n; i++)
				pi *= (1d - beliefs[i]);
		
			for(int i=0; i<n; i++) {
				double qbar = beliefs[i] / (1d - beliefs[i]);
				margins[i] = pi * qbar;
			}
			
			if(normalize) {
				double sum = 0d;
				for(int i=0; i<n; i++) sum += margins[i];
				for(int i=0; i<n; i++) margins[i] /= sum;
			}
		}
		else throw new RuntimeException("implement me");
		
		return margins;
	}
	
	private double[] computeMarginalsBruteForce() {
		
		int n = beliefs.length;
		double[] margins = new double[n];
		
		if(!logDomain) {
			ConfigIter iter = new ConfigIter(n);
			while(iter.hasNext()) {
				boolean[] config = iter.next();
				
				double p = 1d;
				int hot = 0;
				for(int i=0; i<n; i++) {
					if(config[i]) {
						hot++;
						p *= beliefs[i];
					}
					else p *= 1d - beliefs[i];
				}
				if(hot != 1) p = 0d;
				
				for(int i=0; i<n; i++)
					if(config[i])
						margins[i] += p;
			}
			
			if(normalize) {
				double sum = 0d;
				for(int i=0; i<n; i++) sum += margins[i];
				for(int i=0; i<n; i++) margins[i] /= sum;
			}
		}
		else throw new RuntimeException("implement me");
		
		return margins;
	}
	
	/** loops over 2^n configurations */
	private static class ConfigIter implements Iterator<boolean[]> {

		private int i, width;
		
		public ConfigIter(int width) {
			this.i = 0;
			this.width = width;
		}

		public boolean[] i2ba() {
			boolean[] ba = new boolean[width];
			for(int i=0; i<width; i++)
				ba[i] = ((1 << i) & this.i) != 0;
			return ba;
		}
		
		@Override
		public boolean hasNext() {
			return i < (1 << width);
		}

		@Override
		public boolean[] next() {
			boolean[] ba = i2ba();
			i++;
			return ba;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}
	
	public static void main(String[] args) {
		
		long start = System.currentTimeMillis();
		
		for(int width=2; width < 20; width += 2) {
			System.out.println(width);
			for(int t=0; t<100; t++) {
				test(width, true);
				test(width, false);
			}
		}
		
		System.out.printf("done in %.1f seconds\n", (System.currentTimeMillis()-start)/1000d);
	}
	
	private static void test(int width, boolean normalizedBeliefs) {
		
		double[] beliefs = new double[width];
		for(int i=0; i<width; i++) {
			beliefs[i] = Math.random();
			if(!normalizedBeliefs)
				beliefs[i] = Math.exp(2d * beliefs[i] + 1d);
		}
		
		boolean normalize = true;
		boolean logDomain = false;
		ExactlyOne eo = new ExactlyOne(beliefs, logDomain, normalize);
		double[] bfMargins = eo.computeMarginalsBruteForce();
		double[] fMargins = eo.computeMarginals();
		
		assert bfMargins.length == beliefs.length;
		assert fMargins.length == beliefs.length;
		for(int i=0; i<width; i++) {
			//System.out.printf("%d   %.5f  %.5f\n", i, bfMargins[i], fMargins[i]);
			assert Math.abs(bfMargins[i] - fMargins[i]) < 1e-8;
		}
	}
}
