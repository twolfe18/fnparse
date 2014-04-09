package edu.jhu.hlt.fnparse.inference.roleid;

import java.util.ArrayList;
import java.util.List;

import edu.jhu.gm.data.LabeledFgExample;
import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.misc.ParsingSentence;
import edu.jhu.hlt.fnparse.inference.misc.Parser.ParserParams;

/**
 * given some frames (grounded with targets), find out where there args are.
 * can do latent syntax or not.
 * 
 * @author travis
 */
public class RoleIdSentence extends ParsingSentence<RoleVars, FNParse> {
	
	private List<RoleVars> roles;	// this stores frame info too

	public RoleIdSentence(Sentence s, FNTagging frames, ParserParams params) {
		super(s, params, params.factorsForRoleId);
		initRoleVars(frames);
	}

	public RoleIdSentence(Sentence s, FNTagging frames, ParserParams params, FNParse gold) {
		super(s, params, params.factorsForRoleId, gold);
		initRoleVars(frames);
	}
	
	private void initRoleVars(FNTagging frames) {
		roles = new ArrayList<RoleVars>();
		throw new RuntimeException("implement me");
	}

	@Override
	public FNParse decode(FgModel model, FgInferencerFactory infFactory) {
		throw new RuntimeException("implement me");
	}

	public LabeledFgExample getTrainingExamplelksdjflskd() {
		
		// 1) only make the frameVar's needed for the given targets
		// 2) this will give you FrameInstanceHypothesis, on which you can call setupRoles(VarType.Predicted)
		
		// need to make sure that the the Vars from the FrameVars *is not added to the FactorGraph*
		// don't ever instantiate a FrameInstanceHypothesis!
		// all you need is one RoleVars per FrameInstance given on construction
		
		
		throw new RuntimeException("implement me");
	}

	@Override
	protected void setGold(FNParse gold) {
		
		// find the roles realize for every FrameInstance in this.roles
		throw new RuntimeException("implement me");
	}

	/*
	private FNParse decodeArgs(FgModel model, FgInferencerFactory infFactory) {

		if(debugDecodePart2 && params.debug) {
			System.out.printf("[decode part2] fpPen=%.3f fnPen=%.3f\n",
					params.argDecoder.getFalsePosPenalty(), params.argDecoder.getFalseNegPenalty());
		}
		if(params.mode == Mode.FRAME_ID)
			throw new IllegalStateException();
		
		// now that we've clamped the f_i at our predictions,
		// there will be much fewer r_ijk to instantiate.
		setupRoleVars();

		FgExample fge = this.makeFgExample();
		fge.updateFgLatPred(model, params.logDomain);
		FgInferencer inf = infFactory.getInferencer(fge.getOriginalFactorGraph());
		inf.run();

		List<FrameInstance> fis = new ArrayList<FrameInstance>();
		final int n = sentence.size();
		for(int i=0; i<n; i++) {

			FrameVar fv = frameVars[i];
			if(fv == null) continue;
			Frame f = fv.getFrame(0);
			if(f == Frame.nullFrame) continue;

			Span[] args = new Span[f.numRoles()];
			Arrays.fill(args, Span.nullSpan);

			// for each role, choose the most sensible realization of this role
			for(int k=0; k<f.numRoles(); k++) {

				// we are going to decode every r_ijk separately (they're all binary variables for arg realized or not)
				// the only time you have a problem is when more than one r_ijk is decoded (given i,k ranging over j)
				// among these cases, choose the positive r_ijk with the smallest risk
				List<Integer> active = new ArrayList<Integer>();
				List<Double> risks = new ArrayList<Double>();
				double[] riskBuf = new double[2];
				for(int j=0; j<n; j++) {
					RoleVars r_ijk = roleVars[i][j][k];
					if(r_ijk == null) continue;
					DenseFactor df = inf.getMarginals(r_ijk.getRoleVar());
					int nullIndex = 0;
					assert r_ijk.getFrame(nullIndex) == Frame.nullFrame;
					int r_ijk_dec = params.argDecoder.decode(df.getValues(), nullIndex, riskBuf);
					assert r_ijk.getPossibleFrames().size() == 2;
					boolean argIsRealized = r_ijk_dec != nullIndex;
					if(argIsRealized) {
						active.add(j);
						risks.add(riskBuf[r_ijk_dec]);
					}
					if(debugDecodePart2 && params.debug) {
						System.out.printf("[decode part2] %s.%s = %s risks: %s\n",
								f.getName(), f.getRole(k), sentence.getLU(j), Arrays.toString(riskBuf));
					}
				}

				if(active.size() == 0)
					args[k] = Span.nullSpan;
				else  {
					int j = -1;
					if(active.size() == 1) {
						j = active.get(0);
						if(debugDecodePart2 && params.debug)
							System.out.println("[decode part2] unabiguous min risk: " + sentence.getLU(j).getFullString());
					}
					else {
						// have to choose which index has the lowest (marginal) risk
						if(debugDecodePart2 && params.debug) {
							System.out.printf("[decode part2] more than one token has min risk @ arg realized. indices=%s risks=%s\n",
									active, risks);
						}
						double minR = 0d;
						for(int ji=0; ji<active.size(); ji++) {
							double r = risks.get(ji);
							if(r < minR || j < 0) {
								minR = r;
								j = active.get(ji);
							}
						}
					}
					// choose the most likely expansion/span conditioned on the arg head index
					RoleVars r_ijk = roleVars[i][j][k];
					DenseFactor df = inf.getMarginals(r_ijk.getExpansionVar());
					if(debugDecodePart2 && params.debug) {
						System.out.println("[decode part2] expansion marginals: " + Arrays.toString(df.getValues()));
					}
					int expansionConfig = df.getArgmaxConfigId();
					args[k] = r_ijk.getSpan(expansionConfig);
				}
			}
			fis.add(FrameInstance.newFrameInstance(f, Span.widthOne(i), args, sentence));
		}

		return new FNParse(sentence, fis);
	}
	*/
}
