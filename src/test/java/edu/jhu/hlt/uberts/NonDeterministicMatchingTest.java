package edu.jhu.hlt.uberts;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.auto.Rule;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser;
import edu.jhu.hlt.uberts.auto.TypeInference;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser.TG;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
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

  /**
   * This tracks one type of non-determinism: a case where there is one free variable
   * in a LHS functor and there is a shared variable between each functor in the LHS.
   * This is a typical, but easy, case.
   */
  @Test
  public void test0() {
    Relation srl2 = u.readRelData("def srl2 <witness-srl1> <witness-event1>");
    Relation event2 = u.readRelData("def event2 <witness-event1> <frame>");
    Relation role = u.readRelData("def role <frame> <roleLabel>");
    Relation srl3 = u.readRelData("def srl3 <witness-srl2> <witness-event2> <roleLabel>");
    Rule untyped = Rule.parseRule("srl2'(s2,s1,e1) & event2'(e2,e1,f) & role(f,k) => srl3(s2,e2,k)", null);
    ti.add(untyped);
    List<Rule> typed = ti.runTypeInference();
    assertEquals(typed.size(), 1);
    Pair<List<TKey>, TG> pair = p.parse2(typed.get(0), u);
    System.out.println("pattern:\n\t" + StringUtils.join("\n\t", pair.get1()));

//    u.addTransitionGenerator(pair);
    u.addTransitionGenerator(pair.get1(), new TransitionGenerator() {
      @Override
      public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
        System.out.println("firing, " + lhsValues);
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
    assertEquals(numRoles, timesFired);
  }
  
  /**
   * This tests the case where there are two free variables, but the functors are still connected.
   */
  @Test
  public void test1() {
    Relation srl2 = u.readRelData("def srl2 <witness-srl1> <witness-event1>");
    Relation event2 = u.readRelData("def event2 <witness-event1> <frame>");
    Relation role = u.readRelData("def role <frame> <roleLabel>");
    Relation roleRef = u.readRelData("def roleRef <frame> <roleLabel> <refinement>"); // e.g. -R and -C for reference and continuation roles
    Relation srl3 = u.readRelData("def srl3 <witness-srl2> <witness-event2> <roleLabel> <roleRef>");
//    Rule untyped = Rule.parseRule("srl2'(s2,s1,e1) & event2'(e2,e1,f) & role(f,k) => srl3(s2,e2,k)", null);
    Rule untyped = Rule.parseRule("srl2'(s2,s1,e1) & event2'(e2,e1,f) & role(f,k) & roleRef(f,k,c) => srl3(s2,e2,k,c)", null);
    ti.add(untyped);
    List<Rule> typed = ti.runTypeInference();
    assertEquals(typed.size(), 1);
    Pair<List<TKey>, TG> pair = p.parse2(typed.get(0), u);
    System.out.println("pattern:\n\t" + StringUtils.join("\n\t", pair.get1()));

//    u.addTransitionGenerator(pair);
    u.addTransitionGenerator(pair.get1(), new TransitionGenerator() {
      @Override
      public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
        System.out.println("firing, " + lhsValues);
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
    assertEquals(numRoles * 3, timesFired);
  }

  /**
   * This tests the case where there are two free variables and the functors do
   * not share arguments. This does NOT test the ordering over functors in a
   * match pattern, so it only works with R2 & R2 => R3, but NOT R1 & R2 => R3
   * (because I add R1 facts first, then R2 facts).
   */
  @Test
  public void test2() {
    u.readRelData("def R1 <foo> <bar>");
    u.readRelData("def R2 <baz> <quux>");
    u.readRelData("def R3 <foo> <bar> <baz> <quux>");
//    Rule untyped = Rule.parseRule("R1(a,b) & R2(c,d) => R3(a,b,c,d)", null);
    Rule untyped = Rule.parseRule("R2(a,b) & R1(c,d) => R3(a,b,c,d)", null);
    ti.add(untyped);
    List<Rule> typed = ti.runTypeInference();
    assertEquals(typed.size(), 1);
    Pair<List<TKey>, TG> pair = p.parse2(typed.get(0), u);
    System.out.println("pattern:\n\t" + StringUtils.join("\n\t", pair.get1()));

//    u.addTransitionGenerator(pair);
    u.addTransitionGenerator(pair.get1(), new TransitionGenerator() {
      @Override
      public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
        System.out.println("firing, " + lhsValues);
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
  }

}
