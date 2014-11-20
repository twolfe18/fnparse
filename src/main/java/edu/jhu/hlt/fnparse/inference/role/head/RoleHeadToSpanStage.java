package edu.jhu.hlt.fnparse.inference.role.head;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.inf.FgInferencer;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.ExplicitExpFamFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.FrameRoleInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.Counts;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.GlobalParameters;

/**
 * Takes a FNParse where all of the arguments are headwords (spans of width 1)
 * and predicts a full span for each input argument.
 * 
 * @author travis
 */
public class RoleHeadToSpanStage
		extends AbstractStage<FNParse, FNParse> {
	public static boolean SHOW_FEATURES = false;
	public static final Logger LOG = Logger.getLogger(RoleHeadToSpanStage.class);

	// if you check the pareto frontier in ExpansionPruningExperiment:
	// (6,0) gives 77.9 % recall
	// (6,3) gives 86.7 % recall
	// (8,3) gives 88.4 % recall
	// (8,4) gives 90.0 % recall
	// (10,5) gives 92.3 % recall
	// (12,5) gives 93.2 % recall
	private int maxArgRoleExpandLeft = 8;
	private int maxArgRoleExpandRight = 4;
	private boolean disallowArgWithoutConstituent = true;

	public RoleHeadToSpanStage(GlobalParameters globals, String featureTemplatesString) {
	  super(globals, featureTemplatesString);
	}

	@Override
	public void saveModel(DataOutputStream dos, GlobalParameters globals) {
	  super.saveModel(dos, globals);
	  try {
	    dos.writeInt(maxArgRoleExpandLeft);
	    dos.writeInt(maxArgRoleExpandRight);
	    dos.writeBoolean(disallowArgWithoutConstituent);
	  } catch (Exception e) {
	    throw new RuntimeException();
	  }
	}

	@Override
	public void loadModel(DataInputStream dis, GlobalParameters globals) {
	  super.loadModel(dis, globals);
	  try {
	    maxArgRoleExpandLeft = dis.readInt();
	    maxArgRoleExpandRight = dis.readInt();
	    disallowArgWithoutConstituent = dis.readBoolean();
	  } catch (Exception e) {
	    throw new RuntimeException();
	  }
	}

  @Override
  public void configure(java.util.Map<String,String> configuration) {
    super.configure(configuration);

    String key, value;

    key = "disallowArgWithoutConstituent." + getName();
    value = configuration.get(key);
    if (value != null) {
      disallowArgWithoutConstituent = Boolean.valueOf(value);
      LOG.info("setting " + key + " = " + value);
    }

    key = "maxArgRoleExpandLeft";
    value = configuration.get(key);
    if (value != null) {
      maxArgRoleExpandLeft = Integer.parseInt(value);
      LOG.info("setting " + key + " = " + value);
    }

    key = "maxArgRoleExpandRight";
    value = configuration.get(key);
    if (value != null) {
      maxArgRoleExpandRight = Integer.parseInt(value);
      LOG.info("setting " + key + " = " + value);
    }
  }

  @Override
  public void scanFeatures(List<FNParse> data) {
    HeadFinder hf = SemaforicHeadFinder.getInstance();
	  List<FNParse> onlyHeads = DataUtil.convertArgumenSpansToHeads(data, hf);
	  this.scanFeatures(onlyHeads, data, 999, 999_999_999);
  }

	@Override
	public StageDatumExampleList<FNParse, FNParse> setupInference(
			List<? extends FNParse> onlyHeads,
			List<? extends FNParse> labels) {
		List<StageDatum<FNParse, FNParse>> data = new ArrayList<>();
		int n = onlyHeads.size();
		assert labels == null || labels.size() == n;
		for(int i=0; i<n; i++) {
			FNParse x = onlyHeads.get(i);
			if(labels == null)
				data.add(new RoleSpanStageDatum(x));
			else
				data.add(new RoleSpanStageDatum(x, labels.get(i)));
		}
		return new StageDatumExampleList<>(data);
	}

	public static void errAnalysis(Counts<String> errorCounts, FNParse gold, FNParse hyp, FNParse argHeads) {
	  // for every head that was given to this stage, which bucket does it fall into:
	  // 1) not a valid head for the given frame-role
	  // 2) valid head, but we pruned the gold span
	  // 3) valid head, but we predicted the wrong span
	  // 4) valid head, and we predicted the right span
	  Map<FrameInstance, FrameInstance> a = DataUtil.getFrameInstancesByFrameTarget(argHeads);
	  Map<FrameInstance, FrameInstance> b = DataUtil.getFrameInstancesByFrameTarget(hyp);
	  Map<FrameInstance, FrameInstance> c = DataUtil.getFrameInstancesByFrameTarget(gold);
	  for (FrameInstance target : a.keySet()) {
	    FrameInstance heads = a.get(target);
	    FrameInstance hypFI = b.get(target);
	    FrameInstance goldFI = c.get(target);
	    if (hypFI == null) {
	      errorCounts.increment("hypFI-is-null?");
	    } else if (goldFI == null) {
	      errorCounts.increment("predicted-bad-frame-location");
	    } else if (goldFI.getFrame() != hypFI.getFrame()) {
	      errorCounts.increment("predicted-bad-frame-value");
	    } else {
	      // try to line up the arguments
	      int K = goldFI.getFrame().numRoles();
	      for (int k = 0; k < K; k++) {
	        Span h = heads.getArgument(k);
	        assert h == Span.nullSpan || h.width() == 1;
	        Span hypArg = hypFI.getArgument(k);
	        assert h == Span.nullSpan || hypArg.includes(h.start);
	        Span goldArg = goldFI.getArgument(k);
	        if (h != Span.nullSpan)
	          errorCounts.increment("predicted-role-head");
	        if (h == Span.nullSpan && goldArg != Span.nullSpan) {
	          errorCounts.increment("pruned-head");
	        } else if (h == Span.nullSpan && goldArg == Span.nullSpan) {
	          // Correct
	          errorCounts.increment("GOLD-pruned-head");
	        } else if (h != Span.nullSpan && goldArg == Span.nullSpan) {
	          // Correct
	          errorCounts.increment("GOLD-kept-head");
	        } else {
	          if (!goldArg.includes(h.start))
	            errorCounts.increment("predicted-head-outside-of-gold-arg");
	          if (goldArg.start < h.start - 8 || goldArg.end > h.end + 4)
	            errorCounts.increment("pruned-gold-span");
	          if (goldArg == hypArg)
	            errorCounts.increment("GOLD!");
	          if (goldArg.width() < hypArg.width())
	            errorCounts.increment("span-too-wide");
	          if (goldArg.width() > hypArg.width())
	            errorCounts.increment("span-too-narrow");
	        }
	      }
	    }
	  }
	}

	public List<Factor> initFactorsFor(
	    Sentence sent,
	    List<ExpansionVar> inThisSentence,
	    ProjDepTreeFactor d,
	    ConstituencyTreeFactor c) {
	  TemplateContext context = new TemplateContext();
	  List<Factor> factors = new ArrayList<>();
	  for (ExpansionVar ev : inThisSentence) {
	    for (int spanIdx = 0; spanIdx < ev.numSpans(); spanIdx++) {
	      Var sVar = ev.getVar(spanIdx);
	      Span s = ev.getSpan(spanIdx);
	      Var cVar = null;
	      VarSet vs = null;
	      if (useLatentConstituencies && s.width() > 1) {
	        cVar = c.getSpanVar(s.start, s.end - 1);
	        vs = new VarSet(sVar, cVar);
	      } else {
	        vs = new VarSet(sVar);
	      }
	      TemplatedFeatures feats = getFeatures();
	      ExplicitExpFamFactorWithConstraint phi = new ExplicitExpFamFactorWithConstraint(vs, -1);
	      int n = vs.calcNumConfigs();
	      for (int i = 0; i < n; i++) {
	        VarConfig vc = vs.getVarConfig(i);
	        boolean arg = BinaryVarUtil.configToBool(vc.getState(sVar));
	        boolean cons = cVar != null && BinaryVarUtil.configToBool(vc.getState(cVar));
	        context.clear();
	        context.setStage(RoleHeadToSpanStage.class);
	        if (disallowArgWithoutConstituent && arg && cVar != null && !cons) {
	          phi.setBadConfig(i);
	          if (SHOW_FEATURES)
	            LOG.info("CONSTRAINING NEXT CONFIG TO BE -INFINITY");
	        } else {
	          context.setSentence(sent);
	          if (arg || cVar != null) {
	            context.setSpan1(s);
	            context.setSpan2(ev.getTarget());
	            context.setHead1(ev.getArgHeadIdx());
	            context.setHead2(ev.getTargetHeadIdx());
	            if (arg) {
	              context.setFrame(ev.getFrame());
	              context.setRole(ev.getRole());
	              context.setArg(s);
	              context.setArgHead(ev.getArgHeadIdx());
	              context.setTarget(ev.getTarget());
	              context.setTargetHead(ev.getTargetHeadIdx());
	            }
	            if (cVar != null) {
	              context.setSpan1IsConstituent(cons);
	            }
	          }
	        }
	        FeatureVector fv = new FeatureVector();
	        if (SHOW_FEATURES) {
	          String msg = String.format("[variables] arg=%s cons=%s name=%s",
	              arg, cons, sVar.getName());
	          feats.featurizeDebug(fv, context, msg);
	        } else {
	          feats.featurize(fv, context);
	        }
	        phi.setFeatures(i, fv);
	      }
	      factors.add(phi);
	    }
	  }
	  return factors;
	}

	public static class ExplicitExpFamFactorWithConstraint
	    extends ExplicitExpFamFactor {
    private static final long serialVersionUID = 1L;
    private int badConfig = -1;
	  public ExplicitExpFamFactorWithConstraint(VarSet vars, int badConfig) {
      super(vars);
      this.badConfig = badConfig;
    }
	  public int setBadConfig(int config) {
	    assert config >= 0 && config < this.getVars().calcNumConfigs();
	    int old = this.badConfig;
	    this.badConfig = config;
	    return old;
	  }
	  @Override
	  public double getDotProd(int config, FgModel model, boolean logDomain) {
	    if (config == badConfig) {
	      return logDomain ? -100d : 0d;
	    } else {
	      return super.getDotProd(config, model, logDomain);
	    }
	  }
	}

	class RoleSpanStageDatum implements StageDatum<FNParse, FNParse> {
		private final List<ExpansionVar> expansions;
		private final FNParse onlyHeads;
		private final FNParse gold;

		/** Constructor for when you don't have the labels. */
		public RoleSpanStageDatum(FNParse onlyHeads) {
			this(onlyHeads, null);
		}

		/**
		 * Constructor for when you have the labels.
		 * 
		 * This rolls out argument variables for the roles of every
		 * FrameInstance in onlyHeads. If gold is null (i.e. we are doing
		 * prediction), this statement is unqualified. If gold is not null
		 * (i.e. we are doing training), then the set of argument variables
		 * created depends on the overlap of FrameInstances in onlyHeads and
		 * gold.
		 * 
		 * For FrameInstances that are common to both onlyHeads and gold, it is
		 * clear that the gold labels for the role variables should be the
		 * values of the role variables from the corresponding FrameInstance
		 * in gold.
		 * 
		 * For FrameInstances in onlyHeads that are not present in gold, there
		 * are two options:
		 * 1) don't roll out argument variables for these FrameInstances
		 * 2) roll them out with gold values of nullSpan (which typically
		 *    results in higher precision awarded by the evaluation function) 
		 * 
		 * Note that if you train with gold frameId, then onlyHeads == gold, and
		 * these problems go away.
		 * 
		 * TODO This needs to be re-written. It was written thinking that I was
		 * doing argId rather than argExpansion. The general notion that is
		 * described is "is there a viable theory in the input so far that
		 * matches the gold label?". In the argId case, the "viable theory" is a
		 * FrameInstance (target). In the case of argExpansion, the "viable
		 * theory" is (it=FrameInstance,k=role,j=head). To check whether it
		 * matches the gold label requires checking if there exists and span for
		 * itk and if j is in that span.
		 */
		public RoleSpanStageDatum(FNParse onlyHeads, FNParse gold) {
			this.gold = gold;
			this.onlyHeads = onlyHeads;
			this.expansions = new ArrayList<>();

			// Build a map of the correct expansions for all arguments that are
			// present in the gold parse.
			Map<FrameRoleInstance, Span> goldSpans = new HashMap<>();
			if (gold != null) {
				for (FrameInstance fi : gold.getFrameInstances()) {
					int K = fi.getFrame().numRoles();
					for (int k = 0; k < K; k++) {
						Span arg = fi.getArgument(k);
						if (arg == Span.nullSpan) continue;
						FrameRoleInstance fri = new FrameRoleInstance(
								fi.getFrame(), fi.getTarget(), k);
						Span old = goldSpans.put(fri, arg);
						assert old == null;
					}
				}
			}

			// Go over all the arguments in onlyHeads and roll out expansion
			// variables for them.
			HeadFinder hf = SemaforicHeadFinder.getInstance();
			for (int fiIdx = 0; fiIdx < onlyHeads.numFrameInstances(); fiIdx++) {
				FrameInstance fi = onlyHeads.getFrameInstance(fiIdx);
				LOG.debug("roles for " + Describe.frameInstance(fi));
				Frame f = fi.getFrame();
				Span t = fi.getTarget();
				int ti = hf.head(t, fi.getSentence());
				int K = fi.getFrame().numRoles();
				for (int k = 0; k < K; k++) {
					Span arg = fi.getArgument(k);
					if (arg == Span.nullSpan) continue;
					assert arg.width() == 1;	// head only, we're expanding
					int j = arg.start;
					Span goldArg = goldSpans.get(new FrameRoleInstance(f, t, k));
					// Here, unlike argId, we have no way to "recover" from a
					// bad prediction made earlier in the pipeline. At this
					// point, we are assuming that there is an argument, and the
					// only question is how wide is the constituent (i.e. you
					// can't "expand" an argument out of existence like you).
					// So, if we can't find a viable theory (i.e. argument head)
					// to expand upon, then we should not train on it at all.
					// If we're in prediction mode then this doesn't matter and
					// we're going to roll out the variables anyway.
					boolean makeExpansionVar =
							(gold == null)
							|| (goldArg != null && goldArg.includes(j));
					if (!makeExpansionVar) continue;
					addExpansionVar(ti, fiIdx, j, k, goldArg);
				}
			}
		}

		private void addExpansionVar(
				int i,
				int fiIdx,
				int j,
				int k,
				Span goldSpan) {
			// Make sure expanding right/left wouldn't overlap the target
			int maxLeft = maxArgRoleExpandLeft;
			int maxRight = maxArgRoleExpandRight;
			if (j > i && j - maxArgRoleExpandLeft >= i)
				maxLeft = j - i;
			if (j < i && j + maxArgRoleExpandRight > i)
				maxRight = i - j;
			int n = this.onlyHeads.getSentence().size();
			Expansion.Iter ei = new Expansion.Iter(j, n, maxLeft, maxRight);
			int goldExpIdx = -1;
			if (goldSpan != null) {
				Expansion goldExp = Expansion.headToSpan(j, goldSpan);
				goldExpIdx = ei.indexOf(goldExp);
				if (goldExpIdx < 0) return;
			}
			ExpansionVar ev = new ExpansionVar(
					i, fiIdx, j, k, this.onlyHeads, ei, goldExpIdx);
			this.expansions.add(ev);
		}

		@Override
		public FNParse getInput() { return onlyHeads; }

		@Override
		public boolean hasGold() { return gold != null; }

		@Override
		public FNParse getGold() {
			assert hasGold();
			return gold;
		}

		public Sentence getSentence() { return onlyHeads.getSentence(); }

		@Override
		public LabeledFgExample getExample() {
			FactorGraph fg = this.getFactorGraph();
			VarConfig goldConf = new VarConfig();
			for(ExpansionVar ev : this.expansions) {
				assert ev.hasGold();
				ev.addToGoldConfig(goldConf);
			}
			return new LabeledFgExample(fg, goldConf);
		}

		private FactorGraph getFactorGraph() {
			FactorGraph fg = new FactorGraph();
			ProjDepTreeFactor depTree = null;
			ConstituencyTreeFactor consTree = null;
			if (useLatentConstituencies) {
				consTree = new ConstituencyTreeFactor(
						getSentence().size(), VarType.LATENT);
				fg.addFactor(consTree);
			}
			for (Factor f : initFactorsFor(getSentence(), expansions, depTree, consTree))
				fg.addFactor(f);
			return fg;
		}

		@Override
		public Decodable<FNParse> getDecodable() {
			FgInferencerFactory infFact = infFactory();
			return new RoleSpanDecodable(
					getFactorGraph(), infFact, onlyHeads, expansions);
		}
	}

	class RoleSpanDecodable extends Decodable<FNParse> {
		// Indexing for these is the same as the loop order in which you would
		// see non-null roles.
		private FNParse onlyHeads;
		private List<ExpansionVar> vars;
		public RoleSpanDecodable(
				FactorGraph fg,
				FgInferencerFactory infFact,
				FNParse onlyHeads,
				List<ExpansionVar> vars) {
			super(fg, infFact);
			this.onlyHeads = onlyHeads;
			this.vars = vars;
		}
		@Override
		public FNParse decode() {
			// Run inference
			FgInferencer margins = this.getMargins();

			// Clone the FrameInstances
			List<FrameInstance> fis = new ArrayList<>();
			for(FrameInstance fi : onlyHeads.getFrameInstances())
				fis.add(fi.clone());

			// Update arguments with full spans
			for(ExpansionVar ev : this.vars) {
				Span s = ev.decodeSpan(margins);
				fis.get(ev.fiIdx).setArgument(ev.getRole(), s);
			}

			return new FNParse(onlyHeads.getSentence(), fis);
		}
    @Override
    public FgModel getWeights() {
      return RoleHeadToSpanStage.this.getWeights();
    }
    @Override
    public void setWeights(FgModel weights) {
      throw new UnsupportedOperationException();
    }
    @Override
    public boolean logDomain() {
      return RoleHeadToSpanStage.this.logDomain();
    }
	}
}
