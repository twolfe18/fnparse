package edu.jhu.hlt.fnparse.features;

import java.util.Arrays;

// TODO add this to the signature of AbstractFeatures.b
public class Refinements {
	
	public static final Refinements noRefinements = new Refinements("default");

	private double[] weights;
	private String[] names;
	
	// TODO
	public Refinements(int n) {
		this.names = new String[n];
		this.weights = new double[n];
		Arrays.fill(this.weights, 1d);
	}
	
	public Refinements(String... names) {
		this.names = names;
		this.weights = new double[names.length];
		Arrays.fill(this.weights, 1d);
	}
	
	public void set(int i, String name, double weight) {
		this.names[i] = name;
		this.weights[i] = weight;
	}
	
	public String getName(int i) { return names[i]; }
	public double getWeight(int i) { return weights[i]; }

	public int size() {
		return this.names.length;
	}

}
