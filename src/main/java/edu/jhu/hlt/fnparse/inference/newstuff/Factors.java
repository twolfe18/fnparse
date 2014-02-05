package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.model.ExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;

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
	static class SimpleFrameFactors extends Factors {

		@Override
		public void initFactorsFor(FrameVar f, RoleVars r) {
			factors.add(new F(f));
		}

		// i am so tired of coming up with new names for shit
		static class F extends ExpFamFactor {
			
			private static final long serialVersionUID = 1L;
			
			private FrameVar frameVar;

			public F(FrameVar fv) {
				super(new VarSet(fv.getPrototypeVar(), fv.getFrameVar()));
				this.frameVar = fv;
			}
			
			@Override
			public FeatureVector getFeatures(int config) {
				FeatureVector fv = new FeatureVector();
				VarConfig conf = this.getVars().getVarConfig(config);
				int prototypeIdx = conf.getState(frameVar.getPrototypeVar());
				int frameIdx = conf.getState(frameVar.getFrameVar());
				LexicalUnit prototype = frameVar.getPrototype(prototypeIdx);
				Frame frame = frameVar.getFrame(frameIdx);
				fv.add(0, frame == Frame.nullFrame ? 1d : 0d);
				// TODO add some real features
				return fv;
			}
			
		}
	}
	
	static class SimpleFrameRoleFactors extends Factors {
		
		@Override
		public void initFactorsFor(FrameVar f, RoleVars r) {
			// TODO Auto-generated method stub
			
		}
		
		static class F extends ExpFamFactor {

			private static final long serialVersionUID = 1L;
			
			private FrameVar frameVar;
			private RoleVars roleVar;
			
			public F(FrameVar fv, RoleVars rv) {
				super(new VarSet(fv.getFrameVar(), rv.getRoleVar()));
				this.frameVar = fv;
				this.roleVar = rv;
			}
			
			@Override
			public FeatureVector getFeatures(int config) {
				FeatureVector fv = new FeatureVector();
				VarConfig conf = this.getVars().getVarConfig(config);
				int frameIdx = conf.getState(frameVar.getFrameVar());
				int roleConfIdx = conf.getState(roleVar.getRoleVar());
				Frame frame = frameVar.getFrame(frameIdx);
				boolean roleActive = BinaryVarUtil.configToBool(roleConfIdx);
				fv.add(0, frame == Frame.nullFrame ? 1d : 0d);
				fv.add(1, frame != Frame.nullFrame && roleActive ? 1d : 0d);
				fv.add(2 + roleVar.getRoleIdx(), roleActive ? 1d : 0d);
				// TODO add some real features
				return fv;
			}
			
		}
	}
}
