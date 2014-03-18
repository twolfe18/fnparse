package edu.jhu.hlt.fnparse.experiment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.inference.newstuff.FrameVar;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.Mode;
import edu.jhu.hlt.fnparse.inference.newstuff.ParsingSentence;
import edu.jhu.hlt.fnparse.inference.pruning.ArgPruner.LexPruneMethod;
import edu.jhu.hlt.fnparse.util.Avg;
import edu.jhu.hlt.fnparse.util.MultiTimer;

/**
 * assuming we're using the target/frame triage as before, in the parser,
 * and we have the set of frame-targets (f_it) fixed, how many role variables
 * (r_{itjk}) can we prune?
 * 
 * @author travis
 */
public class ArgPruningEfficiencyExperiment {
	
	public static final boolean debug = false;	// for some reason EXACT and SYNSET get different pruning ratios, which is wrong
	
	public static void main(String[] args) {

		MultiTimer t = new MultiTimer();
		t.start("data");
		List<FNParse> examples = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		examples = DataUtil.reservoirSample(examples, 1500);
		t.stop("data");
		
		for(LexPruneMethod lexPrune : Arrays.asList(LexPruneMethod.NONE, LexPruneMethod.EXACT, LexPruneMethod.SYNSET)) {
			Parser parser = new Parser(Mode.JOINT_FRAME_ARG, true);
			boolean punc = lexPrune == LexPruneMethod.NONE;
			boolean determiners = lexPrune == LexPruneMethod.NONE;
			parser.params.argPruner.set(punc, determiners, lexPrune);

			Avg avgKeepRatio = new Avg();	// macro (tells you a little about the skew)
			Avg keepRatio = new Avg();		// micro (what we care about)
			Avg recallRatio = new Avg();
			Avg avgRecallRatio = new Avg();
			Avg roleVarsPerSent = new Avg();
			Avg fiPerSent = new Avg();
			Avg fiPerWord = new Avg();
			Avg rolesPerFrame = new Avg();
			
			t.start("lexPrune="+lexPrune);
			for(FNParse p : examples) {				
				ParsingSentence ps = new ParsingSentence(p.getSentence(), parser.params);
				ps.setGold(p, false);
				ps.setupRoleVars();
				countPruning(ps, avgKeepRatio, keepRatio, avgRecallRatio, recallRatio, roleVarsPerSent, fiPerSent, fiPerWord, rolesPerFrame);
			}
			t.stop("lexPrune="+lexPrune);
			
			System.out.println("using lex role pruning: " + lexPrune);
			System.out.printf("kept %.1f %%\n", 100d * keepRatio.average());
			System.out.printf("average sentence, kept %.1f %% (macro)\n", 100d * avgKeepRatio.average());
			System.out.printf("recall %.1f %%\n", 100d * recallRatio.average());
			System.out.printf("average sentence, recall %.1f %% (macro)\n", 100d * avgRecallRatio.average());
			System.out.printf("average #r_itjk per sentence %.1f\n", roleVarsPerSent.average());
			System.out.printf("average #f_it per sentence %.1f\n", fiPerSent.average());
			System.out.printf("average #f_it per word %.1f\n", fiPerWord.average());
			System.out.printf("average #roles per f_it %.1f\n", rolesPerFrame.average());
			System.out.println();
		}
		System.out.println(t);
	}
	
	/**
	 * So there is a problem...
	 * I haven't changed my variables yet, so I don't have a way to do "partial pruning".
	 * Right now, i have r_ijk that takes on F frame values, but i will be refactoring
	 * to have F * #r_ijk variables that are binary.
	 * Right now I can only prune an entire r_ijk, but in the future i can prune individual
	 * binary variables.
	 * 
	 * I'll handle this by counting as if we had done the refactor.
	 * I'll commandeer ParsingSentence.setupRoleVars to return the number of variables kept.
	 */
	private static void countPruning(ParsingSentence ps,
			Avg avgKeepRatio, Avg keepRatio, Avg avgRecallRatio, Avg recallRatio, Avg roleVarsPerSent,
			Avg fiPerSent, Avg fiPerWord, Avg rolesPerFrame) {
		
		Map<String, Integer> m = ps.setupRoleVars();
		int kept = m.get("kept");
		int possible = m.get("possible");
		double keptRatio = possible == 0 ? 1d : ((double) kept) / possible;
		
		int argsKept = m.get("argsKept");
		int argsPruned = m.get("argsPruned");
		int goldNumArgs = argsKept + argsPruned;
		double recall = goldNumArgs == 0 ? 1 : ((double) argsKept) / goldNumArgs;
		
		avgKeepRatio.accum(keptRatio);
		keepRatio.accum(keptRatio, possible);
		avgRecallRatio.accum(recall);
		recallRatio.accum(recall, goldNumArgs);
		roleVarsPerSent.accum(kept);
		
		int num_f_i = 0;
		int n = ps.sentence.size();
		for(int i=0; i<n; i++) {
			FrameVar fv = ps.frameVars[i];
			if(fv == null) continue;
			for(Frame f : fv.getFrames()) {
				num_f_i++;
				rolesPerFrame.accum(f.numRoles());
			}
		}
		fiPerSent.accum(num_f_i);
		fiPerWord.accum(((double) num_f_i) / n, n);
	}

//	private Set<Role> inGoldSentence(ParsingSentence ps) {
//		Set<Role> roles = new HashSet<Role>();
//		final int n = ps.sentence.size();
//		for(int i=0; i<n; i++) {
//			FrameVar fv = ps.frameVars[i];
//			if(fv == null) continue;
//			int K = fv.getMaxRoles();
//			for(int j=0; j<n; j++) {
//				for(int k=0; k<K; k++) {
//					RoleVars rv = ps.roleVars[i][j][k];
//					if(rv == null) continue;
//					rv.get
//				}
//			}
//		}
//	}
//	
//	static class Role {
//		public final Frame f;
//		public final int i, j, k;
//		public Role(Frame f, int i, int j, int k) {
//			this.f = f;
//			this.i = i;
//			this.j = j;
//			this.k = k;
//		}
//	}

}
