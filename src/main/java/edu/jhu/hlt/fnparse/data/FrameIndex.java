package edu.jhu.hlt.fnparse.data;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
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
public class FrameIndex implements FrameIndexInterface, Serializable {
  private static final long serialVersionUID = 9063336157775364000L;

  /**
   * Frame used to indicate that a word does not evoke a frame
   */
  public static final Frame nullFrame = Frame.nullFrame;
  public static final int framesInFrameNet = 1019;	// The number of frames in Framenet 1.5
  public static final int framesInPropbank = 9999;  // TODO

  // Files where FrameIndexes are memoized to/from.
  // These are the keys for ExperimentProperties.getFile(key)
  public static final String JSER_FN_KEY = "data.framenet.memoizeFrameIndex";
  public static final String JSER_PB_KEY = "data.propbank.memoizeFrameIndex";

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
          String framename = "framenet/" + prevFrameName;
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

  private final String name;
  private List<Frame> allFrames;
  private Map<String, Frame> nameToFrameMap;
  private Map<Integer, String> indexToNameMap;
  private Frame[] byId;
  private boolean checkFrameNotNull = true;

  public FrameIndex(String name, int nFrames) {
    this.name = name;
    allFrames = new ArrayList<Frame>(nFrames);;
    nameToFrameMap = new HashMap<String, Frame>();
    indexToNameMap = new HashMap<Integer, String>();
    byId = new Frame[nFrames + 1];
  }

  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return "<FrameIndex " + name + " numFrames=" + getNumFrames() + ">";
  }

  /**
   * Accepts strings like "framenet/Commerce_buy" or "propbank/kill-v-1",
   * figures out which {@link FrameIndex} to use, and return the right frame.
   */
  public static Frame getFrameWithSchemaPrefix(String frameName) {
    int s = frameName.indexOf('/');
    if (s < 0) {
      throw new IllegalArgumentException("need frame name with schema in it: " + frameName);
    } else {
      // Frames now have the schema in the name, don't need to re-extract
//      String fn = frameName.substring(s + 1);
      Frame f = null;
      switch (frameName.substring(0, s)) {
      case "framenet":
        f = getFrameNet().nameToFrameMap.get(frameName);
        break;
      case "propbank":
        f = getPropbank().nameToFrameMap.get(frameName);
        break;
      default:
        throw new RuntimeException("can't parse: " + frameName);
      }
      assert f != null;
      return f;
    }
  }

  public static FrameIndex getPropbank() {
    if (propbank == null) {
      ExperimentProperties config = ExperimentProperties.getInstance();
      File memo = null;
      if (config.containsKey(JSER_PB_KEY)) {
        memo = config.getFile(JSER_PB_KEY);
        if (memo.isFile()) {
          Log.info("loading memoized frame index!");
          Object fi = FileUtil.deserialize(memo);
          propbank = (FrameIndex) fi;
          return propbank;
        }
      }

      Log.info("[main] reading propbank frames");

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
      FrameIndex fi = new FrameIndex("propbank", frames.size());
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

      if (memo != null) {
        Log.info("writing frame index memo to " + memo.getPath());
        FileUtil.serialize(propbank, memo);
      }
    }
    return propbank;
  }

  public static FrameIndex getFrameNet() {
    if(frameNet == null) {

      File memo = null;
      ExperimentProperties config = ExperimentProperties.getInstance();
      if (config.containsKey(JSER_FN_KEY)) {
        memo = config.getFile(JSER_FN_KEY);
        if (memo.isFile()) {
          Log.info("loading memoized frame index!");
          Object fi = FileUtil.deserialize(memo);
          frameNet = (FrameIndex) fi;
          return frameNet;
        }
      }

      Log.info("reading framenet frames");
      frameNet = new FrameIndex("framenet", framesInFrameNet);
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
      Log.info("reading role core types from " + coreFile.getPath());
      try (BufferedReader r = FileUtil.getReader(coreFile)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] toks = line.split("\\t");
          assert toks.length == 3;
          String fn = "framenet/" + toks[0];
          Frame f = frameNet.nameToFrameMap.get(fn);
          if (f == null)
            throw new RuntimeException("couldn't find frame by name=" + fn);
          int k = Arrays.asList(f.getRoles()).indexOf(toks[1]);
          if (k < 0) {
            Log.warn("missing " + toks[1] + " role for " + toks[0] + "?");
          } else {
            f.setRoleType(k, toks[2]);
          }
        }
        Log.info("done reading role core types");
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      if (memo != null) {
        Log.info("writing frame index memo to " + memo.getPath());
        FileUtil.serialize(frameNet, memo);
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
