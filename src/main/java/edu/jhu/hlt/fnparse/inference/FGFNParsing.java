package edu.jhu.hlt.fnparse.inference;

import java.util.*;

import edu.jhu.gm.data.FgExample;
import edu.jhu.gm.decode.MbrDecoder;
import edu.jhu.gm.decode.MbrDecoder.MbrDecoderPrm;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.FeExpFamFactor;
import edu.jhu.gm.model.FgModel;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.BasicFrameFeatures;
import edu.jhu.hlt.fnparse.features.BasicFrameRoleFeatures;
import edu.jhu.hlt.fnparse.inference.FGFNParser.CParseVars;
import edu.jhu.hlt.fnparse.inference.FGFNParser.DParseVars;
import edu.jhu.hlt.fnparse.inference.factors.FrameFactor;
import edu.jhu.hlt.fnparse.inference.factors.FrameRoleFactor;
import edu.jhu.hlt.fnparse.inference.spans.SingleWordSpanExtractor;
import edu.jhu.hlt.fnparse.inference.spans.SpanExtractor;
import edu.jhu.hlt.fnparse.inference.variables.DefaultFrameHypothesis;
import edu.jhu.hlt.fnparse.inference.variables.ExhaustiveRoleHypothesisFactory;
import edu.jhu.hlt.fnparse.inference.variables.FrameHypothesis;
import edu.jhu.hlt.fnparse.inference.variables.FrameHypothesisFactory;
import edu.jhu.hlt.fnparse.inference.variables.RoleHypothesis;
import edu.jhu.hlt.fnparse.inference.variables.RoleHypothesisFactory;
import edu.jhu.hlt.fnparse.inference.variables.SemaforicFrameHypothesisFactory;


/**
 * A class that holds all of the variables needed to do parsing.
 * 
 * It is tempting to split these into separate classes for frame
 * and argument identification, but I don't think this is the wisest
 * thing to do because at some point we may want to have access to
 * all the variables at once.
 * 
 * The idea right now is that classes can sub-class this class and
 * add specific methods for populating these variables.
 * 
 * @author travis
 */
public abstract class FGFNParsing {
	
	public static class FGFNParsingParams {
		public FgModel model;
		
		public FrameFactor.Features frameFeats = new BasicFrameFeatures();
		public FrameFactor.FeatureExtractor frameFeatExtr = new FrameFactor.FeatureExtractor(frameFeats);
		
		public FrameRoleFactor.Features frameRoleFeatures = new BasicFrameRoleFeatures();
		public FrameRoleFactor.FeatureExtractor frameRoleFeatExtr = new FrameRoleFactor.FeatureExtractor(frameRoleFeatures);
		
		public SpanExtractor targetIdentifier = new SingleWordSpanExtractor();
		public FrameHypothesisFactory frameHypFactory = new SemaforicFrameHypothesisFactory();
		public RoleHypothesisFactory<CParseVars> roleHypFactory = new ExhaustiveRoleHypothesisFactory();
	}
	
	public FGFNParsingParams params;

	public List<FrameHypothesis> frameVars;
	public List<List<RoleHypothesis>> roleVars;	// first index corresponds to frameVars, second is (roleIdx x spans)
	public List<FeExpFamFactor> frameFactors;
	public List<FeExpFamFactor> frameRoleFactors;
	
	public Sentence sentence;
	public FactorGraph fg;
	public VarConfig goldConf;
	
	public DParseVars dParseVars;
	public CParseVars cParseVars;
	
	public FGFNParsing(FGFNParsingParams params) {
		this.params = params;
	}
	
	/**
	 * Extract possible target spans from the given sentence.
	 * If gold is not null, it will still do target span extraction as
	 * it would if it didn't have labels, but it will add these labels
	 * to the factor graph for learning.
	 */
	private void addFrameVars(Sentence s, FNTagging gold) {
		
		frameVars = new ArrayList<FrameHypothesis>();
		frameFactors = new ArrayList<FeExpFamFactor>();

		Map<Span, FrameInstance> goldFrames = gold == null ? null : gold.getFrameLocations();
		
		for(Span sp : params.targetIdentifier.computeSpans(s)) {
			FrameInstance f_i_lab = goldFrames == null ? null : goldFrames.get(sp);
			FrameHypothesis f_i = params.frameHypFactory.make(sp, f_i_lab, sentence);
			if(f_i.numPossibleFrames() == 1) {
				assert f_i.getPossibleFrame(0) == Frame.nullFrame;
				continue;
			}
			assert f_i.numPossibleFrames() > 1;
			FrameFactor ff_i = new FrameFactor(f_i, params.frameFeatExtr);
			addFrame(f_i, ff_i);
		}
	}
	protected void addFrameVars(Sentence s) { addFrameVars(s, null); }
	protected void addFrameVars(FNTagging gold) { addFrameVars(gold.getSentence(), gold); }
	
	

