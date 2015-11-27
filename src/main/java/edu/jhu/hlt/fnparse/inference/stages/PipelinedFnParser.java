package edu.jhu.hlt.fnparse.inference.stages;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdStage;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.inference.role.head.NoRolesStage;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadStage;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadToSpanStage;
import edu.jhu.hlt.fnparse.util.GlobalParameters;
import edu.jhu.hlt.fnparse.util.HasSentence;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.fnparse.util.ParseSelector;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.Span;
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

  public static boolean SHOW_HEAD_RECALL = true;

  private GlobalParameters globals;
  private Stage<Sentence, FNTagging> frameId;
  private Stage<FNTagging, FNParse> argId;
  private Stage<FNParse, FNParse> argExpansion;
  private boolean useParseSelectionForScanFeatures = false;

  public PipelinedFnParser() {
    this.globals = new GlobalParameters();
    frameId = new FrameIdStage(globals, "");
    argId = new RoleHeadStage(globals, "");
    argExpansion = new RoleHeadToSpanStage(globals, "");
  }

  @Override
  public void configure(String key, String value) {
    Map<String, String> m = new HashMap<>();
    m.put(key, value);
    configure(m);
  }

  @Override
  public void configure(Map<String, String> configuration) {
    LOG.info("[configure] " + configuration);

    String key, value;
    key = "features";
    value = configuration.get(key);
    if (value != null) {
      setFeatures(value);
      LOG.info("setting " + key + " = " + value);
    }

    frameId.configure(configuration);
    argId.configure(configuration);
    argExpansion.configure(configuration);
  }

  @Override
  public void setFeatures(String featureTemplateDescription) {
    if (frameId instanceof AbstractStage)
      ((AbstractStage<?, ?>) frameId).setFeatures(featureTemplateDescription);
    else
      LOG.warn("not setting features for frameId");
    if (argId instanceof AbstractStage)
      ((AbstractStage<?, ?>) argId).setFeatures(featureTemplateDescription);
    else
      LOG.warn("not setting features for argId");
    if (argExpansion instanceof AbstractStage)
      ((AbstractStage<?, ?>) argExpansion).setFeatures(featureTemplateDescription);
    else
      LOG.warn("not setting features for argExpansion");
  }

	@Override
	public GlobalParameters getGlobalParameters() {
	  return globals;
	}

	@Override
	public Alphabet<String> getAlphabet() {
	  return globals.getFeatureNames();
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

	  long start = System.currentTimeMillis();
	  if (useParseSelectionForScanFeatures) {
	    LOG.info("[scanFeatures] using ParseSelector to choose a good subset "
	        + "of examples to extract features from");
	    if (examples.size() > 5000) {
	      LOG.warn("[scanFeatures] ParseSelector loads all instances into "
	          + "memory and should not be used with large training sets");
	      assert false;
	    }
	    examples = ParseSelector.sort(examples);
	  }

		getAlphabet().startGrowth();

		frameId.scanFeatures(examples);
		argId.scanFeatures(examples);
		argExpansion.scanFeatures(examples);

		getAlphabet().stopGrowth();
		long time = System.currentTimeMillis() - start;
		System.out.printf(
		    "[computeAlphabet] %d parses with %d features in %.1f seconds\n",
				examples.size(), getAlphabet().size(), time / 1000d);
	}

	public void learnWeights(List<FNParse> examples) {
		if (examples.size() == 0)
			throw new IllegalArgumentException();

		LOG.info("[train] training frameId model...");
		List<Sentence> sentences = DataUtil.stripAnnotations(examples);
		List<FNTagging> goldTags = DataUtil.convertParsesToTaggings(examples);
		frameId.train(sentences, goldTags);

		// TODO if a span is not reachable due to argExpansion's pruning,
		// do not include this as a training variable for argId.
		// NOTE this CAN NOT be done by switching an arg from spanTooBigNotToBePruned -> nullSpan
		// because RoleHeadStage would then try to predict false for all heads for that role
		// Need to pass information about which spans are not reachable directly to RoleHeadStage

		List<FNTagging> frames;
		/*
		if (usePredictedFramesToTrainArgId) {
			LOG.info("[train] predicting frames before training argId model...");
			frames = frameId.setupInference(sentences, null).decodeAll();
		} else {
		*/
			LOG.info("[train] using gold frames to train argId model...");
			frames = DataUtil.convertParsesToTaggings(examples);
		//}
		LOG.info("[train] training argId model...");
		argId.train(frames, examples);

		// If we predict the wrong head, there is no way to recover by
		// predicting it's span so there is no reason not to train on gold
		// heads+expansions
		// TODO The above comment is old and does not appear to make sense,
		// consider alternative training regimes.
		LOG.info("[train] training argId span model...");
		List<FNParse> onlyHeads = DataUtil.convertArgumenSpansToHeads(
				examples, SemaforicHeadFinder.getInstance());
		argExpansion.train(onlyHeads, examples);
	}

	public List<FNParse> predictWithoutPeaking(
			List<? extends HasSentence> hasSentences) {
		List<Sentence> sentences = new ArrayList<>();
		for (HasSentence hs : hasSentences)
			sentences.add(hs.getSentence());
		return parse(sentences, null);
	}

	private static void argHeadAccuracy(
	    List<FNParse> goldHeads,
	    List<FNParse> hypHeads) {
	  assert goldHeads.size() == hypHeads.size();
	  int a = 0, b = 0, c = 0, cc = 0, d = 0, e = 0;
	  for (int i = 0; i < goldHeads.size(); i++) {
	    FNParse g = goldHeads.get(i);
	    FNParse h = hypHeads.get(i);
	    assert g.numFrameInstances() == h.numFrameInstances();
	    for (int fi = 0; fi < g.numFrameInstances(); fi++) {
	      FrameInstance fig = g.getFrameInstance(fi);
	      FrameInstance fih = h.getFrameInstance(fi);
	      assert fig.getFrame() == fih.getFrame();
	      assert fig.getTarget() == fih.getTarget();
	      int K = fig.getFrame().numRoles();
	      for (int k = 0; k < K; k++) {
	        Span gs = fig.getArgument(k);
	        Span hs = fih.getArgument(k);
	        assert hs == Span.nullSpan || hs.width() == 1;
	        assert gs == Span.nullSpan || gs.width() == 1;
	        if (gs != Span.nullSpan)
	          e++;
	        if (gs == Span.nullSpan && hs != Span.nullSpan)
	          a++;
	        else if (gs != Span.nullSpan && hs == Span.nullSpan)
	          b++;
	        else if (gs == hs) {
	          c++;
	          if (gs != Span.nullSpan)
	            cc++;
	        } else
	          d++;
	      }
	    }
	  }
	  LOG.info("[argHeadAccuracy] correct:           " + c);
	  LOG.info("[argHeadAccuracy] correct realized:  " + cc);
	  LOG.info("[argHeadAccuracy] false positive:    " + a);
	  LOG.info("[argHeadAccuracy] false negative:    " + b);
	  LOG.info("[argHeadAccuracy] wrong head:        " + d);
	  LOG.info("[argHeadAccuracy] gold is realized:  " + e);
	  double acc = ((double) c) / (a + b + c + d);
	  LOG.info("[argHeadAccuracy] accuracy:          " + acc);
	  double accR = ((double) cc) / e;
	  LOG.info("[argHeadAccuracy] accuracy realized: " + accR);
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
						labels, SemaforicHeadFinder.getInstance());
		List<FNParse> argHeads;
		if (SHOW_HEAD_RECALL && argId instanceof RoleHeadStage) {
		  argHeads = ((RoleHeadStage) argId).setupInference(frames, goldArgHeads, true).decodeAll();
		} else {
		  argHeads = argId.setupInference(frames, goldArgHeads).decodeAll();
		}
		LOG.info("[parse] argId done in " + (System.currentTimeMillis()-start)/1000d + " seconds");
		if (goldArgHeads != null)
		  argHeadAccuracy(goldArgHeads, argHeads);

		// Arg spans
		start = System.currentTimeMillis();
		List<FNParse> fullParses = argExpansion
				.setupInference(argHeads, labels)
				.decodeAll();
		LOG.info("[parse] argSpans done in " + (System.currentTimeMillis()-start)/1000d + " seconds");

		if (labels != null && argExpansion instanceof RoleHeadToSpanStage) {
		  Counts<String> errs = new Counts<>();
		  for (int i = 0; i < labels.size(); i++) 
		    RoleHeadToSpanStage.errAnalysis(errs, labels.get(i), fullParses.get(i), argHeads.get(i));
		  LOG.info("[parse] errors: " + errs);
		}

		long totalTime = System.currentTimeMillis() - firstStart;
		int toks = 0;
		for (Sentence s : sentences) toks += s.size();
		LOG.info("[parse] " + (totalTime/1000d) + " sec total for "
		    + sentences.size() + " sentences /" + toks + " tokens, "
		    + (toks*1000d)/totalTime + " tokens per second");
		if (SHOW_HEAD_RECALL && argId instanceof RoleHeadStage)
		  ((RoleHeadStage) argId).showHeadRecall();

		return fullParses;
	}

  @Override
  public void loadModel(File directory) {
    LOG.info("loading model from " + directory.getPath());
    if (!directory.isDirectory())
      throw new IllegalArgumentException();
    globals.getFeatureNames().startGrowth();
    DataInputStream dis;
    try {
      dis = Parser.getDIStreamFor(directory, FRAME_ID_MODEL_NAME);
      frameId.loadModel(dis, globals);
      dis.close();

      dis = Parser.getDIStreamFor(directory, ARG_ID_MODEL_NAME);
      argId.loadModel(dis, globals);
      dis.close();

      dis = Parser.getDIStreamFor(directory, ARG_SPANS_MODEL_NAME);
      argExpansion.loadModel(dis, globals);
      dis.close();

      if (frameId instanceof AbstractStage)
        ((AbstractStage) frameId).showExtremeFeatures(30);
      if (argId instanceof AbstractStage)
        ((AbstractStage) argId).showExtremeFeatures(30);
      if (argExpansion instanceof AbstractStage)
        ((AbstractStage) argExpansion).showExtremeFeatures(30);

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    globals.getFeatureNames().stopGrowth();
  }

	@Override
	public void saveModel(File directory) {
		LOG.info("saving model to " + directory.getPath());
		if (!directory.isDirectory())
			throw new IllegalArgumentException();
		try {
		  DataOutputStream dos;

		  dos = Parser.getDOStreamFor(directory, FRAME_ID_MODEL_NAME);
		  frameId.saveModel(dos, globals);
		  dos.close();

		  dos = Parser.getDOStreamFor(directory, ARG_ID_MODEL_NAME);
		  argId.saveModel(dos, globals);
		  dos.close();

		  dos = Parser.getDOStreamFor(directory, ARG_SPANS_MODEL_NAME);
		  argExpansion.saveModel(dos, globals);
		  dos.close();

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
		} catch (Exception e) {
		  throw new RuntimeException(e);
		}
	}
}
