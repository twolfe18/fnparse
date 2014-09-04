package edu.jhu.hlt.fnparse.inference.stages;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.roleid.RoleIdStage;
import edu.jhu.hlt.fnparse.util.HasSentence;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.fnparse.util.ParseSelector;
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

	public FgModel getFrameIdWeights() { return frameId.weights; }
	public FgModel getArgIdWeights() { return argId.weights; }
	public FgModel getArgSpanWeights() { return argExpansion.weights; }

	public Alphabet<String> getAlphabet() { return params.getFeatureAlphabet(); }

	public ParserParams getParams() { return params; }
	public FrameIdStage.Params getFrameIdParams() { return frameId.params; }
	public RoleIdStage.Params getArgIdParams() { return argId.params; }
	public RoleSpanStage.Params getArgExpansionParams() { return argExpansion.params; }
	
	/**
	 * Writes just the weight vectors to a compressed binary file.
	 */
	public void writeModel(File f) throws IOException {
		DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(f)));
		ModelIO.writeBinary(getFrameIdWeights(), dos);
		ModelIO.writeBinary(getArgIdWeights(), dos);
		ModelIO.writeBinary(getArgSpanWeights(), dos);
		dos.close();
	}
	
	public void setAlphabet(Alphabet<String> featureIndices) {
		params.setFeatureAlphabet(featureIndices);
	}

	/**
	 * Builds an Alphabet of feature names and indices, freezes the Alphabet when done.
	 * This is additive, so you can call it and not lose the features already in the
	 * alphabet.
	 * 
	 * @param examples
	 * @param maxTimeInMinutes is the cutoff for each of the three stages,
	 *        so its possible that it could take up to 3x longer than that
	 */
	public void computeAlphabet(List<FNParse> examples, double maxTimeInMinutes, int maxFeaturesAdded) {
		Timer t = params.getTimer("computeAlphabet");
		t.start();

		examples = ParseSelector.sort(examples);

		params.getFeatureAlphabet().startGrowth();
	
		List<Sentence> sentences = DataUtil.stripAnnotations(examples);
		frameId.scanFeatures(sentences, examples, maxTimeInMinutes, maxFeaturesAdded);

		List<FNTagging> frames = DataUtil.convertParsesToTaggings(examples);
		argId.scanFeatures(frames, examples, maxTimeInMinutes, maxFeaturesAdded);

		List<FNParse> onlyHeads = DataUtil.convertArgumenSpansToHeads(examples, params.headFinder);
		argExpansion.scanFeatures(onlyHeads, examples, maxTimeInMinutes, maxFeaturesAdded);

		params.getFeatureAlphabet().stopGrowth();
		long time = t.stop();
		System.out.printf("[computeAlphabet] %d parses with %d features in %.1f seconds\n",
				examples.size(), params.getFeatureAlphabet().size(), time / 1000d);
	}

	public void train(List<FNParse> examples) {
		if (examples.size() == 0)
			throw new IllegalArgumentException();

		List<Sentence> sentences = DataUtil.stripAnnotations(examples);
		frameId.train(examples);

		List<FNTagging> frames = params.usePredictedFramesToTrainArgId
				? frameId.predict(sentences)
				: DataUtil.convertParsesToTaggings(examples);
		argId.train(frames, examples);

		// If we predict the wrong head, there is no way to recover by predicting it's span
		// so there is no reason not to train on gold heads+expansions
		// TODO The above comment is old and does not appear to make sense, consider alternative
		// training regimes.
		List<FNParse> onlyHeads = DataUtil.convertArgumenSpansToHeads(examples, params.headFinder);
		argExpansion.train(onlyHeads, examples);
	}

	public List<FNParse> predict(List<Sentence> sentences) {
		List<FNTagging> frames = frameId.predict(sentences);
		List<FNParse> argHeads = argId.predict(frames);
		List<FNParse> fullParses = argExpansion.predict(argHeads);
		return fullParses;
	}
	
	public List<FNParse> predictWithoutPeaking(List<? extends HasSentence> hasSentences) {
		List<Sentence> sentences = new ArrayList<>();
		for (HasSentence hs : hasSentences)
			sentences.add(hs.getSentence());
		return predict(sentences);
	}

}
