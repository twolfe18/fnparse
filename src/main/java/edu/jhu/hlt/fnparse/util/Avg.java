package edu.jhu.hlt.fnparse.util;

public class Avg {

	private double num = 0d;
	private double denom = 0d;
	
	public void accum(double value) { accum(value, 1d); }
	public void accum(double value, double weight) {
		if(weight < 0d) throw new IllegalArgumentException();
		num += value * weight;
		denom += weight;
	}
	
	public double average() { return num / denom; }
}
