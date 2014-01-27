package edu.jhu.hlt.fnparse.datatypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;

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
	
	// TODO (make sure you update constructor and copy method to)
	private int[] gov;			// values are 0-indexed, root is -1
	private String[] depType;
	
	// null means there are no frame instances labeled (but there may be some in the sentence)
	// empty means there are no frame instances in this sentence
	private List<FrameInstance> frameInstances;
	
	public Sentence(String dataset, String id, String[] tokens, String[] pos, boolean hasFrameInstancesLabeled) {
		if(id == null || tokens == null)
			throw new IllegalArgumentException();
		if(pos != null && tokens.length != pos.length)
			throw new IllegalArgumentException();
		this.dataset = dataset;
		this.id = id;
		this.tokens = tokens;
		this.pos = pos;
		
		if(hasFrameInstancesLabeled)
			this.frameInstances = new ArrayList<FrameInstance>();
		
		// upcase the POS tags for consistency (e.g. with LexicalUnit)
		for(int i=0; i<pos.length; i++)
			this.pos[i] = this.pos[i].toUpperCase();
	}

	public Sentence copy(boolean copyFrameInstances) {
		Sentence s = new Sentence(dataset, id, tokens, pos, copyFrameInstances);
		if(copyFrameInstances)
			for(FrameInstance fi : this.frameInstances)
				s.addFrameInstance(fi);
		return s;
	}
	
	public boolean hasFrameInstanceLabels() {
		return frameInstances != null;
	}
	
	public void addFrameInstance(FrameInstance fi) {
		if(fi.getSentence() != this)
			throw new IllegalArgumentException();
		if(fi.getFrame() == Frame.nullFrame)
			throw new IllegalArgumentException("only add non-null-frame instances");
		frameInstances.add(fi);
	}
	
	/**
	 * returns all (non-null-frame) instances of frames in this sentence.
	 */
	public List<FrameInstance> getFrameInstances() {
		return frameInstances;
	}
	
	public int numFrameInstances() { return frameInstances.size(); }
	
	public String getDataset() { return dataset; }
	public String getId() { return id; }
	
	public LexicalUnit getLU(int i) { return new LexicalUnit(tokens[i], pos[i]); }
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
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("<Sentence");
		for(int i=0; i<size(); i++)
			sb.append(String.format(" %s/%s", getWord(i), getPos(i)));
		sb.append(">");
		return sb.toString();
	}
}
