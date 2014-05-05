package edu.jhu.hlt.fnparse.inference;

import static org.junit.Assert.*;

import java.io.File;
import java.util.Arrays;
import java.util.Iterator;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Before;
import org.junit.Test;

import edu.jhu.gm.data.FgExampleCache;
import edu.jhu.gm.train.AvgBatchObjective;
import edu.jhu.gm.train.CrfObjective;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.features.caching.RawExampleFactory;
import edu.jhu.hlt.fnparse.inference.Parser.Mode;

/**
 * i think i found the problem: pacaya will yield nan objective for empty crf examples...
 * 
 * @author travis
 */
public class NanObjectiveTests {
	
	@Before
	public void turnOnLoggers() {
		Logger.getLogger(CrfObjective.class).setLevel(Level.TRACE);
	}
	
	@Test
	public void basic() {
		
		Parser parser = new Parser(new File("saved-models/alphabets/argId-reg.model.gz"));
		assertTrue(parser.params.mode == Mode.PIPELINE_FRAME_ARG);
		assertTrue(parser.params.featIdx.size() > 1 * 1000 * 1000);
		assertTrue(parser.params.weights != null);
		
		System.out.println("[basic] weights.l2 = " + parser.params.weights.l2Norm());
		
		Iterator<FNParse> iter = FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences();
		while(iter.hasNext()) {
			FNParse p = iter.next();
			String msg = String.format("%s has a NaN objective", p.getSentence().getId());
			assertFalse(msg, objIsNan(parser, p));
			//if(objIsNan(parser, p)) System.err.println(msg);
		}
	}
	
	private boolean objIsNan(Parser parser, FNParse example) {
		RawExampleFactory rexs = new RawExampleFactory(Arrays.asList(example), parser);
		FgExampleCache exs = new FgExampleCache(rexs, 10, false);
		CrfObjective obj = new CrfObjective(exs, parser.infFactory());
		AvgBatchObjective obj2 = new AvgBatchObjective(obj, parser.params.weights, 1);
		double v = obj2.getValue(parser.params.weights.getParams());
		return Double.isNaN(v);
	}

}
