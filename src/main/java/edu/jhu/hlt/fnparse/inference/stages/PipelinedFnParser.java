package edu.jhu.hlt.fnparse.inference.stages;

import java.io.Serializable;
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

public class PipelinedFnParser implements Serializable {
	
	// weight are stored in each stage, feature alphabet is global

	private static final long serialVersionUID = 1L;
	
	private ParserParams params;
	private FrameIdStage frameId;
	private RoleIdStage argId;
	private RoleSpanStage argExpansion;
	
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
	public ParserParams getParams() { return params; }
	
	/**
	 * Builds an Alphabet of feature names and indices, freezes the Alphabet when done.
	 * This is additive, so you can call it and not lose the features already in the
	 * alphabet.
	 * 
	 * @param examples
	 * @param maxTimeInMinutes is the cutoff for each of the three stages,
	 *        so its possible that it could take up to 3x longer than that
	 */
	public void computeAlphabet(List<FNParse> examples, double maxTimeInMinutes) {
		Timer t = params.getTimer("computeAlphabet");
		t.start();
		params.featAlph.startGrowth();
		
		List<Sentence> sentences = DataUtil.stripAnnotations(examples);
		frameId.scanFeatures(sentences, maxTimeInMinutes);

		// TODO: same issue here, compute features on predictions, not gold
		List<FNTagging> frames = DataUtil.convertParsesToTaggings(examples);
		argId.scanFeatures(frames, maxTimeInMinutes);

		List<FNParse> onlyHeads = DataUtil.convertArgumenSpansToHeads(examples, params.headFinder);
		argExpansion.scanFeatures(onlyHeads, maxTimeInMinutes);
		
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
		
		// if we predict the wrong head, there is no way to recover by predicting it's span
		// so there is no reason not to train on gold heads+expansions
		List<FNParse> onlyHeads = DataUtil.convertArgumenSpansToHeads(examples, params.headFinder);
		argExpansion.train(onlyHeads, examples);
	}
	
	public List<FNParse> predict(List<Sentence> sentences) {
		List<FNTagging> frames = frameId.predict(sentences);
		List<FNParse> argHeads = argId.predict(frames);
		List<FNParse> fullParses = argExpansion.predict(argHeads);
		return fullParses;
	}
	
}