	/**
	 * Take the Frame targets given and create frame variables clamped at those labels.
	 * This is used for step 2 of piecewise training: training the argument id model.
	 */
	protected void addGoldFrameVars(FNTagging frames) {

		if(frames == null) throw new IllegalArgumentException();
		
		frameVars = new ArrayList<FrameHypothesis>();
		frameFactors = new ArrayList<FeExpFamFactor>();
		
		// only add variables for gold frames, clamped at the correct value
		for(FrameInstance fi : frames.getFrameInstances()) {
			List<Frame> possibleFrames = new ArrayList<Frame>();
			possibleFrames.add(fi.getFrame());	// equivalent to clamping
			FrameHypothesis fh = new DefaultFrameHypothesis(frames.getSentence(), possibleFrames, 0, fi, fi.getTarget());
			FrameFactor ff = new FrameFactor(fh, params.frameFeatExtr);
			addFrame(fh, ff);
		}
	}
	
	private void addFrame(FrameHypothesis f_i, FrameFactor ff_i) {
		frameVars.add(f_i);
		fg.addVar(f_i.getVar());
		frameFactors.add(ff_i);
		fg.addFactor(ff_i);
		Integer goldFrameIdx = f_i.getGoldFrameIndex();
		if(goldFrameIdx != null)
			goldConf.put(f_i.getVar(), goldFrameIdx);
	}
	
	
	/**
	 * stateful, uses the FrameHypothesis vars that have been populated in frameVars,
	 * so make sure you call addFrameVars() or addGoldFrameVars() before this.
	 */
	protected void addRoleVars() {
		roleVars = new ArrayList<List<RoleHypothesis>>();
		frameRoleFactors = new ArrayList<FeExpFamFactor>();
		for(int i=0; i<frameVars.size(); i++) {
			FrameHypothesis f_i = frameVars.get(i);

			List<RoleHypothesis> roleVars_i = new ArrayList<RoleHypothesis>();
			roleVars.add(roleVars_i);

			int maxRoles = f_i.maxRoles();	// how many roles are needed for the highest-arity Frame
			for(int k=0; k<maxRoles; k++) {
				for(RoleHypothesis r_ijk : params.roleHypFactory.make(f_i, k, sentence, cParseVars)) {

					roleVars_i.add(r_ijk);
					FrameRoleFactor fr = new FrameRoleFactor(f_i, r_ijk, params.frameRoleFeatExtr);
					frameRoleFactors.add(fr);
					fg.addFactor(fr);
					fg.addVar(r_ijk.getVar());
					RoleHypothesis.Label gold = r_ijk.getGoldLabel();
					if(gold != RoleHypothesis.Label.UNK)
						goldConf.put(r_ijk.getVar(), gold.getInt());
				}
			}
		}
	}
	
	protected void runInference() {
		MbrDecoderPrm decPrm = new MbrDecoderPrm();
		MbrDecoder dec = new MbrDecoder(decPrm);
		
		// for now, pass goldConf. will blow up?
		dec.decode(params.model, new FgExample(this.fg, this.goldConf));
		
		VarConfig conf = dec.getMbrVarConfig();
		Map<Var, Double> margins = dec.getVarMargMap();
		for(int i=0; i<frameVars.size(); i++) {
			
			// read the frame that is evoked
			FrameHypothesis f_i = frameVars.get(i);
			int f_i_value = conf.getState(f_i.getVar());
			Frame f_i_hyp = f_i.getPossibleFrame(f_i_value);
			if(f_i_hyp == Frame.nullFrame)
				continue;
			
			FrameInstance fi;
			if(roleVars != null) {
				int numRoles = f_i_hyp.numRoles();
				Span[] roles = new Span[numRoles];
				double[] confidences = new double[numRoles];	// initialized to 0
				for(RoleHypothesis r_ijk : roleVars.get(i)) {
					double r_ijk_prob = margins.get(r_ijk.getVar());
					int j = r_ijk.getRoleIdx();
					if(r_ijk_prob > confidences[j]) {
						roles[j] = r_ijk.getExtent();
						confidences[j] = r_ijk_prob;
					}
				}
				fi = FrameInstance.newFrameInstance(f_i_hyp, f_i.getTargetSpan(), roles, sentence);
			}
			else fi = FrameInstance.frameMention(f_i_hyp, f_i.getTargetSpan(), sentence);
		}
	}
	
