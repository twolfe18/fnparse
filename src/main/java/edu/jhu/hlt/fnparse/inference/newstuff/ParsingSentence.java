package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.decode.MbrDecoder;
import edu.jhu.gm.decode.MbrDecoder.MbrDecoderPrm;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.data.FrameInstanceProviderTest;
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

	// ==== VARIABLES ====
	private FrameVar[] frameVars;
	private RoleVars[][][] roleVars;	// indexed as [i][j][k], i=target head, j=arg head, k=frame role idx
	private CParseVars cParseVars;
	private DParseVars dParseVars;
	
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

		// initialize all the factors
		factors = new ArrayList<Factor>();
		for(FactorFactory ff : factorHolders)
			factors.addAll(ff.initFactorsFor(sentence, frameVars, roleVars));
	}
	
	/**
	 * MBR decode may not be self-consistent...
	 * 
	 * here is how we should do it:
	 * 1. frames = \arg\max p(frames | x) = \sum_{args} p(frame, args | x)
	 * 2. args = \arg\max p(args | frames, x)
	 */
	public FNParse decode(FgModel model) {
		
		// Matt's code doesn't like it when you give it an FgExample
		// with an empty VarConfig for gold (even if it is a test example).
		// This just adds some label, which is the default and wrong.
		int n = frameVars.length;
//		for(int i=0; i<n; i++) {
//			if(frameVars[i] == null)
//				continue;
//			for(int j=0; j<n; j++)
//				for(int k=0; k<roleVars[i][j].length; k++)
//					roleVars[i][j][k].register(fg, gold);
//		}
		
		MbrDecoderPrm prm = new MbrDecoderPrm();
		MbrDecoder decoder = new MbrDecoder(prm);
		decoder.decode(model, this.getFgExample());
		VarConfig conf = decoder.getMbrVarConfig();
		
		// TODO push this code into MbrDecoder
		Map<Var, DenseFactor> margins = new HashMap<Var, DenseFactor>();
		for(DenseFactor df : decoder.getVarMarginals()) {
			assert df.getVars().size() == 1;
			Var v = df.getVars().get(0);
			margins.put(v, df);
		}
		
		List<FrameInstance> frameInstances = new ArrayList<FrameInstance>();
		for(int i=0; i<n; i++) {
			if(frameVars[i] == null) continue;
			Frame f_i = frameVars[i].getFrame(conf);
			if(f_i == Frame.nullFrame) {
				assert frameVars[i].getExpansion(conf).equals(Expansion.noExpansion);
				continue;
			}
			Span target = frameVars[i].getTarget(conf);
			
			// TODO before choose args, I should really clamp the frames and re-run BP
			int K = f_i.numRoles();
			Span[] args = new Span[K];
			Arrays.fill(args, Span.nullSpan);
			for(int k=0; k<K; k++) {	// find the best span for every role
				Span bestSpan = null;
				double bestSpanScore = 0d;
				for(int j=0; j<n; j++) {
					RoleVars rv = roleVars[i][j][k];
					Span s = rv.getSpan(conf);
					DenseFactor mR = margins.get(rv.getRoleVar());
					DenseFactor mE = margins.get(rv.getExpansionVar());
					double mRv = mR.getValue(conf.getState(rv.getRoleVar()));
					double mEv = mE.getValue(conf.getState(rv.getExpansionVar()));
					double score = params.logDomain ? mRv + mEv : mRv * mEv;
					if(score > bestSpanScore || j == 0) {
						bestSpan = s;
						bestSpanScore = score;
					}
				}
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

