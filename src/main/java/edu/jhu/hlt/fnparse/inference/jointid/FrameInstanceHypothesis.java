package edu.jhu.hlt.fnparse.inference.jointid;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.FgRelated;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.FrameVars;
import edu.jhu.hlt.fnparse.inference.roleid.RoleVars;

/**
 * This class is only good for holding information that is indexed in the same way.
 * Don't put much logic here because joint/pipeline decoding still factorizes at the
 * Sentence level, not the FrameInstance level.
 * 
 * FrameIdSentence only really needs a FrameVars
 * ArgIdSentence only really needs a RoleVars
 * this class is really only ever fully used by Joint
 * the other reason to keep this is that FactorFactory uses a list of these in the signature
 * could make this abstract and have implementing classes for each case
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
		if(sent == null) throw new IllegalArgumentException();
		if(f_it == null) throw new IllegalArgumentException();
		if(params == null) throw new IllegalArgumentException();
		this.sent = sent;
		this.frames = f_it;
		this.params = params;
		
		throw new RuntimeException("move stuff from setupRoles to constructor, take gold configuration");
	}
	
	@Override
	public String toString() {
		return "<FrInstHyp in " + sent.getId() + " @ " + frames.getTargetHeadIdx() +
				" with " + frames.numFrames() + " possible frames " +
				(roles == null ? "and no roles" : "and roles") +
				">";
	}
	
	public int numFrames() { return frames.numFrames(); }
	
	public Frame getFrame(int t) { return frames.getFrame(t); }
	
	public Var getFrameVar(int t) { return frames.getVariable(t); }
	
	public FrameVars getFrameVars() { return frames; }
	
	public RoleVars[] getRoleVars() { return roles; }
	
	public int getTargetHeadIdx() { return frames.getTargetHeadIdx(); }
	
	public void setupRoles(VarType varType) {
		/*
		final int T = frames.numFrames();
		final int i = frames.getTargetHeadIdx();
		roles = new RoleVars[frames.numFrames()];
		for(int t=0; t<T; t++) {
			Frame f_it = frames.getFrame(t);
			roles[t] = new RoleVars(i, f_it, varType, sent, params.argPruner);
		}
		*/
		throw new RuntimeException("update me");
	}
	
	/** sets the gold label for the variables that this class is responsible */
	public void setGoldIsNull() {
		/*
		frames.setGoldIsNull();
		if(roles != null) {
			for(int i=0; i<roles.length; i++)
				roles[i].setGoldIsNull();
		}
		*/
		throw new RuntimeException("update me");
	}
	
	/** sets the gold label for the variables that this class is responsible */
	public void setGold(FrameInstance goldFI) {
		/*
		frames.setGold(goldFI);
		if(roles != null)
			for(RoleVars rv : roles)
				rv.setGold(goldFI, params);
		*/
		throw new RuntimeException("update me");
	}
	
	public boolean hasRoles() { return roles != null; }

	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		frames.register(fg, gold);
		if(roles != null) {
			for(int i=0; i<roles.length; i++)
				roles[i].register(fg, gold);
		}
	}
}