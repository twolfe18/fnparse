package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.*;
import edu.jhu.util.Alphabet;

/**
 * @deprecated take a look at this before using it again.
 * @author travis
 */
public class BasicFramePrototypeFeatures extends AbstractFeatures<BasicFramePrototypeFeatures> implements Features.FP {
	
//	private Random rand = new Random(9001);
//	private HeadFinder hf = new BraindeadHeadFinder();
	
	public BasicFramePrototypeFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}
	
	@Override
	public FeatureVector getFeatures(Frame f, int targetHeadIdx, FrameInstance p, Sentence s) {
		
		if(!(p instanceof FrameInstance.Prototype) && !f.equals(p.getFrame()))
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
		
		for(int i=protoTarget.start; i<protoTarget.end; i++) {
			if(p.getSentence().getWord(i).equals(targetHead)) {
				b(fv, "head-in-prototype");
				b(fv, "head-in-prototype_" + fs);
			}
		}
		
		int ni = s.size();
		int nj = p.getSentence().size();
		for(int i=0; i<ni; i++) {
			for(int j=0; j<nj; j++) {
				double ip = ((double) i) / ni;
				double ij = ((double) j) / nj;
				double dist = Math.abs(ip - ij);
				if(s.getWord(i).equals(p.getSentence().getWord(j))) {
					b(fv, 1d - dist, "word-match");
					b(fv, 1d - dist, "word-match", fs);
					int tdist = Math.abs(i - targetHeadIdx);
					boolean l = i < targetHeadIdx;
					b(fv, 1d - dist, "word-match-in", String.valueOf(tdist));
					b(fv, 1d - dist, "word-match-in", String.valueOf(tdist), fs);
					b(fv, 1d - dist, "word-match-to", (l ? "L" : "R"));
					b(fv, 1d - dist, "word-match-to", (l ? "L" : "R"), fs);
					b(fv, 1d - dist, "word-match-like", String.valueOf(tdist), (l ? "L" : "R"));
					b(fv, 1d - dist, "word-match-like", String.valueOf(tdist), (l ? "L" : "R"), fs);
				}
				if(s.getLU(i).equals(p.getSentence().getLU(j))) {
					b(fv, 1d - dist, "word-match-fine");
					b(fv, 1d - dist, "word-match-fine", fs);
					int tdist = Math.abs(i - targetHeadIdx);
					boolean l = i < targetHeadIdx;
					b(fv, 1d - dist, "word-match-fine-in", String.valueOf(tdist));
					b(fv, 1d - dist, "word-match-fine-in", String.valueOf(tdist), fs);
					b(fv, 1d - dist, "word-match-fine-to", (l ? "L" : "R"));
					b(fv, 1d - dist, "word-match-fine-to", (l ? "L" : "R"), fs);
					b(fv, 1d - dist, "word-match-fine-like", String.valueOf(tdist), (l ? "L" : "R"));
					b(fv, 1d - dist, "word-match-fine-like", String.valueOf(tdist), (l ? "L" : "R"), fs);
				}
			}
		}
		
		// multi purpose latent prototypes
		if(p instanceof FrameInstance.Prototype) {
			FrameInstance.Prototype pp = (FrameInstance.Prototype) p;
			b(fv, "proto=", pp.id);
			b(fv, "proto=", pp.id, fs);
			b(fv, "proto=", pp.id, "word=", head.word);
			b(fv, "proto=", pp.id, "word=", head.word, fs);
			b(fv, "proto=", pp.id, "word=", head.pos);
			b(fv, "proto=", pp.id, "word=", head.pos, fs);
		}
		
		return fv;
	}
}
