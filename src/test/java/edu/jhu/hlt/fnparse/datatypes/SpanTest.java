package edu.jhu.hlt.fnparse.datatypes;

import static org.junit.Assert.*;

import org.junit.Test;

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
}
