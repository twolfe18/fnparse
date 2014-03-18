package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.*;

import edu.jhu.gm.model.*;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;

public class FrameVar implements FgRelated {

	public static FrameInstance nullFrameInstance(Sentence s, int head) {
		return FrameInstance.newFrameInstance(Frame.nullFrame, Span.widthOne(head), new Span[0], s);
	}
	
	
	public Frame[] f_it_values;		// first value is nullFrame
	public Var[] f_it;
	public int i;
	public FrameInstance gold;

	/**
	 * @param frames should not contain Frame.nullFrame
	 */
	public FrameVar(int headIdx, List<FrameInstance> prototypes, List<Frame> frames, ParserParams params) {
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

	public int getTargetHeadIdx() { return i; }

	public Frame getFrame(int t) { return f_it_values[t]; }

	public int numFrames() { return f_it_values.length; }

	@Override
	public String toString() {
		return String.format("f_{i=%d,t=1:%d}", i, f_it.length);
	}

	public void setGold(FrameInstance gold) {

		Span target = gold.getTarget();
		if(target.width() != 1 || target.start != getTargetHeadIdx())
			throw new IllegalArgumentException();

		this.gold = gold;
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
		if(gold != null) {
			Frame gf = this.gold.getFrame();
			boolean foundGold = false;
			for(int i=0; i<n; i++) {
				boolean v = f_it_values[i] == gf;
				foundGold |= v;
				gold.put(f_it[i], BinaryVarUtil.boolToConfig(v));
			}
			if(!foundGold)
				gold.put(f_it[0], BinaryVarUtil.boolToConfig(true));	// nullFrame
		}
	}

}