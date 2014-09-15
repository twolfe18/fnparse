package edu.jhu.hlt.fnparse.inference.stages;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.model.FgModel;

public class IdentityStage<T> implements Stage<T, T> {
	private final Logger LOG = Logger.getLogger(OracleStage.class);

	private FgModel model = new FgModel(0);

	@Override
	public FgModel getWeights() {
		return model;
	}

	@Override
	public void setWeights(FgModel weights) {
		throw new RuntimeException("don't call this!");
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
}
