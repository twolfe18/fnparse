package edu.jhu.hlt.fnparse.experiment;

import java.util.List;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.Expansion;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;

/**
 * I want to set the "maxExpansionsLeft/Right" in RoleVars to be as small as possible.
 * This will plot a table of recall values for left/right pruning
 * @author travis
 *
 */
public class ExpansionPruningExperiment {

	public static void main(String[] args) {
		
		int totalArgs = 0;
		int[][] fitIn = new int[30][30];	// indexed as [leftExpansion][rightExpansion]
		
		HeadFinder hf = new SemaforicHeadFinder();
		List<FNParse> parses = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		for(FNParse p : parses) {
			Sentence sent = p.getSentence();
			for(FrameInstance fi : p.getFrameInstances()) {
				int K = fi.getFrame().numRoles();
				for(int k=0; k<K; k++) {
					Span s = fi.getArgument(k);
					if(s == Span.nullSpan) continue;
					int head = hf.head(s, sent);
					
					Expansion e = Expansion.headToSpan(head, s);
					for(int i=0; i<fitIn.length; i++) {
						for(int j=0; j<fitIn[i].length; j++) {
							if(e.getExpandLeft() <= i && e.getExpandRight() <= j)
								fitIn[i][j]++;
						}
					}
					totalArgs++;
				}
			}
		}
		
		int bestLeft = 0, bestRight = 0;
		double bestCost = 9999999d;
		for(int i=0; i<fitIn.length; i++) {
			System.out.printf("l=%d\t", i);
			for(int j=0; j<fitIn[i].length; j++) {
				double recall = ((double) fitIn[i][j]) / totalArgs;
				String r = String.format("%.1f", 100d * recall);
				System.out.printf(" %4s ", r);
				
				double c = cost(i, j, recall);
				if(c < bestCost) {
					bestCost = c;
					bestLeft = i;
					bestRight = j;
				}
			}
			System.out.println();
		}
		double bestRecall = (100d * fitIn[bestLeft][bestRight]) / totalArgs;
		System.out.printf("best expansion is (%d, %d) with a recall of %.1f\n", bestLeft, bestRight, bestRecall);
	}
	
	public static double cost(int expandLeft, int expandRight, double recall) {
		if(recall > 1d || recall < 0d) throw new RuntimeException();
		if(expandLeft < 0 || expandRight < 0) throw new RuntimeException();
		double recallLoss = 1100d * (1d - recall);
		double time = (expandLeft+1) * (expandRight+1);
		return time + recallLoss;
	}
}
