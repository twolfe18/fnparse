package edu.jhu.hlt.fnparse.inference.role.head;

import java.util.Arrays;

import org.apache.log4j.Logger;

import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * @author travis
 */
public class ExpansionVar {
  public static Logger LOG = Logger.getLogger(ExpansionVar.class);
  public static boolean SHOW_DECODE = false;

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
    if (i < 0) {
      assert false : "no gold?"
          + " or some other mistake like predicted head not in gold span?";
      i = values.indexOf(Expansion.headToSpan(j, Span.widthOne(j)));
    }
    LOG.info(this.var.getName() + " has gold "
        + Arrays.toString(getSentence().getWordFor(getSpan(i))));
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
    if (SHOW_DECODE) {
      LOG.info("[decodeSpan] hash=" + this.hashCode());
      for (int i = 0; i < df.size(); i++) {
        Span s = getSpan(i);
        LOG.info(String.format("[decodeSpan] %d %-30s %-80s %.3f",
            i,
            getFrame().getName() + "." + getFrame().getRole(this.k),
            Arrays.toString(onlyHeads.getSentence().getWordFor(s)),
            df.getValue(i)));
      }
    }
    Span m = getSpan(df.getArgmaxConfigId());
    if (SHOW_DECODE) {
      LOG.info("[decodeSpan] argMaxConfig=" + Arrays.toString(onlyHeads.getSentence().getWordFor(m)));
      if (goldIdx >= 0)
        LOG.info("[decodeSpan] goldConfig=" + Arrays.toString(onlyHeads.getSentence().getWordFor(getGoldSpan())));
    }
    return m;
  }

  public Span getTarget() {
    return onlyHeads.getFrameInstance(fiIdx).getTarget();
  }
  
  public Sentence getSentence() {
    return onlyHeads.getSentence();
  }
}
