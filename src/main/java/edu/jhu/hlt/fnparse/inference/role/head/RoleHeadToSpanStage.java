package edu.jhu.hlt.fnparse.inference.role.head;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Level;
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
import edu.jhu.hlt.fnparse.features.Features;
import edu.jhu.hlt.fnparse.inference.BinaryVarUtil;
import edu.jhu.hlt.fnparse.inference.FactorFactory;
import edu.jhu.hlt.fnparse.inference.ParserParams;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures;
import edu.jhu.hlt.fnparse.inference.stages.AbstractStage;
import edu.jhu.hlt.fnparse.inference.stages.Stage;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.HasFeatureAlphabet;
import edu.jhu.hlt.fnparse.util.HasFgModel;
import edu.jhu.hlt.optimize.function.Regularizer;
import edu.jhu.hlt.optimize.functions.L2;

/**
 * Takes a FNParse where all of the arguments are headwords (spans of width 1)
 * and predicts a full span for each input argument.
 * 
 * @author travis
 */
public class RoleHeadToSpanStage
		extends AbstractStage<FNParse, FNParse>
		implements Stage<FNParse, FNParse>, Serializable {
	private static final long serialVersionUID = 1L;
	public static boolean SHOW_FEATURES = false;
	public static boolean DISALLOW_ARG_WITHOUT_CONSTITUENT = true;
	public static final Logger LOG = Logger.getLogger(RoleHeadToSpanStage.class);

	public static class Params implements Serializable {
		private static final long serialVersionUID = 1L;

		// if you check the pareto frontier in ExpansionPruningExperiment:
		// (6,0) gives 77.9 % recall
		// (6,3) gives 86.7 % recall
		// (8,3) gives 88.4 % recall
		// (8,4) gives 90.0 % recall
		// (10,5) gives 92.3 % recall
		// (12,5) gives 93.2 % recall
		public int maxArgRoleExpandLeft = 8;
		public int maxArgRoleExpandRight = 4;

		public int batchSize = 1;
		public int passes = 10;
		public Double learningRate = null;//0.05;    // null means auto-select
		public transient Regularizer regularizer = new L2(10_000_000d);

		public FactorFactory<ExpansionVar> factorTemplate;

		public Params(ParserParams params) {
			factorTemplate = new RoleSpanFactorFactory(params);
		}
	}

	public Params params;

	public RoleHeadToSpanStage(ParserParams globalParams, HasFeatureAlphabet featureNames) {
		super(globalParams, featureNames);
		params = new Params(globalParams);
	}

  @Override
  public void configure(java.util.Map<String,String> configuration) {
    String key = "regularizer." + getName();
    String reg = configuration.get(key);
    if (reg != null)
      params.regularizer = new L2(Double.parseDouble(reg));

    key = "batchSize." + getName();
    String bs = configuration.get(key);
    if (bs != null)
      params.batchSize = Integer.parseInt(bs);

    key = "passes." + getName();
    String passes = configuration.get(key);
    if (passes != null)
      params.passes = Integer.parseInt(passes);
  }

	@Override
	public Serializable getParamters() {
		return params;
	}

	@Override
	public void setPameters(Serializable params) {
		this.params = (Params) params;
	}

	@Override
	public Double getLearningRate() {
		return params.learningRate;
	}

	@Override
	public Regularizer getRegularizer() {
		return params.regularizer;
	}

	@Override
	public int getBatchSize() {
		return params.batchSize;
	}

	@Override
	public int getNumTrainingPasses() {
		return params.passes;
	}

  @Override
  public void scanFeatures(List<FNParse> data) {
	  List<FNParse> onlyHeads = DataUtil.convertArgumenSpansToHeads(
	      data, globalParams.headFinder);
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
				data.add(new RoleSpanStageDatum(x, this));
			else
				data.add(new RoleSpanStageDatum(x, labels.get(i), this));
		}
		return new StageDatumExampleList<>(data);
	}

	/**
	 * Adds factors for this stage.
	 */
	public static class RoleSpanFactorFactory
			implements FactorFactory<ExpansionVar> {
		private static final long serialVersionUID = 1L;

		private TemplatedFeatures features;
		private final ParserParams params;

		public RoleSpanFactorFactory(ParserParams params) {
			this.params = params;
		}

		public TemplatedFeatures getTFeatures() {
		  if (features == null) {
		    features = new TemplatedFeatures("argSpans",
		        params.getFeatureTemplateDescription(),
		        params.getAlphabet());
		  }
		  return features;
		}

		@Override
		public List<Features> getFeatures() {
		  assert false : "remove this method";
			return Arrays.asList((Features) features);
		}

		@Override
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
			    if (params.useLatentConstituencies && s.width() > 1) {
			      cVar = c.getSpanVar(s.start, s.end - 1);
			      vs = new VarSet(sVar, cVar);
			    } else {
			      vs = new VarSet(sVar);
			    }
			    TemplatedFeatures feats = getTFeatures();
			    //ExplicitExpFamFactor phi = new ExplicitExpFamFactor(vs);
			    ExplicitExpFamFactorWithConstraint phi = new ExplicitExpFamFactorWithConstraint(vs, -1);
			    int n = vs.calcNumConfigs();
			    for (int i = 0; i < n; i++) {
			      VarConfig vc = vs.getVarConfig(i);
			      boolean arg = BinaryVarUtil.configToBool(vc.getState(sVar));
			      boolean cons = cVar != null && BinaryVarUtil.configToBool(vc.getState(cVar));
			      context.clear();
			      context.setStage(RoleHeadToSpanStage.class);
			      if (DISALLOW_ARG_WITHOUT_CONSTITUENT && arg && cVar != null && !cons) {
			        phi.setBadConfig(i);
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
			      context.blankOutIllegalInfo(params);
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
	}

	private static class ExplicitExpFamFactorWithConstraint
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

	public static class RoleSpanStageDatum
			implements StageDatum<FNParse, FNParse> {
		public static final Logger LOG =
				Logger.getLogger(RoleSpanStageDatum.class);
		static { LOG.setLevel(Level.INFO); }

		private final List<ExpansionVar> expansions;
		private final FNParse onlyHeads;
		private final FNParse gold;
		private final RoleHeadToSpanStage parent;

		/** Constructor for when you don't have the labels. */
		public RoleSpanStageDatum(FNParse onlyHeads, RoleHeadToSpanStage rss) {
			this(onlyHeads, null, rss);
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
		public RoleSpanStageDatum(
				FNParse onlyHeads,
				FNParse gold,
				RoleHeadToSpanStage rss) {
			this.parent = rss;
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
			for (int fiIdx = 0; fiIdx < onlyHeads.numFrameInstances(); fiIdx++) {
				FrameInstance fi = onlyHeads.getFrameInstance(fiIdx);
				LOG.debug("roles for " + Describe.frameInstance(fi));
				Frame f = fi.getFrame();
				Span t = fi.getTarget();
				int ti = parent.getGlobalParams().headFinder.head(
						t, fi.getSentence());
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
			int maxLeft = parent.params.maxArgRoleExpandLeft;
			int maxRight = parent.params.maxArgRoleExpandRight;
			if (j > i && j - parent.params.maxArgRoleExpandLeft >= i)
				maxLeft = j - i;
			if (j < i && j + parent.params.maxArgRoleExpandRight > i)
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
			if (parent.getGlobalParams().useLatentConstituencies) {
				consTree = new ConstituencyTreeFactor(
						getSentence().size(), VarType.LATENT);
				fg.addFactor(consTree);
			}
			for (Factor f : parent.params.factorTemplate.initFactorsFor(
					getSentence(), expansions, depTree, consTree)) {
				fg.addFactor(f);
			}
			return fg;
		}

		@Override
		public Decodable<FNParse> getDecodable() {
			FgInferencerFactory infFact = parent.infFactory();
			return new RoleSpanDecodable(
					getFactorGraph(), infFact, parent, onlyHeads, expansions);
		}
	}

	public static class RoleSpanDecodable extends Decodable<FNParse> {

		// Indexing for these is the same as the loop order in which you would
		// see non-null roles.
		private FNParse onlyHeads;
		private List<ExpansionVar> vars;

		public RoleSpanDecodable(
				FactorGraph fg,
				FgInferencerFactory infFact,
				HasFgModel hasModel,
				FNParse onlyHeads,
				List<ExpansionVar> vars) {
			super(fg, infFact, hasModel);
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
	}
}
