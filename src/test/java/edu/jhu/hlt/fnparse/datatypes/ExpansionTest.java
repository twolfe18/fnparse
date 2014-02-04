package edu.jhu.hlt.fnparse.datatypes;

import static org.junit.Assert.*;
import static edu.jhu.hlt.fnparse.util.ScalaLike.*;

import java.util.*;

import org.junit.Before;
import org.junit.Test;

public class ExpansionTest {
	
	private Random r;
	
	@Before
	public void setup() {
		r = new Random(9001);
	}
	
	@Test
	public void headToSpanTest() {
		int n = 15;
		for(int i=0; i<100; i++) {
			int e = r.nextInt(n) + 1;
			int s = r.nextInt(e);
			runHeadToSpanTest(Span.getSpan(s, e));
		}
	}
	
	/**
	 * round trip test of expansions (basically + and - for Spans/heads)
	 */
	public void runHeadToSpanTest(Span s) {
		int head = s.start + r.nextInt(s.width());
		assertTrue(s.includes(head));
		Expansion e = Expansion.headToSpan(head, s);
		Span ss = e.upon(head);
		assertEquals(s, ss);
	}

	@Test
	public void iterTest() {
		runIterTest(1, 3);
		runIterTest(5, 10);
	}
	
	public void runIterTest(int head, int sentLen) {
		require(head < sentLen);
		require(head >= 0);
		require(sentLen > 0);
		
		Set<Expansion> explicit = new HashSet<Expansion>();
		Set<Expansion> implicit = new HashSet<Expansion>();
		Set<Expansion> implicitReset = new HashSet<Expansion>();
		
		for(int l=0; l<=head; l++) {
			for(int r=head+1; r<=sentLen; r++) {
				int expLeft = head - l;
				int expRight = r - (head+1);
				Expansion e = new Expansion(expLeft, expRight);
				//System.out.println("explicit " + e);
				explicit.add(e);
			}
		}
		
		Expansion.Iter iter = new Expansion.Iter(head, sentLen);
		while(iter.hasNext()) {
			Expansion e = iter.next();
			//System.out.println("implicit " + e);
			implicit.add(e);
		}
		
		iter.reset();
		while(iter.hasNext()) {
			Expansion e = iter.next();
			implicitReset.add(e);
		}
		
		Expansion empty = new Expansion(0, 0);
		assertTrue(explicit.contains(empty));
		assertTrue(implicit.contains(empty));
		
		assertEquals(explicit.size(), iter.size());	// all unique and size works
		
		assertEquals(explicit, implicit);
		assertEquals(explicit, implicitReset);
	}
}
