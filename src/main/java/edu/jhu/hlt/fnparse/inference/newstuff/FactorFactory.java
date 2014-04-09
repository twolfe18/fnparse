package edu.jhu.hlt.fnparse.inference.newstuff;

import java.io.Serializable;
import java.util.List;

import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Features;

public interface FactorFactory extends Serializable {
	
	/**
	 * Return the features used by the factors that this factory instanitates.
	 */
	public List<Features> getFeatures();
	
	/**
	 * Make some factors.
	 * 
	 * You can build up a factor that is as costly as you want it to be
	 * given these variables.
	 * 
	 * The variables given should correspond to a frame target head word,
	 * such that r.parent == f.
	 */
	public List<Factor> initFactorsFor(Sentence s, List<FrameInstanceHypothesis> fr, ProjDepTreeFactor l);
	
}
