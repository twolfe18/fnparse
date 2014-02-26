package edu.jhu.hlt.fnparse.experiment;

import java.io.File;
import java.util.*;

import edu.jhu.hlt.fnparse.evaluation.*;
import edu.jhu.hlt.fnparse.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.inference.pruning.TriggerPruning;

public class PruningExperiment {
	
	/**
	 * between 0 and 1
	 * 1 means you only care about pruning
	 * 0 means you only care about minimizing incorrect prunes
	 */
	private static final double pruningStrength = 0.6d;
	private static final Random rand = new Random(9001);
	
	public static double score(TriggerPruning pruner, List<FNParse> test) {
		FPR fpr = new FPR();
		int nPrune = 0, nTotal = 0;
		for(FNParse p : test) {
			Sentence s = p.getSentence();
			int n = s.size();
			
			// get the labels
			boolean[] target = new boolean[n];
			for(FrameInstance fi : p.getFrameInstances()) {
				Span trigger = fi.getTarget();
				for(int i=trigger.start; i<trigger.end; i++)
					target[i] = true;
			}
			
			// check the predictions
			for(int i=0; i<n; i++) {
				boolean prune = pruner.prune(i, s);
				boolean shouldPrune = target[i];
				if(prune && shouldPrune)
					fpr.accumTP();
				if(prune && !shouldPrune)
					fpr.accumFP();
				if(!prune && shouldPrune)
					fpr.accumFN();
				if(prune) nPrune++;
				nTotal++;
			}
		}
		double pruneRatio = ((double)nPrune) / nTotal;
		double pruneAccuracy = 1d - Math.pow(fpr.precision(), 1.5d);
		double score = pruningStrength * pruneRatio + (1d - pruningStrength) * pruneAccuracy;
		System.out.printf("[score] pruneRatio=%.1f%% pruneAccuracy=%.1f%% pruneStrength=%.2f => score=%.2f\n",
				pruneRatio*100, pruneAccuracy*100, pruningStrength, score);
		return score;
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
			File f = new File("PruningExperiment-j-PV=" + priorVariance + ".model");
			pruner.params.writeout(f);
		}
		return bestPV;
	}
	
	public static double simpleSweep(TriggerPruning pruner, List<FNParse> train, List<FNParse> dev) {
		double bestPV = 1d;
		double bestThresh = 0.5d;
		double bestScore = 0d;
		for(double priorVariance=0.01d; priorVariance < 100d; priorVariance *= 3d) {
			pruner.train(train, priorVariance);
			for(double thresh=0.5d; thresh<1d; thresh += 0.05) {
				pruner.params.pruneThresh = thresh;
				double score = score(pruner, dev);
				System.out.printf("[simpleSweep] priorVariance=%.2f thresh=%.2f score=%.2f\n",
						priorVariance, thresh, 100d*score);
				if(score > bestScore) {
					bestScore = score;
					bestPV = priorVariance;
					bestThresh = thresh;
					// save the model
					File f = new File("PruningExperiment-ss-PV=" + priorVariance + "_thresh=" + thresh + ".model");
					pruner.params.writeout(f);
				}
			}
		}
		System.out.printf("[simpleSweep] best priorVariance was %.1f and threshold of %.2f with score of %.2f\n",
				bestPV, bestThresh, bestScore*100);
		pruner.params.pruneThresh = bestThresh;
		return bestPV;
	}

	public static void main(String[] args) {
		
		System.out.println("[PruningExperiment] reading in the data...");
		FrameInstanceProvider fip = FileFrameInstanceProvider.fn15trainFIP;
		DataSplitter ds = new DataSplitter();
		List<FNParse> trainDev = new ArrayList<FNParse>();
		List<FNParse> test = new ArrayList<FNParse>();
		ds.split(fip.getParsedSentences(), trainDev, test, 0.15d, "pruning-data-trainDev");
		List<FNParse> train = new ArrayList<FNParse>();
		List<FNParse> dev = new ArrayList<FNParse>();
		ds.split(trainDev, train, dev, 0.15d, "pruning-data-train");
		
		System.out.printf("#train=%d #dev=%d #test=%d\n", train.size(), dev.size(), test.size());
		train = DataUtil.reservoirSample(train, 200);
		dev = DataUtil.reservoirSample(dev, 60);
		test = DataUtil.reservoirSample(test, 60);
		
		TriggerPruning pruner = new TriggerPruning();
		
		//double bestPV = jumpy(pruner, train, dev);
		double bestPV = simpleSweep(pruner, train, dev);
		
		// test the final model we found
		System.out.println("training with best prior variance: " + bestPV + ", and best thresh: " + pruner.params.pruneThresh);
		pruner.train(train, bestPV);
		score(pruner, test);
	}
}
