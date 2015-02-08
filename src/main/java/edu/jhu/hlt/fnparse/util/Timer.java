package edu.jhu.hlt.fnparse.util;

public class Timer {
  private String id;
  private int count;
  private long time;
  private long lastStart = -1;
  public int printIterval;

  public boolean ignoreFirstTime;
  private long firstTime;

  public Timer() {
    this(null, -1, false);
  }
  public Timer(String id) {
    this(id, -1, false);
  }
  public Timer(String id, int printInterval, boolean ignoreFirstTime) {
    this.id = id;
    this.printIterval = printInterval;
    this.ignoreFirstTime = ignoreFirstTime;
  }

  public static Timer start(String id) {
    Timer t = new Timer(id, 1, false);
    t.start();
    return t;
  }

  public Timer ignoreFirstTime() {
    this.ignoreFirstTime = true;
    return this;
  }

  public Timer ignoreFirstTime(boolean ignore) {
    this.ignoreFirstTime = ignore;
    return this;
  }

  public Timer setPrintInterval(int interval) {
    if(interval <= 0) throw new IllegalArgumentException();
    this.printIterval = interval;
    return this;
  }

  public Timer disablePrinting() {
    this.printIterval = -1;
    return this;
  }

  public void start() {
    lastStart = System.currentTimeMillis();
  }

  /** returns the time taken between the last start/stop pair in milliseconds */
  public long stop() {
    long t = System.currentTimeMillis() - lastStart;
    if(count == 0)
      firstTime = t;
    time += t;
    count++;
    if(printIterval > 0 && count % printIterval == 0)
      System.out.println(this);
    return t;
  }

  /**
   * like stop, returns the time taken between the last start/stop pair in
   * milliseconds, but does not stop the timer.
   */
  public long sinceStart() {
    return System.currentTimeMillis() - lastStart;
  }

  public String toString() {
    double spc = secPerCall();
    return String.format("<Timer %s %.2f sec and %d calls total, %.3f sec/call>",
        id, totalTimeInSeconds(), count, spc);
  }

  public double countsPerMSec() {
    if(count > 1)
      return (count - 1d) / (time - firstTime);
    return ((double) count) / time;
  }

  public double secPerCall() {
    if(count > 1)
      return ((time - firstTime)/1000d) / (count-1);
    return (time/1000d) / count;
  }

  public double minutesUntil(int iterations) {
    int remaining = iterations - count;
    double rate = secPerCall() / 60d;
    return rate * remaining;
  }

  /** How many times start/stop has been called */
  public int getCount() {
    return count;
  }

  public long totalTimeInMilliseconds() { return time; }
  public double totalTimeInSeconds() { return time / 1000d; }

  public static final class NoOp extends Timer {
    public NoOp(String id) { super(id); }
    public NoOp(String id, int printInterval)  { super(id, printInterval, false); }
    public void start() {}
    public long stop() { return -1; }
  }

  public static final Timer noOp = new NoOp("noOp");
}