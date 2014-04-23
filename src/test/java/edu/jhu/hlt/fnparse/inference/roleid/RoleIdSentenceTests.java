package edu.jhu.hlt.fnparse.inference.roleid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import edu.jhu.gm.data.FgExampleCache;
import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.train.AvgBatchObjective;
import edu.jhu.gm.train.CrfObjective;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.features.caching.RawExampleFactory;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.Parser.Mode;
import edu.jhu.hlt.fnparse.inference.ParserTests;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars.RVar;

public class RoleIdSentenceTests {

	boolean latentDeps = false;
	boolean debug = true;
	Parser parser = new Parser(Mode.PIPELINE_FRAME_ARG, latentDeps, debug);	//ParserTests.getFrameIdTrainedOnDummy();
	FNParse parse = ParserTests.makeDummyParse();
	RoleIdSentence rIdSent = new RoleIdSentence(parse.getSentence(), parse, parser.params, parse);
	
	@Test
	public void basic() {

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

	
	@Test
	public void nonNaNObjective() {
		Parser p = ParserTests.getFrameIdTrainedOnDummy();
		p.setMode(Mode.PIPELINE_FRAME_ARG, false);
		RawExampleFactory rexs = new RawExampleFactory(Arrays.asList(parse), p);
		FgExampleCache exs = new FgExampleCache(rexs, 10, false);
		CrfObjective obj = new CrfObjective(exs, p.infFactory());
		AvgBatchObjective obj2 = new AvgBatchObjective(obj, p.params.model, 1);
		double v = obj2.getValue(p.params.model.getParams());
		assertFalse(Double.isNaN(v));
	}
}
