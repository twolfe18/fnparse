package edu.jhu.hlt.fnparse.inference.spans;

import java.util.Arrays;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * @author travis
 */
public class ExpansionVar {
  public static Logger LOG = Logger.getLogger(ExpansionVar.class);
  static { LOG.setLevel(Level.INFO); }

  // These are used to point back to the FrameInstance that is being expanded.
  // Just don't change this unless you really think it through.
  public final int i;
  public final int fiIdx;
  public final int j;
  public final int k;
  public final FNParse onlyHeads;
  public Var var;
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
    //String name = String.format(
    //		"r_{i=%d,t=%s,j=%d,k=%d}^e",
    //		i, getFrame().getName(), j, k);
    String name = String.format(
        "r_{i=%s,t=%s,j=%s,k=%s}^e",
        onlyHeads.getSentence().getWord(i),
        getFrame().getName(),
        onlyHeads.getSentence().getWord(j),
        getFrame().getRole(k));
    this.var = new Var(VarType.PREDICTED, values.size(), name, null);
    LOG.debug(name + " = " + (goldIdx < 0 ? "NO_LABEL" : Arrays.toString(
        onlyHeads.getSentence().getWordFor(values.get(goldIdx).upon(j)))));
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
    int i = this.goldIdx;
    // TODO
    // if i == PRUNED_EXPANSION, don't train on this example
    // if i == FALSE_POS_ARG, don't train on this example
    if(i < 0)
      i = values.indexOf(Expansion.headToSpan(j, Span.widthOne(j)));
    goldConf.put(this.var, i);
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

  public Span decodeSpan(FgInferencer hasMargins) {
    DenseFactor df = hasMargins.getMarginals(var);
    if (!LOG.getLevel().isGreaterOrEqual(Level.DEBUG)) {
      for (int i = 0; i < df.size(); i++) {
        Span s = values.get(i).upon(j);
        LOG.info(String.format("[decodeSpan] %d %-30s %-80s %.3f",
            i,
            getFrame().getName() + "." + getFrame().getRole(this.k),
            Arrays.toString(onlyHeads.getSentence().getWordFor(s)),
            df.getValue(i)));
      }
    }
    return getSpan(df.getArgmaxConfigId());
  }
}
