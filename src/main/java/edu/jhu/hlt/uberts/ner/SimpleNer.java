package edu.jhu.hlt.uberts.ner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.Document.TokenItr;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.factor.AtMost1;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;

/**
 * Goal is to get a simple NER system trained as fast as possible.
 *
 * ner3(tokenIndex, tag, biolu)
 *
 * @author travis
 */
public class SimpleNer {

  /*
   * From Ratinov and Roth (2009):
   * "This conditional probability distribution is estimated in NER using the
   * following baseline set of features (Zhang and Johnson, 2003):
   * (1) previous two predictions y_{i−1} and y_{i−2}
   * (2) current word x_i
   * (3) x_i word type (all-capitalized, is-capitalized, all-digits, alphanumeric, etc.)
   * (4) prefixes and suffixes of x_i
   * (5) tokens in the window c = (x_{i−2}, x_{i−1}, x_i, x_{i+1}, x_{i+2})
   * (6) capitalization pattern in the window c
   * (7) conjunction of c and y_{i−1}."
   */
  public int[] features(int tokenIndex) {
    int[] f = new int[10];

    State s = u.getState();

    String nerPrevTag = null;
    HypNode prev = u.lookupNode(this.tokenIndex, tokenIndex-1, false);
    if (prev != null) {
      HypEdge nerPrev = s.match1(ner3, prev);
      nerPrevTag = (String) nerPrev.getTail(1).getValue();
      nerPrevTag = ((String) nerPrev.getTail(2).getValue()) + "-" + nerPrevTag;
      f[0] = nerPrevTag.hashCode();
    }

    String nerPrevPrevTag = null;
    HypNode prevPrev = u.lookupNode(this.tokenIndex, tokenIndex-2, false);
    if (prevPrev != null) {
      HypEdge nerPrevPrev = s.match1(ner3, prevPrev);
      nerPrevPrevTag = (String) nerPrevPrev.getTail(1).getValue();
      nerPrevPrevTag = ((String) nerPrevPrev.getTail(2).getValue()) + "-" + nerPrevPrevTag;
      f[1] = nerPrevPrevTag.hashCode();
    }

    if (nerPrevTag != null && nerPrevPrevTag != null)
      f[2] = (nerPrevTag + "_" + nerPrevPrevTag).hashCode();

    Document d = u.getDoc();
    f[3] = d.getWord(tokenIndex);

    // TODO more features

    return f;
  }

  public static class IdxHypNode extends HypNode {
    private int index;
    public IdxHypNode(NodeType type, Object value, int index) {
      super(type, value);
      this.index = index;
    }
    public int getIndex() {
      return index;
    }
  }

  // Given
  private NodeType tokenIndex;

  // New
  private NodeType nerTag;
  private NodeType biolu;
  private IdxHypNode[] nerTagValues;
  private IdxHypNode[] bioluValues;
  private IdxHypNode O_biolu;
  private Relation ner3;

  // Params
  private AveragedPerceptronWeights[][] theta;    // indexed by [tag][biolu]

  // Misc
  private Uberts u;

