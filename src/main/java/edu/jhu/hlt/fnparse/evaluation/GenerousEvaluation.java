package edu.jhu.hlt.fnparse.evaluation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;

/**
 * Tries to see how well we're doing on roleId by seeing if we can at least get the headword of a span.
 * 
 * @author travis
 */
public class GenerousEvaluation {

	// precision = # predictions that "hit" a role / # predictions
	// recall = # gold roles that were "hit" / # gold roles
	// hit(goldRole, hypRole) =
	//		goldRole.role == hypRole.role && hypRole.span.includes(goldRole.head)
	
	public static EvalFunc generousPrecision = new EvalFunc() {
		@Override
		public String getName() { return "MacroGenerousPrecision"; }
		@Override
		public double evaluate(List<SentenceEval> instances) {
			double p = 0d;
			GenerousEvaluation ge = new GenerousEvaluation(new SemaforicHeadFinder());
			for(SentenceEval se : instances)
				p += ge.precision(se.getGoldParse(), se.getHypothesisParse());
			return p / instances.size();
		}
	};
	public static EvalFunc generousRecall = new EvalFunc() {
		@Override
		public String getName() { return "MacroGenerousRecall"; }
		@Override
		public double evaluate(List<SentenceEval> instances) {
			double r = 0d;
			GenerousEvaluation ge = new GenerousEvaluation(new SemaforicHeadFinder());
			for(SentenceEval se : instances)
				r += ge.recall(se.getGoldParse(), se.getHypothesisParse());
			return r / instances.size();
		}
	};
	public static EvalFunc generousF1 = new EvalFunc() {
		@Override
		public String getName() { return "MacroGenerousF1"; }
		@Override
		public double evaluate(List<SentenceEval> instances) {
			double p = generousPrecision.evaluate(instances);
			double r = generousRecall.evaluate(instances);
			if(p + r == 0d) return 0d;
			return 2d * p * r / (p + r);
		}
	};
	
	private HeadFinder hf;
	private boolean includeTarget = true;
	
	public GenerousEvaluation(HeadFinder hf) { this.hf = hf; }
	
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
		
		// build index of valid roles (from gold)
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
		
		// scan over each prediction
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

		// overgenerate the set of headed-roles that we predicted
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
		
		// scan over every gold label and see if we predicted it
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
}
