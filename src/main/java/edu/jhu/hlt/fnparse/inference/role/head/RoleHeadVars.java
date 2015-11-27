package edu.jhu.hlt.fnparse.inference.role.head;

import java.util.Iterator;

import org.apache.log4j.Logger;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FgRelated;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.pruning.IArgPruner;
import edu.jhu.hlt.tutils.Span;

/**
 * Represents which roles are active for a given frame at a location
 * by identifying likely headwords for arguments.
 * 
 * NOTE: I'm commenting out all of the expansion stuff here because it really needs
 * to be predicted first (in order to reduce the number of r_itjk^e variables that
 * need to have features computed). I'm not removing it completely in case I decide
 * to do SRP and jointly do r_itjk and r_itjk^e later.
 * 
 * @author travis
 */
public class RoleHeadVars implements FgRelated {

  public static final Logger LOG = Logger.getLogger(RoleHeadVars.class);
  public static boolean VERBOSE = false;
  public static boolean STORE_VAR_NAMES = false;
  public static final String DEFAULT_VAR_NAME = "SOME_ROLE_VAR".intern();

  // frame-target that this belongs to
  public final Span target;
  public final Frame frame;

  public final int n; // length of sentence

  // the indices of r_jk and r_jk_e correspond
  // r_jk[k][N], where N=sentence.size, represents this arg not being realized
  // there will be an Exactly1 factor on each r_jk[j] forall j
  public Var[][] r_kj;  // [k][j], may contain null values

  public VarConfig goldConf;
  public FrameInstance gold;

  public boolean hasLabels() { return goldConf != null; }

  private RoleHeadVars(
      FrameInstance gold,
      boolean gotFramePredictionWrong,
      boolean hasGold,
      Span target,
      Frame evoked,
      Sentence sent,
      HeadFinder hf,
      IArgPruner argPruner) {
    assert argPruner != null;
    if (evoked == Frame.nullFrame) {
      throw new IllegalArgumentException(
          "only create these for non-nullFrame f_it");
    }

    assert target != Span.nullSpan;
    this.target = target;
    this.frame = evoked;
    this.n = sent.size();
    this.gold = gold;
    if (hasGold)
      this.goldConf = new VarConfig();

    int K = evoked.numRoles();
    r_kj = new Var[K][n + 1];
    for (int k = 0; k < K; k++) {
      Span jGoldSpan = null;
      int jGold = -1;
      if (hasGold) {
        jGoldSpan = gotFramePredictionWrong
            ? Span.nullSpan : gold.getArgument(k);
        jGold = jGoldSpan == Span.nullSpan
            ? n : hf.head(jGoldSpan, gold.getSentence());
      }

      int inThisRow = 0;
      for (int j = 0; j < n; j++) {
        boolean argRealized = (j == jGold);

        if (argPruner.pruneArgHead(frame, k, j, sent)) {
          if (argRealized) {
            argPruner.falsePrune();
            if (VERBOSE) {
              LOG.warn(String.format(
                  "Pruned %s.%s for head \"%s\"",
                  gold.getFrame().getName(),
                  gold.getFrame().getRole(k),
                  sent.getWord(j)));
            }
          }
          continue;
        }

        String name = STORE_VAR_NAMES
            ? String.format("r_{%s@%s,j=%d,k=%d}", evoked.getName(), target, j, k)
            : DEFAULT_VAR_NAME;
        r_kj[k][j] = new Var(
            VarType.PREDICTED, 2, name, BinaryVarUtil.stateNames);

        if (hasGold) {
          goldConf.put(r_kj[k][j],
              BinaryVarUtil.boolToConfig(argRealized));
        }

        inThisRow++;
      }

      // If all roles were pruned, then no need to use that var
      // (or the "no arg" var)
      if (inThisRow == 0) {
        r_kj[k] = null;
        // I'm not removing the Vars from goldConf because it doesn't
        // have a drop method, probably doesn't matter
      } else {
        // There is no expansion variable for null-realized-arg
        String name = STORE_VAR_NAMES
            ? String.format("r_{%s@%s,k=%d,notRealized}", evoked.getName(), target, k)
            : DEFAULT_VAR_NAME;
        r_kj[k][n] = new Var(
            VarType.PREDICTED, 2, name, BinaryVarUtil.stateNames);
        if (hasGold) {
          boolean goldIsNull =
              gotFramePredictionWrong || (n == jGold);
          goldConf.put(r_kj[k][n],
              BinaryVarUtil.boolToConfig(goldIsNull));
        }
      }
    }
  }

  /** Constructor for prediction */
  public RoleHeadVars(
      Span target,
      Frame evoked,
      Sentence s,
      HeadFinder hf,
      IArgPruner argPruner) {
    this(null, false, false, target, evoked, s, hf, argPruner);
  }

  /**
   * Constructor for training.
   * 
   * This will handle the case where gold's Frame is different from evoked (t).
   * If they are the same, then the normal thing happens: all of the r_itjk
   * are set according to the arguments that actually appeared. If they are
   * different, then r_itjk are set to have a gold value of "not realized".
   */
  public RoleHeadVars(
      FrameInstance gold,
      Span target,
      Frame evoked,
      Sentence s,
      HeadFinder hf,
      IArgPruner argPruner) {
    this(gold, gold == null || gold.getFrame() != evoked, true,
        target, evoked, s, hf, argPruner);
  }

  /**
   * Returns an iterator of un-pruned (a wrapper around) role variables
   * (RVars). An RVar will always have a roleVar, but its expansion may be
   * null (in the case of the roleVar for "arg not realized").
   */
  public Iterator<RVar> getVars() {
    return new RVarIter(this.r_kj);
  }

  public Frame getFrame() { return frame; }

  public Span getTarget() { return target; }

  @Override
  public void register(FactorGraph fg, VarConfig gold) {
    Iterator<RVar> iter = this.getVars();
    while(iter.hasNext())
      fg.addVar(iter.next().roleVar);

    if (hasLabels())
      gold.put(this.goldConf);
  }

  public static final class RVar {
    public int j, k;
    public Var roleVar;			// binary
    public RVar(Var roleVar, int k, int j) {
      this.roleVar = roleVar;
      this.j = j;
      this.k = k;
    }
    @Override
    public String toString() {
      return String.format("<RVar j=%d k=%d %s>",
          j, k, roleVar.toString());
    }
  }

  public static class RVarIter implements Iterator<RVar> {
    private Var[][] roleVars;
    public int j, k;
    public RVarIter(Var[][] roleVars) {
      this.roleVars = roleVars;
      this.k = 0;
      this.j = 0;
      while (hasNext() && (roleVars[k] == null || roleVars[k][j] == null))
        bump();
    }
    private void bump() {
      j++;
      if (j == roleVars[k].length) {
        do { k++; }
        while (k < roleVars.length && roleVars[k] == null);
        j = 0;
      }
    }
    @Override
    public boolean hasNext() {
      return k < roleVars.length
          && roleVars[k] != null
          && j < roleVars[k].length;
    }
    @Override
    public RVar next() {
      RVar r = new RVar(roleVars[k][j], k, j);
      do { bump(); }
      while (hasNext() && roleVars[k][j] == null);
      return r;
    }
    @Override
    public void remove() { throw new UnsupportedOperationException(); }
  }
}

