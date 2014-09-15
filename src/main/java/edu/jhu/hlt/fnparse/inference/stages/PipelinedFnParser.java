package edu.jhu.hlt.fnparse.inference.stages;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.log4j.Logger;

import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.roleid.NoRoleIdStage;
import edu.jhu.hlt.fnparse.inference.roleid.RoleIdStage;
import edu.jhu.hlt.fnparse.util.HasSentence;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.fnparse.util.ParseSelector;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.util.Alphabet;

/**
 * NOTE: weight are stored in each stage, feature alphabet is global
 */
public class PipelinedFnParser implements Serializable {
	private static final long serialVersionUID = 1L;
	private static final Logger LOG = Logger.getLogger(PipelinedFnParser.class);

	private ParserParams params;
	private Stage<Sentence, FNTagging> frameId;
	private Stage<FNTagging, FNParse> argId;
	private Stage<FNParse, FNParse> argExpansion;

	public PipelinedFnParser(ParserParams params) {
		this.params = params;
		frameId = new FrameIdStage(params);
		argId = new RoleIdStage(params);
		argExpansion = new RoleSpanStage(params);
	}

	// TODO replace this with setters for each stage

	public void disableArgId() {
		argId = new NoRoleIdStage();
		argExpansion = new IdentityStage<>();
	}

	public void disableArgSpans() {
		argExpansion = new IdentityStage<>();
	}

	public void useGoldFrameId() {
		frameId = new OracleStage<>();
	}

	/** Implies useGoldFrameId */
	public void useGoldArgId() {
		useGoldFrameId();
		argId = new OracleStage<>();
	}

	public FgModel getFrameIdWeights() { return frameId.getWeights(); }
	public FgModel getArgIdWeights() { return argId.getWeights(); }
	public FgModel getArgSpanWeights() { return argExpansion.getWeights(); }

	public Alphabet<String> getAlphabet() {
		return params.getFeatureAlphabet();
	}

	public ParserParams getParams() {
		return params;
	}

	public FrameIdStage.Params getFrameIdParams() {
		if (frameId instanceof FrameIdStage)
			return ((FrameIdStage) frameId).params;
		throw new RuntimeException("you can't get params for this type of "
				+ "frameId stage: " + frameId.getClass().getName());
	}

	public RoleIdStage.Params getArgIdParams() {
		if (argId instanceof RoleIdStage)
			return ((RoleIdStage) argId).params;
		throw new RuntimeException("you can't get params for this type of "
				+ "argId stage: " + argId.getClass().getName());
	}

	public RoleSpanStage.Params getArgExpansionParams() {
		if (argExpansion instanceof RoleSpanStage)
			return ((RoleSpanStage) argExpansion).params;
		throw new RuntimeException("you can't get params for this type of "
				+ "argExpansion stage: " + argExpansion.getClass().getName());
	}

	public Stage<Sentence, FNTagging> getFrameIdStage() {
		return frameId;
	}
	
	public Stage<FNTagging, FNParse> getArgIdStage() {
		return argId;
	}
	
	public Stage<FNParse, FNParse> getArgSpanStage() {
		return argExpansion;
	}

	/**
	 * Writes just the weight vectors to a compressed binary file.
	 */
	public void writeModel(File f) throws IOException {
		LOG.info("writing model to " + f.getPath());
		DataOutputStream dos = new DataOutputStream(
				new GZIPOutputStream(new FileOutputStream(f)));
		ModelIO.writeBinary(getFrameIdWeights(), dos);
		ModelIO.writeBinary(getArgIdWeights(), dos);
		ModelIO.writeBinary(getArgSpanWeights(), dos);
		dos.close();
		LOG.info("done writing model");
	}

	/**
	 * This does not play well with others (one opaque file with 3 models in it)
	 * ParserEvaluator needs more flexibility in stitching different models together
	 */
	public void readModel(File f) throws IOException {
		LOG.info("reading model from " + f.getPath());
		DataInputStream dis = new DataInputStream(
				new GZIPInputStream(new FileInputStream(f)));
		frameId.setWeights(ModelIO.readBinary(dis));
		argId.setWeights(ModelIO.readBinary(dis));
		argExpansion.setWeights(ModelIO.readBinary(dis));
		dis.close();
		LOG.info("done reading model");
	}

	/* this makes too much work for ParserEvaluator
	public void writeModel(
			File frameIdWeights,
			File argIdWeights,
			File argSpanWeights) throws IOException {
		if (frameIdWeights != null)
			ModelIO.writeBinary(getFrameIdWeights(), frameIdWeights);
		if (argIdWeights != null)
			ModelIO.writeBinary(getArgIdWeights(), argIdWeights);
		if (argSpanWeights != null)
			ModelIO.writeBinary(getArgSpanWeights(), argSpanWeights);
	}

	public void readModel(
			File frameIdWeights,
			File argIdWeights,
			File argSpanWeights) throws IOException {
		if (frameIdWeights != null)
			frameId.setWeights(ModelIO.readBinary(frameIdWeights));
		if (argIdWeights != null)
			argId.setWeights(ModelIO.readBinary(argIdWeights));
		if (argSpanWeights != null)
			argExpansion.setWeights(ModelIO.readBinary(argSpanWeights));
	}
	*/

