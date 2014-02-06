package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.BasicFramePrototypeFeatures;
import edu.jhu.hlt.fnparse.features.BasicFrameRoleFeatures;
import edu.jhu.hlt.fnparse.features.Features;

/**
 * It is expected that this class will be instantiated once per sentence,
 * and initFactorsFor() will be called many times, register() will be called once.
 * If you don't need all unique combinations of (f,r), then keep track of what
 * combinations you've seen given from initFactorsFor().
 * 
 * @author travis
 */
public abstract class Factors implements FactorFactory {

	protected List<Factor> factors = new ArrayList<Factor>();
	
	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		for(Factor f : factors) fg.addFactor(f);
	}
	
	@Override
	public abstract void initFactorsFor(FrameVar f, RoleVars r);
	
	

	/**
	 * looks at (prototype, frame) pairs
	 */
	static class FramePrototypeFactors extends Factors {	// makes the factors

		private BitSet indicesAddedAlready = new BitSet();
		private Sentence sent;
		private Features.FP features;
		
		public FramePrototypeFactors(Sentence sent) {
			this.sent = sent;
			this.features = new BasicFramePrototypeFeatures();
		}
		
		@Override
		public void initFactorsFor(FrameVar f, RoleVars r) {
			// only add once per f.headIdx
			int i = f.getTargetHeadIdx();
			if(!indicesAddedAlready.get(i)) {
				indicesAddedAlready.set(i);
				factors.add(new F(f, features, sent));
			}
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
			public FeatureVector getFeatures(int config) {
				VarConfig conf = this.getVars().getVarConfig(config);
				int prototypeIdx = conf.getState(frameVar.getPrototypeVar());
				int frameIdx = conf.getState(frameVar.getFrameVar());
				LexicalUnit prototype = frameVar.getPrototype(prototypeIdx);
				Frame frame = frameVar.getFrame(frameIdx);
				return features.getFeatures(frame, frameVar.getTargetHeadIdx(), prototype, sent);
			}
			
		}
	}
	
	/**
	 * looks at just frame pairs
	 */
	static class FrameFactors extends Factors {	// makes the factors

		private boolean[] indicesAddedAlready;
		private Sentence sent;
		private Features.F features;
		
		public FrameFactors(Sentence sent) {
			this.sent = sent;
			this.indicesAddedAlready = new boolean[sent.size()];
			this.features = new BasicFrameFeatures();
		}
		
		@Override
		public void initFactorsFor(FrameVar f, RoleVars r) {
			// only add once per f.headIdx
			int i = f.getTargetHeadIdx();
			if(!indicesAddedAlready[i]) {
				indicesAddedAlready[i] = true;
				factors.add(new F(f, features, sent));
			}
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
				int frameIdx = conf.getState(frameVar.getFrameVar());
				Frame frame = frameVar.getFrame(frameIdx);
				return features.getFeatures(frame, frameVar.getTargetHeadIdx(), sent);
			}
		}
	}
	
	
	/**
	 * Looks at (frame.head, role.head) pairs.
	 * NOTE, this factor needs to enforece the hard factor
	 * of k \ge f_i.numRoles => r_ijk = nullSpan. 
	 */
	static class FrameRoleFactors extends Factors {
		
		private Sentence sent;
		private Features.FR features;
		
		public FrameRoleFactors(Sentence s) {
			this.sent = s;
			this.features = new BasicFrameRoleFeatures();
		}
		
		@Override
		public void initFactorsFor(FrameVar f, RoleVars r) {
			factors.add(new F(f, r, sent, features));
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
			public FeatureVector getFeatures(int config) {
				VarConfig conf = this.getVars().getVarConfig(config);
				int frameIdx = conf.getState(frameVar.getFrameVar());
				int roleConfIdx = conf.getState(roleVar.getRoleVar());
				Frame frame = frameVar.getFrame(frameIdx);
				boolean roleActive = BinaryVarUtil.configToBool(roleConfIdx);
				return features.getFeatures(frame, roleActive, frameVar.getTargetHeadIdx(), roleVar.getRoleIdx(), roleVar.getHeadIdx(), sent);
			}
			
		}
	}
	
}
