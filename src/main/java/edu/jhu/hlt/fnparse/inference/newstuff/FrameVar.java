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
	
	private final int headIdx;	// aka i in f_i
	
	private List<FrameInstance> prototypes;
	private Var prototypeVar;
	
	private List<Frame> frames;
	private Var frameVar;
	private FrameInstance gold;
	
	@Override
	public String toString() {
		return String.format("<f/p_%d #frames=%d #protos=%d maxRoles=%d>",
				headIdx, frames.size(), prototypes.size(), maxRoles);
	}
	
	public FrameVar(int headIdx, List<FrameInstance> prototypes, List<Frame> frames, ParserParams params) {
		
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
		
		this.headIdx = headIdx;
		
		this.frames = frames;
		this.frameVar = new Var(VarType.PREDICTED, frames.size(), "f_" + headIdx, frameVarNames);
		
		if(params.usePrototypes) {
			this.prototypes = prototypes;
			this.prototypeVar = new Var(VarType.LATENT, prototypes.size(), "p_" + headIdx, protoVarNames);
		}
	}
	
	public void clamp(Frame prediction) {
		prototypes = null;
		prototypeVar = null;
		frames = Arrays.asList(prediction);
		frameVar = new Var(VarType.PREDICTED, frames.size(), "f_" + headIdx, null);
	}
	
	/**
	 * instances of FrameVar who's label is nullFrame should use the
	 * nullFrameInstance function to provide an argument to this function.
	 */
	public void setGold(FrameInstance gold) {
		
		Span target = gold.getTarget();
		if(target.width() != 1 || target.start != headIdx)
			throw new IllegalArgumentException();
		
		this.gold = gold;
		if(!frames.contains(gold.getFrame())) {
			System.err.printf("WARNING: frame filtering heuristic didn't extract %s for %s\n",
					gold.getFrame(), gold.getSentence().getLU(headIdx));
		}
	}
	
	public Frame getGoldFrame() {
		if(gold == null)
			return null;
		return gold.getFrame();
	}
	
	public FrameInstance getGold() {
		return gold;
	}
	
	public int numberOfConfigs() {
		int nc = frames.size();
		if(prototypes != null)
			nc *= prototypes.size();
		return nc;
	}
	
	@Override
	public void register(FactorGraph fg, VarConfig goldConf) {
		
		fg.addVar(frameVar);
		if(prototypeVar != null)
			fg.addVar(prototypeVar);
		
		int gi = -1;
		if(this.gold != null && this.gold.getFrame() != null)
			gi = frames.indexOf(this.gold.getFrame());
		if(gi < 0)
			gi = frames.indexOf(Frame.nullFrame);
		if(gi < 0) {
			assert this.gold == null;
			gi = 0;	// this is because Matt's code requires a gold label
		}
		goldConf.put(frameVar, gi);
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
	
	public FrameInstance getPrototype(VarConfig conf) {
		return getPrototype(conf.getState(prototypeVar));
	}
	
	public Frame getFrame(VarConfig conf) {
		return getFrame(conf.getState(frameVar));
	}
	
	public FrameInstance getPrototype(int localIdx) { return prototypes.get(localIdx); }
	public Frame getFrame(int localIdx) { return frames.get(localIdx); }
	public List<Frame> getFrames() { return frames; }
}