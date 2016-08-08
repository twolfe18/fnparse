package edu.jhu.hlt.uberts;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.auto.Rule;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser;
import edu.jhu.hlt.uberts.auto.TypeInference;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser.TG;
import edu.jhu.hlt.uberts.transition.TransGen;
import edu.jhu.prim.tuple.Pair;

/**
 * I don't fully understand my own code. Given the rule:
 *   srl2'(s2,s1,e1) & event2'(e2,e1,f) & role(f,k) => srl3(s2,e2,k)
 * {@link TransitionGeneratorForwardsParser} generates the following pattern:
 *   (TKey nt=null nv=null rel=(Relation srl2) argPos=11)
 *   (TKey nt=witness-event1 nv=null rel=null argPos=1)
 *   (TKey nt=null nv=null rel=(Relation event2) argPos=0)
 *   (TKey nt=frame nv=null rel=null argPos=1)
 *   (TKey nt=null nv=null rel=(Relation role) argPos=0)
 *
 * This is fine and all, but how do we ensure that we get ALL k?
 * I believe the semantics are that all free variables in the LHS are
 * existentially quantified, but I don't see how this is enforced by the
 * matching algorithm I've written.
 *
 * @author travis
 */
public class NonDeterministicMatchingTest {
  Random rand = new Random(9001);
  Uberts u = new Uberts(rand);
  TransitionGeneratorForwardsParser p = new TransitionGeneratorForwardsParser();
  TypeInference ti = new TypeInference(u);

  int timesFired = 0;

  boolean onlyNewest = false;
  boolean onlyFirst = false;

  /**
   * This tracks one type of non-determinism: a case where there is one free variable
   * in a LHS functor and there is a shared variable between each functor in the LHS.
   * This is a typical, but easy, case.
   */
  @SuppressWarnings("unused")
  @Test
  public void test0() {
    if (onlyNewest)
      return;
//    Uberts.COARSE_EVENT_LOGGING = true;
//    TNode.COARSE_DEBUG = true;
    Relation srl2 = u.readRelData("def srl2 <witness-srl1> <witness-event1>");
    Relation event2 = u.readRelData("def event2 <witness-event1> <frame>");
    Relation role = u.readRelData("def role <frame> <roleLabel>");
    Relation srl3 = u.readRelData("def srl3 <witness-srl2> <witness-event2> <roleLabel>");
    Rule untyped = Rule.parseRule("srl2'(s2,s1,e1) & event2'(e2,e1,f) & role(f,k) => srl3(s2,e2,k)", null, null);
    ti.add(untyped);
    List<Rule> typed = ti.runTypeInference();
    assertEquals(typed.size(), 1);
//    Pair<List<TKey>, TG> pair = p.parse2(typed.get(0), u);
//    System.out.println("pattern:\n\t" + StringUtils.join("\n\t", pair.get1()));
////    u.addTransitionGenerator(pair);
//    TNode tnode = u.addTransitionGenerator(pair.get1(), new TransitionGenerator() {
//      @Override
//      public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
//        System.out.println("firing, " + lhsValues);
//        timesFired++;
//        return Collections.emptyList();
//      }
//    });
//    tnode.getValue().r = typed.get(0);
    u.addTransitionGenerator(typed.get(0).lhs, new TransGen() {
      @Override
      public List<Pair<HypEdge, Adjoints>> match(HypEdge[] trigger, Uberts u) {
        System.out.println("firing, trigger=" + Arrays.asList(trigger));
        timesFired++;
        return Collections.emptyList();
      }
    });

    // Add some data to catch
    int numRoles = 5;
    for (int i = 0; i < numRoles; i++) {
      System.out.println("adding A");
      u.readRelData("schema role f1 ARG" + i);
    }
    System.out.println("adding B");
    u.readRelData("x event2 e1a f1");
//    TNode.DEBUG = true;
    System.out.println("adding C");
    u.readRelData("x srl2 s1a e1a");

    // Should get one grounding for each role, like:
    //    firing, (GTT bound=[HNode(Edge, srl2(s1a, e1a)), HNode(Edge, event2(e1a, f1)), HNode(Edge, role(f1, ARG4))])
    //    firing, (GTT bound=[HNode(Edge, srl2(s1a, e1a)), HNode(Edge, event2(e1a, f1)), HNode(Edge, role(f1, ARG3))])
    //    firing, (GTT bound=[HNode(Edge, srl2(s1a, e1a)), HNode(Edge, event2(e1a, f1)), HNode(Edge, role(f1, ARG2))])
    //    firing, (GTT bound=[HNode(Edge, srl2(s1a, e1a)), HNode(Edge, event2(e1a, f1)), HNode(Edge, role(f1, ARG1))])
    //    firing, (GTT bound=[HNode(Edge, srl2(s1a, e1a)), HNode(Edge, event2(e1a, f1)), HNode(Edge, role(f1, ARG0))])
    System.out.println("timesFired=" + timesFired);
    assertEquals(numRoles, timesFired);
    System.out.println();
  }

