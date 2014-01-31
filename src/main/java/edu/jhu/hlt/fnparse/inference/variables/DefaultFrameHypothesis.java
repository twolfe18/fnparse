package edu.jhu.hlt.fnparse.inference.variables;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

public class DefaultFrameHypothesis implements FrameHypothesis {

	private Sentence sentence;
	private FrameInstance goldFrameInstance;
	private Integer goldFrameIdx;
	private List<Frame> frames;
	private Span targetSpan;
	private int maxRoles = 0;
	private Var var;

	// note that goldFrameIdx==null does NOT mean nullFrame is correct,
	// it means we don't know the correct answer (i.e. decode time)
	public DefaultFrameHypothesis(Sentence sent, List<Frame> frames, Integer goldFrameIdx, FrameInstance goldFrameInstance, Span targetSpan) {
		if (goldFrameIdx != null && (goldFrameIdx < 0 || goldFrameIdx >= frames.size()))
			throw new IllegalArgumentException(String.format(
					"frames.size=%d goldFrameIdx=%s", frames.size(),
					goldFrameIdx));
		this.sentence = sent;
		this.goldFrameIdx = goldFrameIdx;
		this.goldFrameInstance = goldFrameInstance;
		this.frames = frames;
		this.targetSpan = targetSpan;
		List<String> stateNames = new ArrayList<String>();
		for (Frame f : frames) {
			stateNames.add(f.getName());
			if (f.numRoles() > maxRoles)
				maxRoles = f.numRoles();
		}
		String name = String.format("f_{%s,%d,%d}", sent.getId(),
				targetSpan.start, targetSpan.end);
		this.var = new Var(VarType.PREDICTED, frames.size(), name,
				stateNames);
	}

	public boolean hasGoldLabel() {
		return goldFrameIdx != null;
	}

	@Override
	public Var getVar() {
		return var;
	}

	@Override
	public Span getTargetSpan() {
		return targetSpan;
	}

	@Override
	public Frame getPossibleFrame(int i) {
		return frames.get(i);
	}

	@Override
	public int numPossibleFrames() {
		return frames.size();
	}

	@Override
	public int maxRoles() {
		return maxRoles;
	}

	@Override
	public Integer getGoldFrameIndex() {
		return goldFrameIdx;
	}

	@Override
	public Frame getGoldFrame() {
		Integer fi = getGoldFrameIndex();
		if (fi == null)
			return null;
		else
			return getPossibleFrame(fi);
	}

	@Override
	public FrameInstance getGoldFrameInstance() {
		return goldFrameInstance;
	}

	@Override
	public Sentence getSentence() {
		return sentence;
	}
}