package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;

/**
 * The purpose of this class is to sort parses by their utility to the
 * learning algorithm. The utility of a parse is the sum of the utility of
 * its frame instances. The utility of a frame instance is proportional to
 * the number of times it appears in training data times
 * discount^(#times appears in list so far)
 * where 0 < discount < 1.
 * 
 * This is not very efficient, but I don't think its a big deal.
 * 
 * WARNING: only use this for computing alphabets, for training it
 * introduces biases which may or may not be a good thing (test it yourself).
 * 
 * @author travis
 */
public class ParseSelector {
	
	public static final MultiTimer timer = new MultiTimer();
	
	private List<FNParse> parses;
	private Map<Frame, Double> frameValues;
	private double discount = 0.25d;
	
	public ParseSelector(List<FNParse> parses) {
		Timer t = timer.get("setup", true);
		t.printIterval = 1;
		t.start();
		this.parses = new ArrayList<>(parses);
		this.frameValues = new HashMap<>();
		for(FNParse p : parses) {
			for(FrameInstance fi : p.getFrameInstances()) {
				Double v = frameValues.get(fi.getFrame());
				if(v == null) v = 0d;
				frameValues.put(fi.getFrame(), v + 1d);
			}
		}
		t.stop();
	}
	
	public FNParse next() {
		
		Timer t = timer.get("Parseselector.next", true);
		t.printIterval = 1000;
		t.start();
		
		// sort FNParses by their cumulative value
		List<ParseWithValue> ps = new ArrayList<>();
		int n = parses.size();
		for(int i=0; i<n; i++)
			ps.add(new ParseWithValue(parses.get(i), i));
		Collections.sort(ps);
		ParseWithValue r = ps.get(0);
		
		// update frame values
		for(Frame f : r.getFrames()) {
			double v = frameValues.get(f);
			frameValues.put(f, v * discount);
		}
		
		// remove this FNParse
		parses.remove(r.index);

		t.stop();
		return r.parse;
	}
	
	public int size() {
		return parses.size();
	}
	
	public double getValueOf(FrameInstance fi) {
		double v = frameValues.get(fi.getFrame());
		double k = 1000d;
		return v * k / (k + fi.numRealizedArguments());
	}
	
	public double getValueOf(FNParse p) {
		double v = 0d;
		for(FrameInstance fi : p.getFrameInstances())
			v += getValueOf(fi);
		return v / Math.sqrt(4d + p.getSentence().size());
	}
	
	public static List<FNParse> sort(List<FNParse> input) {
		List<FNParse> r = new ArrayList<>();
		ParseSelector ps = new ParseSelector(input);
		while(ps.size() > 0)
			r.add(ps.next());
		return r;
	}

	
	public class ParseWithValue implements Comparable<ParseWithValue> {
		public FNParse parse;
		public double value;
		public int index;
		public ParseWithValue(FNParse parse, int index) {
			this.parse = parse;
			this.index = index;
			this.value = getValueOf(parse);
		}
		@Override
		public int compareTo(ParseWithValue arg0) {
			if(arg0.value > this.value) return 1;
			if(arg0.value < this.value) return -1;
			return 0;
		}
		public List<Frame> getFrames() {
			List<Frame> fs = new ArrayList<>();
			for(FrameInstance fi : parse.getFrameInstances())
				fs.add(fi.getFrame());
			return fs;
		}
	}
}
