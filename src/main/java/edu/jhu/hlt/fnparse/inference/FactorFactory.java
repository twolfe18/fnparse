package edu.jhu.hlt.fnparse.inference;

import java.io.Serializable;
import java.util.List;

import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Features;

public interface FactorFactory<Hypothesis> extends Serializable {

	/**
	 * Return the features used by the factors that this factory instantiates.
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
	public List<Factor> initFactorsFor(
			Sentence s,
			List<Hypothesis> inThisSentence,
			ProjDepTreeFactor d,
			ConstituencyTreeFactor c);
}
