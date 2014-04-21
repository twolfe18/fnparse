package edu.jhu.hlt.fnparse.features;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Path.EdgeType;
import edu.jhu.hlt.fnparse.features.Path.NodeType;
import edu.jhu.hlt.fnparse.inference.ParserTests;

public class PathTests {
	
	@Test
	public void basic() {
		FNParse parse = ParserTests.makeDummyParse();
		Sentence sent = parse.getSentence();
		List<String> toks = Arrays.asList(sent.getWords());
		int start = toks.indexOf("fox");
		int end = toks.indexOf("fence");
		Path p = new Path(sent, start, end, NodeType.LEMMA, EdgeType.DIRECTION);
		System.out.println(p);
		assertEquals("fox<jump>over>fence", p.toString());
		
		
		int head = toks.indexOf("jumped");
		p = new Path(sent, head, NodeType.POS, EdgeType.DEP);
		System.out.println(p);

		
		head = toks.indexOf("the");
		p = new Path(sent, head, NodeType.LEMMA, EdgeType.DIRECTION);
		List<String> ngrams = new ArrayList<String>();
		p.pathNGrams(5, ngrams, "path5grams-");
		System.out.println(ngrams);
		
		p = new Path(sent, start, end, NodeType.LEMMA, EdgeType.DIRECTION);
		ngrams.clear();
		p.pathNGrams(5, ngrams, null);
		System.out.println(ngrams);
	}

}
