package edu.jhu.hlt.fnparse.util;

import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.role.span.LatentConstituencyPipelinedParser;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;

/**
 * Saving parsers is handled by the parsers themselves.
 * 
 * @author travis
 */
public class ParserLoader {
  public static final Logger LOG = Logger.getLogger(ParserLoader.class);

  public static Parser instantiateParser(Map<String, String> config) {
    String mode = config.get(Parser.PARSER_MODE.getName());
    String synMode = config.get(Parser.SYNTAX_MODE.getName());
    String featureSet = config.get(Parser.FEATURES);
    LOG.info("[instantiateParser] mode=" + mode
        + ", syntaxMode=" + synMode
        + ", featureSet=" + featureSet);
    if (featureSet == null)
      throw new RuntimeException("you need to provide a feature set with " + Parser.FEATURES);
    ParserParams params = new ParserParams();
    params.setFeatureTemplateDescription(featureSet);
    if ("regular".equals(synMode)) {
      params.useLatentConstituencies = false;
      params.useLatentDepenencies = false;
      params.useSyntaxFeatures = true;
    } else if ("latent".equals(synMode)) {
      params.useLatentConstituencies = true;
      params.useLatentDepenencies = true;
      params.useSyntaxFeatures = false;
    } else if ("none".equals(synMode)) {
      params.useLatentConstituencies = false;
      params.useLatentDepenencies = false;
      params.useSyntaxFeatures = false;
    } else {
      throw new RuntimeException("unknown syntax mode: " + synMode);
    }
    if (mode.equalsIgnoreCase("span") || mode.equals("spans")) {
      LatentConstituencyPipelinedParser parser =
          new LatentConstituencyPipelinedParser();
      return parser;
    } else if (mode.equals("head") || mode.equals("heads")) {
      PipelinedFnParser parser = new PipelinedFnParser();
      parser.useGoldFrameId();
      return parser;
    } else {
      assert !Parser.PARSER_MODE.isPossibleValue(mode);
      throw new RuntimeException("this method forgot a mode: " + mode);
    }
  }

  /**
   * This method reads stages from disk
   */
  public static Parser loadParser(Map<String, String> params) {
    String m = params.get("model");
    if ("dep".equals(m)) {
      return loadDepParser(
          params.get("frameId"),
          params.get("argId"),
          params.get("argSpans"));
    } else if ("cons".equals(m)) {
      return loadConsParser(
          params.get("frameId"),
          params.get("argPruning"),
          params.get("argLabeling"));
    } else {
      throw new RuntimeException("provide model type with --model");
    }
  }

  /**
   * the arguments should either be a path to a saved stage or "oracle"
   */
  public static Parser loadDepParser(
      String frameId, String argId, String argSpans) {
    /*
    if (frameId == null)
      throw new IllegalArgumentException("provide frameId");
    if (argId == null)
      throw new IllegalArgumentException("provide argId");
    if (argSpans == null)
      throw new IllegalArgumentException("provide argSpans");
    PipelinedFnParser parser = new PipelinedFnParser(new ParserParams());
    if ("oracle".equals(frameId)) {
      parser.useGoldFrameId();
    } else {
      parser.getFrameIdStage().loadModel(new File(frameId));
    }
    if ("oracle".equals(argId)) {
      assert "oracle".equals(frameId);	// Implement predicted-frames + gold args stage
      parser.useGoldArgId();
    } else {
      parser.getArgIdStage().loadModel(new File(argId));
    }
    assert !"oracle".equals(argSpans);
    parser.getArgSpanStage().loadModel(new File(argSpans));
    return parser;
    */
    throw new RuntimeException("implement me");
  }

  /**
   * the arguments should either be a path to a saved stage or "oracle"
   */
  public static Parser loadConsParser(
      String frameId, String argPruning, String argLabeling) {
    throw new RuntimeException("implement me");
  }
}
