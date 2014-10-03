package edu.jhu.hlt.fnparse.util;

import java.util.Map;

import edu.jhu.hlt.fnparse.inference.Parser;

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
			throw new RuntimeException();
		}
	}

	/**
	 * the arguments should either be a path to a saved stage or "oracle"
	 */
	public static Parser loadDepParser(
			String frameId, String argId, String argSpans) {
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
