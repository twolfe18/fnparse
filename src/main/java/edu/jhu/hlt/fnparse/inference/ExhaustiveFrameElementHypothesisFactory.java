package edu.jhu.hlt.fnparse.inference;

import java.util.List;

import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.spans.ExhaustiveSpanExtractor;
import edu.jhu.hlt.fnparse.inference.spans.SpanExtractor;

public class ExhaustiveFrameElementHypothesisFactory implements FrameElementHypothesisFactory {
	
	private SpanExtractor spanExtractor = new ExhaustiveSpanExtractor();

	@Override
	public String getName() { return "ExhaustiveFrameElementHypothesisFactory"; }

	@Override
	public FrameElementHypothesis make(FrameHypothesis frameHyp, int roleIdx, Sentence s) {
		List<Span> argSpans = spanExtractor.computeSpans(s);
		return new FEH(argSpans, frameHyp.getTargetSpan(), roleIdx, s);
	}

	public static class FEH implements FrameElementHypothesis {

		private List<Span> argSpans;
		private Span targetSpan;
		private int roleIdx;
		private Var var;
		
		public FEH(List<Span> argSpans, Span targetSpan, int roleIdx, Sentence s) {
			this.argSpans = argSpans;
			this.targetSpan = targetSpan;
			this.roleIdx = roleIdx;
			String name = String.format("r_{%s,%d,%d,%d}", s.getId(), targetSpan.start, targetSpan.end, roleIdx);
			List<String> stateNames = null;
			this.var = new Var(VarType.PREDICTED, argSpans.size(), name, stateNames);
		}
		
		@Override
		public Var getVar() { return var; }

		@Override
		public Span getTargetSpan() { return targetSpan; }

		@Override
		public int getRoleIdx() { return roleIdx; }

		@Override
		public Span getSpan(int i) { return argSpans.get(i); }

		@Override
		public int numSpans() { return argSpans.size(); }
	}
}
