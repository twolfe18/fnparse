package edu.jhu.hlt.fnparse.datatypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.fnparse.data.LexicalUnit;
import edu.stanford.nlp.trees.ConllStyleOutput;

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
	
	
	// may be empty
	private List<FrameInstance> frameInstances;
	
	public Sentence(String dataset, String id, String[] tokens, String[] pos) {
		if(id == null || tokens == null)
			throw new IllegalArgumentException();
		if(pos != null && tokens.length != pos.length)
			throw new IllegalArgumentException();
		this.dataset = dataset;
		this.id = id;
		this.tokens = tokens;
		this.pos = pos;
		this.frameInstances = new ArrayList<FrameInstance>();
		StringAndIntArrayTuple gd = ConllStyleOutput.getGovAndDepType(tokens);
		this.gov = gd.getI();
		this.depType = gd.getS();
	}
	
	private Sentence(String dataset, String id, String[] tokens, String[] pos, int[] gov, String[] depType) {
		this.dataset = dataset;
		this.id = id;
		this.tokens = tokens;
		this.pos = pos;
		this.frameInstances = new ArrayList<FrameInstance>();
		this.gov = gov;
		this.depType = depType;
	}

	public Sentence copy(boolean copyFrameInstances) {
		Sentence s = new Sentence(dataset, id, tokens, pos, gov, depType);
		if(copyFrameInstances)
			for(FrameInstance fi : this.frameInstances)
				s.addFrameInstance(fi);
		return s;
	}
	
	public void addFrameInstance(FrameInstance fi) {
		if(fi.getSentence() != this)
			throw new IllegalArgumentException();
		frameInstances.add(fi);
	}
	
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
}
