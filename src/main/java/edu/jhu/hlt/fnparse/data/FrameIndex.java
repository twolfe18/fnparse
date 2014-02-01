package edu.jhu.hlt.fnparse.data;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;

/**
 * Reads frames from disk and provides access to them
 */
public class FrameIndex implements Iterator<Frame> {

	public static final int framesInFrameNet = 1019;	// The number of frames in Framenet 1.5
	
	private List<Frame> allFrames = null;
	
	// The reader that points to the frameindex file
	private LineIterator litFE; 
	private LineIterator litLU;
	
	private String curLineFE = null;
	private String curLineLU = null;
	private String curFrameIDFE;
	private String curFrameIDLU;
	private String curFrameNameFE;
	
	private String prevFrameID;
	
	private String prevFrameName = null;
	private FrameIndex() { // singleton
		try {
			litFE = FileUtils.lineIterator(UsefulConstants.frameIndexPath, "UTF-8");
			litLU = FileUtils.lineIterator(UsefulConstants.frameIndexLUPath, "UTF-8");
			// Do not remove this line. It goes past the preamble
			@SuppressWarnings("unused")
			String _preambleRow1 = litFE.nextLine();
			String _preambleRow2 = litLU.nextLine();
			curLineFE = litFE.nextLine();
			curLineLU = litLU.nextLine();
			prevFrameID = curFrameIDFE = (curLineFE.split("\t"))[0];
			curFrameIDLU = (curLineLU.split("\t"))[0];
			prevFrameName = curFrameNameFE = (curLineFE.split("\t"))[3];
			assert curFrameIDFE.equals(curFrameIDLU);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}	
	private static FrameIndex singleton;
	public static FrameIndex getInstance() {
		if(singleton == null)
			singleton = new FrameIndex();
		return singleton;
	}
	
	/**
	 * Frame used to indicate that a word does not evoke a frame
	 */
	public final Frame nullFrame = Frame.nullFrame;
	
	/**
	 * get a frame by its id (constant time)
	 */
	public Frame getFrame(int id) {
		throw new RuntimeException("implement me");
	}
	
	/**
	 * given a role name (e.g. returned by frame.getRole(3)),
	 * return the index of that role.
	 */
	public int getRoleIdx(Frame f, String roleName) {
		for(int i=0; i<f.numRoles(); i++)
			if(f.getRole(i).equals(roleName))
				return i;
		throw new IllegalStateException("frame=" + f + ", roleName=" + roleName);
	}
	
	/**
	 * includes NULL_FRAME
	 */
	public List<Frame> allFrames() {
		if(allFrames == null){
			allFrames = new ArrayList<Frame>(framesInFrameNet);
			allFrames.add(nullFrame);
			while(hasNext())
				allFrames.add(next());
		}
		return allFrames;
	}

	@Override
	public boolean hasNext() {
		return (litLU.hasNext() && litFE.hasNext());
	}

	@Override
	public Frame next() {
		Frame ret;
		List<String> fename = new ArrayList<String>();
		List<LexicalUnit> lu = new ArrayList<LexicalUnit>();
		while(litFE.hasNext() && curFrameIDFE.equals(prevFrameID)){
			// read lines till the curFrameID is same as prevFrameId (or prevFrameID is null)
			// and we can still read lines
			String[] l = curLineFE.split("\t");
			fename.add(l[4]);
			curLineFE = litFE.nextLine();
			l = curLineFE.split("\t");
			curFrameIDFE = l[0];
			curFrameNameFE = l[3];
			
		}
		while(litLU.hasNext() && curFrameIDLU.equals(prevFrameID)){
			String[] l = curLineLU.split("\t");
			String luRepr = l[3]; 
			lu.add(new LexicalUnit((luRepr.split("\\."))[0], (luRepr.split("\\."))[1]));
			curLineLU = litLU.nextLine();
			l = curLineLU.split("\t");
			curFrameIDLU = l[0];
		}
		// At this point curFrameIDLU has advanced.
		// But the prevFrameID's values should be passed on
		int frameid = Integer.parseInt(prevFrameID)+1;
		String framename = prevFrameName;
		ret = new Frame(frameid, framename, lu.toArray(new LexicalUnit[0]), fename.toArray(new String[0]));
		//assert curFrameIDLU.equals(curFrameIDFE); 
		prevFrameID = curFrameIDFE;
		prevFrameName = curFrameNameFE;
		return ret;
	}

	@Override
	public void remove() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException(); 
	}
}
