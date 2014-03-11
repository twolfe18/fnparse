package edu.jhu.hlt.fnparse.experiment;

import java.util.*;

import edu.jhu.hlt.fnparse.data.*;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.hlt.fnparse.inference.newstuff.*;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser.Mode;
import edu.mit.jwi.IRAMDictionary;

/**
 * what are some features that we can prune this (head, frame, role)?
 * @author travis
 */
public class ArgHeadPruning {

	public static final Set<String> pennPunctuationPosTags =
			new HashSet<String>(Arrays.asList(":", ".", "--", ",", "(", ")", "$", "``", "\""));
	
	public static void main(String[] args) {
		
		FileFrameInstanceProvider fip = FileFrameInstanceProvider.fn15trainFIP;
		List<FNParse> parses = DataUtil.iter2list(fip.getParsedSentences());
		
		Parser parser = new Parser(Mode.JOINT_FRAME_ARG, false);
		
		boolean useFNpos = false;
		IRAMDictionary dict = null;
		if(useFNpos)
			dict = parser.params.targetPruningData.getWordnetDict();
		
		int total = 0;
		int pruned = 0;
		int shouldntHavePruned = 0;
		int totalRoles = 0;
		for(FNParse p : parses) {
			Sentence s = p.getSentence();
			int n = s.size();
			total += n;
			
			boolean[] prnd = new boolean[n];
			for(int i=0; i<n; i++) {
				boolean prune = useFNpos
						? s.getFNStyleLU(i, dict).pos.startsWith(Sentence.fnStyleBadPOSstrPrefix)
						: s.getLU(i).pos.endsWith("DT") || pennPunctuationPosTags.contains(s.getPos(i));
				if(prune) {
					pruned++;
					prnd[i] = true;
				}
			}
			
			for(FrameInstance fi : p.getFrameInstances()) {
				int K = fi.numArguments();
				for(int k=0; k<K; k++) {
					Span arg = fi.getArgument(k);
					if(arg.width() == 1 && prnd[arg.start]) {
						shouldntHavePruned++;
					}
					if(arg != Span.nullSpan)
						totalRoles++;
				}
			}
		}
		
		System.out.printf("pruned tokens: %d / %d (%.2f %%), arguments that would have been unreachable: %d of %d (%.2f %%)\n",
				pruned, total, (100d*pruned)/total, shouldntHavePruned, totalRoles, (100d*shouldntHavePruned)/totalRoles);
	}
	
//	static class ArgPrunable {
//		int j;
//		int k;
//		Frame f;	
//		public ArgPrunable(int j, int k, Frame f) {
//			this.j = j;
//			this.k = k;
//			this.f = f;
//		}
//	}
//	
//	public double pruningScore(List<FNParse> parses, ArgPruner pruner, ParserParams params) {
//		FPR fpr = new FPR();
//		for(FNParse p : parses) {
//			Sentence s = p.getSentence();
//			int n = s.size();
//			
//			// get all the ArgPrunables from the parser
//			ParsingSentence ps = new ParsingSentence(s, params);
//			for(int i=0; i<n; i++) {
//				FrameVar fv = ps.frameVars[i];
//				if(fv == null) continue;
//			}
//			
//			// see which ones 1) were eliminated and 2) were eliminated and sholdn't have been
//			Set<ArgPrunable> needed = new HashSet<ArgPrunable>();
//			for(FrameInstance fi : p.getFrameInstances()) {
//				Frame f = fi.getFrame();
//				for(int k=0; k<f.numRoles(); k++) {
//					Span arg = fi.getArgument(k);
//					if(arg != Span.nullSpan) {
//						needed.add(new ArgPrunable(j, k, f));
//					}
//				}
//				for(int i=0; i<n; i++) {
//					if(pruner.prune(roleIdx, argHeadIdx, sentence))
//				}
//			}
//		}
//	}
//	
//	interface ArgPruner {
//		public boolean prune(int roleIdx, int argHeadIdx, Sentence sentence);
//	}
//	
//	static class NoArgPruner implements ArgPruner {
//		@Override
//		public boolean prune(int roleIdx, int argHeadIdx, Sentence sentence) {
//			return false;
//		}
//	}
//	
//	static class PosArgPruner implements ArgPruner {
//		
////		private Set<String> whitelist;
//		private Set<String> blacklist;
//		
//		public PosArgPruner(String addToBlacklist) {
////			whitelist = new HashSet<String>();
////			whitelist.add("NN");
////			whitelist.add("NNS");
////			whitelist.add("NNP");
////			whitelist.add("NNPS");
////			whitelist.add("RB");
////			whitelist.add("RBR");
////			whitelist.add("RBS");
//			blacklist = new HashSet<String>();
//			blacklist.add(addToBlacklist);
//		}
//		
//		@Override
//		public boolean prune(int roleIdx, int argHeadIdx, Sentence sentence) {
//			String pos = sentence.getPos(argHeadIdx);
//			if(blacklist.contains(pos)) return true;
//			return false;
//		}
//	}
}
