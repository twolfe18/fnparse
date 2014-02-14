package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.decode.MbrDecoder;
import edu.jhu.gm.decode.MbrDecoder.MbrDecoderPrm;
import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.ProjDepTreeFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.heads.BraindeadHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.ParserParams;

public class ParsingSentence {
	
	private static final Logger log = Logger.getLogger(ParsingSentence.class);

	// ==== VARIABLES ====
	private FrameVar[] frameVars;
	private RoleVars[][][] roleVars;	// indexed as [i][j][k], i=target head, j=arg head, k=frame role idx
	private DParseVars dParseVars;
	private ProjDepTreeFactor depTree;	// holds variables too
	
	// ==== FACTORS ====
	private List<FactorFactory> factorHolders;
	private List<Factor> factors;
	
	// ==== MISC ====
	private Sentence sentence;
	private FactorGraph fg;
	private VarConfig gold;
	private ParserParams params;
	private FrameFilteringStrategy frameFilterStrat;
	
	
	/**
	 * In training data:
	 * what do we do when we say that a target may evoke
	 * a frame in set F, but the true frame g \not\in F?
	 */
	static enum FrameFilteringStrategy {
		ALWAYS_INCLUDE_GOLD_FRAME,				// add g to F, might increase R at the cost of P
		USE_NULLFRAME_FOR_FILTERING_MISTAKES,	// set g = nullFrame, might increase P at the cost of R
	}
	
	// TODO: the code is not setup for ALWAYS_INCLUDE_GOLD_FRAME because
	// this requires seeing the gold label while constructing all the variables.
	
	// we want a factor that will connect frame heads with arg heads
	
	
	public ParsingSentence(Sentence s, ParserParams params) {
		
		this.params = params;
		this.factorHolders = params.factors;
		this.sentence = s;
		this.frameFilterStrat = FrameFilteringStrategy.USE_NULLFRAME_FOR_FILTERING_MISTAKES;
		
		int n = s.size();
		
		frameVars = new FrameVar[n];
		for(int i=0; i<n; i++)
			frameVars[i] = makeFrameVar(s, i, params.logDomain);
		
		roleVars = new RoleVars[n][][];
		for(int i=0; i<n; i++) {
			if(frameVars[i] == null)
				continue;
			int maxRoles = frameVars[i].getMaxRoles();
			roleVars[i] = new RoleVars[n][maxRoles];
			for(int j=0; j<n; j++)
				for(int k=0; k<maxRoles; k++)
					roleVars[i][j][k] = new RoleVars(frameVars[i], s, j, k, params.logDomain);	
		}

		depTree = new ProjDepTreeFactor(n, VarType.LATENT);
		
		// initialize all the factors
		factors = new ArrayList<Factor>();
		factors.add(depTree);
		for(FactorFactory ff : factorHolders)
			factors.addAll(ff.initFactorsFor(sentence, frameVars, roleVars, depTree));
	}
	
	/**
	 * MBR decode may not be self-consistent...
	 * 
	 * here is how we should do it:
	 * 1. frames = \arg\max p(frames | x) = \sum_{args} p(frame, args | x)
	 * 2. args = \arg\max p(args | frames, x)
	 */
	public FNParse decode(FgModel model) {
		
		// first decode to find the best frames (marginalizing out role vars)
		MbrDecoderPrm prm = new MbrDecoderPrm();
		MbrDecoder decoder = new MbrDecoder(prm);
		FgExample fge1 = this.getFgExample();
		decoder.decode(model, fge1);
		VarConfig mbr1Conf = decoder.getMbrVarConfig();
		Map<Var, DenseFactor> margins1 = decoder.getVarMarginalsIndexed();
		
		List<Integer> dFrameIdx = new ArrayList<Integer>();
		List<Frame> dFrame = new ArrayList<Frame>();
		List<Span> dFrameTarget = new ArrayList<Span>();
		
		VarConfig clampedFrames = new VarConfig();
		List<FrameInstance> frameInstances = new ArrayList<FrameInstance>();
		int n = frameVars.length;
		for(int i=0; i<n; i++) {
			FrameVar fv = frameVars[i];
			if(fv == null) continue;
			Frame f_i = fv.getFrame(mbr1Conf);
			if(f_i == Frame.nullFrame) {
				assert frameVars[i].getExpansion(mbr1Conf).equals(Expansion.noExpansion);
				continue;
			}
			Span target = fv.getTarget(mbr1Conf);
			
			clampedFrames.put(fv.getFrameVar(), mbr1Conf.getState(fv.getFrameVar()));
			clampedFrames.put(fv.getExpansionVar(), mbr1Conf.getState(fv.getExpansionVar()));
			clampedFrames.put(fv.getPrototypeVar(), mbr1Conf.getState(fv.getPrototypeVar()));
			
			dFrameIdx.add(i);
			dFrame.add(f_i);
			dFrameTarget.add(target);
		}
		
		// decode with frames clamped
		FactorGraph fgWithClampedFrames = fge1.getOriginalFactorGraph().getClamped(clampedFrames);
		FgExample fge2 = new FgExample(fgWithClampedFrames, fge1.getGoldConfig());
		decoder.decode(model, fge2);
		VarConfig mbr2Conf = decoder.getMbrVarConfig();
		Map<Var, DenseFactor> margins2 = decoder.getVarMarginalsIndexed();
		for(int ii=0; ii<dFrameIdx.size(); ii++) {
			int i = dFrameIdx.get(ii);
			Frame f_i = dFrame.get(ii);
			Span target = dFrameTarget.get(ii);
			int K = f_i.numRoles();
			Span[] args = new Span[K];
			Arrays.fill(args, Span.nullSpan);
			for(int k=0; k<K; k++) {	// find the best span for every role
				Span bestSpan = null;
				double bestSpanScore = -9999d;
				for(int j=0; j<n; j++) {
					RoleVars rv = roleVars[i][j][k];
					boolean active = rv.getRoleActive(mbr2Conf);
					Span s = rv.getSpan(mbr2Conf);
					DenseFactor mR = margins2.get(rv.getRoleVar());
					DenseFactor mE = margins2.get(rv.getExpansionVar());
					double mRv = mR.getValue(mbr2Conf.getState(rv.getRoleVar()));
					double mEv = mE.getValue(mbr2Conf.getState(rv.getExpansionVar()));
					double score = params.logDomain ? mRv + mEv : mRv * mEv;
					if(active && (score > bestSpanScore || bestSpan == null)) {
						bestSpan = s;
						bestSpanScore = score;
					}
				}
				if(bestSpan != null)
					args[k] = bestSpan;
			}
			frameInstances.add(FrameInstance.newFrameInstance(f_i, target, args, sentence));
		}
		
		return new FNParse(sentence, frameInstances, true);
	}
	
