package edu.jhu.hlt.fnparse.inference.stages;

import java.util.ArrayList;
import java.util.List;

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
public class IdentityStage<I, O extends I> implements Stage<I, O> {

	@Override
	public FgModel getWeights() {
		throw new RuntimeException();
	}

	@Override
	public boolean logDomain() {
		throw new RuntimeException();
	}

	@Override
	public String getName() {
		return getClass().getName();
	}

	@Override
	public void train(List<I> x, List<O> y) {
		System.out.println("[IdentityStage train] not doing anything!");
	}

	@Override
	public StageDatumExampleList<I, O> setupInference(
			List<? extends I> input,
			List<? extends O> output) {
		List<StageDatum<I, O>> data = new ArrayList<>();
		for (I i : input) {
			data.add(new StageDatum<I, O>() {
				@Override
				public I getInput() {
					// TODO Auto-generated method stub
					return null;
				}

				@Override
				public boolean hasGold() {
					// TODO Auto-generated method stub
					return false;
				}

				@Override
				public O getGold() {
					// TODO Auto-generated method stub
					return null;
				}

				@Override
				public LabeledFgExample getExample() {
					// TODO Auto-generated method stub
					return null;
				}

				@Override
				public edu.jhu.hlt.fnparse.inference.stages.Stage.IDecodable<O> getDecodable() {
					// TODO Auto-generated method stub
					return null;
				}
				
			});
		}
		return new StageDatumExampleList<>(data);
	}
	
	static class IdentityStageDatum<I, O extends I>
			implements StageDatum<I, O> {
		private final I input;
		private O output;

		public IdentityStageDatum(I input) {
			this.input = input;
		}

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
			return output != null;
		}

		@Override
		public O getGold() {
			assert hasGold();
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
					return (O) input;
				}
			};
		}
	}

}
