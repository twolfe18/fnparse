package edu.jhu.hlt.fnparse.util;

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

//		public Model(Alphabet<T> alphabet, FgModel weights) {
//			this.weights = new double[weights.getNumParams()];
//			weights.updateDoublesFromModel(this.weights);
//			this.alphabet = alphabet;
//		}
//
//		public FgModel getFgModel() {
//			FgModel m = new FgModel(weights.length);
//			m.updateModelFromDoubles(weights);
//			return m;
//		}

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

//	// TODO merge this into PipelinedFnParser
//	/** This method will modify the first model argument */
//	@SafeVarargs
//	public static PipelinedFnParser merge(PipelinedFnParser... models) {
//		if (models.length <= 2) throw new IllegalArgumentException();
//		PipelinedFnParser merged = models[0];
//		for (int i = 1; i < models.length; i++)
//			merge(merged, models[i]);
//		return merged;
//	}

	public static <T> Model<T> merge(Model<T> a, Model<T> b) {
		Model<T> m = new Model<>(a.weights.length + b.weights.length);
		addFeaturesAndWeights(a, m);
		addFeaturesAndWeights(b, m);
		return m;
	}

//	/**
//	 * Merges the alphabets and weights of a and b, and then sets them in a.
//	 */
//	public static void merge(PipelinedFnParser a, PipelinedFnParser b) {
//		/*
//		Model<String> ma = mergePipelinedFnParserModel(a);
//		Model<String> mb = mergePipelinedFnParserModel(b);
//		Model<String> mm = merge(ma, mb);
//		a.setAlphabet(mm.alphabet);
//		FgModel mmm = mm.getFgModel();
//		a.getFrameIdStage().setWeights(mmm);
//		a.getArgIdStage().setWeights(mmm);
//		a.getArgSpanStage().setWeights(mmm);
//		*/
//		throw new RuntimeException("make this work");
//	}

//	/**
//	 * Merges the frameId, argId, etc models contained within a PipelinedFnParser
//	 * into one Model<String>
//	 */
//	private static Model<String> mergePipelinedFnParserModel(PipelinedFnParser p) {
//		// The alphabet is already global to the parser, so the models just need
//		// to be extended to all overlap in the global namespace.
//		Alphabet<String> alph = p.getAlphabet();
//		final double[] weights = new double[alph.size()];
//		FnIntDoubleToDouble lambda = new FnIntDoubleToDouble() {
//			@Override
//			public double call(int arg0, double arg1) {
//				if (arg0 >= weights.length) {
//					throw new RuntimeException("index is too high! "
//							+ arg0 + " vs " + weights.length);
//				}
//				assert weights[arg0] == 0d;
//				weights[arg0] = arg1;
//				return arg1;
//			}
//		};
//		p.getFrameIdStage().getWeights().apply(lambda);
//		p.getArgIdStage().getWeights().apply(lambda);
//		p.getArgSpanStage().getWeights().apply(lambda);
//		return new Model<>(alph, weights);
//	}

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
