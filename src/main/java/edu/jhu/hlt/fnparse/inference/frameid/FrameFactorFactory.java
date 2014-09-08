package edu.jhu.hlt.fnparse.inference.frameid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.ProjDepTreeFactor.LinkVar;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.AbstractFeatures;
import edu.jhu.hlt.fnparse.features.BinaryBinaryFactorHelper;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.features.Refinements;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.util.Alphabet;

/**
 * Instantiates factors that touch f_it.
 * 
 * @author travis
 */
public final class FrameFactorFactory implements FactorFactory<FrameVars> {

	private static final long serialVersionUID = 1L;
	
	// If true, the f_it ~ l_ij factors will only have one binary feature,
	// "l_{root,i}=true_f_{i,Some_Frame}=true"
	// Otherwise, these binary factors will have all observed features of f_it.
	public static boolean useSimpleBinaryFactors = false;
	
	public final Features.F features;
	public final BinaryBinaryFactorHelper.Mode rootFactorMode;
	public final Refinements f_it_unaryRef = new Refinements("f_it~1");
	
	public FrameFactorFactory(
			Features.F features,
			BinaryBinaryFactorHelper.Mode rootFactorMode) {
		this.features = features;
		this.rootFactorMode = rootFactorMode;
	}
	
	private Alphabet<String> featureNames = null;
	public void setAlphabet(Alphabet<String> featureNames) {
		this.featureNames = featureNames;
	}
	
	@Override
	public String toString() { return "<FrameFactorFactory>"; }

	/**
	 * Needed to interact with BinaryBinaryFactorHelper,
	 * just implements partial application.
	 */
	private static class FrameDepObservedFeatures
			implements BinaryBinaryFactorHelper.ObservedFeatures {

		private static final long serialVersionUID = 1L;

		private Features.F features;
		private String refinement;
		private Sentence sent;
		private int i;
		private Frame t;

		public FrameDepObservedFeatures(
				Features.F features,
				String refinement) {
			this.features = features;
			this.refinement = refinement;
		}

		public void set(Sentence s, int i, Frame t) {
			this.sent = s;
			this.i = i;
			this.t = t;
		}

		@Override
		public FeatureVector getObservedFeatures(Refinements r) {
			r = Refinements.product(r, this.refinement, 1d);
			FeatureVector fv = new FeatureVector();
			features.featurize(fv, r, i, t, sent);
			return fv;
		}
	}


	// TODO need to add an Exactly1 factor to each FrameVars
	// ^^^^ do i really need this if i'm not doing joint inference?
	@Override
	public List<Factor> initFactorsFor(
			Sentence s,
			List<FrameVars> fr,
			ProjDepTreeFactor l,
			ConstituencyTreeFactor c) {

		FrameDepObservedFeatures depFeats =
				new FrameDepObservedFeatures(features, "f_it~l_{root,i}");
		BinaryBinaryFactorHelper bbfh =
				new BinaryBinaryFactorHelper(this.rootFactorMode, depFeats);

		final int n = s.size();
		assert n > 2 || fr.size() == 0;
		List<Factor> factors = new ArrayList<Factor>();
		for(FrameVars fhyp : fr) {
			final int T = fhyp.numFrames();
			final int i = fhyp.getTargetHeadIdx();
			for(int tIdx=0; tIdx<T; tIdx++) {
				Frame t = fhyp.getFrame(tIdx);

				// Unary factor on f_it
				VarSet vs = new VarSet(fhyp.getVariable(tIdx));
				FeatureVector fv = new FeatureVector();
				features.featurize(fv, f_it_unaryRef, i, t, s);
				ExplicitExpFamFactor phi = new ExplicitExpFamFactor(vs);
				phi.setFeatures(BinaryVarUtil.boolToConfig(true), fv);
				phi.setFeatures(BinaryVarUtil.boolToConfig(false),
						AbstractFeatures.emptyFeatures);
				factors.add(phi);

				// Binary factor f_it ~ l_{root,i}
				assert (rootFactorMode != BinaryBinaryFactorHelper.Mode.NONE)
					== (l != null);
				if(rootFactorMode != BinaryBinaryFactorHelper.Mode.NONE) {
					Var f_it = fhyp.getVariable(tIdx);
					LinkVar l_ij = l.getLinkVar(-1, i);
					assert l_ij != null;
					if (useSimpleBinaryFactors) {
						assert featureNames != null;
						factors.add(BinaryBinaryFactorHelper.simpleBinaryFactor(
							l_ij, f_it, t, featureNames));
					} else {
						depFeats.set(s, i, t);
						vs = new VarSet(f_it, l_ij);
						phi = bbfh.getFactor(vs);
						assert phi != null;
						factors.add(phi);
					}
				}
			}
		}
		return factors;
	}

	@Override
	public List<Features> getFeatures() {
		return Arrays.asList((Features) features);
	}
}

