package edu.jhu.hlt.fnparse.util;

public final class ArgMax<T> {

	private T best = null;
	private double bestScore = 0d;
	
	/**
	 * returns the best item before calling this function.
	 */
	public T accum(T item, double score) {
		T oldBest = best;
		if(best == null || score > bestScore) {
			best = item;
			bestScore = score;
		}
		return oldBest;
	}
	
	public T getBest() { return best; }
}