	public void setGold(FNParse p) {
		
		if(p.getSentence() != sentence)
			throw new IllegalArgumentException();
		
		if(frameFilterStrat != FrameFilteringStrategy.USE_NULLFRAME_FOR_FILTERING_MISTAKES)
			throw new UnsupportedOperationException("implement me");
		
		// set all the labels to nullFrame/nullSpan, and then overwrite those that aren't null
		int n = p.getSentence().size();
		for(int i=0; i<n; i++) {
			if(frameVars[i] == null)
				continue;
			FrameInstance fiNull = FrameVar.nullFrameInstance(sentence, i);
			frameVars[i].setGold(fiNull);
			
			for(int j=0; j<n; j++) {
				int K = roleVars[i][j].length; 
				for(int k=0; k<K; k++)
					roleVars[i][j][k].setGoldIsNull();
			}
		}

		// set the non-nullFrame vars to their correct Frame (and target Span) values
		HeadFinder hf = new BraindeadHeadFinder();	// TODO
		for(FrameInstance fi : p.getFrameInstances()) {
			
			int head = hf.head(fi.getTarget(), p.getSentence());
			if(frameVars[head] == null) {
				System.err.println("[setGold] now you really screwed up: " + sentence.getLU(head) + " has a non-nullFrame label");
				continue;
			}
			frameVars[head].setGold(fi);
			
			// set role variables that were instantiated
			int i = head;
			int K = fi.getFrame().numRoles();
			for(int k=0; k<K; k++) {
				Span argSpan = fi.getArgument(k);
				if(argSpan == Span.nullSpan)
					continue;	// we've already set the relevant vars to nullSpan
				int j = hf.head(argSpan, sentence);
				if(k < roleVars[i][j].length)
					roleVars[i][j][k].setGold(argSpan);
				else {
					System.err.printf("we thought there was a max of %d roles for frames evoked by %s, but the true frame %s has %d roles\n",
							roleVars[i][j].length, sentence.getLU(i), fi.getFrame(), fi.getFrame().numRoles());
				}
			}
		}
	}
	
	
	/**
	 * Given a word in a sentence, extract the set of frames it might evoke.
	 * Basic idea: given a target with head word t, include any frame f s.t.
	 * lemma(t) == lemma(f.target)
	 */
	public FrameVar makeFrameVar(Sentence s, int headIdx, boolean logDomain) {
		LexicalUnit head = s.getLU(headIdx);
		
		List<Frame> frameMatches = new ArrayList<Frame>();
		List<FrameInstance> prototypes = new ArrayList<FrameInstance>();
		
		frameMatches.add(Frame.nullFrame);
		prototypes.add(FrameInstance.nullPrototype);
		
		for(Frame f : params.frameIndex.allFrames()) {
			
			// check if this matches a lexical unit for this frame
			for(int i = 0; i < f.numLexicalUnits(); i++) {
				if(LexicalUnit.approxMatch(head, f.getLexicalUnit(i))) {
					frameMatches.add(f);
					prototypes.addAll(params.prototypes.get(f));
					break;
				}
			}
		}
		
		log.trace("[makeFrameVar] head=" + s.getLU(headIdx));
		for(Frame f : frameMatches)
			log.trace("[makeFrameVar] frame=" + f + ", prototypes=" + params.prototypes.get(f));
		
		if(frameMatches.size() == 1) {
			//System.err.println("[makeFrameVars] WARNING: no frames available for " + s.getLU(headIdx));
			return null;
		}
		else return new FrameVar(s, headIdx, prototypes, frameMatches, logDomain);
	}
	
	public FactorGraph getFactorGraph() { return fg; }
	
	public VarConfig getGoldLabels() {
		if(gold.size() == 0)
			throw new RuntimeException();
		return gold;
	}
	
	public FgExample getFgExample() {
		
		int n = this.sentence.size();
		this.fg = new FactorGraph();
		this.gold = new VarConfig();
		
		// register all the variables and factors
		for(int i=0; i<n; i++) {
			if(frameVars[i] ==  null)
				continue;
			frameVars[i].register(fg, gold);
		}
		for(int i=0; i<n; i++) {
			if(frameVars[i] == null)
				continue;
			for(int j=0; j<n; j++)
				for(int k=0; k<roleVars[i][j].length; k++)
					roleVars[i][j][k].register(fg, gold);
		}
		
		for(Factor f : factors)
			fg.addFactor(f);
		
		System.out.printf("[NewParsingSentence getFgExample] there are %d variables and %d factors, returning example\n",
				fg.getNumVars(), fg.getNumFactors());
		
		return new FgExample(fg, gold);
	}
	
	public List<Factor> getFactorsFromFactories() { return factors; }
}

