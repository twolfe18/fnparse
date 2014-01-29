package edu.stanford.nlp.trees;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.Word;
import edu.stanford.nlp.parser.lexparser.TreebankLangParserParams;
import edu.stanford.nlp.util.Filter;
import edu.stanford.nlp.util.Filters;
import edu.stanford.nlp.util.Function;
import edu.jhu.hlt.fnparse.data.UsefulConstants;
import edu.jhu.hlt.fnparse.datatypes.StringAndIntArrayTuple;

/*
 * Cache the dependency parse of all the files into a single text file so that later run times are fast
 * The test timings are roughly 7.2 s and 2.6s for both the tests
 */
public class GetGovAndDepType {
	private Function<List<? extends HasWord>, Tree> lp;
	private TreebankLangParserParams params;
	private Filter<String> filter;
	private Map<String, StringAndIntArrayTuple> sentId2GDMap;
	
	public GetGovAndDepType(){
		
		lp=loadParser("edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz");
		try {
			Method method = lp.getClass().getMethod("getTLPParams");
			params = (TreebankLangParserParams) method.invoke(lp);
		} catch (Exception cnfe) {
			throw new RuntimeException(cnfe);
		}
		filter = Filters.acceptFilter();
		sentId2GDMap = new HashMap<String, StringAndIntArrayTuple>();
		try{
			BufferedReader br = new BufferedReader(new FileReader(UsefulConstants.sentId2GDMap));  
			String line = null;  
			String key = null;
			List<Integer> govList = new ArrayList<Integer>();
			List<String> depTypeList = new ArrayList<String>();

			while ((line = br.readLine()) != null) {
				if(line.equals("")){
					assert key != null;
					int[] govIntArray = new int[govList.size()];
					for(int i = 0; i<govList.size(); i++){
						govIntArray[i]=govList.get(i);
					}
					sentId2GDMap.put(key, 
							new StringAndIntArrayTuple(
									govIntArray, 
									depTypeList.toArray(new String[govList.size()])
							));
					key=null;
				}
				else{
					String[] tmp=line.split("\t");
					assert tmp.length == 4;
					key = tmp[0].trim();
					//String token = tmp[1].trim(); It is useless to load.
					govList.add(Integer.parseInt(tmp[2].trim()));
					depTypeList.add(tmp[3].trim());
				}
			}
			br.close();
		}
		catch(FileNotFoundException e){
			// do nothing
		} catch (IOException e) {
			// TODO Auto-generated catch block
			throw new RuntimeException();
		}		
	}

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

	public StringAndIntArrayTuple getGovAndDepType(String sentId, String[] tokens ){
		if(sentId2GDMap.get(sentId) != null){
			return sentId2GDMap.get(sentId);
		}
		else{
			StringAndIntArrayTuple tmp = getGovAndDepTypeThroughParsing(tokens);
			sentId2GDMap.put(sentId, tmp);
			int[] gov=tmp.getI();
			String[] depType = tmp.getS();
			try{
				// Open the file
				FileWriter fw = new FileWriter(UsefulConstants.sentId2GDMap, true);
				//write all the things
				for(int i = 0; i < gov.length; i++){
					fw.write(String.format("%s\t%s\t%d\t%s\n", sentId, tokens[i], gov[i], depType[i]));
				}
				fw.write("\n");
				fw.close();
			} catch(IOException e){
				throw new RuntimeException();
			}
			return tmp;
		}		
	}
	
	public StringAndIntArrayTuple getGovAndDepTypeThroughParsing(String[] tokens) {
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
