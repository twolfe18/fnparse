package edu.jhu.hlt.fnparse.features;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public abstract class HasFrameFeatures {

	public Features.FPE fpeFeatures;
	public Features.FP fpFeatures;
	public Features.FE feFeatures;
	public Features.F fFeatures;
	public Features.E eFeatures;
	
	public HasFrameFeatures() {}
	
	public List<Features> getFeatures() {
		List<Features> features = new ArrayList<Features>();
		if(fpeFeatures != null) features.add(fpeFeatures);
		if(fpFeatures != null) features.add(fpFeatures);
		if(feFeatures != null) features.add(feFeatures);
		if(fFeatures != null) features.add(fFeatures);
		if(eFeatures != null) features.add(eFeatures);
		return features;
	}
	
	/**
	 * copy constructor (shallow)
	 */
	public HasFrameFeatures(HasFrameFeatures copy) {
		setFeatures(copy);
	}
	
	public void setFeatures(HasFrameFeatures from) {
		this.fpeFeatures = from.fpeFeatures;
		this.fpFeatures = from.fpFeatures;
		this.feFeatures = from.feFeatures;
		this.fFeatures = from.fFeatures;
		this.eFeatures = from.eFeatures;
	}

	public void setFeatures(Features.FPE features) {
		this.fpeFeatures = features;
	}

	public void setFeatures(Features.FP features) {
		this.fpFeatures = features;
	}
	
	public void setFeatures(Features.FE features) {
		this.feFeatures = features;
	}

	public void setFeatures(Features.F features) {
		this.fFeatures = features;
	}
	
	public void setFeatures(Features.E features) {
		this.eFeatures = features;
	}
	
	public FeatureVector getFeatures(Frame f, FrameInstance p, int targetHead, Span t, Sentence sent) {
		FeatureVector fv = new FeatureVector();
		if(fpeFeatures != null && f != null && p != null && t != null)
			fv.add(fpeFeatures.getFeatures(f, t, p, sent));
		if(fpFeatures != null && f != null && p != null)
			fv.add(fpFeatures.getFeatures(f, targetHead, p, sent));
		if(feFeatures != null && f != null && t != null)
			fv.add(feFeatures.getFeatures(f, t, sent));
		if(fFeatures != null && f != null)
			fv.add(fFeatures.getFeatures(f, targetHead, sent));
		if(eFeatures != null && t != null)
			fv.add(eFeatures.getFeatures(t, sent));
		return fv;
	}

}
