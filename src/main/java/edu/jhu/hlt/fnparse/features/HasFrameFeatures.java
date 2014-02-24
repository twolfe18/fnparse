package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public abstract class HasFrameFeatures {

	protected Features.FPE fpeFeatures;
	protected Features.FP fpFeatures;
	protected Features.F fFeatures;
	
	public HasFrameFeatures() {}
	
	/**
	 * copy constructor (shallow)
	 */
	public HasFrameFeatures(HasFrameFeatures copy) {
		fpeFeatures = copy.fpeFeatures;
		fpFeatures = copy.fpFeatures;
		fFeatures = copy.fFeatures;
	}
	
	public void setFeatures(HasFrameFeatures from) {
		this.fpeFeatures = from.fpeFeatures;
		this.fpFeatures = from.fpFeatures;
		this.fFeatures = from.fFeatures;
	}

	public void setFeatures(Features.FPE features) {
		this.fpeFeatures = features;
	}

	public void setFeatures(Features.FP features) {
		this.fpFeatures = features;
	}

	public void setFeatures(Features.F features) {
		this.fFeatures = features;
	}
	
	public FeatureVector getFeatures(Frame f, FrameInstance p, int targetHead, Span target, Sentence sent) {
		FeatureVector fv = new FeatureVector();
		if(fpeFeatures != null)
			fv.add(fpeFeatures.getFeatures(f, target, p, sent));
		if(fpFeatures != null)
			fv.add(fpFeatures.getFeatures(f, targetHead, p, sent));
		if(fFeatures != null)
			fv.add(fFeatures.getFeatures(f, targetHead, sent));
		return fv;
	}

}