  /**
   * This tests the case where there are two free variables, but the functors are still connected.
   */
  @SuppressWarnings("unused")
  @Test
  public void test1() {
    if (onlyNewest)
      return;
    if (onlyFirst)
      return;
    Relation srl2 = u.readRelData("def srl2 <witness-srl1> <witness-event1>");
    Relation event2 = u.readRelData("def event2 <witness-event1> <frame>");
    Relation role = u.readRelData("def role <frame> <roleLabel>");
    Relation roleRef = u.readRelData("def roleRef <frame> <roleLabel> <refinement>"); // e.g. -R and -C for reference and continuation roles
    Relation srl3 = u.readRelData("def srl3 <witness-srl2> <witness-event2> <roleLabel> <refinement>");
//    Rule untyped = Rule.parseRule("srl2'(s2,s1,e1) & event2'(e2,e1,f) & role(f,k) => srl3(s2,e2,k)", null);
    Rule untyped = Rule.parseRule("srl2'(s2,s1,e1) & event2'(e2,e1,f) & role(f,k) & roleRef(f,k,c) => srl3(s2,e2,k,c)", null, null);
    ti.add(untyped);
    List<Rule> typed = ti.runTypeInference();
    assertEquals(typed.size(), 1);
//    Pair<List<TKey>, TG> pair = p.parse2(typed.get(0), u);
//    System.out.println("pattern:\n\t" + StringUtils.join("\n\t", pair.get1()));
////    u.addTransitionGenerator(pair);
//    u.addTransitionGenerator(pair.get1(), new TransitionGenerator() {
//      @Override
//      public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
//        System.out.println("firing, " + lhsValues);
//        timesFired++;
//        return Collections.emptyList();
//      }
//    });
    u.addTransitionGenerator(typed.get(0).lhs, new TransGen() {
      @Override
      public List<Pair<HypEdge, Adjoints>> match(HypEdge[] trigger, Uberts u) {
        System.out.println("firing, trigger=" + Arrays.asList(trigger));
        timesFired++;
        return Collections.emptyList();
      }
    });

    // Add some data to catch
    int numRoles = 5;
    for (int i = 0; i < numRoles; i++) {
      System.out.println("adding A1");
      u.readRelData("schema role f1 ARG" + i);
      System.out.println("adding A2");
      u.readRelData("schema roleRef f1 ARG" + i + " BASE");
      u.readRelData("schema roleRef f1 ARG" + i + " CONTINUATION");
      u.readRelData("schema roleRef f1 ARG" + i + " REFERENCE");
    }
    System.out.println("adding B");
    u.readRelData("x event2 e1a f1");
//    TNode.DEBUG = true;
    System.out.println("adding C");
    u.readRelData("x srl2 s1a e1a");

    // Should get one grounding for each role, like:
    //    firing, (GTT bound=[HNode(Edge, srl2(s1a, e1a)), HNode(Edge, event2(e1a, f1)), HNode(Edge, role(f1, ARG4))])
    //    firing, (GTT bound=[HNode(Edge, srl2(s1a, e1a)), HNode(Edge, event2(e1a, f1)), HNode(Edge, role(f1, ARG3))])
    //    firing, (GTT bound=[HNode(Edge, srl2(s1a, e1a)), HNode(Edge, event2(e1a, f1)), HNode(Edge, role(f1, ARG2))])
    //    firing, (GTT bound=[HNode(Edge, srl2(s1a, e1a)), HNode(Edge, event2(e1a, f1)), HNode(Edge, role(f1, ARG1))])
    //    firing, (GTT bound=[HNode(Edge, srl2(s1a, e1a)), HNode(Edge, event2(e1a, f1)), HNode(Edge, role(f1, ARG0))])
    System.out.println("timesFired=" + timesFired);
    assertEquals(numRoles * 3, timesFired);
    System.out.println();
  }

