package edu.jhu.hlt.fnparse.data;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.data.PropbankFrameIndex;
import edu.jhu.hlt.tutils.data.PropbankFrameIndex.PropbankFrame;

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
  public static final int framesInPropbank = 9999;  // TODO

  public static class FrameNetIterator implements Iterable<Frame>{
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
    public FrameNetIterator(){
      try {
        litFE = FileUtils.lineIterator(UsefulConstants.getFrameIndexPath(), "UTF-8");
        litLU = FileUtils.lineIterator(UsefulConstants.getFrameIndexLUPath(), "UTF-8");
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

  private static FrameIndex frameNet;
  private static FrameIndex propbank;

  private List<Frame> allFrames;
  private Map<String, Frame> nameToFrameMap;
  private Map<Integer, String> indexToNameMap;
  private Frame[] byId;
  private boolean checkFrameNotNull = true;

  public FrameIndex(int nFrames) {
    allFrames = new ArrayList<Frame>(nFrames);;
    nameToFrameMap = new HashMap<String, Frame>();
    indexToNameMap = new HashMap<Integer, String>();
    byId = new Frame[nFrames + 1];
  }

  public static FrameIndex getPropbank() {
    if (propbank == null) {
      Log.info("[main] reading propbank frames");

      ExperimentProperties config = ExperimentProperties.getInstance();
      boolean universalRoles = config.getBoolean("data.propbank.universalRoles", true);

      File dir = config.getExistingDir("data.propbank.frames");
      PropbankFrameIndex pfi = new PropbankFrameIndex(dir);
      // Sort the frames by name to prevent any change in ids (unless a frame is
      // added or removed...)
      List<PropbankFrame> frames = pfi.getAllFrames();
      Collections.sort(frames, new Comparator<PropbankFrame>() {
        @Override
        public int compare(PropbankFrame o1, PropbankFrame o2) {
          return o1.id.compareTo(o2.id);
        }
      });
      FrameIndex fi = new FrameIndex(frames.size());
      int numericId = 0;
      for (PropbankFrame pf : frames) {
        Frame f = new Frame(pf, numericId, PropbankFrameIndex.MODIFIER_ROLES, universalRoles);
        fi.allFrames.add(f);
        assert fi.byId[f.getId()] == null;
        fi.byId[f.getId()] = f;
        String old1 = fi.indexToNameMap.put(f.getId(), f.getName());
        assert old1 == null;
        Frame old2 = fi.nameToFrameMap.put(f.getName(), f);
        assert old2 == null;
        numericId++;
      }
      fi.checkFrameNotNull = false;
      propbank = fi;
    }
    return propbank;
  }

  public static FrameIndex getFrameNet() {
    if(frameNet == null) {

      LOG.info("reading framenet frames");
      frameNet = new FrameIndex(framesInFrameNet);
      int idx = 0;
      for(Frame f: new FrameNetIterator()){
        frameNet.allFrames.add(f);
        frameNet.nameToFrameMap.put(f.getName(), f);
        frameNet.indexToNameMap.put(idx, f.getName());
        assert frameNet.byId[f.getId()] == null;
        frameNet.byId[f.getId()] = f;
      }

      // Read in information about what the core roles are
      File fnDataDir = UsefulConstants.getDataPath();
      File coreFile = new File(fnDataDir, "core-roles.txt");
      LOG.info("reading role core types from " + coreFile.getPath());
      try (BufferedReader r = FileUtil.getReader(coreFile)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] toks = line.split("\\t");
          assert toks.length == 3;
          Frame f = frameNet.nameToFrameMap.get(toks[0]);
          int k = Arrays.asList(f.getRoles()).indexOf(toks[1]);
          if (k < 0) {
            LOG.warn("missing " + toks[1] + " role for " + toks[0] + "?");
          } else {
            f.setRoleType(k, toks[2]);
          }
        }
        LOG.info("done reading role core types");
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return frameNet;
  }

  public int getNumFrames() {
    return allFrames.size();
  }

  public Frame getFrame(int id) {
    if (id < 0)
      throw new IllegalArgumentException();
    if (id >= byId.length) {
      String n = this == frameNet ? "FrameNet" : this == propbank ? "Propbank" : "???";
      throw new IllegalArgumentException("frame id=" + id + " is not valid in " + n);
    }
    Frame f = byId[id];
    if (f == null)
      throw new RuntimeException();
    return f;
  }

  public Frame getFrame(String name) {
    Frame f = nameToFrameMap.get(name);
    if (checkFrameNotNull && f == null) {
      for (Map.Entry<String, Frame> x : nameToFrameMap.entrySet())
        Log.info(x.getKey() + " -> " + x.getValue());
      throw new RuntimeException("couldn't lookup frame: " + name);
    }
    return f;
  }

  public int getRoleIdx(Frame f, String roleName) {
    for (int i=0; i<f.numRoles(); i++)
      if (f.getRole(i).equals(roleName))
        return i;
    throw new IllegalStateException("Can't find that role! frame.roles="
        + Arrays.toString(f.getRoles()) + ", roleName=" + roleName);
  }

  public List<Frame> allFrames() {
    return allFrames;
  }

  public Map<String, Frame> nameToFrameMap(){
    return nameToFrameMap;
  }
}
