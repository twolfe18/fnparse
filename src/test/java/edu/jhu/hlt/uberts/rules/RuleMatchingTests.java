package edu.jhu.hlt.uberts.rules;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Rule;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser;
import edu.jhu.hlt.uberts.auto.Trigger;
import edu.jhu.hlt.uberts.auto.TypeInference;
import edu.jhu.hlt.uberts.auto.UbertsPipeline;
import edu.jhu.hlt.uberts.rules.Env.Trie3;

/**
 * Test that we can parse rules like "foo(x,y) & bar(y,z) => baz(x,z)", compile
 * them into a matching plan which checks the state graph, and properly
 * retrieves all matches, respecting equality constraints, etc.
 *
 * Some of the tests which should be here are in {@link TransitionGeneratorForwardsParser}.
 *
 * @author travis
 */
public class RuleMatchingTests {

  Uberts u = new Uberts(new Random(9001));
  Relation doneAnno = u.readRelData("def doneAnno <docid>");

  public void readSchemaData() {
    try {
      u.readRelData(new File("data/srl-reldata/testing/relations.def"));
      u.readRelData(new File("data/srl-reldata/testing/spans.schema.facts.gz"));
      u.readRelData(new File("data/srl-reldata/testing/frameTriage4.rel.gz"));
      u.readRelData(new File("data/srl-reldata/testing/coarsenPos2.rel"));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** returns the last HypEdge added */
  private HypEdge addJohnLovesMary() {
    u.addEdgeToState(u.dbgMakeEdge("lemma2(0,John)", false), Adjoints.Constant.ZERO);
    u.addEdgeToState(u.dbgMakeEdge("lemma2(1,love)", false), Adjoints.Constant.ZERO);
    u.addEdgeToState(u.dbgMakeEdge("lemma2(2,Mary)", false), Adjoints.Constant.ZERO);
    u.addEdgeToState(u.dbgMakeEdge("pos2(0,NNS)", false), Adjoints.Constant.ZERO);
    u.addEdgeToState(u.dbgMakeEdge("pos2(1,VBZ)", false), Adjoints.Constant.ZERO);
    return u.addEdgeToState(u.dbgMakeEdge("pos2(2,NNS)", false), Adjoints.Constant.ZERO);
  }

  /**
   * Fails because of the order of 1) adding facts to state and 2) the order of the rule.
   * {@link UbertsPipeline} has a solution: add a parse of the rule which has each functor appearing first once.
   */
  @Test
  public void test0() {
    readSchemaData();
    List<String> lhs = Arrays.asList("doneAnno(docid)", "pos2(i,pf)","lemma2(i,l)", "coarsenPos2(pf,pc)", "frameTriage4(l,pc,synset,frame)", "span-w1(target,i,j)");
    String rhs = "predicate2(target,frame)";

    // TODO Try all orders and ensure that results are the same.

    String rs = StringUtils.join(" & ", lhs) + " => " + rhs;
    Rule rule = Rule.parseRule(rs, u);

    Trie3 t = Trie3.makeRoot();
    t.add(new Trigger(rule.lhs, 0));

    addJohnLovesMary();
    HypEdge last = u.addEdgeToState(u.dbgMakeEdge("doneAnno(test0)", false), Adjoints.Constant.ZERO);

    State s = u.getState();
    t.match(s, last, m -> {
      System.out.println("[match] " + m);
    });
    System.out.println(Trie3.EVENT_COUNTS);
  }

  @Test
  public void test1() {
    System.out.println();
    Log.info("starting...");

    readSchemaData();
    String rs1 = "doneAnno(docid) & pos2(i,p) & span-w1(t,i,j) => event1(t)";
    String rs2 = "event1(t) & span-w1(t,i,j) & pos2(i,posFine) & lemma2(i,lemma)"
        + " & coarsenPos2(posFine,posCoarse) & frameTriage4(lemma,posCoarse,synset,frame)"
        + " => predicate2(t,frame)";
//    String r1 = "foo(x,y) & bar(y) => baz(x)";
//    String r2 = "bar(z) & baz(z) => quuz(z)";
    Rule r1 = Rule.parseRule(rs1, u);
    Rule r2 = Rule.parseRule(rs2, u);
    TypeInference ti = new TypeInference(u);
    ti.add(r1);
    ti.add(r2);
    List<Rule> typedRules = ti.runTypeInference();    // figure out types for event1(t)
//    System.out.println(typedRules);
    r1 = typedRules.get(0);
    r2 = typedRules.get(1);
    System.out.println(r1);
    System.out.println(r2);

    Trie3 t = Trie3.makeRoot();
    System.out.println(Trie3.EVENT_COUNTS);
    t.add(u.lookupTrigger(r1.lhs));
    System.out.println(Trie3.EVENT_COUNTS);
    t.add(u.lookupTrigger(r2.lhs));
    System.out.println(Trie3.EVENT_COUNTS);

    addJohnLovesMary();
    HypEdge last = u.addEdgeToState(u.dbgMakeEdge("doneAnno(test0)", false), Adjoints.Constant.ZERO);

    State s = u.getState();
    t.match(s, last, m -> {
      System.out.println("[match] " + m);
    });
    System.out.println(Trie3.EVENT_COUNTS);

    HypEdge ev1 = s.add(u.dbgMakeEdge("event1(1-2)", false), Adjoints.Constant.ONE);
    t.match(s, ev1, m -> {
      System.out.println("[match after event1] " + m);
    });
    System.out.println(Trie3.EVENT_COUNTS);
  }
}