  /**
   * This tests the case where there are two free variables and the functors do
   * not share arguments. This does NOT test the ordering over functors in a
   * match pattern, so it only works with R2 & R2 => R3, but NOT R1 & R2 => R3
   * (because I add R1 facts first, then R2 facts).
   */
  @Test
  public void test2() {
    if (onlyNewest)
      return;
    if (onlyFirst)
      return;
    u.readRelData("def R1 <foo> <bar>");
    u.readRelData("def R2 <baz> <quux>");
//    u.readRelData("def R3 <foo> <bar> <baz> <quux>");
//    Rule untyped = Rule.parseRule("R1(a,b) & R2(c,d) => R3(a,b,c,d)", null);
    u.readRelData("def R3 <baz> <quux> <foo> <bar>");
    Rule untyped = Rule.parseRule("R2(a,b) & R1(c,d) => R3(a,b,c,d)", null, null);
    ti.add(untyped);
    List<Rule> typed = ti.runTypeInference();
    assertEquals(typed.size(), 1);
    Pair<List<TKey>, TG> pair = p.parse2(typed.get(0), u);
    System.out.println("pattern:\n\t" + StringUtils.join("\n\t", pair.get1()));

////    u.addTransitionGenerator(pair);
//    u.addTransitionGenerator(pair.get1(), new TransitionGenerator() {
//      @Override
//      public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
//        System.out.println("firing, " + lhsValues);
//        timesFired++;
//        return Collections.emptyList();
//      }
//    });
    u.addTransitionGenerator(typed.get(0).lhs, new TransGen() {
      @Override
      public List<Pair<HypEdge, Adjoints>> match(HypEdge[] trigger, Uberts u) {
        System.out.println("firing, trigger=" + Arrays.asList(trigger));
        timesFired++;
        return Collections.emptyList();
      }
    });

    /*
     * Note that the rule only fires 3 times per R2 fact because
     * it only fires for unifications with the new fact's variables
     * bound (not all values of R2).
     * 
     * So you get:
        firing, (GTT bound=[HNode(Edge, R2(x, y)), HNode(Edge, R1(b, c))])
        firing, (GTT bound=[HNode(Edge, R2(x, y)), HNode(Edge, R1(a, c))])
        firing, (GTT bound=[HNode(Edge, R2(x, y)), HNode(Edge, R1(a, b))])
        timesFired=3
        firing, (GTT bound=[HNode(Edge, R2(x, z)), HNode(Edge, R1(b, c))])
        firing, (GTT bound=[HNode(Edge, R2(x, z)), HNode(Edge, R1(a, c))])
        firing, (GTT bound=[HNode(Edge, R2(x, z)), HNode(Edge, R1(a, b))])
        timesFired=6
     *
     * Not:
        firing, (GTT bound=[HNode(Edge, R2(x, y)), HNode(Edge, R1(b, c))])
        firing, (GTT bound=[HNode(Edge, R2(x, y)), HNode(Edge, R1(a, c))])
        firing, (GTT bound=[HNode(Edge, R2(x, y)), HNode(Edge, R1(a, b))])
        timesFired=3
        firing, (GTT bound=[HNode(Edge, R2(x, z)), HNode(Edge, R1(b, c))])
        firing, (GTT bound=[HNode(Edge, R2(x, z)), HNode(Edge, R1(a, c))])
        firing, (GTT bound=[HNode(Edge, R2(x, z)), HNode(Edge, R1(a, b))])
        firing, (GTT bound=[HNode(Edge, R2(x, y)), HNode(Edge, R1(b, c))])
        firing, (GTT bound=[HNode(Edge, R2(x, y)), HNode(Edge, R1(a, c))])
        firing, (GTT bound=[HNode(Edge, R2(x, y)), HNode(Edge, R1(a, b))])
        timesFired=9
     */

//    TNode.DEBUG = true;
    System.out.println("adding R1");
    u.readRelData("x R1 a b");
    u.readRelData("x R1 a c");
    u.readRelData("x R1 b c");
    System.out.println("adding R2");
    u.readRelData("x R2 x y");
    assertEquals(3, timesFired);
    System.out.println("timesFired=" + timesFired);
    u.readRelData("x R2 x z");
    assertEquals(6, timesFired);
    System.out.println("timesFired=" + timesFired);
    u.readRelData("x R2 y z");
    assertEquals(9, timesFired);
    System.out.println("timesFired=" + timesFired);
    System.out.println();
  }

