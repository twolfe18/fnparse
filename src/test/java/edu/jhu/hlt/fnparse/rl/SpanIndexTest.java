package edu.jhu.hlt.fnparse.rl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.util.RandomSpan;

public class SpanIndexTest {
  private Random rand = new Random(9001);
  private RandomSpan randSpanGen = new RandomSpan(rand);

  @Test
  public void test0() {
    int n = 10;
    SpanIndex<Action> ai = new SpanIndex<Action>(n);

    // After initialization, should be empty
    for (int i = 0; i < n; i++) {
      assertNull(ai.startsAt(i));
      assertNull(ai.endsAt(i));
      for (int t = 0; t < 10; t++) {
        Span s = randSpanGen.draw(n);
        assertEquals(0, ai.crosses(s, new ArrayList<>()).size());
      }
    }

    Span s1 = Span.getSpan(3, 6);
    Action a1 = new Action(0, 0, 0, s1);
    SpanIndex<Action> ai2 = ai.persistentUpdate(a1);
    System.out.println("ai2: " + ai2);

    // startsAt
    assertNotNull(ai2.startsAt(3));
    assertTrue(ai2.startsAt(3).payload == a1);
    for (int i = 0; i < n; i++)
      if (i != 3)
        assertNull(ai2.startsAt(i));

    // endsAt
    assertNotNull(ai2.endsAt(6));
    assertTrue(ai2.endsAt(6).payload == a1);
    for (int i = 0; i < n; i++)
      if (i != 6)
        assertNull(ai2.endsAt(i));

    // Share a starting point => No overlap
    assertEquals(s1.end, a1.end);
    for (int e = s1.start + 1; e <= n; e++) {
      List<Action> cx = ai2.crosses(Span.getSpan(s1.start, e), new ArrayList<>());
      if (cx.size() > 0)
        ai2.crosses(Span.getSpan(s1.start, e), new ArrayList<>());
      assertEquals(0, cx.size());
    }

    // Share a ending point => No overlap
    assertEquals(s1.start, a1.start);
    for (int s = 0; s < s1.end; s++) {
      List<Action> cx = ai2.crosses(Span.getSpan(s, s1.end), new ArrayList<>());
//      if (cx.size() > 0)
//        ai2.crosses(Span.getSpan(s, s1.end), new ArrayList<>());
      assertEquals(0, cx.size());
    }

    // Example overlap
    assertEquals(Arrays.asList(a1), ai2.crosses(Span.getSpan(4, 7), new ArrayList<>()));
    assertEquals(Arrays.asList(a1), ai2.crosses(Span.getSpan(1, 4), new ArrayList<>()));

    // Length 1 spans can't overlap
    for (int i = 0; i < n; i++)
      assertEquals(0, ai2.crosses(Span.getSpan(i, i+1), new ArrayList<>()).size());

    // Should match implementation of Span.overlap
    for (int t = 0; t < 100; t++) {
      Span s2 = randSpanGen.draw(n);
      boolean spanCrosses = s1.crosses(s2);
      boolean spanCrosses2 = s2.crosses(s1);
      boolean crosses = ai2.crosses(s2, new ArrayList<>()).size() > 0;
      assertEquals("s1=" + s1 + " s2=" + s2, spanCrosses, crosses);
      assertEquals(spanCrosses2, crosses);
    }
  }

  @Test
  public void visual() {
    int n = 10;
    SpanIndex<Action> ai = new SpanIndex<>(n);
    for (int i = 0; i < 20; i++) {
      Span s = randSpanGen.draw(n);
      Action a = new Action(0, 0, 0, s);
      System.out.println("adding: " + a);
      ai = ai.persistentUpdate(a);
      System.out.println(ai);
      System.out.println();
    }
  }
}
