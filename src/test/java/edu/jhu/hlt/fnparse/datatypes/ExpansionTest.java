package edu.jhu.hlt.fnparse.datatypes;

import static org.junit.Assert.*;
import static edu.jhu.hlt.fnparse.util.ScalaLike.*;

import java.util.*;

import org.junit.Test;

public class ExpansionTest {

	@Test
	public void basic() {
		runTest(1, 3);
		runTest(5, 10);
	}
	
	public void runTest(int head, int sentLen) {
		require(head < sentLen);
		require(head >= 0);
		require(sentLen > 0);
		
		Set<Expansion> explicit = new HashSet<Expansion>();
		Set<Expansion> implicit = new HashSet<Expansion>();
		
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
		
		Expansion empty = new Expansion(0, 0);
		assertTrue(explicit.contains(empty));
		assertTrue(implicit.contains(empty));
		assertEquals(explicit, implicit);
	}
}
