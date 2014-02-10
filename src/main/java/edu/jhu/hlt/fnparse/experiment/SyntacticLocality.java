package edu.jhu.hlt.fnparse.experiment;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.util.Describe;

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
		
		boolean verbose = true;
		
		long start = System.currentTimeMillis();
		int numArgs = 0;
		int numLocalArgs = 0;
		int numArgsChildren = 0;	// of target
		int numArgsParent = 0;		// of target
		int numArgsSelf = 0;		// overlap(target, argument) != emptySet
		
		FileFrameInstanceProvider fip = FileFrameInstanceProvider.fn15trainFIP;
		for(FNParse parse : fip) {
			Sentence s = parse.getSentence();
			for(FrameInstance fi : parse.getFrameInstances()) {
				Span t = fi.getTarget();
				for(int k=0; k<fi.numArguments(); k++) {
					Span a = fi.getArgument(k);
					if(a == Span.nullSpan) continue;
					
					// is there a link in indices(t) -x-> indices(a)?
					
					// is target the parent of this argument?
					boolean local = false;
					for(int ai=a.start; ai<a.end && !local; ai++) {
						if(t.includes(s.governor(ai))) {
							numArgsChildren++;
							local = true;
						}
					}
					
					// is argument the parent of the target
					for(int ti=t.start; ti<t.end && !local; ti++) {
						if(a.includes(s.governor(ti))) {
							numArgsParent++;
							local = true;
						}
					}
					
					if(a.overlaps(t)) {
						numArgsSelf++;
						local = true;
					}
					
					numArgs++;
					if(local) numLocalArgs++;
					
					if(verbose && !local) {
						System.out.println("NON-LOCAL ARGUMENT:");
						System.out.println(s);
						System.out.printf("target[frame=%s] = %s\n", fi.getFrame().getName(), Describe.span(t, s));
						System.out.printf("argument[role=%s] = %s\n\n", fi.getFrame().getRole(k), Describe.span(a, s));
					}
				}
			}
		}
		
		System.out.println("in " + fip.getName());
		System.out.printf("%d of %d (%.1f%%) arguments are local\n",
				numLocalArgs, numArgs, (100d*numLocalArgs)/numArgs);
		System.out.printf("%d of %d (%.1f%%) arguments are a child of the target\n",
				numArgsChildren, numArgs, (100d*numArgsChildren)/numArgs);
		System.out.printf("%d of %d (%.1f%%) arguments are the parent of the target\n",
				numArgsParent, numArgs, (100d*numArgsParent)/numArgs);
		System.out.printf("%d of %d (%.1f%%) arguments overlap with the target\n",
				numArgsSelf, numArgs, (100d*numArgsSelf)/numArgs);
		System.out.printf("took %.2f seconds\n", (System.currentTimeMillis()-start)/1000d);
	}
}
