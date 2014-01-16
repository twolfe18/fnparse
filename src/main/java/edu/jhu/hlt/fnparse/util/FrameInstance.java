package edu.jhu.hlt.fnparse.util;

import edu.jhu.hlt.fnparse.data.UsefulConstants;
import edu.jhu.hlt.fnparse.data.DataUtil;
import java.util.List;
import java.util.Vector;
/**
 * This class should represent all details of the data
 * needed for inference and evaluation, but as little else as
 * possible so that we can add things (like priors over frames
 * in a document cluster) without this code noticing.
 * 
 * @author travis
 */
public class FrameInstance {
    
    private Frame frame;		// e.g. "MOTION" 
    private int triggerIdx;		// index of trigger word for this frame
    private String triggerLUName;
    private int triggerLUId;
    private Span[] arguments;
    private Sentence sentence;
	
    public FrameInstance(Frame frame, int triggerIdx, /*int triggerLUId, String triggerLUName,*/ Span[] arguments, Sentence sent) {
	assert frame.numRoles() == arguments.length;
	this.frame = frame;
	this.triggerIdx = triggerIdx;
	this.triggerLUId = triggerLUId;
	this.triggerLUName = triggerLUName;
	this.arguments = arguments;
	this.sentence = sent;
    }
    
    public int getTriggerIdx() { return triggerIdx; }
    
    public String getTriggerWord() { return sentence.getWord(triggerIdx); }
    
    public Sentence getSentence() { return sentence; }
    
    public Frame getFrame() { return frame; }
    
    public Span getArgument(int roleIdx) { return arguments[roleIdx]; }
    
    public void setArgument(int roleIdx, Span extent) {
	arguments[roleIdx] = extent;
    }
    
    public static List<FrameInstance> allFrameInstance(){
	List<FrameInstance> fil = new Vector<FrameInstance>();
	//List<Frame> allFrames = Frame.allFrames();
	// Make a hashmap of frame ids to frames. for easy processing.
	//try{
	    // Access the sentences
	    
	    // Create a frame Instance from the XML of the full text annotations.
	    
	    // Append to the list.
	//}
	//catch (Exception e){
	  //   e.printStackTrace();System.exit(-1);
//	}
	return fil;
    }
}
