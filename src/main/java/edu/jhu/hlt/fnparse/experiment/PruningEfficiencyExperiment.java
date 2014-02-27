package edu.jhu.hlt.fnparse.experiment;

import java.io.File;
import java.util.*;

import edu.jhu.hlt.fnparse.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.evaluation.FPR;
import edu.jhu.hlt.fnparse.inference.newstuff.*;
import edu.jhu.hlt.fnparse.inference.pruning.*;

/**
 * @deprecated see discussion of pruning in TriggerPruning
 * @author travis
 */
public class PruningEfficiencyExperiment {
	
	/**
	 * between 0 and 1
	 * 1 means you only care about pruning
	 * 0 means you only care about minimizing incorrect prunes
	 */
	private static final double pruningStrength = 0.6d;
	private static final Random rand = new Random(9001);
	private static final Parser.ParserParams parserParams = new Parser().params;
	private static final File dumpModelsTo = new File("pruning");
	
	/**
	 * recall = targetsReachable / targets
	 * timeSavings = pruned / N
	 * score = linear combination of recall and timeSavings
	 * @author travis
	 */
	static final class PruneStats {
		public int numTargets = 0;			// number of FrameInstances
		public int numTargetsReachable = 0;	// number of FrameInstances who's target is reachable by inference
		public int numHeads = 0;			// number of non-null f_i
		public int numHeadsPruned = 0;		// number of non-null f_i
		public double numHeadsW = 0;		// numHeads weighted by |f_i|
		public double numHeadsPrunedW = 0;	// numHeadsPruned weighted by |f_i|
		
		/**
		 * Given a frame instance, is its target reachable by decoding?
		 * @param f is the Frame of the FrameInstance we're trying to reach (not used yet)
		 * @param reachable is whether there is a way to decode this Frame's target appearing at this FrameInstance's target.
		 */
		public void accumTarget(Frame f, boolean reachable) {
			numTargets++;
			if(reachable) numTargetsReachable++;
		}
		
		/**
		 * @param pruned was this f_i variable pruned?
		 * @param weight what would have been the number of frames possible in f_i?
		 */
		public void accumFV(boolean pruned, double weight) {
			numHeads++;
			numHeadsW += weight;
			if(pruned) {
				numHeadsPruned++;
				numHeadsPrunedW += weight;
			}
		}
		
		public double recall() {
			return ((double) numTargetsReachable) / numTargets;
		}
		
		public double pruneRatio() {
			if(numHeads == 0) return 0d;
			return ((double) numHeadsPruned) / numHeads;
		}
		
		public double weightedPruneRatio() {
			if(numHeadsW == 0d) return 0d;
			return ((double) numHeadsPrunedW) / numHeadsW;
		}
		
		public double score(double pruningStrength, boolean useWeightedPruneRatio) {
			if(pruningStrength < 0 || pruningStrength > 1)
				throw new IllegalArgumentException();
			double pr = useWeightedPruneRatio ? weightedPruneRatio() : pruneRatio();
			return pruningStrength * pr + (1d - pruningStrength) * recall();
		}
		
		@Override
		public String toString() {
			return String.format("<PruneStats recall=%.1f pruneRatio=%.1f>", 100*recall(), 100*pruneRatio());
		}
	}
	
	public static double scoreOld(TriggerPruning pruner, List<FNParse> test) {
		FPR prunerFPR = new FPR();
		FPR parserFPR = new FPR();
		int N = 0;
		for(FNParse p : test) {

			Sentence s = p.getSentence();
			int n = s.size();

			// get the labels
			boolean[] inTarget = new boolean[n];
			for(FrameInstance fi : p.getFrameInstances()) {
				Span trigger = fi.getTarget();
				for(int i=trigger.start; i<trigger.end; i++)
					inTarget[i] = true;
			}

			// see what the parser would prune
			ParsingSentence ps = new ParsingSentence(s, parserParams);

			// check the predictions
			for(int i=0; i<n; i++) {
				boolean prunerPrune = pruner.prune(i, s);
				boolean parserPrune = ps.frameVars[i] == null;
				boolean shouldPrune = inTarget[i];
				prunerFPR.accum(shouldPrune, prunerPrune);
				parserFPR.accum(shouldPrune, parserPrune);
				N++;
			}
		}
		double pruneRatio, pruneAccuracy, score;

		// parser scores
		pruneRatio = (parserFPR.getTP() + parserFPR.getFP()) / N;
		pruneAccuracy = Math.pow(parserFPR.precision(), 1.5d);
		score = pruningStrength * pruneRatio + (1d - pruningStrength) * pruneAccuracy;
		System.out.printf("[scoreOld parser] pruneRatio=%.1f%% pruneAccuracy=%.1f%% pruneStrength=%.2f => score=%.2f\n",
				pruneRatio*100, parserFPR.precision()*100, pruningStrength, 100*score);
		
		// pruner scores
		pruneRatio = (prunerFPR.getTP() + prunerFPR.getFP()) / N;
		pruneAccuracy = Math.pow(prunerFPR.precision(), 1.5d);
		score = pruningStrength * pruneRatio + (1d - pruningStrength) * pruneAccuracy;
		System.out.printf("[scoreOld pruner] pruneRatio=%.1f%% pruneAccuracy=%.1f%% pruneStrength=%.2f => score=%.2f\n",
				pruneRatio*100, prunerFPR.precision()*100, pruningStrength, 100*score);
		
		return score;
	}
	
