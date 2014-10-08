package edu.jhu.hlt.fnparse.features;

import java.io.Serializable;
import java.util.Arrays;

// TODO add this to the signature of AbstractFeatures.b
public class Refinements implements Serializable {
	private static final long serialVersionUID = 1L;

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

	public Refinements(String[] names, double[] weights) {
		assert names.length == weights.length;
		assert names.length > 0;
		int n = names.length;
		this.names = Arrays.copyOf(names, n);
		this.weights = Arrays.copyOf(weights, n);
	}

	public static Refinements product(Refinements from, String newName, double newWeight) {
		Refinements r = new Refinements(from.names, from.weights);
		int n = from.names.length;
		for(int i=0; i<n; i++) {
			r.names[i] += newName;
			r.weights[i] *= newWeight;
		}
		return r;
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
