package edu.jhu.hlt.fnparse.inference.frameid;

import java.util.*;

import edu.jhu.gm.model.*;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FgRelated;
import edu.jhu.hlt.fnparse.inference.Parser;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;

/**
 * Represents all of the frames that could be evoked at a given headword.
 * 
 * @author travis
 */
public class FrameVars implements FgRelated {

	public static FrameInstance nullFrameInstance(Sentence s, int head) {
		return FrameInstance.newFrameInstance(Frame.nullFrame, Span.widthOne(head), new Span[0], s);
	}
	
	// these arrays are indexed by t (frame), and i is implicit information in the instance
	public Frame[] f_it_values;		// first value is nullFrame
	public Var[] f_it;
	public int i;
	public FrameInstance gold;
	public boolean goldSet = false;

	/**
	 * @param frames should not contain Frame.nullFrame
	 */
	public FrameVars(int headIdx, List<FrameInstance> prototypes, List<Frame> frames, ParserParams params) {
		this.i = headIdx;
		int n = frames.size() + 1;
		this.f_it = new Var[n];
		this.f_it_values = new Frame[n];
		Frame f;
		for(int i=0; i<n; i++) {
			if(i == 0) f = Frame.nullFrame;
			else {
				f = frames.get(i-1);
				if(f == Frame.nullFrame)
					throw new IllegalArgumentException("don't include nullFrame");
			}
			String name = String.format("f_{i=%d,t=%s}", headIdx, f.getName());
			this.f_it[i] = new Var(VarType.PREDICTED, 2, name, BinaryVarUtil.stateNames);
			this.f_it_values[i] = f;
		}
	}
	
	public int getNullFrameIdx() {
		final int i = 0;
		assert f_it_values[i] == Frame.nullFrame;
		return i;
	}

	public int getTargetHeadIdx() { return i; }

	public Frame getFrame(int t) { return f_it_values[t]; }
	
	public Var getVariable(int t) { return f_it[t]; }

	public int numFrames() { return f_it_values.length; }
	
	private int maxRoles = -1;
	public int getMaxRoles() {
		if(maxRoles < 0) {
			for(Frame f : f_it_values)
				if(f.numRoles() > maxRoles)
					maxRoles = f.numRoles();
		}
		return maxRoles;
	}

	@Override
	public String toString() {
		return String.format("f_{i=%d,t=1:%d}", i, f_it.length);
	}
	
	public void setGoldIsNull() {
		this.gold = null;
		this.goldSet = true;
	}

	public void setGold(FrameInstance gold) {

		if(gold.getFrame() == Frame.nullFrame || gold.getFrame() == null)
			throw new IllegalArgumentException();

		Span target = gold.getTarget();
		if(target.width() != 1 || target.start != getTargetHeadIdx())
			throw new IllegalArgumentException();

		this.gold = gold;
		this.goldSet = true;
		if(!Arrays.asList(f_it_values).contains(gold.getFrame())) {
			System.err.printf("WARNING: frame filtering heuristic didn't extract %s for %s\n",
					gold.getFrame(), gold.getSentence().getLU(getTargetHeadIdx()));
		}
	}

	@Override
	public void register(FactorGraph fg, VarConfig gold) {
		int n = f_it.length;
		for(int i=0; i<n; i++)
			fg.addVar(f_it[i]);
		if(this.goldSet) {
			Frame gf = this.gold == null ? Frame.nullFrame : this.gold.getFrame();
			boolean foundGold = false;
			for(int i=0; i<n; i++) {
				boolean v = f_it_values[i] == gf;
				gold.put(f_it[i], BinaryVarUtil.boolToConfig(v));
				foundGold |= v;
			}
			assert foundGold;
		}
	}

}