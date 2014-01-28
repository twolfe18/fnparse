package edu.stanford.nlp.trees;

import java.lang.reflect.Method;
import java.util.*;

import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.Word;
import edu.stanford.nlp.parser.lexparser.TreebankLangParserParams;
import edu.stanford.nlp.util.Filter;
import edu.stanford.nlp.util.Filters;
import edu.stanford.nlp.util.Function;
import edu.jhu.hlt.fnparse.datatypes.StringAndIntArrayTuple;

public class ConllStyleOutput {

	/** Do this static hack so that you dont load models again and again
	 * 
	 */
	private final static Function<List<? extends HasWord>, Tree> lp = null;
	private final static TreebankLangParserParams params = null;
	private final static Filter<String> filter = null;
	/*
	static{
		lp=loadParser("edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz");
		try {
			Method method = lp.getClass().getMethod("getTLPParams");
			params = (TreebankLangParserParams) method.invoke(lp);
		} catch (Exception cnfe) {
			throw new RuntimeException(cnfe);
		}
		filter = Filters.acceptFilter();
	}*/
	
	@SuppressWarnings("unchecked")
	private static Function<List<? extends HasWord>, Tree> loadParser(String parserFile) {    
		Function<List<? extends HasWord>, Tree> lp;
		// Copy pasta from edu.stanford.nlp.trees.GrammaticalStructure
		// A long comment over there explains why they are using reflection
		try {
			Class<?>[] classes = new Class<?>[] { String.class, String[].class };
			Method method = Class.forName("edu.stanford.nlp.parser.lexparser.LexicalizedParser").getMethod("loadModel", classes);
			lp = (Function<List<? extends HasWord>,Tree>) method.invoke(null, parserFile, new String[]{});
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return lp;
	}	

	public static StringAndIntArrayTuple getGovAndDepType(String[] tokens) {
		List<Word> words = new ArrayList<Word>(); 
		// Make Word-s
		// Words are made by Word(String word, int beginPosition, int endPosition)
		// The endPosition is excluded so help me please .  == 0,4 -- 5,7 -- 8,14 -- 15,16
		int start = 0;
		int end = 0;
		for (String t : tokens){
			end = start + t.length();
			words.add(new Word(t, start, end));
			start = end + 1;
		}
		Tree tree = lp.apply(words);
		//	 new SimpleTree();
		//TreebankLangParserParams params = ReflectionLoading.loadByReflection("edu.stanford.nlp.parser.lexparser.EnglishTreebankParserParams");
		// Some more dependency loop avoiding reflective magic
		GrammaticalStructure gs = params.getGrammaticalStructure(tree,  filter, params.typedDependencyHeadFinder());

		String almostThere = GrammaticalStructure.dependenciesToString(gs, gs.typedDependenciesCCprocessed(true), tree, /*conllx=*/ true, false);

		/*for sentence "Help me please ." 
		 * System.out.println(almostThere);  is the following (\t and \n delimited) 
		 * 1	Help	_	VB	VB	_	0	root	_	_
		 * 2	me	    _	PRP	PRP	_	3	nsubj	_	_
		 * 3	please	_	VB	VB	_	1	ccomp	_	_
		 * 4	.	    _	.	.	_	1	punct	_	_
		 */
		String[] outputLines = almostThere.split("\\n");
		assert outputLines.length == tokens.length;
		String[] depType = new String[tokens.length];
		int[] gov = new int[tokens.length];
		for(int i=0; i<tokens.length; i++){
			String ol = outputLines[i];
			String[] tmp = ol.split("\\t");
			gov[i]=Integer.parseInt(tmp[6])-1;
			depType[i]=tmp[7];
		}
		return new StringAndIntArrayTuple(depType,gov);
	}

}
