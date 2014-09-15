package edu.jhu.hlt.fnparse.util;

import edu.jhu.gm.model.FgModel;
import edu.jhu.util.Alphabet;

public class ModelMerger {
	
	public static class Model<T> {
		public final double[] weights;
		public final Alphabet<T> alphabet;

		public Model(int numWeights) {
			weights = new double[numWeights];
			alphabet = new Alphabet<>();
		}

		public Model(Alphabet<T> alphabet, double[] weights) {
			this.weights = weights;
			this.alphabet = alphabet;
		}

		public Model(Alphabet<T> alphabet, FgModel weights) {
			this.weights = new double[weights.getNumParams()];
			weights.updateDoublesFromModel(this.weights);
			this.alphabet = alphabet;
		}

		public FgModel getFgModel() {
			FgModel m = new FgModel(weights.length);
			m.updateModelFromDoubles(weights);
			return m;
		}

		/**
		 * Indices less than this value are valid in both this model's Alphabet
		 * and weights.
		 */
		public int numValidWeights() {
			int a = alphabet.size();
			return a < weights.length ? a : weights.length;
		}
	}

	@SafeVarargs
	public static <T> Model<T> merge(Model<T>... models) {
		if (models.length <= 2) throw new IllegalArgumentException();
		Model<T> merged = merge(models[0], models[1]);
		for (int i = 2; i < models.length; i++)
			merged = merge(merged, models[i]);
		return merged;
	}

	public static <T> Model<T> merge(Model<T> a, Model<T> b) {
		Model<T> m = new Model<>(a.weights.length + b.weights.length);
		addFeaturesAndWeights(a, m);
		addFeaturesAndWeights(b, m);
		return m;
	}
	
	private static <T> void addFeaturesAndWeights(Model<T> from, Model<T> to) {
		for (int i = 0; i < from.numValidWeights(); i++) {
			double v = from.weights[i];
			if (v == 0d) continue;
			T feat = from.alphabet.lookupObject(i);
			int idx = to.alphabet.lookupIndex(feat, true);
			if (to.weights[idx] != 0d)
				throw new RuntimeException(feat + " appears twice");
			to.weights[idx] = v;
		}
	}

}
