package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.*;

import edu.jhu.gm.model.*;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;

public class FrameVar implements FgRelated {
	
	public static final boolean debug = true;
	
	// target Spans aren't very big
	public static final int maxTargetExpandLeft = 3;
	public static final int maxTargetExpandRight = 2;
	
	public static FrameInstance nullFrameInstance(Sentence s, int head) {
		return FrameInstance.newFrameInstance(Frame.nullFrame, Span.widthOne(head), new Span[0], s);
	}
	
	@SuppressWarnings("unused")
	private Sentence sent;	// TODO this can be removed (good for debugging)
	private int headIdx;
	
	private List<FrameInstance> prototypes;
	private Var prototypeVar;	// f_i == nullFrame  =>  prototypeVar = 0
	
	private List<Frame> frames;
	private Var frameVar;
	
	/** @deprecated */
	private Expansion.Iter expansions;
	/** @deprecated */
	private Var expansionVar;	// f_i == nullFrame  =>  expansionVar = 0
	
	@Override
	public String toString() {
		return String.format("<f/p_%d #frames=%d #protos=%d maxRoles=%d>",
				headIdx, frames.size(), prototypes.size(), maxRoles);
	}
	
	public FrameVar(Sentence s, int headIdx, List<FrameInstance> prototypes, List<Frame> frames, ParserParams params) {
		
		if(frames.size() == 0 || prototypes.size() == 0)
			throw new IllegalArgumentException("#frames=" + frames.size() + ", #prototypes=" + prototypes.size());
		
		List<String> frameVarNames = null;
		List<String> protoVarNames = null;
		if(debug) {
			frameVarNames = new ArrayList<String>();
			for(Frame f : frames)
				frameVarNames.add(f.getId() + "_" + f.getName());
			protoVarNames = new ArrayList<String>();
			for(FrameInstance fi : prototypes)
				protoVarNames.add(fi.getFrame().getId() + "_" + fi.getFrame().getName() + "_" + fi.getSentence().getId());
		}
		
		this.sent = s;
		this.headIdx = headIdx;
		
		this.frames = frames;
		this.frameVar = new Var(VarType.PREDICTED, frames.size(), "f_" + headIdx, frameVarNames);
		
		if(params.usePrototypes) {
			this.prototypes = prototypes;
			this.prototypeVar = new Var(VarType.LATENT, prototypes.size(), "p_" + headIdx, protoVarNames);
		}
		
		//this.expansions = new Expansion.Iter(headIdx, s.size(), maxTargetExpandLeft, maxTargetExpandRight);
		//this.expansionVar = new Var(VarType.PREDICTED, expansions.size(), "f^e_" + headIdx, null);
	}
	
	// indices into frames and expansions respectively
	private int goldFrame;
	/** @deprecated */
	private int goldExpansion;
	
	/**
	 * instances of FrameVar who's label is nullFrame should use the
	 * nullFrameInstance function to provide an argument to this function.
	 */
	public void setGold(FrameInstance gold) {
		
		if(!gold.getTarget().includes(headIdx))
			throw new IllegalArgumentException();
		
		goldFrame = frames.indexOf(gold.getFrame());
		if(goldFrame < 0) {
			// our filtering heuristic didn't include the correct answer
			System.err.printf("WARNING: frame filtering heuristic didn't extract %s for %s\n",
					gold.getFrame(), gold.getSentence().getLU(headIdx));
			goldFrame = frames.indexOf(Frame.nullFrame);
		}
		if(goldFrame < 0)
			throw new IllegalStateException();
		
		if(expansions != null) {
			assert false;
			goldExpansion = -1;
			int n = expansions.size();
			for(int i=0; i<n; i++) {
				Expansion e = expansions.get(i);
				Span target = e.upon(headIdx);	// target implied by this expansion
				if(target.equals(gold.getTarget())) {
					if(goldExpansion >= 0) throw new IllegalStateException();
					goldExpansion = i;
				}
			}
			if(goldExpansion < 0)
				throw new IllegalStateException();
		}
	}
	
	public Frame getGoldFrame() {
		return frames.get(goldFrame);
	}
	
	public int numberOfConfigs() {
		int nc = prototypes.size() * frames.size();
//		if(nc > 500) {
//			System.err.println("poop3");
//		}
		return nc;
	}
	
	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		
		if(prototypeVar != null)
			fg.addVar(prototypeVar);
		
		fg.addVar(frameVar);
		//fg.addVar(expansionVar);
		
		// prototypeVar is latent, no gold label
		gold.put(frameVar, goldFrame);
		//gold.put(expansionVar, goldExpansion);
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
	
	/** @deprecated */
	public Var getExpansionVar() {
		assert false;	// smoke out the callers of this function
		return expansionVar;
	}
	
	public FrameInstance getPrototype(VarConfig conf) {
		return getPrototype(conf.getState(prototypeVar));
	}
	
	public Frame getFrame(VarConfig conf) {
		return getFrame(conf.getState(frameVar));
	}
	
	/** @deprecated */
	public Expansion getExpansion(VarConfig conf) {
		assert false;	// smoke out the callers of this function
		return getExpansion(conf.getState(expansionVar));
	}
	
	/** @deprecated */
	public Span getTarget(VarConfig conf) {
		assert false;	// smoke out the callers of this function
		return getExpansion(conf).upon(headIdx);
	}
	
	public FrameInstance getPrototype(int localIdx) { return prototypes.get(localIdx); }
	public Frame getFrame(int localIdx) { return frames.get(localIdx); }
	public List<Frame> getFrames() { return frames; }
	
	/** @deprecated */
	public Expansion getExpansion(int localIdx) {
		assert false;	// smoke out the callers of this function
		return expansions.get(localIdx);
	}
	
	/** @deprecated */
	public Expansion.Iter getExpansions() {
		assert false;	// smoke out the callers of this function
		return expansions;
	}
	
}