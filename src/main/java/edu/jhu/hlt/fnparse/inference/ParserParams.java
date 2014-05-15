package edu.jhu.hlt.fnparse.inference;

import java.io.Serializable;
import java.util.Random;

import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.util.MultiTimer;
import edu.jhu.hlt.util.stats.Multinomials;
import edu.jhu.util.Alphabet;

public class ParserParams implements Serializable {
	
	private static final long serialVersionUID = 1L;
	
	public boolean logDomain = true;
	public boolean useLatentDepenencies = false;
	public boolean useSyntaxFeatures = true;
	public boolean usePredictedFramesToTrainArgId = false;	// otherwise use gold frames
	
	// store weights at the stage level
//	public FgModel weights;
	public Alphabet<String> featAlph = new Alphabet<>();

	public int threads = 1;
	public Random rand = new Random(9001);
	public MultiTimer timer = new MultiTimer();
	public HeadFinder headFinder = new SemaforicHeadFinder();
	
//	public Features.F  fFeatures;
//	public Features.R  rFeatures;
//	public Features.RE reFeatures;
	
	/** checks if they're log proportions from this.logDomain */
	public void normalize(double[] proportions) {
		if(this.logDomain)
			Multinomials.normalizeLogProps(proportions);
		else
			Multinomials.normalizeProps(proportions);
	}

	public boolean verifyConsistency() {
		return true;
	}
}