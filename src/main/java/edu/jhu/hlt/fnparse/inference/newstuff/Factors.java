package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.*;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.BasicFramePrototypeFeatures;
import edu.jhu.hlt.fnparse.features.BasicFrameRoleFeatures;
import edu.jhu.hlt.fnparse.features.ConstituencyFeatures;
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.util.FeatureUtils;

/**
 * It is expected that this class will be instantiated once per sentence,
 * and initFactorsFor() will be called many times, register() will be called once.
 * If you don't need all unique combinations of (f,r), then keep track of what
 * combinations you've seen given from initFactorsFor().
 * 
 * @author travis
 */
public abstract class Factors implements FactorFactory {

	/**
	 * looks at (prototype, frame) pairs
	 * 
	 * this loops over (frame, prototype) for all prototypes
	 * that were added from the hypothesis set of frames.
	 * Each prototype belongs to one one Frame in this hypothesis,
	 * and the factor should return semiring-0 for combinations
	 * where the prototype does not belong to the Frame.
	 * @see {@code F.getDotProd}
	 */
	static class FramePrototypeFactors extends Factors {	// makes the factors

		private Features.FP features = new BasicFramePrototypeFeatures();
	
		@Override
		public List<Factor> initFactorsFor(Sentence s, FrameVar[] f, RoleVars[][][] r) {
			List<Factor> factors = new ArrayList<Factor>();
			int n = s.size();
			for(int i=0; i<n; i++) {
				if(f[i] == null) continue;
				factors.add(new F(f[i], features, s));
			}
			return factors;
		}

		static class F extends ExpFamFactor {	// is the actual factor
			
			private static final long serialVersionUID = 1L;
			
			private FrameVar frameVar;
			private Features.FP features;
			private Sentence sent;

			public F(FrameVar fv, Features.FP features, Sentence sent) {
				super(new VarSet(fv.getPrototypeVar(), fv.getFrameVar()));	// this is how you know the complexity
				this.frameVar = fv;
				this.features = features;
				this.sent = sent;
			}
			
			@Override
			public double getDotProd(int config, FgModel model, boolean logDomain) {
				VarConfig conf = this.getVars().getVarConfig(config);
				Frame f = frameVar.getFrame(conf);
				FrameInstance p = frameVar.getPrototype(conf);
				if(!p.getFrame().equals(f))
					return logDomain ? Double.NEGATIVE_INFINITY : 0d;
				else return super.getDotProd(config, model, logDomain);
			}
			
			@Override
			public FeatureVector getFeatures(int config) {
				VarConfig conf = this.getVars().getVarConfig(config);
				FrameInstance prototype = frameVar.getPrototype(conf);
				Frame frame = frameVar.getFrame(conf);
				if(!prototype.getFrame().equals(frame))
					return FeatureUtils.emptyFeatures;
				return features.getFeatures(frame, frameVar.getTargetHeadIdx(), prototype, sent);
			}
			
		}
	}
	
	/**
	 * looks at just frame pairs
	 */
	static class FrameFactors extends Factors {	// makes the factors
		
		private Features.F features = new BasicFrameFeatures();
		
		@Override
		public List<Factor> initFactorsFor(Sentence s, FrameVar[] f, RoleVars[][][] r) {
			List<Factor> factors = new ArrayList<Factor>();
			int n = s.size();
			for(int i=0; i<n; i++) {
				if(f[i] == null) continue;
				factors.add(new F(f[i], features, s));
			}
			return factors;
		}

		static class F extends ExpFamFactor {	// is the actual factor
			
			private static final long serialVersionUID = 1L;
			
			private FrameVar frameVar;
			private Features.F features;
			private Sentence sent;

			public F(FrameVar fv, Features.F features, Sentence sent) {
				super(new VarSet(fv.getFrameVar()));	// this is how you know the complexity
				this.frameVar = fv;
				this.features = features;
				this.sent = sent;
			}
			
			@Override
			public FeatureVector getFeatures(int config) {
				VarConfig conf = this.getVars().getVarConfig(config);
				Frame frame = frameVar.getFrame(conf);
				return features.getFeatures(frame, frameVar.getTargetHeadIdx(), sent);
			}
		}
	}
	
	
	/**
	 * Looks at (frame.head, role.head) pairs.
	 * NOTE, this factor needs to enforece the hard factor
	 * of k \ge f_i.numRoles => r_ijk = nullSpan.
	 * @see {@code F.getDotProd}
	 */
	static class FrameRoleFactors extends Factors {
		
		private Features.FR features = new BasicFrameRoleFeatures();;
		
