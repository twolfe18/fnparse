package edu.jhu.hlt.fnparse.data;

import java.io.IOException;
import java.util.*;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;

/**
 * Reads frames from disk and provides access to them
 */
public class FrameIndex implements Iterable<Frame> {

	public static final int framesInFrameNet = 1019;	// The number of frames in Framenet 1.5

	private List<Frame> allFrames = new ArrayList<Frame>(framesInFrameNet);;
	private Map<String, Frame> nameToFrameMap = new HashMap<String, Frame>();
	
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
			@SuppressWarnings("unused")
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
	
	private Map<String, Frame> byName;
	public Frame getFrameByName(String name) {
		if(byName == null) {
			byName = new HashMap<String, Frame>();
			for(Frame f : this.allFrames()) {
				Frame old = byName.put(f.getName(), f);
				assert old == null;
			}
		}
		return byName.get(name);
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
		for(Frame f: this){
			// Essentially I am running the iterable for its side effects.
			int a = 1+1;
		}
		return allFrames;
	}

	public Map<String, Frame> nameToFrameMap(){
		for(Frame f: this){
			// same here running for side effect
			int a = 1+1;
		}

		return nameToFrameMap;
	}
	@Override
	public Iterator<Frame> iterator() {
		Iterator<Frame> it = new Iterator<Frame>(){
			private boolean returnNull = true;

			@Override
			public boolean hasNext() {
				return (litLU.hasNext() && litFE.hasNext());

			}

			@Override
			public Frame next() {
				if( returnNull){
					returnNull = false;
					Frame ret = nullFrame;
					allFrames.add(ret);
					nameToFrameMap.put(ret.getName(), ret);
					return ret;
				}
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
					
					// for multi-word LUs, like "\"domestic violence.N\"",
					// we should strip off the quotes
					luRepr = luRepr.replaceAll("(^\")|(\"$)", "");
					
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
				allFrames.add(ret);
				nameToFrameMap.put(ret.getName(), ret);
				return ret;
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException(); 	
			}
		};
		return it;
	}
}
