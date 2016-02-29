package edu.jhu.hlt.uberts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.tuple.Pair;

public class AgendaTests {

  private Random rand = new Random(9001);

  /**
   * Simple reference implementation (inefficient but obvoiusly correct).
   */
  private static class DumbAgenda {
    Comparator<Pair<HypEdge, Adjoints>> C = new Comparator<Pair<HypEdge, Adjoints>>() {
      @Override
      public int compare(Pair<HypEdge, Adjoints> o1, Pair<HypEdge, Adjoints> o2) {
        double s1 = o1.get2().forwards();
        double s2 = o2.get2().forwards();
        if (s1 > s2)
          return -1;
        if (s1 > s2)
          return 1;
        return 0;
      }
    };
    List<Pair<HypEdge, Adjoints>> list = new ArrayList<>();
    public HypEdge peek() { return list.get(0).get1(); }
    public Adjoints peekScore() { return list.get(0).get2(); }
    public Pair<HypEdge, Adjoints> peekBoth() { return list.get(0); }
    public HypEdge pop() { return list.remove(0).get1(); }
    public Pair<HypEdge, Adjoints> popBoth() { return list.remove(0); }
    public void add(HypEdge e, Adjoints a) {
      Pair<HypEdge, Adjoints> p = new Pair<>(e, a);
      list.add(p);
      Collections.sort(list, C);
    }
    public int size() { return list.size(); }
    public void dbgShowScores() {
      for (int i = 0; i < size(); i++) {
        Pair<HypEdge, Adjoints> e = list.get(i);
        System.out.printf("%d\t%.4f\t%s\n", i, e.get2().forwards(), e.get1());
      }
    }
    public List<HypEdge> adjacent(HypNode n) {
      List<HypEdge> el = new ArrayList<>();
      for (Pair<HypEdge, Adjoints> p : list) {
        HypEdge e = p.get1();
        for (HypNode n2 : e.getNeighbors()) {
          if (n2 == n) {
            el.add(e);
            break;
          }
        }
      }
      return el;
    }
    public List<HypNode> allNodes() {
      HashSet<HypNode> s = new HashSet<>();
      List<HypNode> nl = new ArrayList<>();
      for (Pair<HypEdge, Adjoints> p : list) {
        HypEdge e = p.get1();
        for (HypNode n : e.getNeighbors())
          if (s.add(n))
            nl.add(n);
      }
      return nl;
    }
    public Set<HypNode> nodeSet() {
      HashSet<HypNode> s = new HashSet<>();
      for (Pair<HypEdge, Adjoints> p : list) {
        HypEdge e = p.get1();
        for (HypNode n : e.getNeighbors())
          s.add(n);
      }
      return s;
    }
    public HypNode randomNode(Random r) {
      List<HypNode> l = allNodes();
      Collections.shuffle(l, r);
      return l.get(0);
    }
    public HypEdge randomEdge(Random r) {
      return ReservoirSample.sampleOne(list, r).get1();
    }
    public void remove(HypEdge e) {
      int match = -1;
      for (int i = 0; i < list.size(); i++) {
        HypEdge ei = list.get(i).get1();
        if (ei == e) {
          if (match >= 0)
            throw new RuntimeException();
          match = i;
        }
      }
      if (match >= 0)
        list.remove(match);
      else
        throw new RuntimeException();
    }
  }

  /**
   * Generates K random scores (optionally unique)
   */
  private static class Scores {
    private Adjoints[] scores;
    private Random rand;
    public Scores(int K, boolean deterministic, Random rand) {
      scores = new Adjoints[K];
      this.rand = rand;
      int[] sv = null;
      if (deterministic) {
        // Ensure scores are distinct
        sv = new int[K];
        for (int i = 0; i < K; i++)
          sv[i] = i;
        // Fisher-Yates shuffle
        for (int i = K-1; i > 0; i--) {
          int j = rand.nextInt(i);
          int t = sv[i];
          sv[i] = sv[j];
          sv[j] = t;
        }
      }
      for (int i = 0; i < K; i++)
        scores[i] = deterministic ? new Adjoints.Constant(sv[i]) : randScore();
    }
    private Adjoints randScore() {
      return new Adjoints.Constant(rand.nextGaussian());
    }
    public Adjoints getScore(int i) {
      return scores[i];
    }
  }


