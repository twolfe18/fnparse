
package edu.jhu.hlt.fnparse.inference.pruning;

import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.gm.model.*;

public class TriggerPruning {

	// the goal here is to force certain f_i to be nullFrame
	// lets just do a CRF with a cutoff on prob(f_i == nullFrame)
	// (i could do a SVM with weighted hinge in for FP/FN instanecs,
	// but this is not worth it given how easily i can train with matts code)
	static class TriggerPruningParams {
		public FgModel model;
		public Alphabet<String> featIdx;
	}

	static class TriggerPruningFactor extends ExpFamFactor {
		public final boolean goldLabel;
		public TriggerPruningFactor(Var shouldPrune, boolean goldLabel) {
			super(new VarSet(shouldPrune));
			this.goldLabel = goldLabel;
		}
		public FeatureVector getFeatures(int config) {
			throw new RuntimeException("impelement me");
		}
	}
	
	private TriggerPruningParams params;

	public boolean prune(int headIdx, Sentence s) {
		// TODO do prediction
		return false;
	}

	public void train(List<FNTagging> examples) {
		FgExampleMemoryStore exs = new FgExampleMemoryStore();
		for(FNTagging tagging : examples) {
			Sentence s = tagging.getSentence();
			int n = s.size();
			
		}
	}
}

