package edu.jhu.hlt.fnparse.inference.stages;

import java.util.List;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.hlt.fnparse.util.HasFactorGraph;

/**
 * For big factor graphs, the bottleneck is often feature extraction.
 * A globally normalized graph is not desirable in this case because you
 * will end up normalizing large parts of the sample space which you will
 * never consider. A locally normalized model can improve on this because
 * you may be able to avoid ever computing a normalizing constant for large
 * cliques in the graph.
 * 
 * Consider FrameNet parsing as an example: we want to first decode the frame
 * being evoked, and then choose its roles (assuming that we're not doing joint
 * prediction -- which is of questionable value in this case). We do not want to
 * extract features for role variables that relate to a frame *that we didn't decode*.
 * ^^is a bad example, because we already have code that does these in a pipeline,
 *   the example i really need is [r_itjk before r_itjk^e]. if we choose r_itjk=0,
 *   then all the time spent extracting features for r_itjk^e was wasted.
 *   
 * Maybe I should see if I can run "fast" without working on r_itjk^e variables.
 * If I still can't run "fast", then I should abandon this (although I should mention
 * it to Matt because I think its a useful abstraction).
 * 
 * I just checked (its still running...), but this should result in about a 10-20x speedup.
 * So yeah, I should implement this.
 * 
 * only heads for args:
        [train] done training on 49 examples for 0.9 minutes
        [test] after 1 passes: ArgOnlyMacroF1 = 0.417
        [test] after 1 passes: ArgOnlyMacroPRECISION = 0.765
        [test] after 1 passes: ArgOnlyMacroRECALL = 0.287
        [test] after 1 passes: ArgOnlyMicroF1 = 0.202
        [test] after 1 passes: ArgOnlyMicroPRECISION = 0.673
        [test] after 1 passes: ArgOnlyMicroRECALL = 0.119
        [test] after 1 passes: FullMacroF1 = 0.686
        [test] after 1 passes: FullMacroPRECISION = 0.920
        [test] after 1 passes: FullMacroRECALL = 0.546
        [test] after 1 passes: FullMicroF1 = 0.590
        [test] after 1 passes: FullMicroPRECISION = 0.908
        [test] after 1 passes: FullMicroRECALL = 0.437
        [test] after 1 passes: MacroGenerousF1 = 0.695
        [test] after 1 passes: MacroGenerousPrecision = 0.934
        [test] after 1 passes: MacroGenerousRecall = 0.553
        [test] after 1 passes: TargetMacroF1 = 0.984
        [test] after 1 passes: TargetMacroPRECISION = 0.984
        [test] after 1 passes: TargetMacroRECALL = 0.984
        [test] after 1 passes: TargetMicroF1 = 0.978
        [test] after 1 passes: TargetMicroPRECISION = 0.978
        [test] after 1 passes: TargetMicroRECALL = 0.978
 * 
 * spans for args:
        [train] done training on 49 examples for 19.1 minutes
        [test] after 1 passes: ArgOnlyMacroF1 = 0.424
        [test] after 1 passes: ArgOnlyMacroPRECISION = 0.790
        [test] after 1 passes: ArgOnlyMacroRECALL = 0.290
        [test] after 1 passes: ArgOnlyMicroF1 = 0.204
        [test] after 1 passes: ArgOnlyMicroPRECISION = 0.680
        [test] after 1 passes: ArgOnlyMicroRECALL = 0.120
        [test] after 1 passes: FullMacroF1 = 0.688
        [test] after 1 passes: FullMacroPRECISION = 0.924
        [test] after 1 passes: FullMacroRECALL = 0.548
        [test] after 1 passes: FullMicroF1 = 0.591
        [test] after 1 passes: FullMicroPRECISION = 0.909
        [test] after 1 passes: FullMicroRECALL = 0.438
        [test] after 1 passes: MacroGenerousF1 = 0.695
        [test] after 1 passes: MacroGenerousPrecision = 0.934
        [test] after 1 passes: MacroGenerousRecall = 0.553
        [test] after 1 passes: TargetMacroF1 = 0.984
        [test] after 1 passes: TargetMacroPRECISION = 0.984
        [test] after 1 passes: TargetMacroRECALL = 0.984
        [test] after 1 passes: TargetMicroF1 = 0.978
        [test] after 1 passes: TargetMicroPRECISION = 0.978
        [test] after 1 passes: TargetMicroRECALL = 0.978
 * 
 * @author travis
 */
public interface Stage<Input, Output> {
	
	public String getName();
	
	/**
	 * should include any tuning steps that are necessary (implementer of this method should split off dev/tune data)
	 */
	public void train(List<Input> x, List<Output> y);
	
	/**
	 * create the FactorGraph and other materials needed for prediction.
	 * Technically, {@link Decodable} is lazy, so all that this is guaranteed to do is
	 * instantiate variables and factors and compute features.
	 * 
	 * @param input
	 * @param output may be null, in which case "unlabeled" StageData should be returned (only capable of decoding),
	 *        otherwise labeled StageData should be returned (which is suitable for training).
	 */
	public StageDatumExampleList<Input, Output> setupInference(List<? extends Input> input, List<? extends Output> output);


	/**
	 * Does one step in a pipeline.
	 * An example would be the "frameId" stage:
	 *   class FrameIdStageDatum extends StageDatum<Sentence, FNTagging>
	 * 
	 * Implementations of this interface should be stateless.
	 * 
	 * Implementations of this interface should not memoize because caching and memory
	 * management will be done by classes that call this class.
	 * 
	 * @param <Input> type of the data required for this stage to start its job.
	 * @param <Intermediate> type of variables used for decoding.
	 * @param <Output> type of the data produced by running inference and then decoding this stage.
	 * 
	 * @author travis
	 */
	public static interface StageDatum<Input, Output> {
		
		public Input getInput();
		
		/**
		 * if true, then can call getExample() and getGold(),
		 * otherwise only getDecodable() should be called.
		 */
		public abstract boolean hasGold();
		
		/**
		 * should return null if !hasGold() and a non-null value otherwise.
		 */
		public Output getGold();

		/** for training */
		public abstract LabeledFgExample getExample();

		/** for prediction */
		public abstract Decodable<Output> getDecodable(FgInferencerFactory infFact);
		
	}
	

	/**
	 * Basically a Future<Output>, but stores marginals, so you can
	 * decode many times without running inference more than once.
	 * 
	 * @author travis
	 */
	public static abstract class Decodable<Output> implements HasFactorGraph {
		
		public final FactorGraph fg;
		public final FgInferencerFactory infFact;
		private FgInferencer inf;
		
		public Decodable(FactorGraph fg, FgInferencerFactory infFact) {
			this.fg = fg;
			this.infFact = infFact;
		}
		
		@Override
		public FactorGraph getFactorGraph() { return fg; }
		
		/**
		 * ensures that inference has been run and the result has been cached.
		 */
		public void force() {
			getMargins();
		}

		/**
		 * forces inference to be run, but will only do so once
		 * (future calls are just returned from cache).
		 */
		public FgInferencer getMargins() {
			if(inf == null) {
				inf = infFact.getInferencer(fg);
				inf.run();
			}
			return inf;
		}
		
		/** should call getMargins() */
		public abstract Output decode();
	}
	
	
	
}
