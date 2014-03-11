package edu.jhu.hlt.fnparse.features;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;
import edu.jhu.util.Alphabet;

public class HasRoleFeatures {
	
	protected ParserParams params;
	
	protected Features.FR frFeatures;
	protected Features.RE reFeatures;
	protected Features.E eFeatures;
	
	/** @deprecated slow! */
	protected Features.FRE freFeatures;
	
	private FeatureVector fv_nullFrame;
	private int fv_nullFrame_idx;
	
	public HasRoleFeatures(ParserParams params) {
		this.params = params;
		this.fv_nullFrame_idx = params.featIdx.lookupIndex("HasRoleFeatures_f=nullFrame", true);
		this.fv_nullFrame = new FeatureVector();
		this.fv_nullFrame.add(fv_nullFrame_idx, 1d);
	}
	
	public HasRoleFeatures(HasRoleFeatures copy) {
		freFeatures = copy.freFeatures;
		frFeatures = copy.frFeatures;
		reFeatures = copy.reFeatures;
		eFeatures = copy.eFeatures;
		params = copy.params;
		fv_nullFrame = copy.fv_nullFrame;
		fv_nullFrame_idx = copy.fv_nullFrame_idx;
	}
	
	public List<Features> getFeatures() {
		List<Features> features = new ArrayList<Features>();
		if(freFeatures != null) features.add(freFeatures);
		if(frFeatures != null) features.add(frFeatures);
		if(reFeatures != null) features.add(reFeatures);
		if(eFeatures != null) features.add(eFeatures);
		return features;
	}
	
	public void setFeatures(Features.FRE features) {
		this.freFeatures = features;
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
		this.freFeatures = from.freFeatures;
		this.frFeatures = from.frFeatures;
		this.eFeatures = from.eFeatures;
	}

	public boolean hasNoFeatures() {
		return freFeatures == null && frFeatures == null && eFeatures == null;
	}
	
	public Alphabet<String> getFeatureAlph() { return params.featIdx; }
	
	/**
	 * the catch-all method that dispatches to the appropriate non-null features
	 * and concatenates them all together.
	 */
	public FeatureVector getFeatures(Frame f_i, Frame r_ijk, int targetHeadIdx, int roleIdx, Span arg, int argHead, Sentence s) {
		// NOTE: casting to travis.Vector ensures that the best implementation is dispatched
		// both the travis.Vector and IntDoubleUnsortedVector implementations forward to the same
		// code, this is just an issue of ambiguity.
		FeatureVector fv = new FeatureVector();
		final boolean argIsRealized = r_ijk != Frame.nullFrame;
		if(freFeatures != null)
			fv.add((travis.Vector) freFeatures.getFeatures(f_i, argIsRealized, targetHeadIdx, roleIdx, arg, s));
		if(frFeatures != null)
			fv.add((travis.Vector) frFeatures.getFeatures(f_i, argIsRealized, targetHeadIdx, roleIdx, argHead, s));
		if(reFeatures != null)
			fv.add((travis.Vector) reFeatures.getFeatures(r_ijk, targetHeadIdx, argHead, roleIdx, arg, s));
		if(eFeatures != null)
			fv.add((travis.Vector) eFeatures.getFeatures(arg, s));
		return fv;
	}
}
