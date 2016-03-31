package edu.jhu.hlt.uberts.ner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.apache.commons.math3.analysis.solvers.MullerSolver;

import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.Document.TokenItr;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
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


  // Given
  private NodeType tokenIndex;

  // New
  private NodeType nerTag;
  private NodeType biolu;
  private HypNode[] nerTagValues;
  private HypNode[] bioluValues;
  private HypNode O_biolu;
  private Relation ner3;

  // Params
  private AveragedPerceptronWeights theta;

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
    nerTagValues = new HypNode[] {
        u.lookupNode(nerTag, "PER"),
        u.lookupNode(nerTag, "ORG"),
        u.lookupNode(nerTag, "LOC"),
        u.lookupNode(nerTag, "MISC"),
    };

    bioluValues = new HypNode[] {
        u.lookupNode(biolu, "B"),
        u.lookupNode(biolu, "I"),
        O_biolu = u.lookupNode(biolu, "O"),
        u.lookupNode(biolu, "L"),
        u.lookupNode(biolu, "U"),
    };

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
        HypNode ipp = u.lookupNode(tokenIndex, i+1);
        for (HypNode t : nerTagValues) {
          for (HypNode b : bioluValues) {    // TODO Check previous tag to see what tags are allowable
            HypEdge newE = u.makeEdge(ner3, ipp, t, b);
            Adjoints score = new Adjoints.Constant(u.getRandom().nextGaussian()); // TODO
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
        HypNode i = u.lookupNode(tokenIndex, tok.getIndex());
        HypNode t = u.lookupNode(nerTag, a.ner(tok.getNerG()));
        HypNode b = O_biolu;  // TODO
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
    HypNode i = u.lookupNode(tokenIndex, -1);
    HypNode t = u.lookupNode(nerTag, nerTagValues[0]);
    return u.makeEdge(ner3, i, t, O_biolu);
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
//    ExperimentProperties config = ExperimentProperties.init(args);
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
