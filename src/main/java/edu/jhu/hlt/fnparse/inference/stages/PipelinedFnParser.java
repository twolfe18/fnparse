package edu.jhu.hlt.fnparse.inference.stages;

import java.util.List;

import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.util.Timer;

public class PipelinedFnParser {

	private ParserParams params;

	private FrameIdStage frameId;
	private AbstractStage<FNTagging, FNParse> argId;
	private AbstractStage<FNParse, FNParse> argExpansion;
	
	public PipelinedFnParser() {
		Parser p = new Parser();
		params = p.params;
		frameId = new FrameIdStage(params);
	}
	
	public void computeAlphabet(List<FNParse> examples) {
		Timer t = params.timer.get("compute-alph", true);
		t.start();
		params.featIdx.startGrowth();
		
		List<Sentence> sentences = DataUtil.stripAnnotations(examples);
		frameId.scanFeatures(sentences, 5);
		if(argId != null) {
			// TODO: same issue here, compute features on predictions, not gold
			argId.scanFeatures(examples, 5);
			if(argExpansion != null) {
				argExpansion.scanFeatures(examples, 5);
			}
		}
		
		params.featIdx.stopGrowth();
		t.stop();
	}

	public void train(List<FNParse> examples) {

		// allocate the model
		if(params.featIdx.isGrowing())
			throw new RuntimeException();
		int numParams = params.featIdx.size() + 1;
		params.weights = new FgModel(numParams);

		List<Sentence> sentences = DataUtil.stripAnnotations(examples);
		frameId.train(sentences, examples);

		if(argId == null) return;
		List<? extends FNTagging> frames = examples;
		if(params.usePredictedFramesToTrainRoleId)
			frames = frameId.predict(sentences);
		argId.train(frames, examples);

		// if we predict the wrong head, there is no way to recover by predicting it's span
		// so there is no reason not to train on gold heads+expansions
		if(argExpansion == null) return;
		argExpansion.train(examples, examples);
	}
	
	public List<FNParse> predict(List<Sentence> sentences) {
		List<FNTagging> frames = frameId.predict(sentences);
		
		if(argId == null) return DataUtil.promoteTaggingsToParses(frames);
		List<FNParse> argHeads = argId.predict(frames);
		
		if(argExpansion == null) return argHeads;
		List<FNParse> fullParses = argExpansion.predict(argHeads);

		return fullParses;
	}
	
	public void scanFeatures(List<FNParse> examples, double maxTimeInMinutes) {

		frameId.scanFeatures(DataUtil.stripAnnotations(examples), maxTimeInMinutes);
		
		// TODO: these should take FNTaggings and FNParses produced by the
		// stage that came before them, otherwise they'll be extracting
		// features off of non-real examples.
		argId.scanFeatures(examples, maxTimeInMinutes);
		argExpansion.scanFeatures(examples, maxTimeInMinutes);
	}
}
