package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.heads.BraindeadHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;

/**
 * Outlined in section 4 of:
 * http://www.ark.cs.cmu.edu/SEMAFOR/das+schneider+chen+smith.tr10.pdf
 * 
 * Basic idea: given a target with head word t, include any frame f s.t.
 * lemma(t) == lemma(f.target)
 * 
 * @author travis
 */
public class SemaforicFrameHypothesisFactory implements FrameHypothesisFactory {

	private HeadFinder hf = new BraindeadHeadFinder();
	private List<Frame> allFrames = FrameIndex.getInstance().allFrames();

	@Override
	public String getName() {
		return "SemaforicFrameHypFactory";
	}

	@Override
	public FrameHypothesis make(Span targetSpan, FrameInstance gold,
			Sentence sent) {

		if (gold != null && !gold.getTarget().equals(targetSpan))
			throw new IllegalArgumentException();

		if (targetSpan == Span.nullSpan)
			throw new IllegalArgumentException(
					"Frames cannot be evoked by the null Span");

		Integer goldFrameIdx = sent.hasFrameInstanceLabels() ? -1 : null;
		List<Frame> frameMatches = new ArrayList<Frame>();
		frameMatches.add(Frame.nullFrame);
		int headIdx = hf.head(targetSpan, sent);
		LexicalUnit head = sent.getLU(headIdx);
		for (Frame f : allFrames) {
			for (int i = 0; i < f.numLexicalUnits(); i++) {
				if (LexicalUnit.approxMatch(head, f.getLexicalUnit(i))) {
					frameMatches.add(f);
					if (gold != null && f.getId() == gold.getFrame().getId()) {
						assert goldFrameIdx == null || goldFrameIdx < 0;
						goldFrameIdx = frameMatches.size() - 1;
					}
					break;
				}
			}
		}
		if (goldFrameIdx == -1)
			goldFrameIdx = 0; // null span

		return new FH(sent, frameMatches, goldFrameIdx, gold, targetSpan);
	}

	public static class FH implements FrameHypothesis {

		private Sentence sentence;
		private FrameInstance goldFrameInstance;
		private Integer goldFrameIdx;
		private List<Frame> frames;
		private Span targetSpan;
		private int maxRoles = 0;
		private Var var;

		// note that goldFrameIdx==null does NOT mean nullFrame is correct,
		// it means we don't know the correct answer (i.e. decode time)
		public FH(Sentence sent, List<Frame> frames, Integer goldFrameIdx,
				FrameInstance goldFrameInstance, Span targetSpan) {
			if (goldFrameIdx != null
					&& (goldFrameIdx < 0 || goldFrameIdx >= frames.size()))
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
		public Factor getUnaryFactor() {
			// TODO Auto-generated method stub
			throw new RuntimeException("implement me");
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

}
