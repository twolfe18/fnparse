package edu.jhu.hlt.fnparse.datatypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.util.HasId;

/**
 * A Sentence with Frame targets tagged, but no arguments (necessarily) labeled.
 * If you necessarily have arguments tagged, use FNParse.
 * This class is mainly used by parsers that take a two step approach to parsing:
 * predict frame targets and then predict their arguments.
 * 
 * @author travis
 */
public class FNTagging implements HasId {

	protected Sentence sent;
	protected List<FrameInstance> frameInstances;
	
	public FNTagging(Sentence s, List<FrameInstance> frameMentions) {
		if(frameMentions == null || s == null)
			throw new IllegalArgumentException();
		this.sent = s;
		this.frameInstances = frameMentions;
	}
	
	public Sentence getSentence() { return sent; }
	
	public List<FrameInstance> getFrameInstances() { return frameInstances; }
	
	public FrameInstance getFrameInstance(int i) { return frameInstances.get(i); }
	
	public int numFrameInstances() { return frameInstances.size(); }
	
	public Map<Span, FrameInstance> getFrameLocations() {
		Map<Span, FrameInstance> goldFrames = new HashMap<Span, FrameInstance>();
		for(FrameInstance fi : frameInstances)
			goldFrames.put(fi.getTarget(), fi);
		return goldFrames;
	}

	@Override
	public String getId() { return sent.getId(); }
}
