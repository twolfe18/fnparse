package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.data.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.heads.BraindeadHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;

/**
 * Outlined in section 4 of:
 * http://www.ark.cs.cmu.edu/SEMAFOR/das+schneider+chen+smith.tr10.pdf
 * 
 * Basic idea:
 * given a target with head word t, include any frame f s.t. lemma(t) == lemma(f.target)
 * 
 * @author travis
 */
public class SemaforicFrameHypothesisFactory implements FrameHypothesisFactory {
	
	private HeadFinder hf = new BraindeadHeadFinder();
	private List<Frame> allFrames = new FrameIndex().allFrames();

	@Override
	public String getName() { return "SemaforicFrameHypFactory"; }

	@Override
	public FrameHypothesis make(Span targetSpan, Sentence sent) {
		List<Frame> frameMatches = new ArrayList<Frame>();
		frameMatches.add(Frame.nullFrame);
		int headIdx = hf.head(targetSpan, sent);
		LexicalUnit head = new LexicalUnit(sent.getWord(headIdx), sent.getPos(headIdx));
		for(Frame f : allFrames) { 
			for(int i=0; i<f.numLexicalUnits(); i++) {
				if(head.equals(f.getLexicalUnit(i))) {
					frameMatches.add(f);
					break;
				}
			}
		}
		return new FH(sent, frameMatches, targetSpan);
	}
	
	public static class FH implements FrameHypothesis {

		private List<Frame> frames;
		private Span targetSpan;
		private int maxRoles = 0;
		private Var var;
		
		public FH(Sentence sent, List<Frame> frames, Span targetSpan) {
			this.frames = frames;
			this.targetSpan = targetSpan;
			List<String> stateNames = new ArrayList<String>();
			for(Frame f : frames) {
				stateNames.add(f.getName());
				if(f.numRoles() > maxRoles)
					maxRoles = f.numRoles();
			}
			String name = String.format("f_{%s,%d,%d}", sent.getId(), targetSpan.start, targetSpan.end);
			this.var = new Var(VarType.PREDICTED, frames.size(), name, stateNames);
		}
		
		@Override
		public Var getVar() { return var; }

		@Override
		public Factor getUnaryFactor() {
			// TODO Auto-generated method stub
			throw new RuntimeException("implement me");
		}

		@Override
		public Span getTargetSpan() { return targetSpan; }

		@Override
		public Frame getPossibleFrame(int i) { return frames.get(i); }

		@Override
		public int numPossibleFrames() { return frames.size(); }

		@Override
		public int maxRoles() { return maxRoles; }
	}

}
