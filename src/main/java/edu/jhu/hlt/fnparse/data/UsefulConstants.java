package edu.jhu.hlt.fnparse.data;

import java.io.File;

public class UsefulConstants {

	public static final File dataPath =  new File("//Users//pushpendrerastogi//framenetparser//fnparse//toydata");
	
	public static final File frameIndexPath = new File(dataPath, "fn15-frameindex");
	public static final File frameIndexLUPath = new File(dataPath, "fn15-frameindexLU");
	
	public static final File TestFN15FullTextFramesPath = new File(dataPath, "fn15-fulltext.frames.test");
	public static final File TestFN15FullTextConllPath = new File(dataPath, "fn15-fulltext.conll.test");
	
	public static final File TrainFN15FullTextFramesPath = new File(dataPath, "fn15-fulltext.frames.train");
	public static final File TrainFN15FullTextConllPath = new File(dataPath, "fn15-fulltext.conll.train");
	
	public static final File FN15LexicographicFramesPath = new File(dataPath, "fn15-lex.frames");
	public static final File FN15LexicographicConllPath = new File(dataPath, "fn15-lex.conll");
	
	public static final File semlinkFramesPath = new File(dataPath, "semlink-fulltext.frames");
	public static final File semlinkConllPath = new File(dataPath, "semlink-fulltext.conll");
	
	public static final File semLinkFrameInstanceFile = new File(dataPath, "semlink.1.2.2c");
	
}

