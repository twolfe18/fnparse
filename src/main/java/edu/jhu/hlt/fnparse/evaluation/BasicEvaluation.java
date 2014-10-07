package edu.jhu.hlt.fnparse.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;

public class BasicEvaluation {

  public static interface EvalFunc {
    public String getName();
    public double evaluate(List<SentenceEval> instances);
  }

  public static final StdEvalFunc targetMacroPrecision = new StdEvalFunc(true, false, true, FPR.Mode.PRECISION);
  public static final StdEvalFunc targetMacroRecall =    new StdEvalFunc(true, false, true, FPR.Mode.RECALL);
  public static final StdEvalFunc targetMacroF1 =        new StdEvalFunc(true, false, true, FPR.Mode.F1);

  public static final StdEvalFunc targetMicroPrecision = new StdEvalFunc(true, false, false, FPR.Mode.PRECISION);
  public static final StdEvalFunc targetMicroRecall =    new StdEvalFunc(true, false, false, FPR.Mode.RECALL);
  public static final StdEvalFunc targetMicroF1 =        new StdEvalFunc(true, false, false, FPR.Mode.F1);

  public static final StdEvalFunc fullMacroPrecision = new StdEvalFunc(true, true, true, FPR.Mode.PRECISION);
  public static final StdEvalFunc fullMacroRecall =    new StdEvalFunc(true, true, true, FPR.Mode.RECALL);
  public static final StdEvalFunc fullMacroF1 =        new StdEvalFunc(true, true, true, FPR.Mode.F1);

  public static final StdEvalFunc fullMicroPrecision = new StdEvalFunc(true, true, false, FPR.Mode.PRECISION);
  public static final StdEvalFunc fullMicroRecall =    new StdEvalFunc(true, true, false, FPR.Mode.RECALL);
  public static final StdEvalFunc fullMicroF1 =        new StdEvalFunc(true, true, false, FPR.Mode.F1);

  public static final StdEvalFunc argOnlyMacroPrecision = new StdEvalFunc(false, true, true, FPR.Mode.PRECISION);
  public static final StdEvalFunc argOnlyMacroRecall =    new StdEvalFunc(false, true, true, FPR.Mode.RECALL);
  public static final StdEvalFunc argOnlyMacroF1 =        new StdEvalFunc(false, true, true, FPR.Mode.F1);

  public static final StdEvalFunc argOnlyMicroPrecision = new StdEvalFunc(false, true, false, FPR.Mode.PRECISION);
  public static final StdEvalFunc argOnlyMicroRecall =    new StdEvalFunc(false, true, false, FPR.Mode.RECALL);
  public static final StdEvalFunc argOnlyMicroF1 =        new StdEvalFunc(false, true, false, FPR.Mode.F1);

  public static class StdEvalFunc implements EvalFunc {

    private boolean macro;
    private boolean includeTargets;
    private boolean includeArguments;
    private FPR.Mode mode;

    public StdEvalFunc(
        boolean includeTargets,
        boolean includeArguments,
        boolean macro,
        FPR.Mode mode) {
      if (!includeTargets && !includeArguments)
        throw new IllegalArgumentException();
      this.macro = macro;
      this.mode = mode;
      this.includeTargets = includeTargets;
      this.includeArguments = includeArguments;
    }

    public String getName() {
      StringBuilder sb = new StringBuilder();
      if (includeTargets && includeArguments)
        sb.append("Full");
      else if (includeTargets && !includeArguments)
        sb.append("Target");
      else if (!includeTargets && includeArguments)
        sb.append("Argument");
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
      if (includeTargets && includeArguments)
        fpr.accum(inst.fullTP(), inst.fullFP(), inst.fullFN());
      else if (includeTargets && !includeArguments)
        fpr.accum(inst.targetTP(), inst.targetFP(), inst.targetFN());
      else if (!includeTargets && includeArguments)
        fpr.accum(inst.argOnlyTP(), inst.argOnlyFP(), inst.argOnlyFN());
      return fpr;
    }
  }

  public static List<SentenceEval> zip(List<? extends FNTagging> gold, List<? extends FNTagging> hyp) {
    if(gold.size() != hyp.size())
      throw new IllegalArgumentException("gold.size=" + gold.size() + ", hyp.size=" + hyp.size());
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
    argOnlyMacroF1, argOnlyMacroPrecision, argOnlyMacroRecall,
    argOnlyMicroF1, argOnlyMicroPrecision, argOnlyMicroRecall,
    GenerousEvaluation.generousF1,
    GenerousEvaluation.generousPrecision,
    GenerousEvaluation.generousRecall};

  public static Map<String, Double> evaluate(
      List<FNParse> gold, List<FNParse> hyp) {
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
