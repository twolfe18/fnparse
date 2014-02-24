package edu.jhu.hlt.fnparse.inference.newstuff;

import edu.jhu.gm.model.VarSet;

public class AnnealingDecoder {
	
	/**
	 * note: this class is not ready yet.
	 * i don't even think annealing can be implemented purely as a decoder
	 * we really need to run BP every time we change the temperature, so it
	 * seems like we might need to add temperatures to BP. ask matt about
	 * this, and implement simple greedy decoder for now.
	 * @author travis
	 */

	
	/*
	 * MBR: configuration with lowest expected loss in your posterior
	 *   (usually loss factors with your model)
	 * \arg\min_y E_{posterior} R(y; theta)
	 *   = \arg\min_y \sum_{l \in Delta} R_l(y_l) * p(y_l; theta)
	 * 
	 * Viterbi: most probable joint configuration (no loss term)
	 * 
	 * since the loss in our case is over (frame, args) -- i.e. the arg labels depend on the frame,
	 * MBR decoding amounts to putting a factor over (frame, args) cliques, which is
	 * basically the whole model.
	 * 
	 * what is the relationship between MBR decoding and annealing?
	 * ideally, our decoder should marginalize over prototypes, which
	 * annealing doesn't do (it finds a hard assignment for it).
	 * 
	 * is there a way to say, ``anneal over these vars and marginalize over these"?
	 * 
	 * its not just prototypes that we want to sum over, its syntax too.
	 * multiplying all the model parameters will drive the latent variables towards point masses, so that is wrong.
	 * if you only multiply factors that touch only output variables might work...
	 *   the issue is if the factors that span latent-observed vars will be under-weighted if you do this.
	 *   i believe if factors are renormalized before computing F->V messages, then it should work
	 *   but this is all moot! i should just write out the MBR math.
	 * 
	 */
	
	public static class AnnealingDecoderParams {
		/**
		 * the set of variables to anneal the beliefs for
		 */
		private VarSet anneal;

		/**
		 * for(double temp=1; temp >= minTemp; temp -= tempIncr) {
		 *   // run BP with annealing
		 * }
		 * // output beliefs
		 */
		private double tempIncr = 0.1d;
		private double minTemp = 0.1d;
	}
	
}
