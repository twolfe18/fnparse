package edu.jhu.hlt.fnparse.inference.roleid;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;

/**
 * This stage has the same type as RoleIdStage, but doesn't identify any
 * arguments. It is useful for when you need something to fill out the rest of
 * the pipeline, but you really want to bail out after frameId.
 * 
 * @author travis
 */
public class NoRoleIdStage implements Stage<FNTagging, FNParse> {
	private static final long serialVersionUID = 1L;
	public static final Logger LOG = Logger.getLogger(NoRoleIdStage.class);
	private FgModel model = new FgModel(0);

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
	public void train(List<FNTagging> x, List<FNParse> y) {
		LOG.info("not doing any training");
	}

	@Override
	public StageDatumExampleList<FNTagging, FNParse> setupInference(
			List<? extends FNTagging> input,
			List<? extends FNParse> output) {
		List<StageDatum<FNTagging, FNParse>> data = new ArrayList<>();
		for (int i = 0; i < input.size(); i++) {
			if (output == null)
				data.add(new NoRoleIdStageDatum(input.get(i)));
			else
				data.add(new NoRoleIdStageDatum(input.get(i), output.get(i)));
		}
		return new StageDatumExampleList<FNTagging, FNParse>(data);
	}

	static class NoRoleIdStageDatum implements StageDatum<FNTagging, FNParse> {
		private final FNTagging input;
		private final FNParse parseWithoutArgs;
		private final FNParse gold;

		public NoRoleIdStageDatum(FNTagging input) {
			this(input, null);
		}

		public NoRoleIdStageDatum(FNTagging input, FNParse gold) {
			this.gold = gold;
			this.input = input;
			this.parseWithoutArgs = DataUtil.convertTaggingToParse(input);
		}

		@Override
		public FNTagging getInput() {
			return input;
		}

		@Override
		public boolean hasGold() {
			return gold != null;
		}

		@Override
		public FNParse getGold() {
			return gold;
		}

		@Override
		public LabeledFgExample getExample() {
			throw new UnsupportedOperationException();
		}

		@Override
		public IDecodable<FNParse> getDecodable() {
			return new IDecodable<FNParse>() {
				@Override
				public FNParse decode() {
					return parseWithoutArgs;
				}
			};
		}
	}

	@Override
	public void scanFeatures(
			List<? extends FNTagging> unlabeledExamples,
			List<? extends FNParse> labels,
			double maxTimeInMinutes,
			int maxFeaturesAdded) {
		LOG.info("not actually scanning features");
	}
}
