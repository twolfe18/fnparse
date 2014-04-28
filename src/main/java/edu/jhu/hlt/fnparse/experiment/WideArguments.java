package edu.jhu.hlt.fnparse.experiment;

import java.util.Iterator;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;

/**
 * I'm trying to decide if using just headwords to predict arguments/roles is reasonable,
 * or if I should be doing a flat prediction over (role,span) items. To inform this decision,
 * I want to look at the wider argument spans, were the headword may not well describe the
 * argument and see how many there are.
 * 
 * TODO fix the headfinder, it is garbage
 * 
 * @author travis
 */
public class WideArguments {
	
	public static void main(String[] args) {
		int total = 0, wide = 0;
		HeadFinder hf = new SemaforicHeadFinder();
		Iterator<FNParse> iter = FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences();
		while(iter.hasNext()) {
			FNParse p = iter.next();
			Sentence sent = p.getSentence();
			for(FrameInstance fi : p.getFrameInstances()) {
				int K = fi.getFrame().numRoles();
				for(int k=0; k<K; k++) {
					Span s = fi.getArgument(k);
					if(s == Span.nullSpan) continue;
					total++;
					if(s.width() < 4) continue;
					wide++;
					int head = hf.head(s, sent);
					System.out.printf("%-15s %d\t%-18s %-12s %s\n", sent.getId(), s.width(), fi.getFrame().getRole(k), sent.getWord(head), sent.wordsIn(s));
				}
			}
		}
		System.out.println("total = " + total + ", wide = " + wide);
	}

}
