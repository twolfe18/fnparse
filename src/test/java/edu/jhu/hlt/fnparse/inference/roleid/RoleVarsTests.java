package edu.jhu.hlt.fnparse.inference.roleid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Iterator;

import org.junit.Test;

import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.ParserTests;
import edu.jhu.hlt.fnparse.inference.role.head.RoleIdStage;
import edu.jhu.hlt.fnparse.inference.role.head.RoleVars;
import edu.jhu.hlt.fnparse.inference.role.head.RoleVars.RVar;

public class RoleVarsTests {

	private FNParse parse = ParserTests.makeDummyParse();
	private ParserParams params = new ParserParams();
	private RoleIdStage.Params roleIdParams = new RoleIdStage.Params(params);
	
	@Test
	public void testIterator() {
		testIterator(parse);
	}

	private void testIterator(FNParse parse) {
		final int n = parse.getSentence().size();
		for(FrameInstance fi : parse.getFrameInstances()) {
			System.out.println(fi);
			int targetHead = fi.getTarget().start;
			RoleVars rv = new RoleVars(targetHead, fi.getFrame(), parse.getSentence(), params, roleIdParams);

			Var[][] vars = rv.r_kj;
			assertEquals(vars.length, fi.getFrame().numRoles());
			
			int nnn = 0;
			for(int i=0; i<vars.length; i++)
				for(int j=0; j<vars[i].length; j++)
					if(vars[i][j] != null)
						nnn++;

			int iter_nnn = 0;
			Iterator<RVar> iter = rv.getVars();
			assertTrue(iter.hasNext());
			while(iter.hasNext()) {
				RVar r = iter.next();
				System.out.println(r);
				assertEquals(r.roleVar.getNumStates(), 2);
				assertEquals(r.roleVar.getType(), VarType.PREDICTED);
				assertEquals(vars[r.k][r.j], r.roleVar);
				assertTrue(r.j <= n && r.j >= 0);
				assertTrue(r.k >= 0 && r.k < fi.getFrame().numRoles());
				iter_nnn++;
			}
			assertEquals(nnn, iter_nnn);
		}
	}
}
