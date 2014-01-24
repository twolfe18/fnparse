package edu.jhu.hlt.fnparse.inference;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
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
		
		Span goldSpan = null;
		FrameInstance goldFI = frameHyp.getGoldFrameInstance();
		if(goldFI != null) {
			if(roleIdx >= goldFI.numArguments()) {
				
				// It is possible that other possible frames in frameHyp
				// take more arguments than the gold Frame does. If this
				// is the case, then this argument span variable is meaningless.
				
				// There will be a hard factor that rules out all of these
				// meaningless entries, so it will be as if we're not summing
				// over them, which should mean that the partial gradient and scores
				// for these entries don't matter.
				
				// So, while it is technically incorrect to say that these variables
				// have a gold value of nullSpan (the variable isn't well defined for
				// such cases), I will do it on the condition that they be zeroed out
				// by a hard factor later:
				// \phi_{hard}(f_i, r_ij) = if(j >= f_i.numRoles) 0 else 1
				goldSpan = Span.nullSpan;
			}
			else goldSpan = goldFI.getArgument(roleIdx);
		}
		
		List<Span> argSpans = new ArrayList<Span>();
		Integer goldSpanIdx = spanExtractor.computeSpansAndLookFor(s, goldSpan, argSpans);
		return new FEH(frameHyp, goldSpanIdx, argSpans, frameHyp.getTargetSpan(), roleIdx, s);
	}

	public static class FEH implements FrameElementHypothesis {

		private FrameHypothesis parent;
		private Integer goldSpanIdx;
		private List<Span> argSpans;
		private Span targetSpan;
		private int roleIdx;
		private Var var;
		
		/**
		 * gold may be null
		 */
		public FEH(FrameHypothesis parent, Integer goldSpanIdx, List<Span> argSpans, Span targetSpan, int roleIdx, Sentence s) {
			this.parent = parent;
			this.goldSpanIdx = goldSpanIdx;
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

		@Override
		public Integer getGoldSpanIdx() { return goldSpanIdx; }

		@Override
		public FrameHypothesis parent() { return parent; }
	}
}
