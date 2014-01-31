package edu.jhu.hlt.fnparse.inference.variables;

import java.util.ArrayList;
import java.util.List;

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

		return new DefaultFrameHypothesis(sent, frameMatches, goldFrameIdx, gold, targetSpan);
	}

}
