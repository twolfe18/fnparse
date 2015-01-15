package edu.jhu.hlt.fnparse.evaluation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameArgInstance;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;

/**
 * This class defines EvalFunc functions which given precision, recall, and F1
 * defined for each frame-role.
 * 
 * @author travis
 */
public class FrameRoleEvaluation {

  private static Map<String, FREvalFunc> evalFunctions = genByFrameRole();

  public static FREvalFunc getEvalFuncFor(Frame f, int k, FPR.Mode mode) {
    FREvalFunc ef = evalFunctions.get(key(f, k, mode));
    assert ef != null;
    return ef;
  }

  public static Collection<FREvalFunc> getAllFrameRoleEvalFuncs() {
    List<FREvalFunc> fs = new ArrayList<>();
    fs.addAll(evalFunctions.values());
    Collections.sort(fs, new Comparator<FREvalFunc>() {
      @Override
      public int compare(FREvalFunc o1, FREvalFunc o2) {
        return o1.getName().compareTo(o2.getName());
      }
    });
    assert fs.size() > 0;
    return fs;
  }

  private static Map<String, FREvalFunc> genByFrameRole() {
    Map<String, FREvalFunc> m = new HashMap<>();
    for (Frame f : FrameIndex.getInstance().allFrames()) {
      int K = f.numRoles();
      for (int k = 0; k < K; k++) {
        for (FPR.Mode mode : FPR.Mode.values()) {
          FREvalFunc func = new FREvalFunc(f, k, mode);
          FREvalFunc old = m.put(key(f, k, mode), func);
          if (old != null)
            throw new RuntimeException();
        }
      }
    }
    return m;
  }

  private static String key(Frame frame, int role, FPR.Mode mode) {
    return frame.getId() + "-" + role + "-" + mode;
  }

  public static class FREvalFunc implements EvalFunc {
    private final Frame frame;
    private final int role;
    private final FPR.Mode mode;
    private final String name;

    public FREvalFunc(Frame frame, int role, FPR.Mode mode) {
      this.frame = frame;
      this.role = role;
      this.mode = mode;
      this.name = String.format("Micro%s-%s.%s", mode, frame.getName(), frame.getRole(role));
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public double evaluate(List<SentenceEval> instances) {
      Predicate<FrameArgInstance> relevant = new Predicate<FrameArgInstance>() {
        @Override public boolean test(FrameArgInstance t) {
          return frame.equals(t.frame) && role == t.role;
        }
      };
      boolean macro = false;
      FPR fpr = new FPR(macro);
      for (SentenceEval se : instances) {
        long tp = se.getFullTruePos().stream().filter(relevant).collect(Collectors.counting());
        long fp = se.getFullFalsePos().stream().filter(relevant).collect(Collectors.counting());
        long fn = se.getFullFalseNeg().stream().filter(relevant).collect(Collectors.counting());
        fpr.accum(tp, fp, fn);
      }
      return fpr.get(mode);
    }
  }
}
