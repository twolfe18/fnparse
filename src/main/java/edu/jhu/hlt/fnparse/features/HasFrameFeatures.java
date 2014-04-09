package edu.jhu.hlt.fnparse.features;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;

public abstract class HasFrameFeatures implements Serializable {

	private static final long serialVersionUID = 1L;
	
	public Features.F fFeatures;
	
	public HasFrameFeatures() {}
	
	public List<Features> getFeatures() {
		List<Features> features = new ArrayList<Features>();
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
		this.fFeatures = from.fFeatures;
	}

	public void setFeatures(Features.F features) {
		this.fFeatures = features;
	}
	
	public FeatureVector getFeatures(Frame f, FrameInstance p, int targetHead, Sentence sent) {
		// NOTE: casting to travis.Vector ensures that the best implementation is dispatched
		// both the travis.Vector and IntDoubleUnsortedVector implementations forward to the same
		// code, this is just an issue of ambiguity.
		FeatureVector fv = new FeatureVector();
		if(fFeatures != null && f != null)
			fv.add((travis.Vector) fFeatures.getFeatures(f, targetHead, sent));
		return fv;
	}

}
