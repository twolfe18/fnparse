package edu.jhu.hlt.fnparse.inference.jointid;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.FgRelated;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.FrameVars;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars;

/**
 * a hypothesis for a joint frame-role model.
 * 
 * @author travis
 */
public class FrameInstanceHypothesis implements FgRelated {
	
	private Sentence sent;
	private FrameVars frames;
	private RoleVars[] roles;		// indices correspond to frame values from this.frames
	
	/** constructor for training/known labels */
	public FrameInstanceHypothesis(FrameInstance gold, Sentence sent, FrameVars f_it, ParserParams params) {
		this(true, gold, sent, f_it, params);
	}

	/** constructor for decoding/prediction */
	public FrameInstanceHypothesis(Sentence sent, FrameVars f_it, ParserParams params) {
		this(true, null, sent, f_it, params);
	}

	private FrameInstanceHypothesis(boolean hasGold, FrameInstance gold, Sentence sent, FrameVars f_it, ParserParams params) {
		if(sent == null) throw new IllegalArgumentException();
		if(f_it == null) throw new IllegalArgumentException();
		if(params == null) throw new IllegalArgumentException();

		this.sent = sent;
		this.frames = f_it;
		
		final int i = f_it.getTargetHeadIdx();
		final int T = f_it.numFrames();
		roles = new RoleVars[T];
		for(int tIdx=0; tIdx<T; tIdx++) {
			Frame t = f_it.getFrame(tIdx);
			if(t == Frame.nullFrame) continue;
			FrameInstance goldT = gold;
			if(gold != null && gold.getFrame() != t)
				goldT = null;	// TODO if the frame is wrong, then instead of making it predict "no roles", we should make these variables latent.
			roles[tIdx] = hasGold
					? new RoleVars(goldT, i, t, sent, params)
					: new RoleVars(i, t, sent, params);
		}
	}

	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		// both frames and roles store their own gold configuration,
		// and they can add them themselves in their respective register methods
		frames.register(fg, gold);
		for(int i=0; i<roles.length; i++)
			if(roles[i] != null)
				roles[i].register(fg, gold);
	}
	

	// this class also lets you store factors, because it is convenient to put them here rather than maintaining a separate map
	// array index is for frame (t)
	private List<Factor>[] jointFactors;
	
	/** provide an f_it ~ r_itjk factor that you'd like this instance to hold onto */
	@SuppressWarnings("unchecked")
	public void addJointFactor(int t, Factor f) {
		if(jointFactors == null) {
			int T = frames.numFrames();
			jointFactors = new List[T];
			for(int tt=0; tt<T; tt++)
				jointFactors[tt] = new ArrayList<Factor>();
		}
		assert f.getVars().size() == 2 : "this should be an f_it ~ r_itjk factor";
		jointFactors[t].add(f);
	}
	
	/** returns a list of factors that were added by calling {@code addJointFactor} */
	public List<Factor> getJointFactors(int t) { return jointFactors[t]; }

	
	public Sentence getSentence() { return sent; }
	
	public int getTargetHeadIdx() {
		return frames.getTargetHeadIdx();
	}
	
	public Frame getFrame(int t) {
		return frames.getFrame(t);
	}
	
	public Var getFrameVar(int t) {
		return frames.getVariable(t);
	}
	
	public RoleVars getRoleVars(int t) {
		return roles[t];
	}
	
	public int numFrames() {
		int T = frames.numFrames();
		assert T == roles.length;
		return T;
	}
	
	@Override
	public String toString() {
		return "<FrInstHyp in " + sent.getId() + " @ " + frames.getTargetHeadIdx() +
				" with " + frames.numFrames() + " possible frames>";
	}
}