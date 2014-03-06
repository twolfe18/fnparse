package edu.jhu.hlt.fnparse.features;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.LexicalUnit;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.util.Alphabet;

public class RoleConstituencyFeatures extends AbstractFeatures<RoleConstituencyFeatures> implements Features.RE {

	public RoleConstituencyFeatures(Alphabet<String> featIdx) {
		super(featIdx);
	}

	@Override
	public FeatureVector getFeatures(Frame frameFrom_r_ijk, int targetHeadIdx, int roleIdx, Span argSpan, Sentence sent) {
		
		if(argSpan == Span.nullSpan)
			return emptyFeatures;
		
		FeatureVector v = new FeatureVector();
		
		String r = frameFrom_r_ijk == Frame.nullFrame
				? "null-frame"
				: frameFrom_r_ijk.getName();
		String rr = frameFrom_r_ijk == Frame.nullFrame
				? "role-for-null-frame"
				: frameFrom_r_ijk.getName() + "." + frameFrom_r_ijk.getRole(roleIdx);
		
		b(v, 2d, "intercept");
		b(v, 0.5d, r, "intercept");
		b(v, rr, "intercept");

		b(v, 2d, "width=", String.valueOf(argSpan.width()));
		b(v, 2d, "width/2=", String.valueOf(argSpan));
		b(v, 2d, "width/3=", String.valueOf(argSpan.width()/3));
		b(v, 2d, "width/4=", String.valueOf(argSpan.width()/4));
		b(v, 0.5d, r, "width=", String.valueOf(argSpan.width()));
		b(v, 0.5d, r, "width/2=", String.valueOf(argSpan));
		b(v, 0.5d, r, "width/3=", String.valueOf(argSpan.width()/3));
		b(v, 0.5d, r, "width/4=", String.valueOf(argSpan.width()/4));
		b(v, rr, "width=", String.valueOf(argSpan.width()));
		b(v, rr, "width/2=", String.valueOf(argSpan));
		b(v, rr, "width/3=", String.valueOf(argSpan.width()/3));
		b(v, rr, "width/4=", String.valueOf(argSpan.width()/4));
		
		long p = Math.round((10d * argSpan.width()) / sent.size());
		b(v, 2d, "propWidth=" + p);
		b(v, r, "propWidth=" + p);
		b(v, rr, "propWidth=" + p);
		
		int s = argSpan.start;
		if(s > 0) {
			b(v, "oneLeft=", sent.getWord(s-1));
			b(v, "oneLeft=", sent.getPos(s-1));
			b(v, rr, "oneLeft=", sent.getWord(s-1));
			b(v, rr, "oneLeft=", sent.getPos(s-1));
			if(s > 1) {
				b(v, "twoLeft=", sent.getWord(s-2));
				b(v, "twoLeft=", sent.getPos(s-2));
				b(v, rr, "twoLeft=", sent.getWord(s-2));
				b(v, rr, "twoLeft=", sent.getPos(s-2));
			}
		}
		
		int e = argSpan.end;
		if(e < sent.size()) {
			b(v, "oneRight=", sent.getWord(e));
			b(v, "oneRight=", sent.getPos(e));
			b(v, rr, "oneRight=", sent.getWord(e));
			b(v, rr, "oneRight=", sent.getPos(e));
			if(e < sent.size() - 1) {
				b(v, "twoRight=", sent.getWord(e+1));
				b(v, "twoRight=", sent.getPos(e+1));
				b(v, rr, "twoRight=", sent.getWord(e+1));
				b(v, rr, "twoRight=", sent.getPos(e+1));
			}
		}
		
		for(int i=argSpan.start; i<argSpan.end; i++) {
			
			LexicalUnit x = sent.getLU(i);
			String si = String.valueOf(i - argSpan.start) + "->";
			String ei = "<-" + String.valueOf(argSpan.end - i - 1);
			
			b(v, si, x.word);
			b(v, si, x.pos);
			b(v, ei, x.word);
			b(v, ei, x.pos);
			b(v, r, si, x.word);
			b(v, r, si, x.pos);
			b(v, r, ei, x.word);
			b(v, r, ei, x.pos);
			b(v, 2d, rr, si, x.word);
			b(v, 2d, rr, si, x.pos);
			b(v, 2d, rr, ei, x.word);
			b(v, 2d, rr, ei, x.pos);
			
			b(v, "contains", x.word);
			b(v, "contains", x.pos);
			b(v, r, "contains", x.word);
			b(v, r, "contains", x.pos);
			b(v, 2d, rr, "contains", x.word);
			b(v, 2d, rr, "contains", x.pos);
		}
		
		return v;
	}

}
