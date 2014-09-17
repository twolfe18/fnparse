package edu.jhu.hlt.fnparse.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.Sentence;

public class Path {

	enum NodeType {
		CFG,		// requires a constituency parse, not yet supported
		LEMMA,
		POS,
		NONE		// puts in a "*" for every head/phrase
	}

	enum EdgeType {
		DIRECTION,		// either "<" for up or ">" for down
		DEP				// typed dependency label
	}

	private final int start, end;
	private final Sentence sent;
	private final int n;
	private final NodeType nodeType;
	private final EdgeType edgeType;

	private String top;
	private List<String> upNodes, downNodes;
	private List<String> upEdges, downEdges;
	private boolean connected;
	private boolean toRoot;		// if true, end and down don't mean anything

	/** path from root to head */
	public Path(Sentence s, int head, NodeType nodeType, EdgeType edgeType) {
		this(s, head, -1, nodeType, edgeType, true);
	}

	/** path between two tokens */
	public Path(Sentence s, int start, int end, NodeType nodeType, EdgeType edgeType) {
		this(s, start, end, nodeType, edgeType, false);
	}

	private Path(Sentence s, int start, int end, NodeType nodeType, EdgeType edgeType, boolean toRoot) {
		if(start < 0)
			throw new IllegalArgumentException();
		if(!toRoot && end < 0)
			throw new IllegalArgumentException();

		this.sent = s;
		this.start = start;
		this.end = end;
		this.n = sent.size();
		this.nodeType = nodeType;
		this.edgeType = edgeType;
		this.toRoot = toRoot;

		if(nodeType == NodeType.CFG)
			throw new RuntimeException("not supported yet");

		// used to detect loops
		boolean[] seen = new boolean[n];

		// the path from start to root, counting up from 0
		int[] upIndices = new int[n];
		Arrays.fill(upIndices, -1);

		// start from start and work your way up to root
		upNodes = new ArrayList<String>();
		upEdges = new ArrayList<String>();
		int ptr = start;
		while(ptr >= 0 && ptr < n && !seen[ptr]) {
			seen[ptr] = true;
			upIndices[ptr] = upNodes.size();
			upNodes.add(getNodeNameFor(ptr));
			upEdges.add(getEdgeNameFor(ptr, true));
			ptr = sent.governor(ptr);
		}

		// the order down starts out backwards
		if(!toRoot) {
			Arrays.fill(seen, false);
			downNodes = new ArrayList<String>();
			downEdges = new ArrayList<String>();
			ptr = end;
			while(ptr >= 0 && ptr < n && upIndices[ptr] < 0 && !seen[ptr]) {
				seen[ptr] = true;
				downNodes.add(getNodeNameFor(ptr));
				downEdges.add(getEdgeNameFor(ptr, false));
				ptr = sent.governor(ptr);
			}
			Collections.reverse(downNodes);
			Collections.reverse(downEdges);
		}

		if(ptr >= 0 && ptr < n && !seen[ptr]) {
			assert upIndices[ptr] >= 0;
			connected = true;
			top = getNodeNameFor(ptr);
			upNodes = upNodes.subList(0, upIndices[ptr]);	// trim up at the point where the two paths meet
			upEdges = upEdges.subList(0, upIndices[ptr]);
		}
		else {
			connected = false;
			top = "ROOT";
		}
	}

	/** Returns the total length of the path in number of edges */
	public int size() {
		return upEdges.size() + downEdges.size();
	}

	/** Returns (# up edges) - (# down edges) */
	public int deltaDepth() {
		return upEdges.size() - downEdges.size();
	}

	private String getNodeNameFor(int i) {
		if(nodeType == NodeType.LEMMA) return sent.getLemma(i);
		else if(nodeType == NodeType.POS) return sent.getPos(i);
		else if(nodeType == NodeType.NONE) return "*";
		else throw new RuntimeException();
	}

	private String getEdgeNameFor(int i, boolean goingUp) {
		if(edgeType == EdgeType.DEP)
			return sent.dependencyType(i) + (goingUp ? "<" : ">");
		else if(edgeType == EdgeType.DIRECTION)
			return goingUp ? "<" : ">";
		else throw new RuntimeException();
	}

	/** returns true if represents a path from a token to the root of the sentence */
	public boolean toRoot() { return toRoot; }

	public boolean isConnected() {
		if(toRoot) {
			assert false : "trivially true";
			return true;
		}
		return connected;
	}

	public NodeType getNodeType() { return nodeType; }

	public EdgeType getEdgeType() { return edgeType; }

	public int getStart() { return start; }

	public int getEnd() {
		if(toRoot)
			throw new RuntimeException("not defined");
		return end;
	}

	private String path;

	/** memoizes the string so you can call more than once cheaply */
	public String getPath() {
		if(path == null) {
			StringBuilder sb = new StringBuilder();
			for(int i=0; i<upNodes.size(); i++) {
				sb.append(upNodes.get(i));
				sb.append(upEdges.get(i));
			}
			sb.append(top);
			if(!toRoot) {
				for(int i=0; i<downNodes.size(); i++) {
					sb.append(downEdges.get(i));
					sb.append(downNodes.get(i));
				}
			}
			path = sb.toString();
		}
		return path;
	}

	/** prefix may be null */
	public void pathNGrams(int length, Collection<String> addTo, String prefix) {
		int U = 2*upNodes.size() + 1;
		if(!toRoot) U += 2*downNodes.size();
		for(int s=0; s < U-length+1; s++) {
			StringBuilder ngram = new StringBuilder();
			if(prefix != null)
				ngram.append(prefix);
			for(int i=0; i<length; i++)
				ngram.append(pathNGramsHelper2(s + i));
			addTo.add(ngram.toString());
		}
	}
	private String pathNGramsHelper2(int i) {
		// 012345678901234
		//  < < < < > > >
		// A B C D R X Y Z
		// 0 1 2 3   0 1 2
		int U = 2 * upNodes.size();	// U=8 in example above
		if(i < U) {
			if(i % 2 == 0) return upNodes.get(i / 2);
			else return upEdges.get(i / 2);
		}
		else if(i == U) return "ROOT";
		else {
			assert !toRoot;
			int j = i - U;
			if(j % 2 == 0) return downNodes.get((j / 2) - 1);
			else return downEdges.get(j / 2);
		}
	}

	@Override
	public String toString() { return getPath(); }
}
