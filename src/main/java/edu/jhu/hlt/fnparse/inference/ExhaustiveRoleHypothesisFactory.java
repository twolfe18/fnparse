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
			assert goldSpan != null;
		}
		else {
			// If goldFrameInstance is null, then this means that it was not based on a
			// *positive* frame instance contained in a Sentence.
			// This means that this frame evokes nullFrame.
			// the reason for this is that it is wasteful to store all of the targets that
			// evoke nullFrame explicitly.
			
			// If nullFrame is evoked, then it has no arguments, which means that this
			// r_ij variable will be pruned by the hard factor that enforces
			//   r_ij = nullSpan \forall i, j \ge f_i.numRoles
			// We should have a value here though because Matt's library expects a gold value
			// for every variable, even if it will be excluded from the likelihood by the
			// hard factor.
			
			goldSpan = Span.nullSpan;
		}
		
		List<Span> argSpans = new ArrayList<Span>();
		Integer goldSpanIdx = spanExtractor.computeSpansAndLookFor(s, goldSpan, argSpans);
		if(goldSpanIdx == null)
			System.out.println("foo");
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
			List<String> stateNames = getStateNames(argSpans);
			this.var = new Var(VarType.PREDICTED, argSpans.size(), name, stateNames);
		}
		
		public List<String> getStateNames(List<Span> spans) {
			List<String> names = new ArrayList<String>();
			for(Span s : spans)
				names.add(s.toString());
			return names;
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
