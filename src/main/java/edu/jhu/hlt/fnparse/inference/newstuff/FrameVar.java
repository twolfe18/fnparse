package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.List;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class FrameVar implements FgRelated {
	
	// target Spans aren't very big
	public static final int maxTargetExpandLeft = 5;
	public static final int maxTargetExpandRight = 5;
	
	public static FrameInstance nullFrameInstance(Sentence s, int head) {
		return FrameInstance.newFrameInstance(Frame.nullFrame, Span.widthOne(head), new Span[0], s);
	}
	
	private int headIdx;
	
	private List<LexicalUnit> prototypes;
	private Var prototypeVar;	// f_i == nullFrame  =>  prototypeVar = 0
	
	private List<Frame> frames;
	private Var frameVar;
	
	private Expansion.Iter expansions;
	private Var expansionVar;	// f_i == nullFrame  =>  expansionVar = 0
	
	public FrameVar(Sentence s, int headIdx, List<LexicalUnit> prototypes, List<Frame> frames) {
		this.headIdx = headIdx;
		this.prototypes = prototypes;
		this.prototypeVar = new Var(VarType.LATENT, prototypes.size(), "p_" + headIdx, null);
		this.frames = frames;
		this.frameVar = new Var(VarType.PREDICTED, frames.size(), "f_" + headIdx, null);
		this.expansions = new Expansion.Iter(headIdx, s.size(), maxTargetExpandLeft, maxTargetExpandRight);
		this.expansionVar = new Var(VarType.PREDICTED, expansions.size(), "f^e_" + headIdx, null);
	}
	
	// indices into frames and expansions respectively
	private int goldFrame = -1;
	private int goldExpansion = -1;
	
	/**
	 * instances of FrameVar who's label is nullFrame should use the
	 * nullFrameInstance function to provide an argument to this function.
	 */
	public void setGold(FrameInstance gold) {
		
		if(!gold.getTarget().includes(headIdx))
			throw new IllegalArgumentException();
		
		goldFrame = -1;
		for(int i=0; i<frames.size(); i++) {
			Frame f = frames.get(i);
			if(f.equals(gold.getFrame())) {
				if(goldFrame >= 0) throw new IllegalStateException();
				goldFrame = i;
			}
		}
		
		goldExpansion = -1;
		for(int i=0; expansions.hasNext(); i++) {
			Expansion e = expansions.next();
			Span target = e.upon(headIdx);
			if(target.equals(gold.getTarget())) {
				if(goldExpansion >= 0) throw new IllegalStateException();
				goldExpansion = i;
			}
		}
		expansions.reset();
		
		if(goldFrame < 0 || goldExpansion < 0)
			throw new IllegalStateException();
	}
	
	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		fg.addVar(prototypeVar);
		fg.addVar(frameVar);
		fg.addVar(expansionVar);
		
		// prototypeVar is latent, no gold label
		if(goldFrame >= 0)
			gold.put(frameVar, goldFrame);
		if(goldExpansion >= 0)
			gold.put(expansionVar, goldExpansion);
		
		// TODO add a whole bunch of hard factors
	}

	private int maxRoles = -1;
	public int getMaxRoles() {
		if(maxRoles < 0) {
			for(Frame f : frames)
				if(f.numRoles() > maxRoles)
					maxRoles = f.numRoles();
		}
		return maxRoles;
	}
	
	public int getTargetHeadIdx() { return headIdx; }
	
	public Var getPrototypeVar() { return prototypeVar; }
	public Var getFrameVar() { return frameVar; }
	public Var getExpansionVar() { return expansionVar; }
	
	public LexicalUnit getPrototype(int localIdx) { return prototypes.get(localIdx); }
	public Frame getFrame(int localIdx) { return frames.get(localIdx); }
	public Expansion getExpansion(int localIdx) { return expansions.get(localIdx); }
	
}