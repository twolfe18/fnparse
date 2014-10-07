package edu.jhu.hlt.fnparse.evaluation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameRoleInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;

/**
 * Tries to see how well we're doing on roleId by seeing if we can at least get
 * the headword of a span.
 * 
 * precision = # predictions that "hit" a role / # predictions
 * recall = # gold roles that were "hit" / # gold roles
 * hit(goldRole, hypRole) =
 *   goldRole.role == hypRole.role && hypRole.span.includes(goldRole.head)
 *
 * @author travis
 */
public class GenerousEvaluation {
	
	public static GenerousEvaluation evaluator =
			new GenerousEvaluation(
					Mode.ExtractHead,
					new SemaforicHeadFinder(),
					false /* includeTarget */);

	public static EvalFunc generousPrecision = new EvalFunc() {
		@Override
		public String getName() { return "GenerousMacroPRECISION"; }
		@Override
		public double evaluate(List<SentenceEval> instances) {
			double p = 0d;
			for(SentenceEval se : instances) {
				p += evaluator.precision(
						se.getGoldParse(), se.getHypothesisParse());
			}
			return p / instances.size();
		}
	};

	public static EvalFunc generousRecall = new EvalFunc() {
		@Override
		public String getName() { return "GenerousMacroRECALL"; }
		@Override
		public double evaluate(List<SentenceEval> instances) {
			double r = 0d;
			for(SentenceEval se : instances) {
				r += evaluator.recall(
						se.getGoldParse(), se.getHypothesisParse());
			}
			return r / instances.size();
		}
	};

	public static EvalFunc generousF1 = new EvalFunc() {
		@Override
		public String getName() { return "GenerousMacroF1"; }
		@Override
		public double evaluate(List<SentenceEval> instances) {
			double p = generousPrecision.evaluate(instances);
			double r = generousRecall.evaluate(instances);
			if(p + r == 0d) return 0d;
			return 2d * p * r / (p + r);
		}
	};

	public static enum Mode {
		Exact,        // hit = eq(gold, hyp)
		F1,           // hit = tokenF1(gold, hyp)
		ExtractHead,  // hit = gold.contains(head(hyp))
		ExpectHead    // hit = gold.contains(hyp.start), assert hyp.width=1
	}

	private final HeadFinder hf;
	private final Mode mode;
	private final boolean includeTarget;

	public GenerousEvaluation(Mode mode, HeadFinder hf, boolean includeTarget) {
		if (mode == Mode.ExtractHead && hf == null)
			throw new IllegalArgumentException();
		this.mode = mode;
		this.hf = hf;
		this.includeTarget = includeTarget;
	}

	/**
	 * Build an index of all realized arguments in this parse.
	 */
	public Map<FrameRoleInstance, Span> indexArgs(FNParse p) {
		Map<FrameRoleInstance, Span> args = new HashMap<>();
		for (FrameInstance fi : p.getFrameInstances()) {
			Frame f = fi.getFrame();
			Span t = fi.getTarget();
			int K = f.numRoles();
			for (int k = 0; k < K; k++) {
				Span arg = fi.getArgument(k);
				if (arg == Span.nullSpan) continue;
				Span old = args.put(new FrameRoleInstance(f, t, k), arg);
				assert old == null;
			}
			if (includeTarget) {
				Span old = args.put(new FrameRoleInstance(f, t, -1), t);
				assert old == null;
			}
		}
		return args;
	}

	/**
	 * Determine if the hyp span 
	 */
	public double hit(Span gold, Span hyp, Sentence sent) {
		if (mode == Mode.Exact) {
			return gold.equals(hyp) ? 1d : 0d;
		} else if (mode == Mode.F1) {
			int s = Math.min(gold.start, hyp.start);
			int e = Math.min(gold.end, hyp.end);
			int tp = 0, fp = 0, fn = 0;
			for (int i = s; i < e; i++) {
				if (gold.includes(i) && hyp.includes(i)) tp++;
				else if (gold.includes(i) && !hyp.includes(i)) fn++;
				else if (!gold.includes(i) && hyp.includes(i)) fp++;
			}
			int Zp = tp + fp, Zr = tp + fn;
			double p = Zp == 0 ? 1d : ((double) tp) / Zp;
			double r = Zr == 0 ? 1d : ((double) tp) / Zr;
			return 2 * p * r / (p + r);
		} else if (mode == Mode.ExpectHead) {
			if (hyp.width() != 1)
				throw new IllegalArgumentException();
			return gold.includes(hyp.start) ? 1d : 0d;
		} else if (mode == Mode.ExtractHead) {
			return gold.includes(hf.head(hyp, sent)) ? 1d : 0d;
		} else {
			throw new RuntimeException("unknown mode: " + mode);
		}
	}

	public double precision(FNParse gold, FNParse hyp) {
		Sentence s = gold.getSentence();
		if (!s.getId().equals(hyp.getSentence().getId()))
			throw new IllegalArgumentException();
		Map<FrameRoleInstance, Span> goldArgs = indexArgs(gold);
		Map<FrameRoleInstance, Span> hypArgs = indexArgs(hyp);
		double n = 0d, d = 0d;
		for (FrameRoleInstance role : hypArgs.keySet()) {
			d += 1d;
			Span g = goldArgs.get(role);
			if (g != null)
				n += hit(g, hypArgs.get(role), s);
		}
		if (d == 0d) return 1d;
		return n / d;
	}

