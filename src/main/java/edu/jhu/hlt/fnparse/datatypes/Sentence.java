package edu.jhu.hlt.fnparse.datatypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Sentence {

	/**
	 * a __globally__ unique identifier
	 * should not overlap between datasets
	 */
	private String id;

	/**
	 * where did this sentence come from?
	 * redundant with id, a convenience
	 */
	private String dataset;
	
	private String[] tokens;
	private String[] pos;

	// may be empty
	private List<FrameInstance> goldFrames, hypFrames;
	
	public Sentence(String dataset, String id, String[] tokens, String[] pos) {
		if(id == null || tokens == null)
			throw new IllegalArgumentException();
		if(pos != null && tokens.length != pos.length)
			throw new IllegalArgumentException();
		this.dataset = dataset;
		this.id = id;
		this.tokens = tokens;
		this.pos = pos;
	}
	
	public void addGoldFrame(FrameInstance fi) {
		if(fi.getSentence() != this)
			throw new IllegalArgumentException();
		if(goldFrames == null)
			goldFrames = new ArrayList<FrameInstance>();
		goldFrames.add(fi);
	}
	
	public List<FrameInstance> getGoldFrames() {
		if(goldFrames == null)
			throw new IllegalStateException();
		return goldFrames;
	}
	
	public boolean hasGoldFrames() { return goldFrames != null; }
	
	public void addHypFrame(FrameInstance fi) {
		if(fi.getSentence() != this)
			throw new IllegalArgumentException();
		if(hypFrames == null)
			hypFrames = new ArrayList<FrameInstance>();
		hypFrames.add(fi);
	}
	
	public List<FrameInstance> getHypFrames() {
		if(hypFrames == null)
			throw new IllegalStateException();
		return hypFrames;
	}
	
	public boolean hasHypFrames() { return hypFrames != null; }
	
	public String getDataset() { return dataset; }
	public String getId() { return id; }
	public String getWord(int i) { return tokens[i]; }
	public String getPos(int i) { return pos[i]; }
	public String[] getWord() { return tokens; }
	public String[] getPos() { return pos; }
	public String[] getWord(Span s) { return Arrays.copyOfRange(tokens, s.start, s.end); }
	public String[] getPos(Span s) { return Arrays.copyOfRange(pos, s.start, s.end); }
	
	public List<String> wordsIn(Span s) {
		List<String> l = new ArrayList<String>();
		for(int i=s.start; i<s.end; i++)
			l.add(tokens[i]);
		return l;
	}
	
	public List<String> posIn(Span s) {
		List<String> l = new ArrayList<String>();
		for(int i=s.start; i<s.end; i++)
			l.add(pos[i]);
		return l;
	}
	
	public int size() { return tokens.length; }
}