  /**
   * Test the heap functionality (always return the highest scoring HypEdge).
   *
   * NOTE: This test will fail if you set the number of adds (K) to be too big
   * (I think the smallest I hit it with was 32). I believe the reason for this
   * is the difference... wait
   */
  @Test
  public void test0() {

    boolean verbose = false;
    int maxK = 25;
    int numChecks = 2000;
    for (int K = 1; K < maxK; K++) {
      for (boolean deterministic : Arrays.asList(true, false)) {
        Log.info("K=" + K + " deterministic=" + deterministic);
        for (int iter = 0; iter < numChecks; iter++) {

          // Re-initialize the scores randomly every time
          Scores scores = new Scores(K, deterministic, rand);

          NodeType col = new NodeType("col"); // lets say its domain is [K]
          Relation r = new Relation("test", col);
          DumbAgenda comp = new DumbAgenda();
          Agenda agenda = new Agenda();
          for (int i = 0; i < K; i++) {
            HypNode head = null;
            HypNode[] tail = new HypNode[] { new HypNode(col, i) };
            HypEdge e = new HypEdge(r, head, tail);
            //          Adjoints a = deterministic ? deterministicScore() : randScore();
            Adjoints a = scores.getScore(i);
            agenda.add(e, a);
            assertTrue(agenda.parentInvariantSatisfied());
            comp.add(e, a);
            if (verbose)
              System.out.println("just added " + e + " with score "+ a);
            assertSame(comp.peek(), agenda.peek());
            assertSame(comp.peekScore(), agenda.peekScore());
          }


          for (int i = 0; i < K; i++) {
            if (verbose) {
              System.out.println();
              System.out.println("comp");
              comp.dbgShowScores();
              System.out.println("agenda");
              agenda.dbgShowScores();
              System.out.println("peek: " + comp.peek() + " with score " + comp.peekScore());
            }
            assertEquals(comp.size(), agenda.size());
            //          assertSame(comp.peek(), agenda.peek());
            //          assertSame(comp.peekScore(), agenda.peekScore());
            //          assertSame(comp.pop(), agenda.pop());

            assertEquals(comp.peekBoth(), agenda.peekBoth());
            assertEquals(comp.popBoth(), agenda.popBoth());

            assertEquals(comp.size(), agenda.size());
            assertTrue(agenda.parentInvariantSatisfied());
          }
        }
      }
    }
  }

  /**
   * Test remove and adjacency functionality
   */
  @Test
  public void test1() {
    TimeMarker tm = new TimeMarker();
    int maxK = 1<<9;
    for (int K = 1; K <= maxK; K *= 2) {
      Log.info("K=" + K);
      int maxIter = 1<<7;
      for (int iter = 0; iter < maxIter; iter++) {
        if (tm.enoughTimePassed(10))
          Log.info("K=" + K + " iter=" + iter + " of=" + maxIter);
        test1Helper(K);
      }
    }
  }

  public void test1Helper(int K) {
    Agenda agenda = new Agenda();
    DumbAgenda dumb = new DumbAgenda();

    NodeType col = new NodeType("col"); // lets say its domain is [K]
    Relation r = new Relation("test", col);

    boolean deterministic = true;
    Scores scores = new Scores(K, deterministic, rand);

    HypNode n;
    boolean verbose = false;

    for (int i = 0; i < K; i++) {
      HypNode head = null;
      HypNode[] tail = new HypNode[] { new HypNode(col, i) };
      HypEdge e = new HypEdge(r, head, tail);
      Adjoints a = scores.getScore(i);
      if (verbose)
        Log.info("about to add " + e);

      agenda.add(e, a);
      dumb.add(e, a);

//      assertEquals(dumb.peekBoth(), agenda.peekBoth());
//      assertEquals(dumb.nodeSet(), agenda.nodeSet1());
//      assertEquals(dumb.nodeSet(), agenda.nodeSet2());

      n = dumb.randomNode(rand);
      assertEquals(new HashSet<>(dumb.adjacent(n)), new HashSet<>(agenda.adjacent(n)));

      if (rand.nextDouble() < 0.3 && dumb.size() > 1) {
//        HypNode n = dumb.randomNode(rand);
        HypEdge edge = dumb.randomEdge(rand);
        if (verbose)
          Log.info("about to remove " + edge);

        agenda.remove(edge);
        dumb.remove(edge);
//        assertEquals(dumb.peekBoth(), agenda.peekBoth());

        Set<HypNode> dns = dumb.nodeSet();
        if (verbose) {
          Log.info("dumb.nodeSet=" + dns);
          Log.info("agenda.nodeSet1=" + agenda.nodeSet1());
          Log.info("agenda.nodeSet2=" + agenda.nodeSet2());
        }
        assertEquals(agenda.nodeSet1(), agenda.nodeSet2());
        assertEquals(dns, agenda.nodeSet1());
        assertEquals(dns, agenda.nodeSet2());

        n = dumb.randomNode(rand);
        assertEquals(new HashSet<>(dumb.adjacent(n)), new HashSet<>(agenda.adjacent(n)));
      }
    }
  }

}