	public void writeAlphabet(File f) {
		LOG.info("writing alphabet to " + f.getPath());
		ModelIO.writeAlphabet(getAlphabet(), f);
		LOG.info("done writing alphabet");
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
	public void scanFeatures(
			List<FNParse> examples,
			double maxTimeInMinutes,
			int maxFeaturesAdded) {
		Timer t = params.getTimer("computeAlphabet");
		t.start();

		examples = ParseSelector.sort(examples);

		params.getFeatureAlphabet().startGrowth();

		// TODO add scanFeatures to interface Stage
		// and remove all these type checks

		if (frameId instanceof FrameIdStage) {
			List<Sentence> sentences = DataUtil.stripAnnotations(examples);
			((FrameIdStage) frameId).scanFeatures(
					sentences, examples, maxTimeInMinutes, maxFeaturesAdded);
		} else {
			LOG.warn("not scanning frameId features because frameId stage is "
					+ "not typical: " + frameId.getClass().getName());
		}

		if (argId instanceof RoleIdStage) {
			List<FNTagging> frames = DataUtil.convertParsesToTaggings(examples);
			((RoleIdStage) argId).scanFeatures(
				frames, examples, maxTimeInMinutes, maxFeaturesAdded);
		} else {
			LOG.warn("not scanning argId features because argId stage is "
					+ "not typical: " + argId.getClass().getName());
		}

		if (argExpansion instanceof RoleSpanStage) {
			List<FNParse> onlyHeads = DataUtil.convertArgumenSpansToHeads(
					examples, params.headFinder);
			((RoleSpanStage) argExpansion).scanFeatures(
				onlyHeads, examples, maxTimeInMinutes, maxFeaturesAdded);
		} else {
			LOG.warn("not scanning argExpansion features because argExpansion "
					+ "stage is not typical: "
					+ argExpansion.getClass().getName());
		}

		params.getFeatureAlphabet().stopGrowth();
		long time = t.stop();
		System.out.printf("[computeAlphabet] %d parses with %d features in %.1f seconds\n",
				examples.size(), params.getFeatureAlphabet().size(), time / 1000d);
	}

	public void train(List<FNParse> examples) {
		if (examples.size() == 0)
			throw new IllegalArgumentException();

		LOG.info("[PipelinedFnParser train] training frameId model...");
		List<Sentence> sentences = DataUtil.stripAnnotations(examples);
		List<FNTagging> goldTags = DataUtil.convertParsesToTaggings(examples);
		frameId.train(sentences, goldTags);

		List<FNTagging> frames;
		if (params.usePredictedFramesToTrainArgId) {
			LOG.info("[PipelinedFnParser train] predicting frames before training argId model...");
			frames = frameId.setupInference(sentences, null).decodeAll();
		} else {
			LOG.info("[PipelinedFnParser train] using gold frames to train argId model...");
			frames = DataUtil.convertParsesToTaggings(examples);
		}
		LOG.info("[PipelinedFnParser train] training argId model...");
		argId.train(frames, examples);

		// If we predict the wrong head, there is no way to recover by
		// predicting it's span so there is no reason not to train on gold
		// heads+expansions
		// TODO The above comment is old and does not appear to make sense,
		// consider alternative training regimes.
		LOG.info("[PipelinedFnParser train] training argId span model...");
		List<FNParse> onlyHeads = DataUtil.convertArgumenSpansToHeads(
				examples, params.headFinder);
		argExpansion.train(onlyHeads, examples);
	}

	public List<FNParse> predictWithoutPeaking(
			List<? extends HasSentence> hasSentences) {
		List<Sentence> sentences = new ArrayList<>();
		for (HasSentence hs : hasSentences)
			sentences.add(hs.getSentence());
		return predict(sentences);
	}

	public List<FNParse> predict(List<Sentence> sentences) {
		return predict(sentences, null);
	}

	/**
	 * Use this method if any of the stages are oracles (i.e. need to look at
	 * the answer at prediction time).
	 */
	public List<FNParse> predict(List<Sentence> sentences, List<FNParse> labels) {
		if (labels != null && labels.size() != sentences.size())
			throw new IllegalArgumentException();

		// Frame id
		List<FNTagging> goldFrames = labels == null
				? null : DataUtil.convertParsesToTaggings(labels);
		List<FNTagging> frames = frameId
				.setupInference(sentences, goldFrames)
				.decodeAll();

		// Arg id
		List<FNParse> goldArgHeads = labels == null
				? null : DataUtil.convertArgumenSpansToHeads(
						labels, params.headFinder);
		List<FNParse> argHeads = argId
				.setupInference(frames, goldArgHeads)
				.decodeAll();

		// Arg spans
		List<FNParse> fullParses = argExpansion
				.setupInference(argHeads, labels)
				.decodeAll();

		return fullParses;
	}

}
