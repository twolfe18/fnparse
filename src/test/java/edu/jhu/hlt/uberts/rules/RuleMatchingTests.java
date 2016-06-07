package edu.jhu.hlt.uberts.rules;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.Labels.Perf;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Step;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Rule;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser;
import edu.jhu.hlt.uberts.auto.TransitionGeneratorForwardsParser.TG;
import edu.jhu.hlt.uberts.auto.UbertsPipeline;
import edu.jhu.hlt.uberts.factor.LocalFactor;
import edu.jhu.hlt.uberts.rules.Env.Trie3;
import edu.jhu.prim.tuple.Pair;

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

  @Before
  public void setup() {
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
    u.addEdgeToState(u.dbgMakeEdge("lemma2(0,John)"), Adjoints.Constant.ZERO);
    u.addEdgeToState(u.dbgMakeEdge("lemma2(1,love)"), Adjoints.Constant.ZERO);
    u.addEdgeToState(u.dbgMakeEdge("lemma2(2,Mary)"), Adjoints.Constant.ZERO);
    u.addEdgeToState(u.dbgMakeEdge("pos2(0,NNS)"), Adjoints.Constant.ZERO);
    u.addEdgeToState(u.dbgMakeEdge("pos2(1,VBZ)"), Adjoints.Constant.ZERO);
    return u.addEdgeToState(u.dbgMakeEdge("pos2(2,NNS)"), Adjoints.Constant.ZERO);
  }

  /**
   * Fails because of the order of 1) adding facts to state and 2) the order of the rule.
   * {@link UbertsPipeline} has a solution: add a parse of the rule which has each functor appearing first once.
   */
  @Test
  public void test0() {
    List<String> lhs = Arrays.asList("doneAnno(docid)", "pos2(i,pf)","lemma2(i,l)", "coarsenPos2(pf,pc)", "frameTriage4(l,pc,synset,frame)", "span-w1(target,i,j)");
    String rhs = "predicate2(target,frame)";

//    Set<HashableHypEdge> s0 = run(lhs, rhs);

//    Random r = new Random(9001);
//    for (int i = 0; i < 10; i++) {
//      List<String> lhs2 = new ArrayList<>();
//      lhs2.addAll(lhs);
//      Set<HashableHypEdge> s = run(lhs2, rhs);
//      assertEquals(s0, s);
//    }


    // NEW STUFF
    String rs = StringUtils.join(" & ", lhs) + " => " + rhs;
    Rule rule = Rule.parseRule(rs, u);

    Trie3 t = Trie3.makeRoot();
    rule.index = 0;
    t.add(rule);

    HypEdge last = addJohnLovesMary();
    last = u.addEdgeToState(u.dbgMakeEdge("doneAnno(test0)"), Adjoints.Constant.ZERO);

    State s = u.getState();
    t.match(s, last, m -> {
      System.out.println("[match] " + m);
    });
    System.out.println(Trie3.EVENT_COUNTS);
  }


  private Set<HashableHypEdge> run(List<String> lhs, String rhs) {
    String rule = StringUtils.join(" & ", lhs) + " => " + rhs;
    return run(rule);
  }

  private Set<HashableHypEdge> run(String rule) {
    Rule r = Rule.parseRule(rule, u);
    TransitionGeneratorForwardsParser p = new TransitionGeneratorForwardsParser();
    Pair<List<TKey>, TG> x = p.parse2(r, u);

    System.out.println();
    System.out.println(r);
    System.out.println();
    System.out.println(StringUtils.join("\n", x.get1()));
    System.out.println();

    x.get2().feats = LocalFactor.Constant.ONE;  // always assign score=1 to edges so they are kept
    u.addTransitionGenerator(x);

//    Uberts.DEBUG = 3;
    addJohnLovesMary();

    Pair<Perf, List<Step>> t = u.dbgRunInference();

    System.out.println(t.get2());
    Set<HashableHypEdge> s = new HashSet<>();
    for (Step st : t.get2())
      s.add(new HashableHypEdge(st.edge));
    return s;
  }

}
