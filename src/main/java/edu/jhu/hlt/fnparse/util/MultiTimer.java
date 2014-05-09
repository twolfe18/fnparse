package edu.jhu.hlt.fnparse.util;

import java.io.PrintStream;
import java.util.*;

public class MultiTimer {

	private Map<String, Timer> timers = new HashMap<String, Timer>();
	private long firstStart = -1;
	
	public Timer get(String key) { return get(key, false); }
	public Timer get(String key, boolean addIfNotPresent) {
		Timer t = timers.get(key);
		if(t == null && addIfNotPresent) {
			t = new Timer(key);
			timers.put(key, t);
		}
		return t;
	}
	
	public void start(String key) {
		Timer t = timers.get(key);
		if(t == null) {
			t = new Timer(key);
			timers.put(key, t);
		}
		t.start();
		
		if(firstStart < 0)
			firstStart = System.currentTimeMillis();
	}
	
	/** returns the time taken between the last start/stop pair for this key */
	public long stop(String key) {
		Timer t = timers.get(key);
		if(t == null)
			throw new IllegalArgumentException("there is no timer for " + key);
		return t.stop();
	}
	
	/** returns the amount of time between the first call to start and now */
	public double totalTimeInSeconds() {
		if(firstStart < 0)
			throw new IllegalStateException("you have to have called start at least once to use this");
		return (System.currentTimeMillis() - firstStart) / 1000d;
	}
	
	public void print(PrintStream ps, String key) {
		Timer t = timers.get(key);
		if(t == null)
			throw new IllegalArgumentException("there is no timer for " + key);
		ps.println(t);
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for(Map.Entry<String, Timer> x : timers.entrySet())
			sb.append(x.getKey() + ": " + x.getValue() + "\n");
		long total = System.currentTimeMillis() - firstStart;
		sb.append("total: " + (total/1000d));
		return sb.toString();
	}
}