	public double recall(FNParse gold, FNParse hyp) {
		return precision(hyp, gold);
	}

	/*
	private static class Role {
		public Frame frame;
		public int targetHead;
		public int k;
		public Role(Frame frame, int targetHead, int k) {
			this.frame = frame;
			this.targetHead = targetHead;
			this.k = k;
		}
		@Override
		public int hashCode() {
			return (frame.getId() << 16) ^ (targetHead << 8) ^ k;
		}
		@Override
		public boolean equals(Object other) {
			if(other instanceof Role) {
				Role r = (Role) other;
				return frame == r.frame && targetHead == r.targetHead && k == r.k;
			}
			else return false;
		}
	}

	private static class RoleWithArgHead extends Role {
		public int argHead;
		public RoleWithArgHead(Frame frame, int targetHead, int k, int argHead) {
			super(frame, targetHead, k);
			this.argHead = argHead;
		}
		@Override
		public int hashCode() {
			return super.hashCode() * (argHead + 1);
		}
		@Override
		public boolean equals(Object other) {
			if(other instanceof RoleWithArgHead) {
				RoleWithArgHead r = (RoleWithArgHead) other;
				return super.equals(r) && argHead == r.argHead;
			}
			else return false;
		}
	}

	public double precision(FNParse gold, FNParse hyp) {

		// Build index of valid roles from gold
		Map<Role, Integer> roleHeads = new HashMap<Role, Integer>();
		for(FrameInstance fi : gold.getFrameInstances()) {
			Frame f = fi.getFrame();
			int K = f.numRoles();
			for(int k=0; k<K; k++) {
				Span s = fi.getArgument(k);
				if(s == Span.nullSpan)
					continue;
				int head = hf.head(s, fi.getSentence());
				Role role = new Role(f, fi.getTarget().start, k);
				Integer old = roleHeads.put(role, head);
				assert old == null;
			}

			if(includeTarget) {
				int head = hf.head(fi.getTarget(), fi.getSentence());
				Role role = new Role(f, fi.getTarget().start, -1);
				Integer old = roleHeads.put(role, head);
				//assert old == null;
			}
		}

		// Scan over each prediction
		int hits = 0, predictions = 0;
		for(FrameInstance fi : hyp.getFrameInstances()) {
			Frame f = fi.getFrame();
			int K = f.numRoles();
			for(int k=0; k<K; k++) {
				Span s = fi.getArgument(k);
				if(s == Span.nullSpan)
					continue;

				Role role = new Role(f, fi.getTarget().start, k);
				Integer head = roleHeads.get(role);
				if(head != null && s.includes(head))
					hits++;
				predictions++;
			}

			if(includeTarget) {
				Role role = new Role(f, fi.getTarget().start, -1);
				Integer head = roleHeads.get(role);
				Span s = fi.getTarget();
				if(head != null && s.includes(head))
					hits++;
				predictions++;
			}
		}

		if(predictions == 0)
			return 1d;
		return ((double) hits) / predictions;
	}
	
	public double recall(FNParse gold, FNParse hyp) {

		// Overgenerate the set of headed-roles that we predicted
		Set<RoleWithArgHead> predictions = new HashSet<RoleWithArgHead>();
		for(FrameInstance fi : hyp.getFrameInstances()) {
			Frame f = fi.getFrame();
			int K = f.numRoles();
			for(int k=0; k<K; k++) {
				Span s = fi.getArgument(k);
				if(s == Span.nullSpan)
					continue;
				for(int i=s.start; i<s.end; i++) {
					RoleWithArgHead role = new RoleWithArgHead(f, fi.getTarget().start, k, i);
					boolean added = predictions.add(role);
					assert added;
				}
			}
			if(includeTarget) {
				int head = hf.head(fi.getTarget(), fi.getSentence());
				RoleWithArgHead role = new RoleWithArgHead(f, fi.getTarget().start, -1, head);
				boolean added = predictions.add(role);
				assert added;
			}
		}

		// Scan over every gold label and see if we predicted it
		int hits = 0, labels = 0;
		for(FrameInstance fi : gold.getFrameInstances()) {
			Frame f = fi.getFrame();
			int K = f.numRoles();
			for(int k=0; k<K; k++) {
				Span s = fi.getArgument(k);
				if(s == Span.nullSpan)
					continue;

				int argHead = hf.head(s, fi.getSentence());
				RoleWithArgHead role = new RoleWithArgHead(f, fi.getTarget().start, k, argHead);
				if(predictions.contains(role))
					hits++;
				labels++;
			}
			if(includeTarget) {
				int head = hf.head(fi.getTarget(), fi.getSentence());
				RoleWithArgHead role = new RoleWithArgHead(f, fi.getTarget().start, -1, head);
				if(predictions.contains(role))
					hits++;
				labels++;
			}
		}

		if(labels == 0)
			return 1d;
		return ((double) hits) / labels;
	}
	*/
}
