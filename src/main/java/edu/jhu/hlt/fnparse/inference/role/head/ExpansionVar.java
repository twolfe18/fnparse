package edu.jhu.hlt.fnparse.inference.role.head;

import java.util.Arrays;

import org.apache.log4j.Logger;

import edu.jhu.autodiff.Tensor;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;

/**
 * @author travis
 */
public class ExpansionVar {
  public static Logger LOG = Logger.getLogger(ExpansionVar.class);
  public static boolean DEBUG = false;

  // These are used to point back to the FrameInstance that is being expanded.
  // Just don't change this unless you really think it through.
  public final int i;
  public final int fiIdx;
  public final int j;
  public final int k;
  public final FNParse onlyHeads;
  private Var[] vars; // indices correspond to that of this.values
  public Expansion.Iter values;
  private int goldIdx;

  public ExpansionVar(
      int i,
      int fiIdx,
      int j,
      int k,
      FNParse onlyHeads,
      Expansion.Iter values,
      int goldIdx) {
    this.i = i;
    this.fiIdx = fiIdx;
    this.j = j;
    this.k = k;
    this.onlyHeads = onlyHeads;
    this.values = values;
    this.goldIdx = goldIdx;
    this.vars = new Var[values.size()];
    for (int idx = 0; idx < vars.length; idx++) {
      Span arg = getSpan(idx);
      String name = String.format(
          "r_{%s.%s@%s=%s}",
          getFrame().getName(),
          getFrame().getRole(k),
          Arrays.asList(getSentence().getWordFor(getTarget())),
          Arrays.asList(getSentence().getWordFor(arg)));
      vars[idx] = new Var(VarType.PREDICTED, 2, name, BinaryVarUtil.stateNames);
    }
  }

  public Var getVar(int spanIdx) {
    return vars[spanIdx];
  }

  public int numSpans() {
    return vars.length;
  }

  public int getTargetHeadIdx() { return i; }
  public Frame getFrame() {
    return onlyHeads.getFrameInstance(fiIdx).getFrame();
  }
  public int getArgHeadIdx() { return j; }
  public int getRole() { return k; }

  public boolean hasGold() {
    return goldIdx >= 0;
  }

  public void addToGoldConfig(VarConfig goldConf) {
    assert hasGold();
    for (int i = 0; i < vars.length; i++) {
      int gold = BinaryVarUtil.boolToConfig(i == goldIdx);
      if (DEBUG)
        LOG.info("[addToGoldConfig] " + vars[i].getName() + " has gold " + gold);
      goldConf.put(vars[i], gold);
    }
  }

  public Span getSpan(int configIdx) {
    Expansion e = values.get(configIdx);
    return e.upon(j);
  }

  public Span getGoldSpan() {
    assert goldIdx >= 0;
    Expansion e = values.get(goldIdx);
    return e.upon(j);
  }

  public Span decodeSpan(FgInferencer hasMargins, boolean logDomain) {
    Span bestSpan = null;
    double bestScore = 0d;
    for (int i = 0; i < vars.length; i++) {
      Tensor df = logDomain
          ? hasMargins.getLogMarginals(vars[i])
          : hasMargins.getMarginals(vars[i]);
      df.normalize();
      double p = df.getValue(BinaryVarUtil.boolToConfig(true));
      Span s = getSpan(i);
      if (i == 0 || p > bestScore) {
        bestScore = p;
        bestSpan = s;
      }
      if (DEBUG) {
        LOG.info(String.format("[decodeSpan] %d %-30s %-80s %.3f",
            i,
            getFrame().getName() + "." + getFrame().getRole(this.k),
            Arrays.toString(onlyHeads.getSentence().getWordFor(s)),
            p));
      }
    }
    return bestSpan;
  }

  public Span getTarget() {
    return onlyHeads.getFrameInstance(fiIdx).getTarget();
  }

  public Sentence getSentence() {
    return onlyHeads.getSentence();
  }
}
