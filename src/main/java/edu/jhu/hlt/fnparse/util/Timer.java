package edu.jhu.hlt.fnparse.util;

import java.io.PrintStream;

public class Timer {
	
	private String id;
	private int count;
	private long time;
	private long lastStart = -1;
	public int printIterval;
	
	public boolean ignoreFirstTime;
	private long firstTime;
	
	public Timer(String id) {
		this.id = id;
		printIterval = -1;
		ignoreFirstTime = true;
	}
	public Timer(String id, int printInterval) {
		this.id = id;
		this.printIterval = printInterval;
		ignoreFirstTime = true;
	}
	
	public void start() {
		lastStart = System.currentTimeMillis();
	}
	
	public long stop() {
		long t = System.currentTimeMillis() - lastStart;
		if(count == 0)
			firstTime = t;
		time += t;
		count++;
		if(printIterval > 0 && count % printIterval == 0)
			print(System.out);
		return t;
	}
	
	public void print(PrintStream ps) {
		double rate = countsPerMSec();
		if(rate >= 0.5d)
			ps.printf("<Timer %s %.2f sec and %d calls total, %.1f k call/sec\n", id, totalTimeInSec(), count, rate);
		else
			ps.printf("<Timer %s %.2f sec and %d calls total, %.1f sec/call\n", id, totalTimeInSec(), count, secPerCall());
	}
	
	private double countsPerMSec() {
		if(count > 1)
			return (count - 1d) / (time - firstTime);
		return ((double) count) / time;
	}
	
	private double secPerCall() {
		if(count > 1)
			return ((time - firstTime)/1000d) / (count-1);
		return (time/1000d) / count;
	}
	
	public double totalTimeInSec() { return time / 1000d; }
	
	public static final class NoOp extends Timer {
		public NoOp(String id) { super(id); }
		public NoOp(String id, int printInterval)  { super(id, printInterval); }
		public void start() {}
		public long stop() { return -1; }
	}
	
	public static final Timer noOp = new NoOp("noOp");
}