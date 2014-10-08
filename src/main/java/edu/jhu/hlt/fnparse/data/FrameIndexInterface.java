package edu.jhu.hlt.fnparse.data;

import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Frame;

public interface FrameIndexInterface {

  /**
   * includes NULL_FRAME
   */
  public List<Frame> allFrames();
  /** 
   * Constant time retrieval of Frame
   */
  public Frame getFrame(int i);
  public Frame getFrame(String s);

  /**
   * given a role name (e.g. returned by frame.getRole(3)),
   * return the index of that role.
   */
  public int getRoleIdx(Frame f, String s);
}
