package edu.jhu.hlt.fnparse.inference.jointid;

import edu.jhu.gm.model.FactorGraph;
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
	
	public FrameInstanceHypothesis(FrameInstance gold, Sentence sent, FrameVars f_it, ParserParams params) {
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
			roles[tIdx] = new RoleVars(gold, i, t, sent, params);
		}
	}
	
	@Override
	public String toString() {
		return "<FrInstHyp in " + sent.getId() + " @ " + frames.getTargetHeadIdx() +
				" with " + frames.numFrames() + " possible frames>";
	}

	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		// both frames and roles store their own gold configuration,
		// and they can add them themselves in their respective register methods
		frames.register(fg, gold);
		for(int i=0; i<roles.length; i++)
			roles[i].register(fg, gold);
	}
}