	public static double score(TriggerPruning pruner, List<FNParse> test) {
		
		PruneStats prunerStats = new PruneStats();
		PruneStats parserStats = new PruneStats();
		
		for(FNParse p : test) {
			
			Sentence s = p.getSentence();
			int n = s.size();

			// get the labels
			boolean[] inTarget = new boolean[n];
			for(FrameInstance fi : p.getFrameInstances()) {
				Span trigger = fi.getTarget();
				for(int i=trigger.start; i<trigger.end; i++)
					inTarget[i] = true;
			}

			// see what the parser would prune
			ParsingSentence ps = new ParsingSentence(s, parserParams);

			// set what the pruner would prune
			boolean[] pruned = new boolean[n];
			for(int i=0; i<n; i++) pruned[i] = pruner.prune(i, s);

			
			// PARSER RECALL
			List<Boolean> parserReachable = new ArrayList<Boolean>();
			for(FrameInstance fi : p.getFrameInstances()) {
				Span target = fi.getTarget();
				boolean targetReachable = false;
				for(int i=target.start; i<target.end; i++) {
					Expansion e = Expansion.headToSpan(i, target);
					FrameVar fv = ps.frameVars[i];
					if(fv == null) continue;
					int fv_fi = fv.getFrames().indexOf(fi.getFrame());
					int fv_ei = fv.getExpansions().indexOf(e);
					if(fv_ei >= 0 && fv_fi >= 0) {
						targetReachable = true;
						break;
					}					
				}
				parserStats.accumTarget(fi.getFrame(), targetReachable);
				parserReachable.add(targetReachable);
			}
			
			// PARSER PRUNING
			for(int i=0; i<n; i++) {
				FrameVar fv = ps.frameVars[i];
				if(fv == null) parserStats.accumFV(true, 1d);
				else parserStats.accumFV(false, fv.getFrames().size());
			}
			
			
			// PRUNER RECALL
			int fiIdx = 0;
			for(FrameInstance fi : p.getFrameInstances()) {
				
				// lets pretend that we took the intersection of parser & pruner:
				// we only consider an f_i open if the parser produced a FrameVar and pruner didn't prune it.
				// we'll use this assumption in determining when 
				boolean parserTargetReachable = parserReachable.get(fiIdx++);
				if(!parserTargetReachable) {
					prunerStats.accumTarget(fi.getFrame(), false);
					continue;
				}
				
				Span target = fi.getTarget();
				boolean stillReachable = false;
				for(int i=target.start; i<target.end; i++) {
					Expansion e = Expansion.headToSpan(i, target);
					FrameVar fv = ps.frameVars[i];
					if(fv == null) continue;
					if(pruned[i]) continue;		// the difference from above!
					int fv_fi = fv.getFrames().indexOf(fi.getFrame());
					int fv_ei = fv.getExpansions().indexOf(e);
					if(fv_ei >= 0 && fv_fi >= 0) {
						stillReachable = true;
						break;
					}
				}
				prunerStats.accumTarget(fi.getFrame(), stillReachable);
			}
			
			// PRUNER PRUNING
			for(int i=0; i<n; i++) {
				FrameVar fv = ps.frameVars[i];
				if(fv == null || pruned[i]) {
					double weight = fv == null ? 1d : fv.getFrames().size();
					prunerStats.accumFV(true, weight);
				}
				else prunerStats.accumFV(false, fv.getFrames().size());
			}
		}
		
		System.out.println("[score] pruner: " + prunerStats);
		System.out.println("[score] parser: " + parserStats);
		final boolean useWeightedPruneRatio = false;
		return prunerStats.score(pruningStrength, useWeightedPruneRatio);
	}
	
	
	
