package edu.jhu.hlt.fnparse.inference.roleid;

import java.util.Iterator;

import org.apache.log4j.Logger;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FgRelated;
import edu.jhu.hlt.fnparse.inference.ParserParams;

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
public class RoleVars implements FgRelated {

  public static final Logger LOG = Logger.getLogger(RoleVars.class);

  //// if you check the pareto frontier in ExpansionPruningExperiment:
  //// (6,0) gives 77.9 % recall
  //// (6,3) gives 86.7 % recall
  //// (8,3) gives 88.4 % recall
  //// (8,4) gives 90.0 % recall
  //// (10,5) gives 92.3 % recall
  //// (12,5) gives 93.2 % recall
  //public static final int maxArgRoleExpandLeft = 8;
  //public static final int maxArgRoleExpandRight = 3;
  //private static int prunedExpansions = 0, totalExpansions = 0;

  public boolean verbose = true;

  // frame-target that this belongs to
  public final int i;
  public final Frame t;

  public final int n;	// length of sentence

  // the indices of r_jk and r_jk_e correspond
  // r_jk[k][N], where N=sentence.size, represents this arg not being realized
  // there will be an Exactly1 factor on each r_jk[j] forall j
  public Var[][] r_kj;	// [k][j], may contain null values

  public VarConfig goldConf;
  public FrameInstance gold;

  public boolean hasLabels() { return goldConf != null; }

  private RoleVars(
      FrameInstance gold,
      boolean gotFramePredictionWrong,
      boolean hasGold,
      int targetHeadIdx,
      Frame evoked,
      Sentence sent,
      ParserParams globalParams,
      RoleIdStage.Params params) {
    if(evoked == Frame.nullFrame) {
      throw new IllegalArgumentException(
          "only create these for non-nullFrame f_it");
    }

    this.i = targetHeadIdx;
    this.t = evoked;
    this.n = sent.size();
    this.gold = gold;
    if(hasGold)
      this.goldConf = new VarConfig();

    int K = evoked.numRoles();
    r_kj = new Var[K][n+1];
    for(int k=0; k<K; k++) {
      Span jGoldSpan = null;
      int jGold = -1;
      if(hasGold) {
        jGoldSpan = gotFramePredictionWrong
            ? Span.nullSpan : gold.getArgument(k);
        jGold = jGoldSpan == Span.nullSpan
            ? n : globalParams.headFinder.head(
                jGoldSpan, gold.getSentence());
      }

      int inThisRow = 0;
      for(int j=0; j<n; j++) {
        boolean argRealized = (j == jGold);

        if(params.argPruner.pruneArgHead(t, k, j, sent)) {
          if(argRealized) {
            params.argPruner.falsePrune();
            if(verbose) {
              LOG.warn(String.format(
                  "Pruned %s.%s for head \"%s\"",
                  gold.getFrame().getName(),
                  gold.getFrame().getRole(k),
                  sent.getWord(j)));
            }
          }
          continue;
        }

        String name = String.format("r_{i=%d,t=%s,j=%d,k=%d}",
            i, evoked.getName(), j, k);
        r_kj[k][j] = new Var(
            VarType.PREDICTED, 2, name, BinaryVarUtil.stateNames);

        if(hasGold) {
          goldConf.put(r_kj[k][j],
              BinaryVarUtil.boolToConfig(argRealized));
        }

        inThisRow++;
      }

      // If all roles were pruned, then no need to use that var
      // (or the "no arg" var)
      if(inThisRow == 0) {
        r_kj[k] = null;
        // I'm not removing the Vars from goldConf because it doesn't
        // have a drop method, probably doesn't matter
      }
      else {
        // There is no expansion variable for null-realized-arg
        String name = String.format(
            "r_{i=%d,t=%s,k=%d,notRealized}",
            i, evoked.getName(), k);
        r_kj[k][n] = new Var(
            VarType.PREDICTED, 2, name, BinaryVarUtil.stateNames);
        if(hasGold) {
          boolean goldIsNull =
              gotFramePredictionWrong || (n == jGold);
          goldConf.put(r_kj[k][n],
              BinaryVarUtil.boolToConfig(goldIsNull));
        }
      }
    }
  }

  /** Constructor for prediction */
  public RoleVars(
      int targetHeadIdx,
      Frame evoked,
      Sentence s,
      ParserParams globalParams,
      RoleIdStage.Params params) {
    this(null, false, false, targetHeadIdx, evoked, s, globalParams, params);
  }

  /**
   * Constructor for training.
   * 
   * This will handle the case where gold's Frame is different from evoked (t).
   * If they are the same, then the normal thing happens: all of the r_itjk
   * are set according to the arguments that actually appeared. If they are
   * different, then r_itjk are set to have a gold value of "not realized".
   */
  public RoleVars(
      FrameInstance gold,
      int targetHeadIdx,
      Frame evoked,
      Sentence s,
      ParserParams globalParams,
      RoleIdStage.Params params) {
    this(gold, gold == null || gold.getFrame() != evoked, true,
        targetHeadIdx, evoked, s, globalParams, params);
  }

  /**
   * Returns an iterator of un-pruned (a wrapper around) role variables
   * (RVars). An RVar will always have a roleVar, but its expansion may be
   * null (in the case of the roleVar for "arg not realized").
   */
  public Iterator<RVar> getVars() {
    return new RVarIter(this.r_kj);
  }

  public Frame getFrame() { return t; }

  public int getTargetHead() { return i; }

  @Override
  public void register(FactorGraph fg, VarConfig gold) {
    Iterator<RVar> iter = this.getVars();
    while(iter.hasNext())
      fg.addVar(iter.next().roleVar);

    if(hasLabels())
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
      while(hasNext() && (roleVars[k] == null || roleVars[k][j] == null))
        bump();
    }
    private void bump() {
      j++;
      if(j == roleVars[k].length) {
        do { k++; }
        while(k < roleVars.length && roleVars[k] == null);
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
      while(hasNext() && roleVars[k][j] == null);
      return r;
    }
    @Override
    public void remove() { throw new UnsupportedOperationException(); }
  }
}

