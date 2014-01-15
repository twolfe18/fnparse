package edu.jhu.hlt.fnparse.evaluation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BasicEvaluation {

	public static interface EvalFunc {
		public String getName();
		public double evaluate(List<SentenceEval> instances);
	}
	
	public static final EvalFunc[] evaluationFunctions = new EvalFunc[] {};	// TODO add some
	
	public static Map<String, Double> evaluate(List<SentenceEval> instances) {
		Map<String, Double> results = new HashMap<String, Double>();
		int n = evaluationFunctions.length;
		for(int i=0; i<n; i++) {
			EvalFunc ef = evaluationFunctions[i];
			double v = ef.evaluate(instances);
			results.put(ef.getName(), v);
		}
		return results;
	}
	
	public static void showResults(String meta, Map<String, Double> results) {
		for(Map.Entry<String, Double> x : results.entrySet())
			System.out.printf("%s: %s = %.3f\n", meta, x.getKey(), x.getValue());
	}
}
