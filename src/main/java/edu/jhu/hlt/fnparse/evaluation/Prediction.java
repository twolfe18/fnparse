package edu.jhu.hlt.fnparse.evaluation;

import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * used for computing precision/recall/F1
 * 
 * if you want to evaluate target/frame identification only,
 * add one prediction per target, and set roleIdx=-1;
 */
class Prediction {
	
	public final Span span;		// could represent a target or a arg/role
	public final Frame frame;
	public final int roleIdx;	// will be -1 for target Predictions
	
	public Prediction(Span s, Frame f, int roleIdx) {
		if(f == Frame.nullFrame)
			throw new IllegalArgumentException("don't eval with null-frame/span predictions");
		this.span = s;
		this.frame = f;
		this.roleIdx = roleIdx;
	}
	
	@Override
	public int hashCode() {
		return span.hashCode() ^ frame.hashCode() ^ (roleIdx * 999331);
	}
	
	@Override
	public boolean equals(Object other) {
		if(other instanceof Prediction) {
			Prediction p = (Prediction) other;
			return span.equals(p.span) && frame.equals(p.frame) && roleIdx == p.roleIdx;
		}
		else return false;
	}
	
	@Override
	public String toString() {
		return String.format("<Prediction %s @ %s for role %d>", frame.getName(), span, roleIdx);
	}
}