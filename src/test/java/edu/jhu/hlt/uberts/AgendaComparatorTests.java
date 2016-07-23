package edu.jhu.hlt.uberts;

import static org.junit.Assert.*;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Random;

import org.junit.Test;

import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda.AgendaItem;

public class AgendaComparatorTests {

  @Test
  public void test0() {
    Comparator<AgendaItem> c = AgendaComparators.BY_RELATION
        .thenComparing(AgendaComparators.BY_TARGET)
        .thenComparing(AgendaComparators.BY_FRAME)
        .thenComparing(AgendaComparators.BY_ROLE)
        .thenComparing(AgendaComparators.BY_SCORE);
    Agenda imp = new Agenda(null, c);
    PriorityQueue<AgendaItem> ref = new PriorityQueue<>(c);


    Random rand = new Random(9001);
    Uberts u = new Uberts(rand);
    u.readRelData("def argument4 <span> <frame> <span> <role>");
    int n = 30;
    for (int i = 0; i < 100000; i++) {
      Span target = Span.randomSpan(n, rand);
      String frame = "frame" + rand.nextInt(10);
      Span arg = Span.randomSpan(n, rand);
      String role = "role" + rand.nextInt(30);
      HypEdge f = u.dbgMakeEdge("argument4(" + target.shortString() + ", " + frame + ", " + arg.shortString() + ", " + role + ")", false);
      Adjoints score = new Adjoints.Constant(rand.nextGaussian());
      AgendaItem ai = new AgendaItem(f, score, 0);
      imp.add(ai.edge, ai.score);
      ref.add(ai);

      int take = rand.nextInt(ref.size());
      for (int j = 0; j < take; j++) {
        HypEdge e1 = imp.pop();
        HypEdge e2 = ref.remove().edge;
        assertEquals(e1, e2);
      }
    }
  }
}
