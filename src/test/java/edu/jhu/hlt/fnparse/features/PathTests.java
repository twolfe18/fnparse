package edu.jhu.hlt.fnparse.features;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Path.RuleType;
import edu.jhu.hlt.fnparse.inference.ParserTests;

public class PathTests {
	
	@Test
	public void basic() {
		FNParse parse = ParserTests.makeDummyParse();
		Sentence sent = parse.getSentence();
		List<String> toks = Arrays.asList(sent.getWords());
		int start = toks.indexOf("fox");
		int end = toks.indexOf("fence");
		Path p = new Path(sent, start, end, RuleType.LEMMA);
		System.out.println(p);
		assertEquals("fox<jump>over>fence", p.toString());
	}

}
