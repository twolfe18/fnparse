package edu.jhu.hlt.fnparse.features;

import java.io.Serializable;
import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.util.Alphabet;

public class HasRoleFeatures implements Serializable {
	
	private static final long serialVersionUID = 1L;

	protected Alphabet<String> featIdx;
	
	protected Features.FR frFeatures;
	protected Features.RE reFeatures;
	protected Features.E eFeatures;
	
	public HasRoleFeatures(Alphabet<String> featIdx) {
		this.featIdx = featIdx;
	}
	
	public HasRoleFeatures(HasRoleFeatures copy) {
		frFeatures = copy.frFeatures;
		reFeatures = copy.reFeatures;
		eFeatures = copy.eFeatures;
		featIdx = copy.featIdx;
	}
	
	public List<Features> getFeatures() {
		List<Features> features = new ArrayList<Features>();
		if(frFeatures != null) features.add(frFeatures);
		if(reFeatures != null) features.add(reFeatures);
		if(eFeatures != null) features.add(eFeatures);
		return features;
	}
	
	public void setFeatures(Features.FR features) {
		this.frFeatures = features;
	}
	
	public void setFeatures(Features.RE features) {
		this.reFeatures = features;
	}
	
	public void setFeatures(Features.E features) {
		this.eFeatures = features;
	}

	public void setFeatures(HasRoleFeatures from) {
		this.frFeatures = from.frFeatures;
		this.reFeatures = from.reFeatures;
		this.eFeatures = from.eFeatures;
	}

	public boolean hasNoFeatures() {
		return frFeatures == null && reFeatures == null && eFeatures == null;
	}
	
	public Alphabet<String> getFeatureAlph() { return featIdx; }
	
	/**
	 * the catch-all method that dispatches to the appropriate non-null features
	 * and concatenates them all together.
	 */
	public FeatureVector getFeatures(Frame f_i, int targetHeadIdx, int roleIdx, Span arg, int argHead, Sentence s) {
		// NOTE: casting to travis.Vector ensures that the best implementation is dispatched
		// both the travis.Vector and IntDoubleUnsortedVector implementations forward to the same
		// code, this is just an issue of ambiguity.
		FeatureVector fv = new FeatureVector();
		if(frFeatures != null)
			fv.add(frFeatures.getFeatures(f_i, targetHeadIdx, roleIdx, argHead, s));
		if(reFeatures != null)
			fv.add(reFeatures.getFeatures(f_i, targetHeadIdx, roleIdx, argHead, arg, s));
		if(eFeatures != null)
			fv.add(eFeatures.getFeatures(arg, s));
		return fv;
	}
}
