package edu.jhu.hlt.fnparse.evaluation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameRoleInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.tutils.FPR;

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
  public static Logger LOG = Logger.getLogger(GenerousEvaluation.class);

	public static GenerousEvaluation evaluator =
			new GenerousEvaluation(
					Mode.ExtractHead,  // Use this because if you call this with fully parsed things it will blow up under ExpectHead
					new SemaforicHeadFinder(),
					false /* includeTarget */);

	/**
	 * @param timeCost is the cost of 1 frame-argument per sentence. Since this is
	 * being weighed against recall (between 0 and 1), a reasonable value might be
	 * 0.001
	 */
	public static EvalFunc generousPlusTime(FPR.Mode mode, boolean macro, double timeCost) {
	  if (timeCost < 0)
	    throw new IllegalArgumentException("timeCost must be >= 0: " + timeCost);
	  return new EvalFunc() {
	    private final boolean useMacro = macro;
	    private final FPR.Mode m = mode;
	    private final double tc = timeCost;
	    private final String name =
	        String.format("GenerousMicroRecallPlusTime:" + tc);
	    private final GenerousEvaluation ge =
	        new GenerousEvaluation(Mode.ExpectHead, null, useMacro);
      @Override
      public String getName() {
        return name;
      }
      @Override
      public double evaluate(List<SentenceEval> instances) {
        if (instances.size() == 0) {
          assert false;
          return 0d;
        }
        FPR fpr = new FPR(false);
        double time = 0d;
        for (SentenceEval se : instances) {
          FNParse hyp = se.getHypothesisParse();
          for (FrameInstance fi : hyp.getFrameInstances())
            time += fi.numRealizedArguments();
          ge.fpr(se.getGoldParse(), hyp, fpr);
        }
        time /= instances.size();
        double x = fpr.get(m);
        double value = x - time * tc;
        LOG.info(String.format(
            "[%s+time] (f1=%.3f p=%.3f r=%.3f) obj=%.3f - %.3f * time=%.3f = %.3f",
            m, fpr.get(FPR.Mode.F1), fpr.get(FPR.Mode.PRECISION), fpr.get(FPR.Mode.RECALL),
            x, tc, time, value));
        return value;
      }
	  };
	}

	public static EvalFunc generousPrecision = new EvalFunc() {
		@Override
		public String getName() { return "GenerousMacroPRECISION"; }
		@Override
		public double evaluate(List<SentenceEval> instances) {
		  FPR fpr = new FPR(true);
			for(SentenceEval se : instances)
			  evaluator.fpr(se.getGoldParse(), se.getHypothesisParse(), fpr);
			return fpr.get(FPR.Mode.PRECISION);
		}
	};

	public static EvalFunc generousRecall = new EvalFunc() {
		@Override
		public String getName() { return "GenerousMacroRECALL"; }
		@Override
		public double evaluate(List<SentenceEval> instances) {
		  FPR fpr = new FPR(true);
			for(SentenceEval se : instances)
			  evaluator.fpr(se.getGoldParse(), se.getHypothesisParse(), fpr);
			return fpr.get(FPR.Mode.RECALL);
		}
	};

	public static EvalFunc generousF1 = new EvalFunc() {
		@Override
		public String getName() { return "GenerousMacroF1"; }
		@Override
		public double evaluate(List<SentenceEval> instances) {
		  FPR fpr = new FPR(true);
			for(SentenceEval se : instances)
			  evaluator.fpr(se.getGoldParse(), se.getHypothesisParse(), fpr);
			return fpr.get(FPR.Mode.F1);
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

	public double fpr(FNParse gold, FNParse hyp, FPR fpr) {
		Sentence s = gold.getSentence();
		if (!s.getId().equals(hyp.getSentence().getId()))
			throw new IllegalArgumentException();
		Map<FrameRoleInstance, Span> goldArgs = indexArgs(gold);
		Map<FrameRoleInstance, Span> hypArgs = indexArgs(hyp);
		Set<FrameRoleInstance> all = new HashSet<>();
		all.addAll(goldArgs.keySet());
		all.addAll(hypArgs.keySet());
		for (FrameRoleInstance fri : all) {
		  Span g = goldArgs.get(fri);
		  Span h = hypArgs.get(fri);
		  if (g != null && h != null) {
		    double x = hit(g, h, s);
		    assert x >= 0d && x <= 1d;
		    fpr.accum(x, 1d - x, 1d - x);
		  } else if (g == null && h != null) {
		    fpr.accumFP();
		  } else if (g != null && h == null) {
		    fpr.accumFN();
		  } else {
		    assert false;
		  }
		}
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
}
