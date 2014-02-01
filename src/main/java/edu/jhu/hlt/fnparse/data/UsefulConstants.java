package edu.jhu.hlt.fnparse.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import edu.stanford.nlp.trees.PennTreeReader;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreeReader;


public class UsefulConstants {

	public static final File dataPath =  new File("toydata");
	public static final File frameIndexPath = new File(dataPath, "fn15-frameindex");
	public static final File frameIndexLUPath = new File(dataPath, "fn15-frameindexLU");
	public static final File frameXMLDirPath = new File(dataPath, "frame/");
	public static final File fullTextXMLDirPath = new File(dataPath, "fulltext/");
	public static final File sentId2GDMap = new File("sentId2GDMap.txt");
	public static final File semLinkFrameInstanceFile = new File(dataPath, "semlink.1.2.2c");
	public static Tree getPennTree(String parseFileName, int sentenceIndex, String fileSuffix){
		assert parseFileName.endsWith(".parse");
		assert parseFileName.startsWith("nw/wsj/");

		String treeFileName = "toydata//"+parseFileName.substring(0, parseFileName.length()-6)+fileSuffix;
		try{
			TreeReader tr = new PennTreeReader(new InputStreamReader(new FileInputStream(treeFileName), "UTF-8"));
			Tree t = tr.readTree();
			int count = 0;
			assert t != null;
			while (t != null) {
//				System.out.println(t);
//				System.out.println();
				if(count == sentenceIndex){
					break;
				}
				else{
					t = tr.readTree();
					count++;
				}
			}
			return t;
		}
		catch(Exception e){
			throw new RuntimeException(e);
		}
	}
}