  /**
   * Uses the {@link Document} for words and number of tokens.
   * @param supervised if true means take the NER labels in the given document
   * and add them to the {@link Uberts} as supservision.
   */
  public SimpleNer(Uberts u, boolean supervised) {
    if (u.getDoc() == null)
      throw new IllegalArgumentException("doc is used to know how many tokens there are (...and get words)");
    this.u = u;
    tokenIndex = u.lookupNodeType("tokenIndex", false);
    nerTag = u.lookupNodeType("nerTag", true);
    biolu = u.lookupNodeType("biolu", true);
    ner3 = u.addEdgeType(new Relation("ner3", tokenIndex, nerTag, biolu));

    // CoNLL 2003 tags for now
    nerTagValues = new IdxHypNode[] {
        new IdxHypNode(nerTag, "PER", 0),
        new IdxHypNode(nerTag, "ORG", 1),
        new IdxHypNode(nerTag, "LOC", 2),
        new IdxHypNode(nerTag, "MISC", 3),
    };
    bioluValues = new IdxHypNode[] {
        new IdxHypNode(biolu, "B", 0),
        new IdxHypNode(biolu, "I", 1),
        O_biolu = new IdxHypNode(biolu, "O", 2),
        new IdxHypNode(biolu, "L", 3),
        new IdxHypNode(biolu, "U", 4),
    };
    for (IdxHypNode n : nerTagValues)
      u.putNode(n);
    for (IdxHypNode n : bioluValues)
      u.putNode(n);

    int T = nerTagValues.length;
    int B = bioluValues.length;
    int D = 1 << 18;    // dimension of weights
    this.theta = new AveragedPerceptronWeights[T][B];
    for (int t = 0; t < T; t++)
      for (int b = 0; b < B; b++)
        this.theta[t][b] = new AveragedPerceptronWeights(D, 0);

    // ner3(i,t,b) => ner3(i+1,t',b') forall t',b'
    TKey[] newNerLabel = new TKey[] {
        new TKey(ner3),
    };
    u.addTransitionGenerator(newNerLabel, new TransitionGenerator() {
      @Override
      public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
        HypEdge e = lhsValues.getBoundEdge(0);
        assert e.getRelation() == ner3;
        assert e.getTail(0).getNodeType() == tokenIndex;
        int i = (Integer) e.getTail(0).getValue();
        if (i >= getNumTokens()) {
          // We've reached the end of the document
          return Collections.emptyList();
        }
        List<Pair<HypEdge, Adjoints>> newHyps = new ArrayList<>();
        HypNode ipp = u.lookupNode(tokenIndex, i+1, true);
        for (IdxHypNode t : nerTagValues) {
          for (IdxHypNode b : bioluValues) {    // TODO Check previous tag to see what tags are allowable
            HypEdge newE = u.makeEdge(ner3, ipp, t, b);
            AveragedPerceptronWeights w = theta[t.getIndex()][b.getIndex()];
            int[] f = features(i+1);
            boolean reindex = true;
            Adjoints score = w.score(f, reindex);

            // Get these scores above 0 to get them selected
            score = Adjoints.sum(score, Adjoints.Constant.ONE);

            newHyps.add(new Pair<>(newE, score));
          }
        }
        return newHyps;
      }
    });

    // ner3(i,*,*) => !ner(j,*,*) for i!=j
    u.addGlobalFactor(newNerLabel, new AtMost1.RelNode1(ner3, gtt -> {
        HypEdge e = gtt.getBoundEdge(0);
        assert e.getRelation() == ner3;
        HypNode i = e.getTail(0);
        assert i.getNodeType() == tokenIndex;
        return i;
    }));

    // Supervision
    if (supervised) {
      Document d = u.getDoc();
      MultiAlphabet a = d.getAlphabet();
      for (TokenItr tok = d.getTokenItr(0); tok.isValid(); tok.forwards()) {
        assert tok.getNerG() >= 0;
        String nerTagBiolu = a.ner(tok.getNerG());
        String[] ntb = nerTagBiolu.split("-");
        assert (ntb.length == 1 && ntb[0].equals("O"))
            || ntb.length == 2 : "should be <biolu>-<tag>: " + nerTagBiolu + ", " + Arrays.toString(ntb);
        HypNode i = u.lookupNode(tokenIndex, tok.getIndex(), true);
        HypNode b = u.lookupNode(biolu, ntb[0], false);
        HypNode t = b == O_biolu
            ? nerTagValues[0]
            : u.lookupNode(nerTag, ntb[1], false);
        HypEdge goldEdge = u.makeEdge(ner3, i, t, b);
        u.addLabel(goldEdge);
      }
    }
  }

  // Implementation of this could change...
  private int getNumTokens() {
    Document d = u.getDoc();
    if (d == null)
      throw new IllegalStateException("need document");
    return d.numTokens();
  }

  /**
   * Builds a tag for tokenIndex=-1 to kick things off L2R.
   */
  public HypEdge makeRootEdge() {
    HypNode i = u.lookupNode(tokenIndex, -1, true);
    HypNode t = nerTagValues[0];
    HypEdge e = u.makeEdge(ner3, i, t, O_biolu);
    return e;
  }

  public static Document getTestDocument() {
    File f = new File("data/ner/conll2003/ner/eng.train");
    assert f.isFile();
    try (CoNLL03Reader r = new CoNLL03Reader(f)) {
      assert r.hasNext();
      Document d = r.next();
      assert d != null;
      return d;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void main(String[] args) {
    ExperimentProperties.init(args);
    Random rand = new Random(9001);
    Uberts u = new Uberts(rand);
    u.setDocument(getTestDocument());
    u.lookupNodeType("tokenIndex", true);
    boolean supervised = true;
    SimpleNer ner = new SimpleNer(u, supervised);
    Log.info("numTokens=" + ner.getNumTokens());
    u.addEdgeToState(ner.makeRootEdge());
    u.dbgRunInference();
    u.getState().dbgShowEdges();
  }
}
