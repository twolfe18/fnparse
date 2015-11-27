package edu.jhu.hlt.fnparse.datatypes;

import static org.junit.Assert.*;

import org.junit.Test;

import edu.jhu.hlt.tutils.Span;

public class SpanTest {

	@Test
	public void basic() {
		for(int i=0; i<10; i++) {
			for(int j=i+1; j<100; j++) {
				Span s1 = Span.getSpan(i, j);
				Span s2 = Span.getSpan(i, j);
				assertTrue(s1 == s2);
				assertTrue(s1.equals(s2));
				assertEquals(i, s1.start);
				assertEquals(j, s1.end);
			}
		}
	}
	
	@Test
	public void overlap() {
		Span s1 = Span.getSpan(0, 1);
		Span s2 = Span.getSpan(0, 2);
		Span s3 = Span.getSpan(1, 2);
		assertEquals(true, s1.overlaps(s2));
		assertEquals(false, s1.overlaps(s3));
		assertEquals(true, s2.overlaps(s3));
		
		// reflexivity
		Span[] spans = new Span[] { s1, s2, s3 };
		for(int i=0; i<spans.length-1; i++) {
			for(int j=i+1; j<spans.length; j++) {
				Span sa = spans[i];
				Span sb = spans[j];
				assertEquals(sa.overlaps(sb), sb.overlaps(sa));
			}
		}
	}
}