  @Test
  public void test3() {
    if (onlyFirst)
      return;
    // cd ~/code/fnparse/data/srl-reldata/trump-pizza

    // cat relations.def | perl -pe 's/(.*)/u.readRelData("\1\");/'
    u.readRelData("def argument <span> <frame> <span> <role>");
    u.readRelData("def constituent <span> <label>");
    u.readRelData("def frameTriage <lemma> <frame>");
    u.readRelData("def lemma2 <tokenIndex> <lemma>");
    u.readRelData("def pos2 <tokenIndex> <pos>");
    u.readRelData("def predicate <span> <frame>");
    u.readRelData("def role <frame> <role>");
    u.readRelData("def span <span> <tokenIndex> <tokenIndex>");
    u.readRelData("def span-w1 <span> <tokenIndex> <tokenIndex>");
    u.readRelData("def word2 <tokenIndex> <word>");

    // cat grammar.trans | perl -pe 's/(.*)/ti.add(Rule.parseRule("\1", null));/'
//    ti.add(Rule.parseRule("span-w1(t,i,j) & lemma2(i,l) & pos2(i,p) => pred1(t)", null));
    ti.add(Rule.parseRule("lemma2(i,l) & span-w1(t,i,j) & pos2(i,p) => pred1(t)", null, null));
//    ti.add(Rule.parseRule("pred1(t) & span-w1(t,i,j) & lemma2(i,l) & frameTriage(l,f) => predicate(t,f)", null));
//    ti.add(Rule.parseRule("pred1(t) & constituent(span,label) => arg2(t,span)", null));
//    ti.add(Rule.parseRule("pred2(t,f) & role(f,k) => arg3(t,f,k)", null));
//    ti.add(Rule.parseRule("arg2(t,s) & arg3(t,f,k) => argument(t,f,s,k)", null));

    for (Rule typedRule : ti.runTypeInference()) {
//      Pair<List<TKey>, TG> pair = p.parse2(typedRule, u);
//      System.out.println("pattern:\n\t" + StringUtils.join("\n\t", pair.get1()));
//      u.addTransitionGenerator(pair.get1(), new TransitionGenerator() {
//        @Override
//        public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
//          System.out.println("firing, " + lhsValues);
//          timesFired++;
//          return Collections.emptyList();
//        }
//      });
      u.addTransitionGenerator(typedRule.lhs, new TransGen() {
        @Override
        public List<Pair<HypEdge, Adjoints>> match(HypEdge[] trigger, Uberts u) {
          System.out.println("firing, trigger=" + Arrays.asList(trigger));
          timesFired++;
          return Collections.emptyList();
        }
      });
    }

    addSchemaEdges();

    // cat trump-pizza-fork.backwards.xy.rel.multi | perl -pe 's/(.*)/u.readRelData("\1\");/'
    System.out.println("adding X");
    u.readRelData("x word2 0 Trump");
    u.readRelData("x word2 1 ate");
    u.readRelData("x word2 2 the");
    u.readRelData("x word2 3 pizza");
    u.readRelData("x word2 4 with");
    u.readRelData("x word2 5 a");
    u.readRelData("x word2 6 fork");
    u.readRelData("x pos2 0 NNP");
    u.readRelData("x pos2 1 VBD");
    u.readRelData("x pos2 2 DT");
    u.readRelData("x pos2 3 NN");
    u.readRelData("x pos2 4 IN");
    u.readRelData("x pos2 5 DT");
    u.readRelData("x pos2 6 NN");
//    TNode.DEBUG = true;
//    Uberts.COARSE_EVENT_LOGGING = true;
    u.readRelData("x lemma2 1 eat");
//    assertTrue("timesFired=" + timesFired, timesFired > 0);
    assertEquals(1, timesFired);
//    u.readRelData("x lemma2 3 pizza");
//    u.readRelData("x lemma2 6 fork");
//    u.readRelData("x constituent 5-7 NP");
//    u.readRelData("x constituent 4-7 PP");
//    u.readRelData("x constituent 2-4 NP");
//    u.readRelData("x constituent 1-7 VP");
//    u.readRelData("x constituent 0-1 NP");
//    u.readRelData("x constituent 0-7 S");

    System.out.println("adding Y");
//    u.readRelData("y predicate 1-2 framenet/Ingestion");
//    u.readRelData("y argument 1-2 framenet/Ingestion 0-1 Ingestor");
//    u.readRelData("y argument 1-2 framenet/Ingestion 2-4 Ingestibles");
//    u.readRelData("y argument 1-2 framenet/Ingestion 4-7 Instrument");
//    u.readRelData("y pred1 1-2");
//    u.readRelData("y arg2 1-2 0-1");
//    u.readRelData("y arg3 1-2 framenet/Ingestion Ingestor");
//    u.readRelData("y arg2 1-2 2-4");
//    u.readRelData("y arg3 1-2 framenet/Ingestion Ingestibles");
//    u.readRelData("y arg2 1-2 4-7");
//    u.readRelData("y arg3 1-2 framenet/Ingestion Instrument");
//    u.readRelData("y pred2 1-2 framenet/Ingestion");
//    u.readRelData("y role framenet/Ingestion Ingestor");
//    u.readRelData("y role framenet/Ingestion Ingestibles");
//    u.readRelData("y role framenet/Ingestion Instrument");
  }

