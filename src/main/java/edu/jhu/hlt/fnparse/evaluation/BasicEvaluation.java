package edu.jhu.hlt.fnparse.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.util.Avg;

public class BasicEvaluation {

	public static interface EvalFunc {
		public String getName();
		public double evaluate(List<SentenceEval> instances);
	}
	
	public static class FrameAccuracy implements EvalFunc {
		@Override
		public String getName() { return "FrameAccuracy"; }
		@Override
		public double evaluate(List<SentenceEval> instances) {
			Avg a = new Avg();
			for(SentenceEval s : instances)
				a.accum(s.getFrameAccuracy(), s.size());
			return a.average();
		}
	}
	
	public static final FrameAccuracy frameAccuracy = new FrameAccuracy();
	
	public static final StdEvalFunc targetMacroPrecision = new StdEvalFunc(true, true, FPR.Mode.PRECISION);
	public static final StdEvalFunc targetMacroRecall = new StdEvalFunc(true, true, FPR.Mode.RECALL);
	public static final StdEvalFunc targetMacroF1 = new StdEvalFunc(true, true, FPR.Mode.F1);
	
	public static final StdEvalFunc targetMicroPrecision = new StdEvalFunc(false, true, FPR.Mode.PRECISION);
	public static final StdEvalFunc targetMicroRecall = new StdEvalFunc(false, true, FPR.Mode.RECALL);
	public static final StdEvalFunc targetMicroF1 = new StdEvalFunc(false, true, FPR.Mode.F1);
	
	public static final StdEvalFunc fullMacroPrecision = new StdEvalFunc(true, false, FPR.Mode.PRECISION);
	public static final StdEvalFunc fullMacroRecall = new StdEvalFunc(true, false, FPR.Mode.RECALL);
	public static final StdEvalFunc fullMacroF1 = new StdEvalFunc(true, false, FPR.Mode.F1);
	
	public static final StdEvalFunc fullMicroPrecision = new StdEvalFunc(false, false, FPR.Mode.PRECISION);
	public static final StdEvalFunc fullMicroRecall = new StdEvalFunc(false, false, FPR.Mode.RECALL);
	public static final StdEvalFunc fullMicroF1 = new StdEvalFunc(false, false, FPR.Mode.F1);
	
	public static class StdEvalFunc implements EvalFunc {
		
		private boolean macro;
		private boolean targets;	// else full/targetRoles
		private FPR.Mode mode;
		
		public StdEvalFunc(boolean macro, boolean targets, FPR.Mode mode) {
			this.macro = macro;
			this.targets = targets;
			this.mode = mode;
		}
		
		public String getName() {
			StringBuilder sb = new StringBuilder();
			sb.append(targets ? "Target" : "Full");
			sb.append(macro ? "Macro" : "Micro");
			sb.append(mode);
			return sb.toString();
		}

		public double evaluate(List<SentenceEval> instances) {
			return evaluateAll(instances).get(mode);
		}
		
		public FPR evaluateAll(List<SentenceEval> instances) {
			FPR fpr = new FPR(macro);
			for(SentenceEval se : instances)
				fpr.accum(evaluateAll(se));
			return fpr;
		}
		
		public double evaluate(SentenceEval inst) {
			return evaluateAll(inst).get(mode);
		}
		
		public FPR evaluateAll(SentenceEval inst) {
			FPR fpr = new FPR(macro);
			if(targets)
				fpr.accum(inst.targetTP(), inst.targetFP(), inst.targetFN());
			else
				fpr.accum(inst.fullTP(), inst.fullFP(), inst.fullFN());
			return fpr;
		}
	}

	public static List<SentenceEval> zip(List<FNParse> gold, List<FNParse> hyp) {

		if(gold.size() != hyp.size())
			throw new IllegalArgumentException();

		List<SentenceEval> se = new ArrayList<SentenceEval>();
		for(int i=0; i<gold.size(); i++)
			se.add(new SentenceEval(gold.get(i), hyp.get(i)));

		return se;
	}


	public static final EvalFunc[] evaluationFunctions = new EvalFunc[] {
			targetMacroF1, targetMacroPrecision, targetMacroRecall,
			targetMicroF1, targetMicroPrecision, targetMicroRecall,
			fullMacroF1, fullMacroPrecision, fullMacroRecall,
			fullMicroF1, fullMicroPrecision, fullMicroRecall,
			frameAccuracy,
			GenerousEvaluation.generousF1, GenerousEvaluation.generousPrecision, GenerousEvaluation.generousRecall};
	
	public static Map<String, Double> evaluate(List<FNParse> gold, List<FNParse> hyp) {
		
		List<SentenceEval> se = zip(gold, hyp);
		Map<String, Double> results = new HashMap<String, Double>();
		int n = evaluationFunctions.length;
		for(int i=0; i<n; i++) {
			EvalFunc ef = evaluationFunctions[i];
			double v = ef.evaluate(se);
			results.put(ef.getName(), v);
		}
		return results;
	}
	
	public static void showResults(String meta, Map<String, Double> results) {
		List<String> keys = new ArrayList<String>();
		keys.addAll(results.keySet());
		Collections.sort(keys);
		for(String key : keys)
			System.out.printf("%s: %s = %.3f\n", meta, key, results.get(key));
	}
}
