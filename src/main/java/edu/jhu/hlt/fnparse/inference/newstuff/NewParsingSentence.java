package edu.jhu.hlt.fnparse.inference.newstuff;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.FGFNParser.CParseVars;
import edu.jhu.hlt.fnparse.inference.FGFNParser.DParseVars;
import edu.jhu.hlt.fnparse.inference.heads.BraindeadHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;

public class NewParsingSentence {

	// ==== VARIABLES ====
	private FrameVar[] frameVars;
	private RoleVars[][][] roleVars;	// indexed as [i][j][k], i=target head, j=arg head, k=frame role idx
	private CParseVars cParseVars;
	private DParseVars dParseVars;
	
	// ==== FACTORS ====
	private List<FactorFactory> factorHolders;
	
	// ==== MISC ====
	private Sentence sentence;
	private FactorGraph fg;
	private VarConfig gold;
	private FrameIndex frameIndex;
	
	public NewParsingSentence(Sentence s) {
		
		this.sentence = s;
		this.frameIndex = FrameIndex.getInstance();
		
		int n = s.size();
		
		frameVars = new FrameVar[n];
		for(int i=0; i<n; i++)
			frameVars[i] = makeFrameVar(s, i);
		
		roleVars = new RoleVars[n][n][];
		for(int i=0; i<n; i++) {
			int maxRoles = frameVars[i].getMaxRoles();
			for(int j=0; j<n; j++) {
				roleVars[i][j] = new RoleVars[maxRoles];
				for(int k=0; k<maxRoles; k++)
					roleVars[i][j][k] = new RoleVars(frameVars[i], s, j, k);	
			}
		}

		// make all the factors
		factorHolders = new ArrayList<FactorFactory>();
		factorHolders.add(new Factors.SimpleFrameFactors());
		factorHolders.add(new Factors.SimpleFrameRoleFactors());
		for(FactorFactory ff : factorHolders)
			for(int i=0; i<n; i++)
				for(int j=0; j<n; j++)
					for(int k=0; k<roleVars[i][j].length; k++)
						ff.initFactorsFor(frameVars[i], roleVars[i][j][k]);
		
		// register all the variables and factors
		this.fg = new FactorGraph();
		this.gold = new VarConfig();
		for(int i=0; i<n; i++)
			frameVars[i].register(fg, gold);
		for(int i=0; i<n; i++)
			for(int j=0; j<n; j++)
				for(int k=0; k<roleVars[i][j].length; k++)
					roleVars[i][j][k].register(fg, gold);
		for(FactorFactory ff : factorHolders)
			ff.register(fg, gold);
	}
	
	public void setGold(FNParse p) {
		
		if(p.getSentence() != sentence)
			throw new IllegalArgumentException();
		
		setGoldFrameVars(p);
		setGoldRoleVars(p);
		// TODO constituency and dependency parse variables
	}
	
	private void setGoldFrameVars(FNParse p) {
		// set all the labels to nullFrame, and then overwrite those that aren't nullFrame
		for(int i=0; i<p.getSentence().size(); i++) {
			FrameInstance fiNull = FrameVar.nullFrameInstance(sentence, i);
			frameVars[i].setGold(fiNull);
		}

		// set the non-nullFrame vars to their correct Frame (and target Span) values
		HeadFinder hf = new BraindeadHeadFinder();
		for(int i=0; i<p.numFrameInstances(); i++) {
			FrameInstance fi = p.getFrameInstance(i);
			int head = hf.head(fi.getTarget(), p.getSentence());
			frameVars[head].setGold(fi);
		}
	}
	
	private void setGoldRoleVars(FNParse p) {
		throw new RuntimeException("implement me");
	}
	
	/**
	 * Given a word in a sentence, extract the set of frames it might evoke.
	 * Basic idea: given a target with head word t, include any frame f s.t.
	 * lemma(t) == lemma(f.target)
	 */
	public FrameVar makeFrameVar(Sentence s, int headIdx) {
		LexicalUnit head = s.getLU(headIdx);
		
		List<Frame> frameMatches = new ArrayList<Frame>();
		List<LexicalUnit> luMatches = new ArrayList<LexicalUnit>();
		
		frameMatches.add(Frame.nullFrame);
		assert Frame.nullFrame.numLexicalUnits() == 0;
		
		for(Frame f : frameIndex.allFrames()) {
			
			// check if this matches a lexical unit for this frame
			boolean fm = false;
			for(int i = 0; i < f.numLexicalUnits(); i++) {
				if(LexicalUnit.approxMatch(head, f.getLexicalUnit(i))) {
					frameMatches.add(f);
					fm = true;
					break;
				}
			}
			
			if(fm) {	// collect lexical units
				for(int i=0; i<f.numLexicalUnits(); i++)
					luMatches.add(f.getLexicalUnit(i));
			}
		}
		return new FrameVar(s, headIdx, luMatches, frameMatches);
	}
	

	
}