	public List<Var> getAllVariables() {
		List<Var> vars = new ArrayList<Var>();
		for(FrameHypothesis f : frameVars)
			vars.add(f.getVar());
		for(List<RoleHypothesis> lr : roleVars)
			for(RoleHypothesis r : lr)
				vars.add(r.getVar());
		if(dParseVars != null)
			for(Var v : dParseVars.getAllVariables())
				vars.add(v);
		if(cParseVars != null)
			for(Var v : cParseVars.getAllVariables())
				vars.add(v);
		return vars;
	}
	
	public void printStatus() {
		System.out.printf("FGFNParsing of size %d\n", sentence.size());
		double totalFramesPossible = 0d;
		for(FrameHypothesis fh : frameVars)
			totalFramesPossible += fh.numPossibleFrames();
		System.out.printf("there are %d frame vars, with an average of %.1f frames/target and %.1f targets/word\n",
				frameVars.size(), totalFramesPossible/frameVars.size(), ((double)frameVars.size())/sentence.size());
		int totalRoleVars = 0;
		int X = 0;
		for(int i=0; i<frameVars.size(); i++) {
			List<RoleHypothesis> lrh = roleVars.get(i);
			int numRoles = 0;
			for(RoleHypothesis rh : lrh) {	// spans X roles
				if(rh.getRoleIdx() > numRoles)
					numRoles = rh.getRoleIdx();
			}
			X += numRoles+1;	// +1 to go from 0-indexing to counts
			totalRoleVars += lrh.size();
		}
		double spansPerRole = totalRoleVars / X;
		System.out.printf("there are %d role-span vars, with an average of %.1f roles-spans/frame and %.1f spans/frame-role\n",
				totalRoleVars, ((double)totalRoleVars) / roleVars.size(), spansPerRole);
		System.out.printf("n*(n-1)/2 = %d\n", sentence.size() * (sentence.size()-1) / 2);
	}
	
	
	public static class JointParsing extends FGFNParsing {
		
		public JointParsing(FGFNParsingParams params) {
			super(params);
		}
		
		public FNParse parse(Sentence s) {
			addFrameVars(s);
			addRoleVars();
			runInference();
			// TODO extract marginals and decode
			throw new RuntimeException("implement me");
		}
		
		public FgExample getTrainingInstance(FNParse parsed) {
			addFrameVars(parsed);
			addRoleVars();
			return new FgExample(fg, goldConf);
		}
	}
	
	public static class FrameTagging extends FGFNParsing {
		
		public FrameTagging(FGFNParsingParams params) {
			super(params);
		}
		
		public FNTagging getFrames(Sentence s) {
			addFrameVars(s);
			runInference();
			// TODO extract marginals and decode
			throw new RuntimeException("implement me");
		}
		
		public FgExample getTrainingInstance(FNParse parsed) {
			addFrameVars(parsed);
			return new FgExample(fg, goldConf);
		}
	}
	
	public static class ArgumentTagging extends FGFNParsing {
		
		public ArgumentTagging(FGFNParsingParams params) {
			super(params);
		}
		
		public FNParse getArguments(FNTagging s) {
			addFrameVars(s);
			addRoleVars();
			runInference();
			// TODO extract marginals and decode
			throw new RuntimeException("implement me");
		}
		
		public FgExample getTrainingInstance(FNParse parsed) {
			addGoldFrameVars(parsed);
			addRoleVars();
			return new FgExample(fg, goldConf);
		}
	}
	
}



