package edu.jhu.hlt.fnparse.util;

import java.io.PrintStream;
import java.util.*;

public class MultiTimer {

	private Map<String, Timer> timers = new HashMap<String, Timer>();
	
	public void start(String key) {
		Timer t = timers.get(key);
		if(t == null) {
			t = new Timer(key);
			timers.put(key, t);
		}
		t.start();
	}
	
	public void stop(String key) {
		Timer t = timers.get(key);
		if(t == null)
			throw new IllegalArgumentException("there is no timer for " + key);
		t.stop();
	}
	
	public void print(PrintStream ps, String key) {
		Timer t = timers.get(key);
		if(t == null)
			throw new IllegalArgumentException("there is no timer for " + key);
		t.print(ps);
	}
}
