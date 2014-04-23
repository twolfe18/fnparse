package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.inference.Parser.Mode;
import edu.jhu.hlt.fnparse.inference.roleid.RoleIdSentence;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars.RVar;

public class RoleIdSentenceTests {
	
	@Test
	public void basic() {
		boolean latentDeps = false;
		boolean debug = true;
		Parser parser = new Parser(Mode.PIPELINE_FRAME_ARG, latentDeps, debug);	//ParserTests.getFrameIdTrainedOnDummy();
		FNParse parse = ParserTests.makeDummyParse();
		RoleIdSentence rIdSent = new RoleIdSentence(parse.getSentence(), parse, parser.params, parse);

		List<RoleVars> roleVars = rIdSent.getHypotheses();
		assertTrue(roleVars.size() == parse.numFrameInstances());
		
		// check the variables
		LabeledFgExample fge = rIdSent.getTrainingExample();
		FactorGraph fg = fge.getFgLatPred();
		Set<Var> fgVars = new HashSet<Var>();
		fgVars.addAll(fg.getVars());
		int nVarFound = 0;
		for(RoleVars rv : roleVars) {
			int nNullExpansions = 0;
			Iterator<RVar> iter = rv.getVars();
			assertTrue(iter.hasNext());
			while(iter.hasNext()) {
				RVar rvar = iter.next();
				assertTrue(fgVars.contains(rvar.roleVar));
				nVarFound++;
				if(rvar.expansionVar != null) {
					nVarFound++;
					assertTrue(fgVars.contains(rvar.expansionVar));
				}
				else nNullExpansions++;
			}
			assertEquals(rv.getFrame().numRoles(), nNullExpansions);
			assertTrue(rv.getFrame() != Frame.nullFrame);
		}
		if(!latentDeps)
			assertEquals(fg.getVars().size(), nVarFound);
		assertTrue(fg.getVars().size() > 0);
		
		// check the factors
		assertTrue(fg.getFactors().size() >= fg.getVars().size());
	}

}
