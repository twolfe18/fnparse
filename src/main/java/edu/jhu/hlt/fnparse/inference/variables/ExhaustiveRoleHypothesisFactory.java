package edu.jhu.hlt.fnparse.inference.variables;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.model.Var;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.FGFNParser.CParseVars;

/**
 * takes all constituent variables (i.e. all spans) from a CParseVars provided
 * @author travis
 */
public class ExhaustiveRoleHypothesisFactory implements RoleHypothesisFactory<CParseVars> {
	
	@Override
	public String getName() { return "ExhaustiveRoleHypothesisFactory"; }
	
	@Override
	public List<RoleHypothesis> make(FrameHypothesis frameHyp, int roleIdx, Sentence s, CParseVars constituents) {
		
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
		
//		List<Span> argSpans = new ArrayList<Span>();
//		Integer goldSpanIdx = spanExtractor.computeSpansAndLookFor(s, goldSpan, argSpans);
//		return new RH(frameHyp, goldSpanIdx, argSpans, frameHyp.getTargetSpan(), roleIdx, s);
		
		List<RoleHypothesis> hyps = new ArrayList<RoleHypothesis>();
		for(Span span : constituents.getAllConstituents())
			hyps.add(new RH(goldSpan.equals(span), frameHyp, span, roleIdx));
		
		// make sure that you don't use a stale value
		// you should only call this method once per setConstituents()
		constituents = null;
		
		return hyps;
	}

	public static class RH extends Var implements RoleHypothesis {

		@SuppressWarnings("serial")
		private static final List<String> stateNames = new ArrayList<String>() {{ add("t"); add("f"); }};
		private static final long serialVersionUID = -8122540108077855321L;
		
		//private FrameHypothesis parent;
		private int extentStart, extentEnd;	// they're is going to be a bunch of these instances, worth the savings
		private int roleIdx;
		private boolean hasGold, gold;
		
		/**
		 * goldLabel may be null
		 */
		public RH(Boolean goldLabel, FrameHypothesis parent, Span extent, int roleIdx) {
			super(VarType.PREDICTED, 2, getName(parent, extent, roleIdx), stateNames);
			if(goldLabel == null) {
				this.hasGold = false;
				this.gold = false;
			} else {
				this.hasGold = true;
				this.gold = goldLabel;
			}
			//this.parent = parent;
			this.extentStart = extent.start;
			this.extentEnd = extent.end;
			this.roleIdx = roleIdx;
		}
		
		public static String getName(FrameHypothesis parent, Span extent, int roleIdx) {
			return String.format("ROLE[target=%d-%d,extent=%d-%d,roleIdx=%d]",
				parent.getTargetSpan().start, parent.getTargetSpan().end, extent.start, extent.end, roleIdx);
		}
		
		public List<String> getStateNames(List<Span> spans) {
			List<String> names = new ArrayList<String>();
			for(Span s : spans)
				names.add(s.toString());
			return names;
		}

		@Override
		public int getRoleIdx() { return roleIdx; }

		@Override
		public Span getExtent() { return Span.getSpan(extentStart, extentEnd); }

		@Override
		public Var getVar() { return this; }

		@Override
		public Label getGoldLabel() {
			if(!hasGold) return Label.UNK;
			else if(gold) return Label.TRUE;
			else return Label.FALSE;
		}
	}
}
