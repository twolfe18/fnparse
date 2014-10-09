package edu.jhu.hlt.fnparse.inference.stages;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.model.FgModel;

public class IdentityStage<T> implements Stage<T, T> {
	private static final long serialVersionUID = 1L;
	private static final Logger LOG = Logger.getLogger(IdentityStage.class);

	private final FgModel model = new FgModel(0);

	@Override
	public FgModel getWeights() {
		return model;
	}

	@Override
	public void setWeights(FgModel weights) {
		LOG.warn("not actually setting weights");
	}

	@Override
	public boolean logDomain() {
		return true;
	}

	@Override
	public String getName() {
		return getClass().getName();
	}

	@Override
	public void train(List<T> x, List<T> y) {
		LOG.info("[train] not doing anything!");
	}

	@Override
	public StageDatumExampleList<T, T> setupInference(
			List<? extends T> input,
			List<? extends T> output) {
		if (output != null && output.size() != input.size())
			throw new IllegalArgumentException();
		List<StageDatum<T, T>> data = new ArrayList<>();
		for (int i = 0; i < input.size(); i++) {
			if (output == null)
				data.add(new IdentityStageDatum<T>(input.get(i)));
			else
				data.add(new IdentityStageDatum<T>(input.get(i), output.get(i)));
		}
		return new StageDatumExampleList<>(data);
	}

	static class IdentityStageDatum<T> implements StageDatum<T, T> {
		public final T input, gold;

		public IdentityStageDatum(T input) {
			this(input, null);
		}

		public IdentityStageDatum(T input, T gold) {
			if (input == null)
				throw new IllegalArgumentException();
			this.input = input;
			this.gold = gold;
		}

		@Override
		public T getInput() { return input; }

		@Override
		public boolean hasGold() { return false; }

		@Override
		public T getGold() { throw new RuntimeException(); }

		@Override
		public LabeledFgExample getExample() {
			throw new UnsupportedOperationException();
		}

		@Override
		public IDecodable<T> getDecodable() {
			return new IDecodable<T>() {
				@Override
				public T decode() {
					return input;
				}
			};
		}
	}

	@Override
	public void scanFeatures(
			List<? extends T> unlabeledExamples,
			List<? extends T> labels,
			double maxTimeInMinutes,
			int maxFeaturesAdded) {
		LOG.info("not really scanning features");
	}

	@Override
	public void saveModel(File file) {
		LOG.info("not really saving model");
	}

	@Override
	public void loadModel(File file) {
		LOG.info("not really loading model");
	}
}
