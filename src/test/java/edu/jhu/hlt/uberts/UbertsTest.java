package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import org.junit.Test;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.factor.AtMost1;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;

public class UbertsTest {

  Uberts u;
  NodeType tokenIndex = new NodeType("tokenIndex");
  NodeType word = new NodeType("word");
  NodeType posTag = new NodeType("posTag");
  NodeType nerTag = new NodeType("nerTag");
  String[] sent;
  List<HypNode> tokens;
  List<HypNode> posTags;

  public void generalSetup() {
    u = new Uberts(new Random(9001));
    tokenIndex = new NodeType("tokenIndex");
    word = new NodeType("word");
    posTag = new NodeType("posTag");
    nerTag = new NodeType("nerTag");
    u.addEdgeType(new Relation("word", tokenIndex, word));
    u.addEdgeType(new Relation("pos", tokenIndex, posTag));
    u.addEdgeType(new Relation("ner", tokenIndex, tokenIndex, nerTag)); // (inclusive, exclusive, tag)
    u.addEdgeType(new Relation("coref", tokenIndex, tokenIndex, tokenIndex, tokenIndex)); // (inclusive, exclusive, inclusive, exclusive)

    tokens = new ArrayList<>();
//    sent = new String[] {"<s>", "This", "is", "a", "very", "long", "run", "on", "sentence", "designed" , "to", "help", "me", "observe", "interesting", "events", "which", "do", "not", "occur", "in", "shorter", "sentences"};
    sent = new String[] {"<s>", "John", "loves", "Mary", "more", "than", "himself"};
//    sent = new String[] {"<s>", "short", "sentence"};
    for (int i = 0; i < sent.length; i++) {
      HypNode[] tail = new HypNode[] {
          u.lookupNode(tokenIndex, i),
          u.lookupNode(word, sent[i].intern()),
      };
      HypEdge e = u.makeEdge("word", tail);
      u.addEdgeToState(e);
      tokens.add(u.lookupNode(tokenIndex, i));
    }
    Log.info("sent.length=" + sent.length);

    // This is how we convert "pos(i,*) => pos(i+1,*)" into code
    posTags = new ArrayList<>();
//    for (String p : Arrays.asList("N", "V", "PP", "PRP", "DT", "A", "J"))
    for (String p : Arrays.asList("N", "V", "OTHER"))
//    for (String p : Arrays.asList("N", "V"))
//    for (String p : Arrays.asList("N"))
      posTags.add(u.lookupNode(posTag, p.intern()));
  }

  public void posTestSetup() {
    // pos(i,*) => pos(i+1,*)
    TNode newPosGraphFragment;
    //    t = u.trie.lookup(new TKey[] {
    //        new TKey(u.getEdgeType("pos")),
    //        new TKey(posTag),
    //        TNode.GOTO_PARENT,
    //        new TKey(tokenIndex)}, true);
    // If we don't actually need the POS tag, I suppose we could write:
    newPosGraphFragment = u.getGraphFragments().lookup(new TKey[] {
        new TKey(u.getEdgeType("pos")),
        new TKey(tokenIndex)}, true);
    assert newPosGraphFragment.getValue().u == null;
    newPosGraphFragment.getValue().u = u;
    newPosGraphFragment.getValue().tg = new TransitionGenerator() {
      @Override
      public Iterable<HypEdge> generate(GraphTraversalTrace lhsValues) {
        int i = (Integer) lhsValues.getBoundNode(1).getValue();
        i++;
        if (i == sent.length)
          return Collections.emptyList();
        List<HypEdge> pos = new ArrayList<>();
        for (HypNode pt : posTags) {
          Log.info("adding pos(" + i + ", " + pt.getValue() + ")");
          HypNode[] tail = new HypNode[] { tokens.get(i), pt };
          pos.add(u.makeEdge("pos", tail));
        }
        return pos;
      }
    };
    // Lets have a global factor of -inf for pos(i,T) => pos(i,S) if S!=T.
    Function<GraphTraversalTrace, HypNode> tokIdx = gtt -> gtt.getBoundNode(1);
    newPosGraphFragment.getValue().gf = new AtMost1.RelNode1(u.getEdgeType("pos"), tokIdx);
  }

  public void posTestKickoff() {
    // This should kick off pos(i,*) => pos(i+1,*)
    HypNode[] tail = new HypNode[] {
        u.lookupNode(tokenIndex, 0),
        u.lookupNode(posTag, "<s>"),
    };
    u.addEdgeToState(u.makeEdge("pos", tail));

    Agenda a = u.getAgenda();
    for (int i = 0; a.size() > 0; i++) {
      Log.info("choosing the best action, i=" + i + " size=" + a.size() + " cap=" + a.capacity());
      a.dbgShowScores();
      Pair<HypEdge, Adjoints> p = a.popBoth();
      HypEdge best = p.get1();
      if (best.getRelation() == u.getEdgeType("ner") && p.get2().forwards() < 0.5)
        continue;
      Log.info("best=" + best);
      u.addEdgeToState(best);
    }

    // Print out the state graph
    u.getState().dbgShowEdges();
  }

  /**
   * Just do pos tagging with the transition rule `pos(i,*) => pos(i+1,*)` and
   * random scores.
   */
//  @Test
  public void posTest() {
    generalSetup();
    posTestSetup();
    posTestKickoff();
  }

