package edu.jhu.hlt.fnparse.inference.newstuff;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;

/**
 * This class is only good for holding information that is indexed in the same way.
 * Don't put much logic here because joint/pipeline decoding still factorizes at the
 * Sentence level, not the FrameInstance level.
 * 
 * @author travis
 */
public class FrameInstanceHypothesis implements FgRelated {
	
	// TODO figure out when to actually set gold

	private Sentence sent;
	private ParserParams params;
	private FrameVars frames;
	private RoleVars[] roles;		// indices correspond to frame values from this.frames
	private FrameInstance gold;
	
	public FrameInstanceHypothesis(Sentence sent, FrameVars f_it, ParserParams params) {
		this.sent = sent;
		this.frames = f_it;
		this.params = params;
	}
	
	public int numFrames() { return frames.numFrames(); }
	
	public Frame getFrame(int t) { return frames.getFrame(t); }
	
	public Var getFrameVar(int t) { return frames.getVariable(t); }
	
	public FrameVars getFrameVars() { return frames; }
	
	public RoleVars[] getRoleVars() { return roles; }
	
	public int getTargetHeadIdx() { return frames.getTargetHeadIdx(); }
	
	public void setupRoles(VarType varType) {
		final int T = frames.numFrames();
		final int i = frames.getTargetHeadIdx();
		roles = new RoleVars[frames.numFrames()];
		for(int t=0; t<T; t++) {
			Frame f_it = frames.getFrame(t);
			roles[t] = new RoleVars(i, f_it, varType, sent, params.argPruner);
		}
	}
	
	public void setGoldIsNull() {
		gold = null;
	}
	
	public void setGold(FrameInstance goldFI) {
		/*
		frames.setGold(goldFI);
		if(roles != null)
			for(RoleVars rv : roles)
				rv.setGold(goldFI, params);
		*/
		gold = goldFI;
	}
	
	public boolean hasRoles() { return roles != null; }

	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		frames.register(fg, gold);
		for(int i=0; i<roles.length; i++)
			roles[i].register(fg, gold);
	}
}