package edu.jhu.hlt.fnparse.features;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;
import edu.jhu.util.Alphabet;

/**
 * (f_i=nullFrame, r_ijk^e=*) => r_ijk=true
 * 
 * 
 * @author travis
 *
 */
public abstract class HasRoleFeatures {
	
	protected ParserParams params;

	protected Features.FRE freFeatures;
	protected Features.FR frFeatures;
	protected Features.E cFeatures;
	
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
		cFeatures = copy.cFeatures;
		params = copy.params;
		fv_nullFrame = copy.fv_nullFrame;
		fv_nullFrame_idx = copy.fv_nullFrame_idx;
	}
	
	public List<Features> getFeatures() {
		List<Features> features = new ArrayList<Features>();
		if(freFeatures != null) features.add(freFeatures);
		if(frFeatures != null) features.add(frFeatures);
		if(cFeatures != null) features.add(cFeatures);
		return features;
	}
	
	public void setFeatures(Features.FRE features) {
		this.freFeatures = features;
	}
	
	public void setFeatures(Features.FR features) {
		this.frFeatures = features;
	}
	
	public void setFeatures(Features.E features) {
		this.cFeatures = features;
	}

	public void setFeatures(HasRoleFeatures from) {
		this.freFeatures = from.freFeatures;
		this.frFeatures = from.frFeatures;
		this.cFeatures = from.cFeatures;
	}

	public boolean hasNoFeatures() {
		return freFeatures == null && frFeatures == null && cFeatures == null;
	}
	
	public Alphabet<String> getFeatureAlph() { return params.featIdx; }
	
	public FeatureVector getFeatures(Frame f, int targetHeadIdx, boolean argIsRealized, int roleIdx, Span arg, int argHead, Sentence s) {
		
//		if(f == Frame.nullFrame) {
//			if(argIsRealized)
//				return AbstractFeatures.emptyFeatures;	// gradient calls this, no params associated with this constraint.
//				//throw new RuntimeException("should be ruled out by an overridden getDotProd");
//			else {
//				// uniform cost for (nullFrame, r_ijk=false, expansion) assignment
//				// lower cost biases towards nullFrame
//				// should be uniform because nullFrame doesn't care about which expansion,
//				// just that we are summing out the same number of expansions when comparing
//				// margins of r_ijk for nullFrame vs non-nullFrame.
//				return fv_nullFrame;
//			}
//		}
//		// non-nullFrame r_ijk=false assignments will get their own features
//		// (rather than a constant) because there may be features that indicate
//		// that certain spans are particularly *bad* for certain frames.
//		
//		if(roleIdx >= f.numRoles())
//			return AbstractFeatures.emptyFeatures;	// gradient calls this, no params associated with this constraint.
//			//throw new RuntimeException("should be ruled out by an overridden getDotProd");
		
		FeatureVector fv = new FeatureVector();
		if(freFeatures != null)
			fv.add(freFeatures.getFeatures(f, argIsRealized, targetHeadIdx, roleIdx, arg, s));
		if(frFeatures != null)
			fv.add(frFeatures.getFeatures(f, argIsRealized, targetHeadIdx, roleIdx, argHead, s));
		if(cFeatures != null)
			fv.add(cFeatures.getFeatures(arg, s));
		return fv;
	}
}
