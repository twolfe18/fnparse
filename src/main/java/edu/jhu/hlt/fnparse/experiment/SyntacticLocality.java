package edu.jhu.hlt.fnparse.experiment;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;

/**
 * 
 * In Naradowsky et al. http://maroo.cs.umass.edu/getpdf.php?id=1072 ,
 * they do not use path features for SRL; presumably because all of the
 * roles are syntactically local to their predicates. This may not be
 * true for Framenet parsing. The purpose of this experiment is to see
 * what proportion of argument (heads) are not neighbors (parent or child)
 * of the frame trigger.
 * 
 * @author travis
 */
public class SyntacticLocality {

	public static void main(String[] args) {
		
		int numArgs = 0;
		int numLocalArgs = 0;
		
		FileFrameInstanceProvider fip = FileFrameInstanceProvider.fn15trainFIP;
		for(FNParse parse : fip) {
			Sentence s = parse.getSentence();
			for(FrameInstance fi : parse.getFrameInstances()) {
				Span t = fi.getTarget();
				for(int k=0; k<fi.numArguments(); k++) {
					Span a = fi.getArgument(k);
					if(a == Span.nullSpan) continue;
					
					// is there a link in indices(t) -x-> indices(a)?
					boolean local = false;
					for(int ai=a.start; ai<a.end && !local; ai++)
						local |= t.includes(s.governor(ai));
					
					numArgs++;
					if(local) numLocalArgs++;
				}
			}
		}
		
		System.out.printf("%d of %d (%.1f%%) arguments are local in %s\n",
				numLocalArgs, numArgs, (100d*numLocalArgs)/numArgs, fip.getName());
	}
}
