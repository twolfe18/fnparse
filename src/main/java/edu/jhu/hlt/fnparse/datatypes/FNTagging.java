package edu.jhu.hlt.fnparse.datatypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * a Sentence with Frame targets tagged, but no arguments labeled
 * @author travis
 */
public class FNTagging {

	private Sentence sent;
	private List<FrameInstance> frameInstances;
	
	public FNTagging(Sentence s, List<FrameInstance> frameMentions) {
		this.sent = s;
		this.frameInstances = frameMentions;
	}
	
	public Sentence getSentence() { return sent; }
	
	public List<FrameInstance> getFrameInstances() { return frameInstances; }
	
	public FrameInstance getFrameInstance(int i) { return frameInstances.get(i); }
	
	public int numFrameInstances() { return frameInstances.size(); }
	
	public Map<Span, FrameInstance> getFrameLocations() {
		Map<Span, FrameInstance> goldFrames = new HashMap<Span, FrameInstance>();
		for(FrameInstance fi : sent.getFrameInstances())
			goldFrames.put(fi.getTarget(), fi);
		return goldFrames;
	}
}
