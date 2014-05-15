package edu.jhu.hlt.fnparse.inference.stages;

import java.util.List;

import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.roleid.RoleIdStage;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.util.Alphabet;

public class PipelinedFnParser {
	
	// TODO need to not regularize (at all!) the features from the previous stage
	// (the reason I say "at all" is that the current impl of dontRegularize multiplies by 1000, but not infinity)
	// HeterogeneousL2.zeroMeanIgnoringIndices(dontRegularize, regularizerMult, numParams);
	
	private boolean skipExpansions = false;

	private ParserParams params;
	private FrameIdStage frameId;
	private RoleIdStage argId;
	private AbstractStage<FNParse, FNParse> argExpansion;
	
	public PipelinedFnParser() {
		params = new ParserParams();
		frameId = new FrameIdStage(params);
		argId = new RoleIdStage(params);
		argExpansion = new RoleSpanStage(params);
	}
	
	public FgModel getFrameIdParams() { return frameId.weights; }
	public FgModel getArgIdParams() { return argId.weights; }
	public FgModel getArgSpanParams() { return argExpansion.weights; }
	
	public Alphabet<String> getAlphabet() { return params.featAlph; }
	
	public void computeAlphabet(List<FNParse> examples) {
		Timer t = params.timer.get("computeAlphabet", true);
		t.start();
		params.featAlph.startGrowth();
		
		List<Sentence> sentences = DataUtil.stripAnnotations(examples);
		frameId.scanFeatures(sentences, 5);

		// TODO: same issue here, compute features on predictions, not gold
		List<FNTagging> frames = DataUtil.convertParsesToTaggings(examples);
		argId.scanFeatures(frames, 5);

		if(!skipExpansions) {
			List<FNParse> onlyHeads = DataUtil.convertArgumenSpansToHeads(examples, params.headFinder);
			argExpansion.scanFeatures(onlyHeads, 5);
		}
		
		params.featAlph.stopGrowth();
		t.stop();
	}

	public void train(List<FNParse> examples) {

		List<Sentence> sentences = DataUtil.stripAnnotations(examples);
		frameId.train(examples);
		
		List<FNTagging> frames = params.usePredictedFramesToTrainArgId
				? frameId.predict(sentences)
				: DataUtil.convertParsesToTaggings(examples);
		argId.train(frames, examples);
		
		if(!skipExpansions) {
			// if we predict the wrong head, there is no way to recover by predicting it's span
			// so there is no reason not to train on gold heads+expansions
			List<FNParse> onlyHeads = DataUtil.convertArgumenSpansToHeads(examples, params.headFinder);
			argExpansion.train(onlyHeads, examples);
		}
	}
	
	public List<FNParse> predict(List<Sentence> sentences) {
		List<FNTagging> frames = frameId.predict(sentences);
		List<FNParse> argHeads = argId.predict(frames);
		if(skipExpansions)
			return argHeads;
		List<FNParse> fullParses = argExpansion.predict(argHeads);
		return fullParses;
	}
	
}