		@Override
		public List<Factor> initFactorsFor(Sentence s, FrameVar[] f, RoleVars[][][] r) {
			List<Factor> factors = new ArrayList<Factor>();
			int n = s.size();
			for(int i=0; i<n; i++) {
				if(f[i] == null) continue;
				for(int j=0; j<n; j++)
					for(int k=0; k<r[i][j].length; k++)
						factors.add(new F(f[i], r[i][j][k], s, features));
			}
			return factors;
		}
		
		static class F extends ExpFamFactor {

			private static final long serialVersionUID = 1L;
			
			private Sentence sent;
			private Features.FR features;
			private FrameVar frameVar;
			private RoleVars roleVar;
			
			public F(FrameVar fv, RoleVars rv, Sentence sent, Features.FR feats) {
				super(new VarSet(fv.getFrameVar(), rv.getRoleVar()));	// this is how you know the complexity
				this.frameVar = fv;
				this.roleVar = rv;
				this.sent = sent;
				this.features = feats;
			}
			
			@Override
			public double getDotProd(int config, FgModel model, boolean logDomain) {
				VarConfig conf = this.getVars().getVarConfig(config);
				Frame frame = frameVar.getFrame(conf);
				boolean roleActive = roleVar.getRoleActive(conf);
				if(roleActive && roleVar.getRoleIdx() >= frame.numRoles())
					return logDomain ? Double.NEGATIVE_INFINITY : 0d;
				else return super.getDotProd(config, model, logDomain);
			}
			
			@Override
			public FeatureVector getFeatures(int config) {
				VarConfig conf = this.getVars().getVarConfig(config);
				Frame frame = frameVar.getFrame(conf);
				boolean roleActive = roleVar.getRoleActive(conf);
				if(roleActive && roleVar.getRoleIdx() >= frame.numRoles())
					return FeatureUtils.emptyFeatures;
				return features.getFeatures(frame, roleActive, frameVar.getTargetHeadIdx(), roleVar.getRoleIdx(), roleVar.getHeadIdx(), sent);
			}
			
		}
	}
	
	
	static class FrameExpansionFactors extends Factors {
		
		private Features.C features = new ConstituencyFeatures("Frames");
		
		@Override
		public List<Factor> initFactorsFor(Sentence s, FrameVar[] f, RoleVars[][][] r) {
			List<Factor> factors = new ArrayList<Factor>();
			int n = s.size();
			for(int i=0; i<n; i++) {
				if(f[i] == null) continue;
				factors.add(new F(f[i], s, features));
			}
			return factors;
		}
		
		static class F extends ExpFamFactor {

			private static final long serialVersionUID = 1L;
			
			private Sentence sent;
			private FrameVar frameVar;
			private Features.C features;
			
			public F(FrameVar fv, Sentence sent, Features.C feats) {
				super(new VarSet(fv.getExpansionVar()));	// this is how you know the complexity
				this.frameVar = fv;
				this.sent = sent;
				this.features = feats;
			}
			
			@Override
			public FeatureVector getFeatures(int config) {
				VarConfig conf = this.getVars().getVarConfig(config);
				Expansion e = frameVar.getExpansion(conf);
				Span s = e.upon(frameVar.getTargetHeadIdx());
				return features.getFeatures(s, sent);
			}
		}
	}
	
	static class ArgExpansionFactors extends Factors {
		
		private ConstituencyFeatures features = new ConstituencyFeatures("Args");
		
		@Override
		public List<Factor> initFactorsFor(Sentence s, FrameVar[] f, RoleVars[][][] r) {
			List<Factor> factors = new ArrayList<Factor>();
			int n = s.size();
			for(int i=0; i<n; i++) {
				if(f[i] == null) continue;
				for(int j=0; j<n; j++)
					for(int k=0; k<r[i][j].length; k++)
						factors.add(new F(r[i][j][k], s, features));
			}
			return factors;
		}
		
		static class F extends ExpFamFactor {

			private static final long serialVersionUID = 1L;
			
			private Sentence sent;
			private RoleVars roleVar;
			private Features.C features;
			
			public F(RoleVars rv, Sentence sent, Features.C feats) {
				super(new VarSet(rv.getExpansionVar()));	// this is how you know the complexity
				this.roleVar = rv;
				this.sent = sent;
				this.features = feats;
			}
			
			@Override
			public FeatureVector getFeatures(int config) {
				VarConfig conf = this.getVars().getVarConfig(config);
				Span s = roleVar.getSpan(conf);
				return features.getFeatures(s, sent);
			}
		}
	}
	
}
