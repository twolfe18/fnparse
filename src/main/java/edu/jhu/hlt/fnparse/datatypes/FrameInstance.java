package edu.jhu.hlt.fnparse.datatypes;

import java.util.Arrays;

import edu.jhu.hlt.fnparse.datatypes.Sentence;

/**
 * This class should represent all details of the data
 * needed for inference and evaluation, but as little else as
 * possible so that we can add things (like priors over frames
 * in a document cluster) without this code noticing.
 * 
 * @author travis
 */
public class FrameInstance {

	private Frame frame; 
	private Span target;		// index of the target word
	private Sentence sentence;

	/**
	 * indices correspond to frame.getRoles()
	 * null-instantiated arguments should be null in the array
	 */
	private Span[] arguments;

	private FrameInstance(Frame frame, Span target, Span[] arguments, Sentence sent) {
		this.frame = frame;
		this.target = target; // targetIdx is the index of trigger token in the sentence.
		this.arguments = arguments;
		this.sentence = sent;
	}
	
	public static FrameInstance newFrameInstance(Frame frame, Span target, Span[] arguments, Sentence sent) {
		if(frame == null || arguments == null || sent == null)
			throw new IllegalArgumentException();
		if(frame.numRoles() != arguments.length)
			throw new IllegalArgumentException("null-instantiated roles should be null entries in the arguments array");
		return new FrameInstance(frame, target, arguments, sent);
	}
	
	public static FrameInstance frameMention(Frame frame, Span target, Sentence sent) {
		if(frame == null || sent == null)
			throw new IllegalArgumentException();
		Span[] arguments = new Span[frame.numRoles()];
		Arrays.fill(arguments, Span.nullSpan);
		return new FrameInstance(frame, target, arguments, sent);
	}

	public Span getTarget() { return target; }

//	public String getTargetWord() { return sentence.getWord(target); }

	public Sentence getSentence() { return sentence; }

	public Frame getFrame() { return frame; }

	public Span getArgument(int roleIdx) { return arguments[roleIdx]; }

	public String[] getArgumentTokens(int roleIdx) { return sentence.getWord(arguments[roleIdx]); }
	
	public void setArgument(int roleIdx, Span extent) {
		arguments[roleIdx] = extent;
	}

	@Override
	public String toString() {
		return String.format("<FrameInstance target=%d>", target);
	}
}