  public void nerTestSetup() {
    // Setup the NER rule:
    // pos(j,N) => ner(i,j,PER) s.t. i<=j and (j-i)+1<=5
    int maxEntWidth = 5;
    TNode trie = u.getGraphFragments();
    TNode newNounGraphFragment = trie.lookup(new TKey[] {
        new TKey(u.getEdgeType("pos")),
        new TKey(posTag, "N"),
        TNode.GOTO_PARENT,
        new TKey(tokenIndex),
    }, true);
    newNounGraphFragment.getValue().u = u;
    newNounGraphFragment.getValue().tg = new TransitionGenerator() {
      @Override
      public Iterable<HypEdge> generate(GraphTraversalTrace lhsValues) {
        int i = (Integer) lhsValues.getBoundNode(2).getValue();
        if (i == sent.length)
          return Collections.emptyList();
        HypNode end = u.lookupNode(tokenIndex, i+1);
        List<HypEdge> el = new ArrayList<>();
        for (String nerType : Arrays.asList("PER", "GPE", "ORG", "LOC", "MISC")) {
          HypNode PER = u.lookupNode(nerTag, nerType);
          int j0 = Math.max(0, (i - maxEntWidth) + 1);
          for (int j = j0; j <= i; j++) {
            HypNode start = u.lookupNode(tokenIndex, j);
            HypNode[] tail = new HypNode[] {start, end, PER};
            el.add(u.makeEdge("ner", tail));
          }
        }
        return el;
      }
    };

    // A given (i,j) span may only have one ner tag
    TNode newNerGraphFragment = trie.lookup(new TKey[] {
        new TKey(u.getEdgeType("ner")),
        new TKey(tokenIndex),
        TNode.GOTO_PARENT,
        new TKey(tokenIndex),
    }, true);
    newNerGraphFragment.getValue().u = u;
    newNerGraphFragment.getValue().gf = new AtMost1.RelNode2(
        u.getEdgeType("ner"),
        gtt -> gtt.getBoundNode(1),
        gtt -> gtt.getBoundNode(2));  // 2 and not 3 since GOTO_PARENT doesn't bind anything
  }

  /**
   * Manually add POS tags and do NER tagging with
   * `pos(j,N) => ner(i,j,PER) s.t. i<=j and (j-i)+1<=5`
   */
//  @Test
  public void nerTest() {
    generalSetup();
    nerTestSetup();

    // Setup all the POS tags to be N
    for (int i = 1; i < sent.length; i++) {
      HypNode[] tail = new HypNode[] {
          u.lookupNode(tokenIndex, i),
          u.lookupNode(posTag, "N"),
      };
      u.addEdgeToState(u.makeEdge("pos", tail));
    }

    Log.info("before adding NER:");
    Agenda a = u.getAgenda();
    State s = u.getState();
    a.dbgShowScores();
    s.dbgShowEdges();

    // Add what should be NER tags
    while (a.size() > 0) {
      Pair<HypEdge, Adjoints> p = a.popBoth();
      if (p.get2().forwards() > 0.5)
        u.addEdgeToState(p.get1());
      else
        break;
    }

    Log.info("after adding NER:");
    a.dbgShowScores();
    s.dbgShowEdges();
  }

  public void corefSetup() {
    TNode cn = u.getGraphFragments().lookup(new TKey[] {
        new TKey(u.getEdgeType("ner")),
        new TKey(u.lookupNode(nerTag, "PER")),
        new TKey(u.getEdgeType("ner")),
        new TKey(u.getWitnessNodeType("ner")),
    }, true);
    cn.getValue().u = u;
    cn.getValue().tg = new TransitionGenerator() {
      @Override
      public Iterable<HypEdge> generate(GraphTraversalTrace lhsValues) {
        Log.info("INTERESTING");
        HypEdge ner1 = lhsValues.getBoundEdge(0);
        HypEdge ner2 = lhsValues.getBoundEdge(2);
        assert ner1.getRelation() == u.getEdgeType("ner");
        assert ner2.getRelation() == u.getEdgeType("ner");

        int i = (Integer) ner1.getTail(0).getValue();
        int j = (Integer) ner1.getTail(1).getValue();
        String tag1 = (String) ner1.getTail(2).getValue();

        int k = (Integer) ner2.getTail(0).getValue();
        int l = (Integer) ner2.getTail(1).getValue();
        String tag2 = (String) ner2.getTail(2).getValue();

        assert i < j;
        assert k < l;

        if (!tag1.equals(tag2))
          return Collections.emptyList();

        Span s1 = Span.getSpan(i, j);
        Span s2 = Span.getSpan(k, l);
        if (s1.crosses(s2))
          return Collections.emptyList();

        HypEdge e = u.makeEdge("coref",
            u.lookupNode(tokenIndex, i),
            u.lookupNode(tokenIndex, j),
            u.lookupNode(tokenIndex, k),
            u.lookupNode(tokenIndex, l));
        return Arrays.asList(e);
      }
    };
  }

  /**
   * Jointly tag POS and NER.
   */
//  @Test
  public void posAndNerTest() {
    generalSetup();
    posTestSetup();
    nerTestSetup();
    posTestKickoff();
  }

  /**
   * Lets do a proof of concept with POS, NER, coref
   *
   * Transitions:
   * pos(i,*) => pos(i+1,*)
   * pos(i,N) => ner(j,i,PER) forall j s.t. i-j \in [0..K]
   * ner(i,j,PER) ^ ner(k,l,PER) => coref(i,j,k,l)
   *
   * Factors:
   * pos(i,*) ~ word(i)
   * pos(i,*) ~ pos(i-1,*)
   * ner(i,j,PER) ~ pos(i,*) ^ pos(j,*)
   * ner(i,j,PER) ~ word(k) if i<=k<=j
   * coref(i,j,k,l) ~ dist(j,k)
   */
  @Test
  public void fullTest() {
    generalSetup();
    posTestSetup();
    nerTestSetup();
    corefSetup();
    posTestKickoff();
  }
}
