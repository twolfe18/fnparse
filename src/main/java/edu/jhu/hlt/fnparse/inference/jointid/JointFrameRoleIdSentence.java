package edu.jhu.hlt.fnparse.inference.jointid;

import edu.jhu.gm.inf.BeliefPropagation.FgInferencerFactory;
import edu.jhu.gm.model.FgModel;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.inference.Parser.ParserParams;
import edu.jhu.hlt.fnparse.inference.ParsingSentence;
import edu.jhu.hlt.fnparse.inference.frameid.FrameIdSentence;
import edu.jhu.hlt.fnparse.inference.frameid.FrameVars;

public class JointFrameRoleIdSentence extends ParsingSentence<FrameInstanceHypothesis, FNParse> {
	
	// TODO for pruning experiments, probably want to be able to keep stats of what
	// was kept and what was pruned.
	// TODO am I going to do everything here? e.g. have a FrameIdSentence and a ArgIdSentence
	// for doing the first stage of the cascade for joint inference?

	public JointFrameRoleIdSentence(Sentence s, ParserParams params) {
		super(s, params, params.factorsForJointId);
		throw new RuntimeException("implement me");
	}

	public JointFrameRoleIdSentence(Sentence s, ParserParams params, FNParse gold) {
		super(s, params, params.factorsForJointId, gold);

		FrameIdSentence frameId = new FrameIdSentence(s, params, gold);
		// we can get the possible frames from frameId,
		// BUT...
		// RoleIdSentence takes a FNTagging to know what frames to use
		// we will have multiple frames for the same span, so a vanilla FNTagging
		// probably wont allow that (its not really a tagging).
		// -> i could create singleton taggings for every possible frame?
		
		// at the end of the day, i'm supposed to stich these up into a FrameInstanceHypothesis
		// maybe i should be either
		// 1) removing that class or
		// 2) utilizing it more
		// the problem is that i want to keep all of the role var setup code in RoleIdSentence,
		// which currently doesn't live in FrameInstanceHypothesis
		
		// maybe i can make FrameInstanceHypothesis do the work of making singleton FNTaggings
		// and dispatching to FrameIdSentence/RoleIdSentence?
		
		
		// the code in RoleIdSentence would be hard to expose in a general way (to JointFrameRoleIdSentence or FrameInstanceHypothesis)
		

		// i think the real problem is that RoleIdSentence doesn't really care that its a FNTagging, only that it has some
		// frames to look at.
		// => create a super-class of FNTagging that doesn't require targets have 0 or 1 frames
		

		// ok now, assuming we do all of this, what does JointFrameRoleIdSentence use for its Hypothesis?
		// FrameIdSentence and RoleIdSentence will create the FrameVars and RoleVars needed, but something
		// needs to point to the correct ones
		// => this is FrameInstanceHypothesis
		// => but how does it collect these FrameVars and RoleVars?
		//    thats what this class should do.
		//    1) create a FrameIdSentence which creates all the RoleVars
		//    2) create a FrameLocs object (frameHyps.flatMap(frameVars => framevars.listByTarget))
		//    3) at the same time, package up these (frame-target, RoleVars) pairs into FrameInstanceHypotheses
		
		for(FrameVars fv : frameId.getPossibleFrames()) {
			FrameInstance goldFI = null;	// get from fiByTargetHead map
			this.hypotheses.add(new FrameInstanceHypothesis(goldFI, s, fv, params));
			
			
			//for(int tIdx=0; tIdx<fv.numFrames(); tIdx++) {
				
				// something's still bugging me:
				// why are we using a RoleIdSentence with just one FrameLocation?
				// all we want is one RoleVars that is setup
				
				// OH!
				// the reason we have RoleIdSentence around now is it's decoding code
				// but that can largely pushed down to RoleVars
				
				// wait, we shouldn't push the decode method from RoleIdSentence down into RoleVars because
				// the joint decode will work differently than the RoleIdSentence.decode!
				
				// if we don't want decode, can we just instantiate RoleVars here without much code?
				// YES
				// this should go in FrameInstanceHypothesis
			//}
			
		}
		
		
	}
	
	// TODO^C RoleIdSentence should take a List<FrameLocation> instead of an FNTagging
	// as a directive for what hypotheses to instantiate.
	// ^^^correction^^^^ we don't need to do this anymore, because we dont need to steal functionality from RoleIdSentence anymore
	static class FrameLocation {
		public Frame frame;
		public int targetHead;
		public FrameLocation(int i, Frame t) {
			this.frame = t;
			this.targetHead = i;
		}
	}

	@Override
	public FNParse decode(FgModel model, FgInferencerFactory infFactory) {
		
		// this will need to iterate over the full (f_it, r_itjk) table to choose the max configuration
		// i think this can be implemented by matt's BP code by asking for the factor beliefs for the
		// f_it ~ r_itjk binary factor
		
		throw new RuntimeException("implement me");
	}

}
