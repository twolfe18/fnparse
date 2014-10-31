package edu.jhu.hlt.fnparse.inference.stages;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.datatypes.FNParse;


/**
 * Passes the gold answer as output. Assumes that the gold answer is always
 * given during setupInference. Does no training.
 * 
 * @author travis
 *
 * @param <I>
 * @param <O>
 */
public class OracleStage<I, O> implements Stage<I, O> {
	private static final long serialVersionUID = 1L;
	private static final Logger LOG = Logger.getLogger(OracleStage.class);

	private final FgModel model = new FgModel(0);

	public void configure(java.util.Map<String,String> configuration) {
	}

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
			data.add(new OracleStageDatum<I, O>(input.get(i), output.get(i)));
		return new StageDatumExampleList<>(data);
	}

	static class OracleStageDatum<I, O> implements StageDatum<I, O> {
		private final I input;
		private final O output;
		/**
		 * You have to provide an output, which will be used in place of
		 * inference.
		 */
		public OracleStageDatum(I input, O output) {
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

	@Override
	public void scanFeatures(
			List<? extends I> unlabeledExamples,
			List<? extends O> labels,
			double maxTimeInMinutes,
			int maxFeaturesAdded) {
		LOG.info("not actually scanning features");
	}

	@Override
	public void saveModel(File file) {
		LOG.info("not actually saving model");
	}

	@Override
	public void loadModel(File file) {
		LOG.info("not actually loading model");
	}

  @Override
  public void scanFeatures(List<FNParse> data) {
		LOG.info("not actually loading model");
  }

}
