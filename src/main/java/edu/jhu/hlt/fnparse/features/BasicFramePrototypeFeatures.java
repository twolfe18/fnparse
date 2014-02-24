package edu.jhu.hlt.fnparse.features;

import java.util.Random;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.util.Alphabet;

public class BasicFramePrototypeFeatures extends AbstractFeatures<BasicFramePrototypeFeatures> implements Features.FP {
	
	private Random rand = new Random(9001);
	
	public BasicFramePrototypeFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, int targetHeadIdx, FrameInstance p, Sentence s) {
		
		if(!f.equals(p.getFrame()))
			throw new IllegalArgumentException("wut");
		
		FeatureVector fv = new FeatureVector();
		
		// frame backoff features are done elsewhere
		
		String targetHead = s.getWord(targetHeadIdx);
		Span protoTarget = p.getTarget();
		String protoId = f.getId() + "@" + p.getSentence().getId();
		
		try {
			for(int i=protoTarget.start; i<protoTarget.end; i++) {
				if(p.getSentence().getWord(i).equals(targetHead)) {
					b(fv, "head-in-prototype");
					b(fv, "head-in-prototype_prototype=" + protoId);
					b(fv, "head-in-prototype_frame=" + f.getId());
				}
			}
		}
		catch(ArrayIndexOutOfBoundsException e) {
			//e.printStackTrace();
			if(rand.nextInt(2500) == 0)
				System.err.println("[BasicFramePrototypeFeatures] bug pushpendre about a bug in the readers!");
		}
		
		int ni = s.size();
		int nj = p.getSentence().size();
		for(int i=0; i<ni; i++) {
			for(int j=0; j<nj; j++) {
				double ip = ((double) i) / ni;
				double ij = ((double) j) / nj;
				double dist = Math.abs(ip - ij);
				if(s.getWord(i).equals(p.getSentence().getWord(j))) {
					b(fv, "word-match", 1d - dist);
					int tdist = Math.abs(i - targetHeadIdx);
					boolean l = i < targetHeadIdx;
					b(fv, "word-match-in" + tdist, 1d - dist);
					b(fv, "word-match-to" + (l ? "L" : "R"), 1d - dist);
					b(fv, "word-match-like" + tdist + (l ? "L" : "R"), 1d - dist);
				}
				if(s.getLU(i).equals(p.getSentence().getLU(j))) {
					b(fv, "word-match-fine", 1d - dist);
					int tdist = Math.abs(i - targetHeadIdx);
					boolean l = i < targetHeadIdx;
					b(fv, "word-match-fine-in" + tdist, 1d - dist);
					b(fv, "word-match-fine-to" + (l ? "L" : "R"), 1d - dist);
					b(fv, "word-match-fine-like" + tdist + (l ? "L" : "R"), 1d - dist);
				}
			}
		}
		
		return fv;
	}
}
