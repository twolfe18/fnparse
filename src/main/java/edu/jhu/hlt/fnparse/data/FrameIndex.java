package edu.jhu.hlt.fnparse.data;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;

/**
 * Reads frames from disk and provides access to them
 */
public class FrameIndex implements FrameIndexInterface {
  public static final Logger LOG = Logger.getLogger(FrameIndex.class);
  /**
   * Frame used to indicate that a word does not evoke a frame
   */
  public static final Frame nullFrame = Frame.nullFrame;
  public static final int framesInFrameNet = 1019;	// The number of frames in Framenet 1.5

  public static class FrameIndexIterator implements Iterable<Frame>{
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
    public FrameIndexIterator(){
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
            return ret;
          }
          Frame ret;
          List<String> fename = new ArrayList<String>();
          List<LexicalUnit> lu = new ArrayList<LexicalUnit>();
          while(litFE.hasNext() && curFrameIDFE.equals(prevFrameID)){
            // read lines till the curFrameID is same as prevFrameId (or prevFrameID is null)
            // and we can still read lines
            String[] l = curLineFE.split("\t");

            // Roles can have quotes on them, remove them
            String roleName = l[4].replaceAll("(^\")|(\"$)", "");

            fename.add(roleName);
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

  private static FrameIndex singleton;
  private List<Frame> allFrames = new ArrayList<Frame>(framesInFrameNet);;
  private Map<String, Frame> nameToFrameMap = new HashMap<String, Frame>();
  private Map<Integer, String> indexToNameMap = new HashMap<Integer, String>();
  private Frame[] byId = new Frame[framesInFrameNet + 1];

  private FrameIndex() { 
    // singleton
    // Since its a singleton and its really lightweight.
    // Just populate it during construction.
    LOG.info("reading frames");
    int idx = 0;
    for(Frame f: new FrameIndexIterator()){
      allFrames.add(f);
      nameToFrameMap.put(f.getName(), f);
      indexToNameMap.put(idx, f.getName());
      assert byId[f.getId()] == null;
      byId[f.getId()] = f;
    }

    // Read in information about what the core roles are
    try {
      File coreFile = new File("toydata/core-roles.txt");
      LOG.info("reading role core types from " + coreFile.getPath());
      BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(coreFile)));
      while (r.ready()) {
        String line = r.readLine();
        String[] toks = line.split("\\t");
        assert toks.length == 3;
        Frame f = nameToFrameMap.get(toks[0]);
        int k = Arrays.asList(f.getRoles()).indexOf(toks[1]);
        if (k < 0) {
          LOG.warn("missing " + toks[1] + " role for " + toks[0] + "?");
        } else {
          f.setRoleType(k, toks[2]);
        }
      }
      r.close();
      LOG.info("done reading role core types");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static FrameIndex getInstance() {
    if(singleton == null)
      singleton = new FrameIndex();
    return singleton;
  }

  public Frame getFrame(int id) {
    Frame f = byId[id];
    if(f == null)
      throw new RuntimeException();
    return f;
  }

  public Frame getFrame(String name) {
    return nameToFrameMap.get(name);
  }

  public int getRoleIdx(Frame f, String roleName) {
    for(int i=0; i<f.numRoles(); i++)
      if(f.getRole(i).equals(roleName))
        return i;
    throw new IllegalStateException("frame=" + f + ", roleName=" + roleName);
  }

  public List<Frame> allFrames() {
    return allFrames;
  }

  public Map<String, Frame> nameToFrameMap(){
    return nameToFrameMap;
  }
}
