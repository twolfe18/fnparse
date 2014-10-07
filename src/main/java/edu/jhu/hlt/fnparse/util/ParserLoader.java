package edu.jhu.hlt.fnparse.util;

import java.io.File;
import java.util.Map;

import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.stages.PipelinedFnParser;

/**
 * Saving parsers is handled by the parsers themselves.
 * 
 * @author travis
 */
public class ParserLoader {

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
  }

  /**
   * the arguments should either be a path to a saved stage or "oracle"
   */
  public static Parser loadConsParser(
      String frameId, String argPruning, String argLabeling) {
    throw new RuntimeException("implement me");
  }
}
