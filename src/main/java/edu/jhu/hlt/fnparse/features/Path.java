package edu.jhu.hlt.fnparse.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;

public class Path {
	
	enum RuleType {
		CFG,		// requires a constituency parse, not yet supported
		
		// the following use dependency parses
		DEP,		// assumes labeled dependency parse
		LEMMA,
		POS,
		NONE		// puts in a "*" for every head/phrase
	}
	
	private final int start, end;
	private final Sentence sent;
	private final int n;
	private final RuleType ruleType;

	private String top;
	private List<String> up, down;
	private boolean connected;

	public Path(Sentence s, int start, int end, RuleType ruleType) {
		this.sent = s;
		this.start = start;
		this.end = end;
		this.n = sent.size();
		this.ruleType = ruleType;
		
		if(ruleType == RuleType.CFG)
			throw new RuntimeException("not supported yet");
	
		// the path from start to root, counting up from 0
		int[] upIndices = new int[n];
		Arrays.fill(upIndices, -1);
		
		// start from start and work your way up to root
		up = new ArrayList<String>();
		int ptr = start;
		while(ptr >= 0 && ptr < n) {
			upIndices[ptr] = up.size();
			up.add(getNodeNameFor(ptr));
			ptr = sent.governor(ptr);
		}
		
		// the order down starts out backwards
		down = new ArrayList<String>();
		ptr = end;
		while(ptr >= 0 && ptr < n && upIndices[ptr] < 0) {
			down.add(getNodeNameFor(ptr));
			ptr = sent.governor(ptr);
		}
		Collections.reverse(down);

		if(ptr >= 0 && ptr < n) {
			connected = true;
			top = getNodeNameFor(ptr);
			up = up.subList(0, upIndices[ptr]);	// trim up at the point where the two paths meet
		}
		else {
			connected = false;
			top = "<unconnected>";
		}
	}
	
	private String getNodeNameFor(int i) {
		if(ruleType == RuleType.DEP) return sent.dependencyType(i);
		else if(ruleType == RuleType.LEMMA) return sent.getLemma(i);
		else if(ruleType == RuleType.POS) return sent.getPos(i);
		else if(ruleType == RuleType.NONE) return "*";
		else throw new RuntimeException();
	}
	
	public boolean isConnected() { return connected; }
	
	public RuleType getRuleType() { return ruleType; }
	
	public int getStart() { return start; }
	
	public int getEnd() { return end; }
	
	private String path;
	public String getPath() {
		if(path == null) {
			StringBuilder sb = new StringBuilder();
			for(String s : up) {
				sb.append(s);
				sb.append("<");
			}
			sb.append(top);
			for(String s : down) {
				sb.append(">");
				sb.append(s);
			}
			path = sb.toString();
		}
		return path;
	}
	
	@Override
	public String toString() { return getPath(); }
}
