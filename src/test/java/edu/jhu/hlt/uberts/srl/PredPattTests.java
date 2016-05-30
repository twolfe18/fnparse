package edu.jhu.hlt.uberts.srl;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Before;
import org.junit.Test;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.srl.PredPatt.Target;

/**
 * {@link PredPatt} is currently not used.
 *
 * @author travis
 */
public class PredPattTests {
  private Uberts u;
  private NodeType tokenIndex;
  private NodeType posTag;
  private Relation posRel;

  @Before
  public void setup() {
    u = new Uberts(new Random(9001));

    // This is how we trigger PredPatt to add edges
    tokenIndex = u.lookupNodeType("tokenIndex", true);
    posTag = u.lookupNodeType("posTag", true);
    posRel = u.addEdgeType(new Relation("pos", tokenIndex, posTag));
  }

  @Test
  public void ioTest() throws IOException {
    // Run pred-patt on some concrete-stanford annotated Communications
    File rawComms = null;
    File annoComms = null;
    PredPatt pp = new PredPatt(u, annoComms);
    runInference();
  }

  @Test
  public void noIOTest() {
    // Register the TransitionGenerator
    List<Target> targets = Arrays.asList(
        new Target("doc1", Span.getSpan(1, 2)),
        new Target("doc1", Span.getSpan(4, 6)));
    PredPatt pp = new PredPatt(u, targets);
    runInference();
  }

  private void runInference() {
    // See if the TransitionGenerator fires
    for (int i = 0; i < 10; i++) {
      HypNode idx = u.lookupNode(tokenIndex, i, true);
      HypNode tag = u.lookupNode(posTag, "tag" + (i%3), true);
      HypEdge e = u.makeEdge(posRel, idx, tag);
      Log.info("adding on " + e);
      u.addEdgeToState(e, Adjoints.Constant.ZERO);
    }

    // Pull the edges off the agenda
    u.dbgRunInference();
  }
}
