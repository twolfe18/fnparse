package edu.jhu.hlt.fnparse.datatypes;

import java.util.*;

import edu.jhu.hlt.fnparse.data.UsefulConstants;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathConstants;

import java.io.*;

import org.w3c.dom.*;

import javax.xml.parsers.*;

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
	private int targetIdx;		// index of the target word
	private Sentence sentence;

	/**
	 * indices correspond to frame.getRoles()
	 * null-instantiated arguments should be null in the array
	 */
	private Span[] arguments;

	public FrameInstance(Frame frame, int targetIdx, Span[] arguments, Sentence sent) {
		assert (arguments == null && frame == null) || frame.numRoles() == arguments.length;
		this.frame = frame;

		this.targetIdx = targetIdx; // targetIdx is the index of trigger token in the sentence.
		this.arguments = arguments;
		this.sentence = sent;
	}

	public int getTriggerIdx() { return targetIdx; }

	public String getTriggerWord() { return sentence.getWord(targetIdx); }

	public Sentence getSentence() { return sentence; }

	public Frame getFrame() { return frame; }

	public Span getArgument(int roleIdx) { return arguments[roleIdx]; }

	public void setArgument(int roleIdx, Span extent) {
		arguments[roleIdx] = extent;
	}

	@Override
	public String toString() {
		return String.format("<FrameInstance target=%d>", targetIdx);
	}
}
