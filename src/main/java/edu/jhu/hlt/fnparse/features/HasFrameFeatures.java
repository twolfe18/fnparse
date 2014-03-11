package edu.jhu.hlt.fnparse.features;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;

public abstract class HasFrameFeatures {

	public Features.FP fpFeatures;
	public Features.F fFeatures;
	
	public HasFrameFeatures() {}
	
	public List<Features> getFeatures() {
		List<Features> features = new ArrayList<Features>();
		if(fpFeatures != null) features.add(fpFeatures);
		if(fFeatures != null) features.add(fFeatures);
		return features;
	}
	
	/**
	 * copy constructor (shallow)
	 */
	public HasFrameFeatures(HasFrameFeatures copy) {
		setFeatures(copy);
	}
	
	public void setFeatures(HasFrameFeatures from) {
		this.fpFeatures = from.fpFeatures;
		this.fFeatures = from.fFeatures;
	}

	public void setFeatures(Features.FP features) {
		this.fpFeatures = features;
	}

	public void setFeatures(Features.F features) {
		this.fFeatures = features;
	}
	
	public FeatureVector getFeatures(Frame f, FrameInstance p, int targetHead, Sentence sent) {
		// NOTE: casting to travis.Vector ensures that the best implementation is dispatched
		// both the travis.Vector and IntDoubleUnsortedVector implementations forward to the same
		// code, this is just an issue of ambiguity.
		FeatureVector fv = new FeatureVector();
		if(fpFeatures != null && f != null && p != null)
			fv.add((travis.Vector) fpFeatures.getFeatures(f, targetHead, p, sent));
		if(fFeatures != null && f != null)
			fv.add((travis.Vector) fFeatures.getFeatures(f, targetHead, sent));
		return fv;
	}

}
