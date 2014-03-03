package edu.jhu.hlt.fnparse.util;

public class Avg {

	private double num = 0d;
	private double denom = 0d;
	private int nObservations = 0;
	
	public void accum(double value) { accum(value, 1d); }
	public void accum(double value, double weight) {
		if(weight < 0d) throw new IllegalArgumentException();
		num += value * weight;
		denom += weight;
		nObservations++;
	}
	
	public double average() { return num / denom; }
	
	public double sum() { return num; }
	
	public int numObservations() { return nObservations; }
}
