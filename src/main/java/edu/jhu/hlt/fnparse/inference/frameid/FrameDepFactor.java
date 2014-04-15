package edu.jhu.hlt.fnparse.inference.frameid;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExpFamFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.util.Alphabet;

public class FrameDepFactor extends ExpFamFactor {
	
	private static final long serialVersionUID = 1L;

	// characterize frame
	private int i;
	private Frame t;
	
	// characterize link
	private boolean linkIsParent;	// false -> l_ij, true -> l_ji
	private int j;

	public FrameDepFactor(Var f_it, int i, Frame t, Var link, boolean linkIsParent, int j) {
		super(new VarSet(f_it, link));
		this.i = i;
		this.t = t;
		this.linkIsParent = linkIsParent;
		this.j = j;
	}

	// this will do until I split out the feature functions
	private Alphabet<String> featIdx;
	private Sentence sent;
	public void setState(Alphabet<String> featIdx, Sentence sent) {
		this.featIdx = featIdx;
		this.sent = sent;
	}
	
	@Override
	public FeatureVector getFeatures(int config) {
		FeatureVector fv = new FeatureVector();
		String c = "cfg=" + String.valueOf(config);
		String d = "-dir=" + (linkIsParent ? "parent" : "child");
		String f = "-frame=" + t.getName();
		String wi = "-wi=" + sent.getLemma(i);
		String pi = "-pi=" + sent.getPos(i);
		fv.add(featIdx.lookupIndex(c, true), 1d);
		fv.add(featIdx.lookupIndex(c + d, true), 1d);
		fv.add(featIdx.lookupIndex(c + d + f, true), 1d);
		fv.add(featIdx.lookupIndex(c + d + f + pi, true), 1d);
		fv.add(featIdx.lookupIndex(c + d + f + wi, true), 1d);
		return fv;
	}

}
