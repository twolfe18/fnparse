package edu.jhu.hlt.fnparse.evaluation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BasicEvaluation {

	public static interface EvalFunc {
		public String getName();
		public double evaluate(List<SentenceEval> instances);
	}
	
	// TODO implement macro/micro distinction
	
	public static final TargetPrecision targetPrecision = new TargetPrecision();
	public static class TargetPrecision implements EvalFunc {
		public String getName() { return "TargetPrecision"; }
		public double evaluate(List<SentenceEval> instances) {
			// micro for now
			int tp = 0, fp = 0;
			for(SentenceEval se : instances) {
				tp += se.targetTP();
				fp += se.targetFP();
			}
			if(tp + fp == 0d) return 1d;
			return tp / (tp + fp);
		}
		public double evaluate(SentenceEval inst) {
			double tp = inst.targetTP();
			double fp = inst.targetFP();
			if(tp + fp == 0d) return 1d;
			return tp / (tp + fp);
		}
	}
	
	public static final TargetRecall targetRecall = new TargetRecall();
	public static class TargetRecall implements EvalFunc {
		public String getName() { return "TargetRecall"; }
		public double evaluate(List<SentenceEval> instances) {
			// micro for now
			int tp = 0, fn = 0;
			for(SentenceEval se : instances) {
				tp += se.targetTP();
				fn += se.targetFN();
			}
			if(tp + fn == 0d) return 1d;
			return tp / (tp + fn);
		}
		public double evaluate(SentenceEval inst) {
			double tp = inst.targetTP();
			double fn = inst.targetFN();
			if(tp + fn == 0d) return 1d;
			return tp / (tp + fn);
		}
	}
	
	public static final TargetF1 targetF1 = new TargetF1();
	public static class TargetF1 implements EvalFunc {
		public String getName() { return "TargetF1"; }
		public double evaluate(List<SentenceEval> instances) {
			// micro for now
			double p = targetPrecision.evaluate(instances);
			double r = targetRecall.evaluate(instances);
			if(p + r == 0d) return 0d;
			return 2d * p * r / (p + r);
		}
		public double evaluate(SentenceEval inst) {
			double p = targetPrecision.evaluate(inst);
			double r = targetRecall.evaluate(inst);
			if(p + r == 0d) return 0d;
			return 2d * p * r / (p + r);
		}
	}
	
	public static final EvalFunc[] evaluationFunctions = new EvalFunc[] {targetF1, targetPrecision, targetRecall};
	
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