	/**
	 * @deprecated too crazy, doesn't handle threshold
	 */
	public static double jumpy(TriggerPruning pruner, List<FNParse> train, List<FNParse> dev) {
		double bestPV = 10d;
		double bestScore = 0d;
		
		double prevPriorVariance = 3d;
		double priorVariance = 5d;
		double prevScore = 0.2d;
		double score = 0.25d;
		double prevDScore = 0.1d;
		for(int iter=0; iter<25; iter++) {
			
			// choose a new prior parameter
			double forgetfulness = 0.9d;
			double dScore = forgetfulness * (score - prevScore) + (1d-forgetfulness) * prevDScore;
			double logPVstep = dScore * (Math.log(priorVariance) - Math.log(prevPriorVariance));
			double logPVmu = Math.log(priorVariance) + 25d * logPVstep;
			double energy = 1d / (0.1 + Math.abs(dScore));	// if dScore is small, take a large random jump
			energy *= 4d / (4d + iter);	//	...but only at early iterations
			double logPV = rand.nextGaussian() * energy * 0.01d + logPVmu;
			double expectedNewPV = Math.exp(logPVmu);
			double newPriorVariance = Math.exp(logPV);
			System.out.printf("PV %.1f => %.1f, Score %.1f => %.1f, energy=%.1f, new PV = %.1f ~ %.1f\n",
					prevPriorVariance, priorVariance, 100*prevScore, 100d*score, energy, newPriorVariance, expectedNewPV);
			prevPriorVariance = priorVariance;
			priorVariance = newPriorVariance;
			prevDScore = dScore;
			
			// test it out
			pruner.train(train, priorVariance);
			double newScore = score(pruner, dev);
			//double newScore = rand.nextDouble();
			System.out.printf("iter=%d priorVariance=%.2f score=%.2f\n", iter+1, priorVariance, 100d*score);
			prevScore = score;
			score = newScore;
			if(score > bestScore) {
				bestScore = score;
				bestPV = priorVariance;
			}
			
			// save the model
			if(dumpModelsTo != null) {
				File f = new File(dumpModelsTo, "PruningExperiment-j-PV=" + priorVariance + ".model");
				pruner.params.writeout(f);
			}
		}
		return bestPV;
	}
	
	public static double simpleSweep(TriggerPruning pruner, List<FNParse> train, List<FNParse> dev) {
		
		List<FNParse> trainButSmaller = DataUtil.reservoirSample(train, 200);
		
		double bestPV = 1d;
		double bestThresh = 0.5d;
		double bestScore = 0d;
		
		for(double priorVariance=4d; priorVariance < 1500d; priorVariance *= 2d) {
			System.out.printf("[simpleSweep] starting priorVariance=%.2f\n", priorVariance);
			pruner.train(train, priorVariance);
			for(double thresh=0.4d; thresh<1d; thresh += 0.05) {
				pruner.params.pruneThresh = thresh;
				double score = score(pruner, dev);
				System.out.printf("[simpleSweep] priorVariance=%.2f thresh=%.2f score=%.2f\n", priorVariance, thresh, 100d*score);
				if(score > bestScore) {
					bestScore = score;
					bestPV = priorVariance;
					bestThresh = thresh;
					// save the model
					if(dumpModelsTo != null) {
						File f = new File(dumpModelsTo, "PruningExperiment-ss-PV=" + priorVariance + "_thresh=" + thresh + ".model");
						pruner.params.writeout(f);
					}
				}
				
				// performance is really bad!
				// lets see if we are fitting the training data
				System.out.println("[simpleSweep] scoring on training data for threshold=" + thresh);
				score(pruner, trainButSmaller);
			}
		}
		System.out.printf("[simpleSweep] best priorVariance was %.2f and threshold of %.2f with score of %.2f\n",
				bestPV, bestThresh, bestScore*100);
		pruner.params.pruneThresh = bestThresh;
		return bestPV;
	}

	
	// in training data: 5961 / 24534 pruned = 24.3%
	// i'm getting prune ratio's around 19.8%, with precision^1.5 = 73.4
	// ...this is about as good as we could hope to do :(
	
	
	public static void main(String[] args) {
		
		if(dumpModelsTo != null && !dumpModelsTo.isDirectory()) {
			System.out.println("dumping models to " + dumpModelsTo.getPath());
			dumpModelsTo.mkdir();
		}
		
		System.out.println("[PruningExperiment] reading in the data...");
		FrameInstanceProvider fip = FileFrameInstanceProvider.fn15trainFIP;
		DataSplitter ds = new DataSplitter();
		List<FNParse> trainDev = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(fip.getParsedSentences(), trainDev, test, 0.1d, "pruning-data-trainDev");
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> dev = new ArrayList<FNParse>();
		ds.split(trainDev, train, dev, 0.2d, "pruning-data-train");
		
//		train = DataUtil.reservoirSample(train, 200);
//		dev = DataUtil.reservoirSample(dev, 50);
//		test = DataUtil.reservoirSample(test, 50);
		System.out.printf("#train=%d #dev=%d #test=%d\n", train.size(), dev.size(), test.size());
		
		TriggerPruning pruner = new TriggerPruning();
		
		//double bestPV = jumpy(pruner, train, dev);
		double bestPV = simpleSweep(pruner, train, dev);
		
		// test the final model we found
		System.out.println("training with best priorVariance: " + bestPV + ", and best thresh: " + pruner.params.pruneThresh);
		pruner.train(trainDev, bestPV);
		score(pruner, test);
	}
}