  private void addSchemaEdges() {
    u.readRelData("schema frameTriage eat framenet/Ingestion");
    u.readRelData("schema role framenet/Ingestion Ingestibles");
    u.readRelData("schema role framenet/Ingestion Ingestor");
    u.readRelData("schema role framenet/Ingestion Degree");
    u.readRelData("schema role framenet/Ingestion Duration");
    u.readRelData("schema role framenet/Ingestion Instrument");
    u.readRelData("schema role framenet/Ingestion Manner");
    u.readRelData("schema role framenet/Ingestion Means");
    u.readRelData("schema role framenet/Ingestion Place");
    u.readRelData("schema role framenet/Ingestion Purpose");
    u.readRelData("schema role framenet/Ingestion Source");
    u.readRelData("schema role framenet/Ingestion Time");
    u.readRelData("schema span-w1 0-1 0 1");
    u.readRelData("schema span-w1 1-2 1 2");
    u.readRelData("schema span-w1 2-3 2 3");
    u.readRelData("schema span-w1 3-4 3 4");
    u.readRelData("schema span-w1 4-5 4 5");
    u.readRelData("schema span-w1 5-6 5 6");
    u.readRelData("schema span-w1 6-7 6 7");
    u.readRelData("schema span-w1 7-8 7 8");
    u.readRelData("schema span-w1 8-9 8 9");
    u.readRelData("schema span-w1 9-10 9 10");
    u.readRelData("schema span 0-0 0 0");
    u.readRelData("schema span 0-1 0 1");
    u.readRelData("schema span 0-2 0 2");
    u.readRelData("schema span 0-3 0 3");
    u.readRelData("schema span 0-4 0 4");
    u.readRelData("schema span 0-5 0 5");
    u.readRelData("schema span 0-6 0 6");
    u.readRelData("schema span 0-7 0 7");
    u.readRelData("schema span 0-8 0 8");
    u.readRelData("schema span 0-9 0 9");
    u.readRelData("schema span 1-1 1 1");
    u.readRelData("schema span 1-2 1 2");
    u.readRelData("schema span 1-3 1 3");
    u.readRelData("schema span 1-4 1 4");
    u.readRelData("schema span 1-5 1 5");
    u.readRelData("schema span 1-6 1 6");
    u.readRelData("schema span 1-7 1 7");
    u.readRelData("schema span 1-8 1 8");
    u.readRelData("schema span 1-9 1 9");
    u.readRelData("schema span 2-2 2 2");
    u.readRelData("schema span 2-3 2 3");
    u.readRelData("schema span 2-4 2 4");
    u.readRelData("schema span 2-5 2 5");
    u.readRelData("schema span 2-6 2 6");
    u.readRelData("schema span 2-7 2 7");
    u.readRelData("schema span 2-8 2 8");
    u.readRelData("schema span 2-9 2 9");
    u.readRelData("schema span 3-3 3 3");
    u.readRelData("schema span 3-4 3 4");
    u.readRelData("schema span 3-5 3 5");
    u.readRelData("schema span 3-6 3 6");
    u.readRelData("schema span 3-7 3 7");
    u.readRelData("schema span 3-8 3 8");
    u.readRelData("schema span 3-9 3 9");
    u.readRelData("schema span 4-4 4 4");
    u.readRelData("schema span 4-5 4 5");
    u.readRelData("schema span 4-6 4 6");
    u.readRelData("schema span 4-7 4 7");
    u.readRelData("schema span 4-8 4 8");
    u.readRelData("schema span 4-9 4 9");
    u.readRelData("schema span 5-5 5 5");
    u.readRelData("schema span 5-6 5 6");
    u.readRelData("schema span 5-7 5 7");
    u.readRelData("schema span 5-8 5 8");
    u.readRelData("schema span 5-9 5 9");
    u.readRelData("schema span 6-6 6 6");
    u.readRelData("schema span 6-7 6 7");
    u.readRelData("schema span 6-8 6 8");
    u.readRelData("schema span 6-9 6 9");
    u.readRelData("schema span 7-7 7 7");
    u.readRelData("schema span 7-8 7 8");
    u.readRelData("schema span 7-9 7 9");
    u.readRelData("schema span 8-8 8 8");
    u.readRelData("schema span 8-9 8 9");
    u.readRelData("schema span 9-9 9 9");
  }

}
