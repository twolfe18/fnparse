package edu.jhu.hlt.fnparse.data;

import java.io.File;

public class UsefulConstants {
	
	public static final int framesInFrameNet = 1019 ; // The number of frames in Framenet 1.5
	public static final File dataPath =  new File("toydata");	//System.getProperty("toydataPath");
	public static final File frameIndexXMLPath = new File(dataPath, "frameIndex.xml");
	public static final File frameXMLDirPath = new File(dataPath, "frame/");
	public static final File fullTextXMLDirPath = new File(dataPath, "fulltext/");
}
