package edu.jhu.hlt.fnparse.util;

import java.util.*;

public class Counts<T> {

	private Map<T, Integer> counts = new HashMap<T, Integer>();
	
	public int getCount(T t) {
		Integer c = counts.get(t);
		return c == null ? 0 : c;
	}
	
	public int increment(T t) {
		int c = getCount(t) + 1;
		counts.put(t, c);
		return c;
	}
	
	public int numNonZero() {
		return counts.size();
	}
	
	public List<T> countIsAtLeast(int minCount) {
		if(minCount <= 0)
			throw new IllegalArgumentException();
		List<T> l = new ArrayList<T>();
		for(T t : counts.keySet())
			if(getCount(t) >= minCount)
				l.add(t);
		return l;
	}
	
	public List<T> countIsLessThan(int maxCount) {
		if(maxCount <= 0)
			throw new IllegalArgumentException();
		List<T> l = new ArrayList<T>();
		for(T t : counts.keySet())
			if(getCount(t) < maxCount)
				l.add(t);
		return l;
	}
	
	public void clear() { counts.clear(); }
}
