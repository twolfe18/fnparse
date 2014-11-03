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
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.role.head.NoRolesStage;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadStage;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadToSpanStage;
import edu.jhu.hlt.fnparse.util.HasSentence;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.fnparse.util.ParseSelector;
import edu.jhu.hlt.fnparse.util.Timer;
import edu.jhu.util.Alphabet;

/**
 * NOTE: weight are stored in each stage, feature alphabet is global
 */
public class PipelinedFnParser implements Serializable, Parser {
	private static final long serialVersionUID = 1L;
	private static final Logger LOG = Logger.getLogger(PipelinedFnParser.class);

	// Names of the files that each stage will be serialized to when saveModel
	// is called.
	public static final String FRAME_ID_MODEL_NAME = "frameId.ser.gz";
	public static final String ARG_ID_MODEL_NAME = "argId.ser.gz";
	public static final String ARG_SPANS_MODEL_NAME = "argSpans.ser.gz";

	public static String FRAME_ID_MODEL_HUMAN_READABLE = null;
	public static String ARG_ID_MODEL_HUMAN_READABLE = null;
	public static String ARG_SPANS_MODEL_HUMAN_READABLE = null;

	private ParserParams params;
	private Stage<Sentence, FNTagging> frameId;
	private Stage<FNTagging, FNParse> argId;
	private Stage<FNParse, FNParse> argExpansion;

	public PipelinedFnParser(ParserParams params) {
		this.params = params;
		frameId = new FrameIdStage(params, this);
		argId = new RoleHeadStage(params, this);
		argExpansion = new RoleHeadToSpanStage(params, this);
	}

	@Override
	public void configure(Map<String, String> configuration) {
	  LOG.info("[configure] " + configuration);
	  frameId.configure(configuration);
	  argId.configure(configuration);
	  argExpansion.configure(configuration);
	}

	@Override
	public Alphabet<String> getAlphabet() {
		return params.getAlphabet();
	}

	// TODO replace this with setters for each stage

	public void disableArgId() {
		argId = new NoRolesStage();
		argExpansion = new IdentityStage<>();
	}

	public void disableArgSpans() {
		argExpansion = new IdentityStage<>();
	}

	public void useGoldArgSpans() {
		argExpansion = new OracleStage<>();
	}

	public void useGoldFrameId() {
		frameId = new OracleStage<>();
	}

	/** Implies useGoldFrameId */
	public void useGoldArgId() {
		useGoldFrameId();
		argId = new OracleStage<>();
	}

