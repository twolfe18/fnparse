package edu.jhu.hlt.uberts.srl;

import java.util.List;

import edu.jhu.hlt.fnparse.rl.full.FModel;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;

/**
 * Given labeled targets/predicates (frames), identify and label the semantic
 * arguments to each predicate.
 *
 * Currently uses a de-serialized {@link FModel}.
 *
 * @author travis
 */
public class Srl {
  public static boolean DEBUG = false;

  protected Uberts u;

  // Borrowed (assumed to already exist)
  protected NodeType tokenIndex;
  protected NodeType frames;
//  protected NodeType preds;       // head(event1)
  protected Relation event1;
  protected Relation event2;

  // Created
//  protected NodeType args;        // head(srl1)
  protected NodeType roles;
  protected Relation srl1;        // srl1(s)      -- location of arguments
  protected Relation srl2;        // srl2(t,s)    -- pred-arg binding
  protected Relation srl3;        // srl3(t,s,k)  -- semantic role

  /**
   * Don't create multiple {@link Srl}s per {@link Uberts}s, as the constructor
   * adds without checking if something is there already... TODO fix
   *
   * @param supervised if true, look for SRL labels in {@link Uberts#getDoc()}
   * and add them with {@link Uberts#addLabel(HypEdge)}.
   */
  public Srl(Uberts u) {
    this.u = u;
    this.tokenIndex = u.lookupNodeType("tokenIndex", false);
    this.frames = u.lookupNodeType("frames", false);
    this.roles = u.lookupNodeType("roles", true);
    this.event1 = u.getEdgeType("event1");
    this.event2 = u.getEdgeType("event2");
    this.srl1 = u.addEdgeType(new Relation("srl1", tokenIndex, tokenIndex));
//    this.preds = u.getWitnessNodeType(event1);
//    this.args = u.getWitnessNodeType(srl1);
//    this.srl2 = u.addEdgeType(new Relation("srl2", preds, args));
//    this.srl3 = u.addEdgeType(new Relation("srl3", u.getWitnessNodeType(srl2), roles));
    this.srl2 = u.addEdgeType(r)

    setupTransitions();
    setupHardFactors();

//    if (supervised) {
//      Document d = u.getDoc();
//      MultiAlphabet a = d.getAlphabet();
//      int cid = d.cons_propbank_gold;
//      if (cid == Document.UNINITIALIZED)
//        throw new RuntimeException();
//      NodeType frames = u.lookupNodeType("frames", false);
//      for (ConstituentItr sit = d.getConstituentItr(cid); sit.isValid(); sit.gotoRightSib()) {
//        Constituent pred = sit.getLeftChildC();
//
//
//        // TODO These event labels should go in FrameId!
//
//
//        // event1(t)
//        HypNode targetStart = u.lookupNode(tokenIndex, pred.getFirstToken(), true);
//        HypNode targetEnd = u.lookupNode(tokenIndex, pred.getLastToken()+1, true);
//        HypEdge event1e = u.makeEdge(event1, targetStart, targetEnd);
//        u.addLabel(event1e);
//
//        // event2(t,f)
//        if (pred.getLhs() >= 0) {
//          String label = a.srl(pred.getLhs());
//          HypNode labelNode = u.lookupNode(frames, label, true);
//          HypEdge event2e = u.makeEdge(event2, event1e.getHead(), labelNode);
//          u.addLabel(event2e);
//        }
//
//        for (ConstituentItr arg = pred.getRightSibCI(); arg.isValid(); arg.gotoRightSib()) {
//          // srl1(t)
//          HypNode argStart = u.lookupNode(tokenIndex, arg.getFirstToken(), true);
//          HypNode argEnd = u.lookupNode(tokenIndex, arg.getLastToken()+1, true);
//          HypEdge srl1e = u.makeEdge(srl1, argStart, argEnd);
//          u.addLabel(srl1e);
//          // srl2(t,s)
//          HypEdge srl2e = u.makeEdge(srl2, event1e.getHead(), srl1e.getHead());
//          u.addLabel(srl2e);
//          // srl3(t,s,k)
//          if (arg.getLhs() >= 0) {
//            String role = a.srl(arg.getLhs());
//            HypNode roleNode = u.lookupNode(roles, role, true);
//            HypEdge srl3e = u.makeEdge(srl3, srl2e.getHead(), roleNode);
//            u.addLabel(srl3e);
//          }
//        }
//      }
//    }

  }

  /**
   * This is the setup to be done for when the model is fully uberts-ized.
   * Currently I'm just using an {@link FModel}, which doesn't really need to
   * care about srl1,srl2, just srl3.
   */
  protected void setupTransitions() {
    Relation posRel = u.getEdgeType("pos");

    // () => srl1(s)
    // TODO add a table for constituents
    // TODO add a table for constituents derived using XUE_PALMER_HERMANN etc
    // Currently works by taking j from pos(j,t) and creating all srl1(i,j) s.t. j-i \in [0,W] for some max width W.
    TKey[] newSrl1 = new TKey[] {
        new TKey(posRel),
        new TKey(tokenIndex),
    };

    // srl1(s) ^ event1(t) => srl2(t,s)
    
    // srl2(t,s) ^ event2(t,f) => srl3(t,s,k) forall k in roles(f)
    
    throw new RuntimeException("finish me");
  }


  // Soft factors are local features, build into TransitionGenerator
  public void setupHardFactors() {
    // TODO
    Log.warn("TODO");
  }
}
