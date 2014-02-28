package edu.jhu.hlt.fnparse.features;

import java.util.Random;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.heads.*;
import edu.jhu.util.Alphabet;

public class BasicFramePrototypeFeatures extends AbstractFeatures<BasicFramePrototypeFeatures> implements Features.FP {
	
	private Random rand = new Random(9001);
	private HeadFinder hf = new BraindeadHeadFinder();
	
	public BasicFramePrototypeFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, int targetHeadIdx, FrameInstance p, Sentence s) {
		
		if(!f.equals(p.getFrame()))
			throw new IllegalArgumentException("wut");
		
		FeatureVector fv = new FeatureVector();
		
		// frame backoff features are done elsewhere
		
		String fs = "f=" + f.getName();
		String targetHead = s.getWord(targetHeadIdx);
		Span protoTarget = p.getTarget();				// TODO Lexicalizations of the prototype?
		LexicalUnit head = s.getLU(targetHeadIdx);
		
//		Sentence protoSentence = p.getSentence();
//		LexicalUnit protoHead = protoSentence.getLU(hf.head(protoTarget, protoSentence));
//		
//		b(fv, "protoHead=" + protoHead.word + "-instanceHead=" + head.word);
//		b(fv, "protoHead=" + protoHead.pos + "-instanceHead=" + head.word);
//		b(fv, "protoHead=" + protoHead.word + "-instanceHead=" + head.pos);
//		b(fv, "protoHead=" + protoHead.pos + "-instanceHead=" + head.pos);
//		
//		String w1, w2;
//		w1 = protoHead.word;
//		w2 = head.word;
//		if(w1.contains(w2) || w2.contains(w1)) {
//			b(fv, "case-contains-head-prototype");
//			b(fv, fs + "case-contains-head-prototype");
//			b(fv, fs + "case-contains-head-prototype_word=" + w2);
//		}
//		w1 = protoHead.word.toLowerCase();
//		w2 = head.word.toLowerCase();
//		if(w1.contains(w2) || w2.contains(w1)) {
//			b(fv, "nocase-contains-head-prototype");
//			b(fv, fs + "nocase-contains-head-prototype");
//			b(fv, fs + "nocase-contains-head-prototype_word=" + w2);
//		}
//		
//		if(protoHead.word.equals(head.word)) {
//			b(fv, "sameword-as-prototype");
//			b(fv, fs + "_sameword-as-prototype");
//		}
//		if(protoHead.word.equalsIgnoreCase(head.word)) {
//			b(fv, "nocase-sameword-as-prototype");
//			b(fv, fs + "_nocase-sameword-as-prototype");
//		}
//		if(protoHead.pos.equalsIgnoreCase(head.pos)) {
//			b(fv, "samepos-as-prototype");
//			b(fv, fs + "_samepos-as-prototype");
//		}
		
//		try {
			for(int i=protoTarget.start; i<protoTarget.end; i++) {
				if(p.getSentence().getWord(i).equals(targetHead)) {
					b(fv, "head-in-prototype");
					b(fv, "head-in-prototype_" + fs);
				}
			}
//		}
//		catch(ArrayIndexOutOfBoundsException e) {
//			//e.printStackTrace();
//			if(rand.nextInt(2500) == 0)
//				System.err.println("[BasicFramePrototypeFeatures] bug pushpendre about a bug in the readers!");
//		}
		
		int ni = s.size();
		int nj = p.getSentence().size();
		for(int i=0; i<ni; i++) {
			for(int j=0; j<nj; j++) {
				double ip = ((double) i) / ni;
				double ij = ((double) j) / nj;
				double dist = Math.abs(ip - ij);
				if(s.getWord(i).equals(p.getSentence().getWord(j))) {
					b(fv, "word-match", 1d - dist);
					b(fv, fs + "word-match", 1d - dist);
					int tdist = Math.abs(i - targetHeadIdx);
					boolean l = i < targetHeadIdx;
					b(fv, "word-match-in" + tdist, 1d - dist);
					b(fv, fs + "word-match-in" + tdist, 1d - dist);
					b(fv, "word-match-to" + (l ? "L" : "R"), 1d - dist);
					b(fv, fs + "word-match-to" + (l ? "L" : "R"), 1d - dist);
					b(fv, "word-match-like" + tdist + (l ? "L" : "R"), 1d - dist);
					b(fv, fs + "word-match-like" + tdist + (l ? "L" : "R"), 1d - dist);
				}
				if(s.getLU(i).equals(p.getSentence().getLU(j))) {
					b(fv, "word-match-fine", 1d - dist);
					b(fv, fs + "word-match-fine", 1d - dist);
					int tdist = Math.abs(i - targetHeadIdx);
					boolean l = i < targetHeadIdx;
					b(fv, "word-match-fine-in" + tdist, 1d - dist);
					b(fv, fs + "word-match-fine-in" + tdist, 1d - dist);
					b(fv, "word-match-fine-to" + (l ? "L" : "R"), 1d - dist);
					b(fv, fs + "word-match-fine-to" + (l ? "L" : "R"), 1d - dist);
					b(fv, "word-match-fine-like" + tdist + (l ? "L" : "R"), 1d - dist);
					b(fv, fs + "word-match-fine-like" + tdist + (l ? "L" : "R"), 1d - dist);
				}
			}
		}
		
		// multi purpose latent prototypes
		if(p instanceof FrameInstance.Prototype) {
			FrameInstance.Prototype pp = (FrameInstance.Prototype) p;
			b(fv, "proto=" + pp.id);
			b(fv, "proto=" + pp.id + "_word=" + head.word);
			b(fv, "proto=" + pp.id + "_word=" + head.pos);
			b(fv, fs + "_proto=" + pp.id);
			b(fv, fs + "_proto=" + pp.id + "_word=" + head.word);
			b(fv, fs + "_proto=" + pp.id + "_word=" + head.pos);
		}	
		
		return fv;
	}
}
