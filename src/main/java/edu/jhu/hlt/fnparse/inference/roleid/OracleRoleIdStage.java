package edu.jhu.hlt.fnparse.inference.roleid;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;

/**
 * A stage that receives some frames that are tagged as input and takes the gold
 * roles for those frames.
 * 
 * TODO fix this implementation (see note in decode()).
 * 
 * @author travis
 */
public class OracleRoleIdStage
		implements Stage<FNTagging, FNParse>, Serializable {
	private static final long serialVersionUID = 1L;

	@Override
	public FgModel getWeights() {
		throw new RuntimeException("don't call this!");
	}

	@Override
	public boolean logDomain() {
		throw new RuntimeException("don't call this!");
	} 

	@Override
	public String getName() { return this.getClass().getName(); }

	@Override
	public void train(List<FNTagging> x, List<FNParse> y) {
		System.out.println("[OracleRoleIdStage train] not doing anything.");
	}

	@Override
	public StageDatumExampleList<FNTagging, FNParse> setupInference(
			List<? extends FNTagging> input, List<? extends FNParse> output) {
		if (output == null) {
			throw new IllegalArgumentException(
					"You must provide the oracle with the answers!");
		}
		assert input.size() == output.size();
		List<StageDatum<FNTagging, FNParse>> data = new ArrayList<>();
		for (int i = 0; i < input.size(); i++)
			data.add(new OracleRoleIdStageDatum(input.get(i), output.get(i)));
		return new StageDatumExampleList<>(data);
	}

	static class OracleRoleIdStageDatum
			implements StageDatum<FNTagging, FNParse> {
		private final FNTagging x;
		private final FNParse y;	// i.e. label

		public OracleRoleIdStageDatum(FNTagging x, FNParse y) {
			this.x = x;
			this.y = y;
		}

		@Override
		public FNTagging getInput() { return x; }

		@Override
		public boolean hasGold() { return true; }

		@Override
		public FNParse getGold() { return y; }

		@Override
		public LabeledFgExample getExample() {
			throw new RuntimeException("don't call this!");
		}

		@Override
		public IDecodable<FNParse> getDecodable() {
			return new IDecodable<FNParse>() {
				@Override
				public FNParse decode() {
					throw new RuntimeException("FIXME: this returns the full "
							+ "FNParse answer, regardless of what the frames "
							+ "that were tagged in the input. You need to add a"
							+ " step that checks for those frames and copies "
							+ "the roles over from the gold label.");
					//return y;
				}
			};
		}
		
	}

}
