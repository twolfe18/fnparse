package edu.jhu.hlt.uberts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import edu.jhu.hlt.tutils.Log;
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
  NodeType bool = new NodeType("bool");
  String[] sent;
  List<HypNode> tokens;
  List<HypNode> posTags;

  public void generalSetup() {
    u = new Uberts(new Random(9001));
    tokenIndex = new NodeType("tokenIndex");
    word = new NodeType("word");
    posTag = new NodeType("posTag");
    nerTag = new NodeType("nerTag");
    bool = new NodeType("bool");
    u.addEdgeType(new Relation("word", tokenIndex, word));
    u.addEdgeType(new Relation("pos", tokenIndex, posTag));
    u.addEdgeType(new Relation("ner", tokenIndex, tokenIndex, nerTag));
    u.addEdgeType(new Relation("coref", tokenIndex, tokenIndex, tokenIndex, tokenIndex, bool));

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

    // This is how we convert "pos(i,*) => pos(i+1,*)" into code
    posTags = new ArrayList<>();
    //    for (String p : Arrays.asList("N", "V", "PP", "PRP", "DT", "A", "J"))
    //    for (String p : Arrays.asList("N", "V", "OTHER"))
    //    for (String p : Arrays.asList("N", "V"))
    for (String p : Arrays.asList("N"))
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
    newPosGraphFragment.getValue().u = u;
    assert newPosGraphFragment.getValue().tg == null;
    newPosGraphFragment.getValue().tg = new TransitionGenerator() {
      @Override
      public Iterable<HypEdge> generate(GraphTraversalTrace lhsValues) {
        int i = (Integer) lhsValues.getValueFor(tokenIndex).getValue();
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
    newPosGraphFragment.getValue().gf = new AtMost1(u.getEdgeType("pos"), tokenIndex);
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
      HypEdge best = a.pop();
      Log.info("best=" + best);
      u.addEdgeToState(best);
    }

    // Print out the state graph
    u.getState().dbgShowEdges();
  }

  @Test
  public void nerTestSetup() {
    generalSetup();

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
        Log.info("INTERESTING");
        int i = (Integer) lhsValues.getValueFor(tokenIndex).getValue();
        int j0 = Math.max(0, (i - maxEntWidth) + 1);
        HypNode PER = u.lookupNode(nerTag, "PER");
        HypNode end = u.lookupNode(tokenIndex, i);
        List<HypEdge> el = new ArrayList<>();
        for (int j = j0; j <= i; j++) {
          HypNode start = u.lookupNode(tokenIndex, j);
          HypNode[] tail = new HypNode[] {start, end, PER};
          el.add(u.makeEdge("ner", tail));
        }
        return el;
      }
    };

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
        s.add(p.get1());
      else
        break;
    }

    Log.info("after adding NER:");
    a.dbgShowScores();
    s.dbgShowEdges();
  }

  /**
   * Just do pos tagging with the transition rule `pos(i,*) => pos(i+1,*)` and
   * random scores.
   */
//  @Test
  public void test0() {
    generalSetup();
    posTestKickoff();
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
//  @Test
  public void fullTest() {
    throw new RuntimeException("implement me");
  }
}
