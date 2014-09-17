package edu.jhu.hlt.fnparse.evaluation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameArgInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * Holds the data needed to evaluate parses.
 * 
 * @author travis
 */
public class SentenceEval {

	/*
	 * indexed with [gold][hyp]
	 * e.g. targets[1][0] = # false negatives
	 * 
	 * NOTE: true negatives is a lower bound rather than exact,
	 * it assumes that the only possible set of predictions in the
	 * universe are the ones that were either predicted or in the gold
	 * labels (real set of true negatives would include all possible
	 * negatives, which is much larger).
	 */
	private final int[][] targetConfusion;
	private final int[][] fullConfusion;

	// like fullConfusion, but doesn't include frame-targets as a prediction, should only be used for argId evaluation (gold frameId)
	private final int[][] argOnlyConfusion;

	// True if we are only evaluating the frames tagged and not the args
	private final boolean onlyTagging;

	private final int size;		// Length of sentence in gold and hyp examples
	private final FNTagging gold, hyp;

	// Not always populated
	private List<FrameArgInstance> targetFalsePos, targetFalseNeg;
	private List<FrameArgInstance> fullFalsePos, fullFalseNeg;

	public SentenceEval(FNTagging gold, FNTagging hyp) {
		this(gold, hyp, true);
	}

	public SentenceEval(FNTagging gold, FNTagging hyp, boolean storeDebugInfo) {
		if(!gold.getSentence().getId().equals(hyp.getSentence().getId())) {
			String msg = "goldSent=" + gold.getSentence().getId()
					+ " hypSent=" + hyp.getSentence().getId();
			throw new IllegalArgumentException(msg);
		}
		onlyTagging = !(gold instanceof FNParse) || !(hyp instanceof FNParse);
		if (hyp instanceof FNParse)
			assert gold instanceof FNParse;

		this.targetConfusion = new int[2][2];
		if (!onlyTagging) {
			this.fullConfusion = new int[2][2];
			this.argOnlyConfusion = new int[2][2];
		}
		else {
			this.fullConfusion = null;
			this.argOnlyConfusion = null;
		}
		this.size = gold.getSentence().size();
		this.gold = gold;
		this.hyp = hyp;

		if(storeDebugInfo) {
			targetFalsePos = new ArrayList<>();
			targetFalseNeg = new ArrayList<>();
			if (!onlyTagging) {
				fullFalsePos = new ArrayList<>();
				fullFalseNeg = new ArrayList<>();
			}
		}

		Set<FrameArgInstance> goldTargets = new HashSet<>();
		Set<FrameArgInstance> hypTargets = new HashSet<>();

		Set<FrameArgInstance> goldTargetRoles = null, hypTargetRoles = null;
		Set<FrameArgInstance> goldRoles = null, hypRoles = null;
		if (!onlyTagging) {
			goldTargetRoles = new HashSet<>();
			hypTargetRoles = new HashSet<>();
			goldRoles = new HashSet<>();
			hypRoles = new HashSet<>();
		}

		fillPredictions(gold.getFrameInstances(),
				goldTargets, goldTargetRoles, goldRoles);
		fillPredictions(hyp.getFrameInstances(),
				hypTargets, hypTargetRoles, hypRoles);

		fillConfusionTable(goldTargets, hypTargets,
				targetConfusion, targetFalsePos, targetFalseNeg);
		if (goldTargetRoles != null) {
			fillConfusionTable(goldTargetRoles, hypTargetRoles,
					fullConfusion, fullFalsePos, targetFalseNeg);
		}
		if (goldRoles != null) {
			fillConfusionTable(goldRoles, hypRoles,
					argOnlyConfusion, null, null);
		}
	}

	public boolean isFNTagging() {
		return onlyTagging;
	}

	// only work if storeDebugInfo was true
	public FNTagging getGold() { return gold; }
	public FNTagging getHypothesis() { return hyp; }
	public FNParse getGoldParse() { return (FNParse) gold; }
	public FNParse getHypothesisParse() {
		assert !onlyTagging;
		return (FNParse) hyp;
	}
	public List<FrameArgInstance> getTargetFalsePos() { return targetFalsePos; }
	public List<FrameArgInstance> getTargetFalseNeg() { return targetFalseNeg; }
	public List<FrameArgInstance> getFullFalsePos() {
		assert !onlyTagging;
		return fullFalsePos;
	}
	public List<FrameArgInstance> getFullFalseNeg() {
		assert !onlyTagging;
		return fullFalseNeg;
	}
	
	@Override
	public String toString() {
		return gold.toString() + "\n" + hyp.toString();
	}
	
	public String longString() {
		throw new RuntimeException("implement me");
	}
	
	public int size() { return size; }
	
	public static void fillPredictions(
			List<FrameInstance> fis,
			Collection<FrameArgInstance> targetPreds,
			Collection<FrameArgInstance> targetRolePreds,
			Collection<FrameArgInstance> onlyArgPreds) {
		for(FrameInstance fi : fis) {
			Frame f = fi.getFrame();
			Span t = fi.getTarget();
			if (targetRolePreds != null)
				targetRolePreds.add(new FrameArgInstance(f, t, -1, null));
			int n = fi.getFrame().numRoles();
			for(int i=0; i<n; i++) {
				Span arg = fi.getArgument(i);
				if(arg != Span.nullSpan) {
					FrameArgInstance p = new FrameArgInstance(f, t, i, arg);
                    if (targetRolePreds != null)
                    	targetRolePreds.add(p);
                    if (onlyArgPreds != null)
                    	onlyArgPreds.add(p);
				}
			}
		}
	}
	
	public void fillConfusionTable(
			Collection<FrameArgInstance> gold,
			Collection<FrameArgInstance> hyp,
			int[][] confusion,
			List<FrameArgInstance> fpStore,
			List<FrameArgInstance> fnStore) {
		Set<FrameArgInstance> s = new HashSet<>();

		// TP = G & H
		s.clear();
		s.addAll(gold);
		s.retainAll(hyp);
		confusion[1][1] = s.size();

		// FP = H -- G
		s.clear();
		s.addAll(hyp);
		s.removeAll(gold);
		confusion[0][1] = s.size();
		if(fpStore != null)
			fpStore.addAll(s);

		// FN = G -- H
		s.clear();
		s.addAll(gold);
		s.removeAll(hyp);
		confusion[1][0] = s.size();
		if(fnStore != null)
			fnStore.addAll(s);

		// TN
		s.clear();
		s.addAll(gold);
		s.addAll(hyp);
		confusion[0][0] = s.size() - (confusion[1][1] + confusion[0][1] + confusion[1][0]);

		assert confusion[1][1] >= 0;
		assert confusion[0][1] >= 0;
		assert confusion[1][0] >= 0;
		assert confusion[0][0] >= 0;
	}

	public int targetTP() { return targetConfusion[1][1]; }
	public int targetFP() { return targetConfusion[0][1]; }
	public int targetFN() { return targetConfusion[1][0]; }

	public int fullTP() { return fullConfusion[1][1]; }
	public int fullFP() { return fullConfusion[0][1]; }
	public int fullFN() { return fullConfusion[1][0]; }

	public int argOnlyTP() { return argOnlyConfusion[1][1]; }
	public int argOnlyFP() { return argOnlyConfusion[0][1]; }
	public int argOnlyFN() { return argOnlyConfusion[1][0]; }

	// TODO arg accuracy (both predictions? just args?)
}
