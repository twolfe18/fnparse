package edu.jhu.hlt.fnparse.util;


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
	private Span[] arguments;
	private Sentence sentence;
	
	public FrameInstance(Frame frame, int triggerIdx, Span[] arguments, Sentence sent) {
		assert frame.numRoles() == arguments.length;
		this.frame = frame;
		this.triggerIdx = triggerIdx;
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
}
