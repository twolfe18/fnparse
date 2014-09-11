package edu.jhu.hlt.fnparse.inference.stages;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.model.FgModel;

/**
 * TODO finish this
 * i want to use it for making the pipelined parser skip the later stages
 * (just pass through a FNTagging as a FNParse).
 * 
 * @author travis
 *
 * @param <I>
 * @param <O>
 */
public class IdentityStage<I, O> implements Stage<I, O> {
	private final Logger LOG = Logger.getLogger(IdentityStage.class);
	
	private FgModel model = new FgModel(0);

	@Override
	public FgModel getWeights() {
		return model;
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
	public void train(List<I> x, List<O> y) {
		LOG.info("[train] not doing anything!");
	}

	@Override
	public StageDatumExampleList<I, O> setupInference(
			List<? extends I> input,
			List<? extends O> output) {
		if (output == null) {
			throw new IllegalArgumentException("You must provide the labels to "
					+ "an identity stage because it doesn't know how to do "
					+ "inference and doesn't support casting the input as the "
					+ "output type (yet).");
		}
		List<StageDatum<I, O>> data = new ArrayList<>();
		for (int i = 0; i < input.size(); i++)
			data.add(new IdentityStageDatum<I, O>(input.get(i), output.get(i)));
		return new StageDatumExampleList<>(data);
	}
	
	static class IdentityStageDatum<I, O> implements StageDatum<I, O> {
		private final I input;
		private final O output;
		/**
		 * You have to provide an output, which will be used in place of
		 * inference.
		 */
		public IdentityStageDatum(I input, O output) {
			this.input = input;
			this.output = output;
		}
		@Override
		public I getInput() {
			return input;
		}
		@Override
		public boolean hasGold() {
			return true;
		}
		@Override
		public O getGold() {
			return output;
		}
		@Override
		public LabeledFgExample getExample() {
			throw new UnsupportedOperationException();
		}
		@Override
		public IDecodable<O> getDecodable() {
			return new IDecodable<O>() {
				@Override
				public O decode() {
					return output;
				}
			};
		}
	}

}
