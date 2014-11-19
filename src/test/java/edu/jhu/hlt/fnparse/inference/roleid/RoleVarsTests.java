package edu.jhu.hlt.fnparse.inference.roleid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Iterator;

import org.junit.Test;

import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.inference.ParserTests;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.inference.pruning.IArgPruner;
import edu.jhu.hlt.fnparse.inference.pruning.NoArgPruner;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadVars;
import edu.jhu.hlt.fnparse.inference.role.head.RoleHeadVars.RVar;

public class RoleVarsTests {

  private FNParse parse = ParserTests.makeDummyParse();

  @Test
  public void testIterator() {
    testIterator(parse);
  }

  private void testIterator(FNParse parse) {
    final int n = parse.getSentence().size();
    for(FrameInstance fi : parse.getFrameInstances()) {
      System.out.println(fi);
      HeadFinder hf = SemaforicHeadFinder.getInstance();
      IArgPruner argPruner = new NoArgPruner();
      RoleHeadVars rv = new RoleHeadVars(
          fi.getTarget(), fi.getFrame(), parse.getSentence(), hf, argPruner);

      Var[][] vars = rv.r_kj;
      assertEquals(vars.length, fi.getFrame().numRoles());

      int nnn = 0;
      for (int i=0; i<vars.length; i++)
        for (int j=0; vars[i] != null && j<vars[i].length; j++)
          if (vars[i][j] != null)
            nnn++;

      int iter_nnn = 0;
      Iterator<RVar> iter = rv.getVars();
      assertTrue(iter.hasNext());
      int prev_j = -1, prev_k = -1;
      while (iter.hasNext()) {
        RVar r = iter.next();
        System.out.println(r);
        assertEquals(r.roleVar.getNumStates(), 2);
        assertEquals(r.roleVar.getType(), VarType.PREDICTED);
        assertEquals(vars[r.k][r.j], r.roleVar);
        assertTrue(r.j <= n && r.j >= 0);
        assertTrue(r.k >= 0 && r.k < fi.getFrame().numRoles());
        if (prev_j >= 0)
          assertTrue(r.j != prev_j || r.k != prev_k);
        prev_j = r.j;
        prev_k = r.k;
        iter_nnn++;
      }
      assertEquals(nnn, iter_nnn);
    }
  }
}
