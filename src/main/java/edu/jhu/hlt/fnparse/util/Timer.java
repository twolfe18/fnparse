package edu.jhu.hlt.fnparse.util;

public class Timer {
	private String id;
	private int count;
	private long time;
	private long lastStart = -1;
	private int printIterval;
	
	public Timer(String id) {
		this.id = id;
		printIterval = -1;
	}
	public Timer(String id, int printInterval) {
		this.id = id;
		this.printIterval = printInterval;
	}
	
	public void start() {
		lastStart = System.currentTimeMillis();
	}
	
	public long stop() {
		long t = System.currentTimeMillis() - lastStart;
		time += t;
		count++;
		if(printIterval > 0 && count % printIterval == 0) {
			double rate = ((double)count)/time;
			if(rate >= 0.5d)
				System.out.printf("<Timer %s %.2f sec total, %.1f k call/sec\n", id, time/1000d, rate);
			else
				System.out.printf("<Timer %s %.2f sec total, %.1f sec/call\n", id, time/1000d, (time/1000d)/count);
		}
		return t;
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