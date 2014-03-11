package edu.jhu.hlt.fnparse.util;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.fnparse.data.DataUtil;
import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.inference.newstuff.FrameVar;
import edu.jhu.hlt.fnparse.inference.newstuff.Parser;
import edu.jhu.hlt.fnparse.inference.newstuff.ParsingSentence;
import edu.jhu.hlt.fnparse.inference.newstuff.RoleVars;

/**
 * keeps statistics about ParsingSentences so that I can figure out what's slow.
 * @author travis
 */
public class ParsingSentenceStats {

	public long totalFrameVars;
	public long totalRoleVars;
	public int numSent;
	
	// for computing order statistics on variable domains
	public List<Integer> frameVarDomainSizes = new ArrayList<Integer>();
	public List<Integer> roleVarDomainSizes = new ArrayList<Integer>();
	
	public void accum(ParsingSentence s) {
		
		if(true) return;	// TODO debug this later
		
		int nf = 0, nr = 0;
		int n = s.sentence.size();
		for(int i=0; i<n; i++) {
			FrameVar f_i = s.frameVars[i];
			if(f_i == null) {
				//assert s.roleVars[i] == null;
				continue;
			}
			nf++;
			frameVarDomainSizes.add(f_i.getFrames().size());
			
			int K = f_i.getMaxRoles();
			for(int j=0; j<n; j++) {
				for(int k=0; k<K; k++) {
					RoleVars r_ijk = s.roleVars[i][j][k];
					if(r_ijk == null) continue;
					nr++;
					roleVarDomainSizes.add(r_ijk.getPossibleFrames().size());
				}
			}
		}
		
		totalFrameVars += nf;
		totalRoleVars += nr;
		numSent++;
	}
	
	public void printStats(PrintStream ps) {
		ps.println("totalFramVars = " + totalFrameVars);
		ps.println("totalRoleVars = " + totalRoleVars);
		ps.printf ("frameVars/sent = %.2f\n", (totalFrameVars/((double)numSent)));
		ps.printf ("roleVars/frame = %.2f\n", (totalRoleVars/((double)totalFrameVars)));
		ps.printf ("roleVars/sent = %.2f\n", (totalRoleVars/((double)numSent)));
		ps.println("frameVarDomainOrderStatistics = " + orderStatistics(frameVarDomainSizes));
		ps.println("roleVarDomainOrderStatistics = " + orderStatistics(roleVarDomainSizes));
	}
	
	public String orderStatistics(List<Integer> items) {
		StringBuilder sb = new StringBuilder();
		
		if(true) return sb.toString();	// TODO debug this later
		
		Collections.sort(items);
		int n = items.size();
		for(double percentile : Arrays.asList(0.5, 0.9, 0.95, 0.99)) {
			int idx = (int)(percentile * n);
			int val = items.get(idx);
			sb.append(String.format("\t%d%%: %d", (int)(percentile*100), val));
		}
		sb.append("\tmax: " + items.get(items.size()-1));
		return sb.toString();
	}
	
	public static void main(String[] args) {
		MultiTimer timer = new MultiTimer();
		ParsingSentenceStats stats = new ParsingSentenceStats();
		Parser p = new Parser();
		
		timer.start("getData");
		List<FNParse> train = DataUtil.iter2list(FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences());
		timer.stop("getData");
		
		for(FNParse parse : train) {
			timer.start("getSentenceForTraining");
			stats.accum(p.getExampleForTraining(parse).get(0).cameFrom);
			timer.stop("getSentenceForTraining");
		}
		
		timer.print(System.out, "getData");
		timer.print(System.out, "getSentenceForTraining");
		
		stats.printStats(System.out);
	}
}
