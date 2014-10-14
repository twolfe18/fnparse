package edu.jhu.hlt.fnparse.features;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
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
		Path p = new Path(sent, sent.getCollapsedDeps(), start, end,
		    NodeType.LEMMA, EdgeType.DIRECTION);
		System.out.println(p);
		assertEquals("fox<jump>over>fence", p.toString());

		int head = toks.indexOf("jumped");
		p = new Path(sent, sent.getCollapsedDeps(), head,
		    NodeType.POS, EdgeType.DEP);
		System.out.println(p);

		head = toks.indexOf("the");
		p = new Path(sent, sent.getCollapsedDeps(), head,
		    NodeType.LEMMA, EdgeType.DIRECTION);
		List<String> ngrams = new ArrayList<String>();
		p.pathNGrams(5, ngrams, "path5grams-");
		System.out.println(ngrams);

		p = new Path(sent, sent.getCollapsedDeps(), start, end,
		    NodeType.LEMMA, EdgeType.DIRECTION);
		ngrams.clear();
		p.pathNGrams(5, ngrams, null);
		System.out.println(ngrams);
	}

	@Test
	public void fuzz() {
		Random r = new Random(9001);
		for(FNParse p : DataUtil.iter2list(
		    FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences())) {
			Sentence s = p.getSentence();
			for(int i=0; i<20; i++) {
				int target = r.nextInt(s.size());
				int arg = r.nextInt(s.size());
				System.out.printf("[fuzz] target=%d arg=%d sent=%s\n",
				    target, arg, s.getId());
				Path path = new Path(s, s.getCollapsedDeps(), target, arg,
				    NodeType.POS, EdgeType.DIRECTION);
				assertTrue(path != null);
				path = new Path(s, s.getCollapsedDeps(), target,
				    NodeType.LEMMA, EdgeType.DEP);
				assertTrue(path != null);
			}
		}
	}
}