	public ParserParams getParams() {
		return params;
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
		ModelIO.writeBinary(getFrameIdStage().getWeights(), dos);
		ModelIO.writeBinary(getArgIdStage().getWeights(), dos);
		ModelIO.writeBinary(getArgSpanStage().getWeights(), dos);
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

	public void writeAlphabet(File f) {
		LOG.info("writing alphabet to " + f.getPath());
		ModelIO.writeAlphabet(getAlphabet(), f);
		LOG.info("done writing alphabet");
	}

	public void setAlphabet(Alphabet<String> featureIndices) {
		params.setFeatureAlphabet(featureIndices);
	}

	@Override
	public void train(List<FNParse> data) {
	  scanFeatures(data, 999, 999_999_999);
	  learnWeights(data);
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

		params.getAlphabet().startGrowth();

		frameId.scanFeatures(examples);
		argId.scanFeatures(examples);
		argExpansion.scanFeatures(examples);

		params.getAlphabet().stopGrowth();
		long time = t.stop();
		System.out.printf("[computeAlphabet] %d parses with %d features in %.1f seconds\n",
				examples.size(), params.getAlphabet().size(), time / 1000d);
	}

	public void learnWeights(List<FNParse> examples) {
		if (examples.size() == 0)
			throw new IllegalArgumentException();

		LOG.info("[train] training frameId model...");
		List<Sentence> sentences = DataUtil.stripAnnotations(examples);
		List<FNTagging> goldTags = DataUtil.convertParsesToTaggings(examples);
		frameId.train(sentences, goldTags);

		List<FNTagging> frames;
		if (params.usePredictedFramesToTrainArgId) {
			LOG.info("[train] predicting frames before training argId model...");
			frames = frameId.setupInference(sentences, null).decodeAll();
		} else {
			LOG.info("[train] using gold frames to train argId model...");
			frames = DataUtil.convertParsesToTaggings(examples);
		}
		LOG.info("[train] training argId model...");
		argId.train(frames, examples);

		// If we predict the wrong head, there is no way to recover by
		// predicting it's span so there is no reason not to train on gold
		// heads+expansions
		// TODO The above comment is old and does not appear to make sense,
		// consider alternative training regimes.
		LOG.info("[train] training argId span model...");
		List<FNParse> onlyHeads = DataUtil.convertArgumenSpansToHeads(
				examples, params.headFinder);
		argExpansion.train(onlyHeads, examples);
	}

	public List<FNParse> predictWithoutPeaking(
			List<? extends HasSentence> hasSentences) {
		List<Sentence> sentences = new ArrayList<>();
		for (HasSentence hs : hasSentences)
			sentences.add(hs.getSentence());
		return parse(sentences, null);
	}

	@Override
	public List<FNParse> parse(List<Sentence> sentences, List<FNParse> labels) {
		if (labels != null && labels.size() != sentences.size())
			throw new IllegalArgumentException();
		long start;
		long firstStart = System.currentTimeMillis();

		// Frame id
		start = firstStart;
		List<FNTagging> goldFrames = labels == null
				? null : DataUtil.convertParsesToTaggings(labels);
		List<FNTagging> frames = frameId
				.setupInference(sentences, goldFrames)
				.decodeAll();
		LOG.info("[parse] frameId done in " + (System.currentTimeMillis()-start)/1000d + " seconds");

		// Arg id
		start = System.currentTimeMillis();
		List<FNParse> goldArgHeads = labels == null
				? null : DataUtil.convertArgumenSpansToHeads(
						labels, params.headFinder);
		List<FNParse> argHeads = argId
				.setupInference(frames, goldArgHeads)
				.decodeAll();
		LOG.info("[parse] argId done in " + (System.currentTimeMillis()-start)/1000d + " seconds");

		// Arg spans
		start = System.currentTimeMillis();
		List<FNParse> fullParses = argExpansion
				.setupInference(argHeads, labels)
				.decodeAll();
		LOG.info("[parse] argSpans done in " + (System.currentTimeMillis()-start)/1000d + " seconds");

		long totalTime = System.currentTimeMillis() - firstStart;
		int toks = 0;
		for (Sentence s : sentences) toks += s.size();
		LOG.info("[parse] " + (totalTime/1000d) + " sec total for "
		    + sentences.size() + " sentences /" + toks + " tokens, "
		    + (toks*1000d)/totalTime + " tokens per second");

		return fullParses;
	}

	@Override
	public void saveModel(File directory) {
		LOG.info("saving model to " + directory.getPath());
		if (!directory.isDirectory())
			throw new IllegalArgumentException();
		frameId.saveModel(new File(directory, FRAME_ID_MODEL_NAME));
		argId.saveModel(new File(directory, ARG_ID_MODEL_NAME));
		argExpansion.saveModel(new File(directory, ARG_SPANS_MODEL_NAME));

		if (FRAME_ID_MODEL_HUMAN_READABLE != null) {
		  ModelIO.writeHumanReadable(frameId.getWeights(), getAlphabet(),
		      new File(directory, FRAME_ID_MODEL_HUMAN_READABLE), true);
		}
		if (ARG_ID_MODEL_HUMAN_READABLE != null) {
		  ModelIO.writeHumanReadable(argId.getWeights(), getAlphabet(),
		      new File(directory, ARG_ID_MODEL_HUMAN_READABLE), true);
		}
		if (ARG_SPANS_MODEL_HUMAN_READABLE != null) {
		  ModelIO.writeHumanReadable(argExpansion.getWeights(), getAlphabet(),
		      new File(directory, ARG_SPANS_MODEL_HUMAN_READABLE), true);
		}
	}
}
