package edu.jhu.hlt.uberts.srl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;

/**
 * Takes a {@link RelDoc} which contains some argument4 facts. For roles which
 * don't have any realized argument, add an argument4 fact with s=nullSpan.
 *
 * ACTIONS: new tfk => new (tfk, nullSpan)
 * GOLD: new tf && no tfk => new (tfk, nullSpan) forall k in role2(f,*)
 *
 * ACTIONS is handled in the grammar (its too complex to do it another way since I would need to add plumbing to tell the code when its working on a new document)
 * GOLD is handled by this class. Should call a method on this instance upon seeing a {@link RelDoc}, perhaps in UbertsLearnPipeline.consume.
 *
 * @author travis
 */
public class AddNullSpanArgs {

  static class TFK {
    final Span t;
    final String f;
    final String k;
    final int hc;
    public TFK(Span t, String f, String k) {
      assert t != null && t != Span.nullSpan;
      assert f != null;
      this.t = t;
      this.f = f;
      this.k = k;
      this.hc = k == null
          ? Hash.mix(t.start, t.end, Hash.hash(f))
          : Hash.mix(t.start, t.end, Hash.hash(f), Hash.hash(k));
    }
    @Override
    public String toString() {
      return "(t=" + t.shortString() + " f=" + f + " k=" + k + ")";
    }
    @Override
    public int hashCode() {
      return hc;
    }
    @Override
    public boolean equals(Object other) {
      if (other instanceof TFK) {
        TFK x = (TFK) other;
        return t == x.t && f.equals(x.f)
            && (k == x.k || (k != null && k.equals(x.k)));
      }
      return false;
    }
  }

  private Uberts u; // used to lookup role2 facts
  private Relation role2;
  private Relation coarsenFrame2; // coarsenFrame(f,fc)
  private Relation argument4;
  private Relation predicate2;

  public AddNullSpanArgs(Uberts u) {
    this(u, u.getEdgeType("role2"), u.getEdgeType("coarsenFrame2"), u.getEdgeType("argument4"), u.getEdgeType("predicate2"));
  }
  public AddNullSpanArgs(Uberts u, Relation role2, Relation coarsenFrame2, Relation argument4, Relation predicate2) {
    if (u == null)
      throw new IllegalArgumentException();
    if (role2 == null)
      throw new IllegalArgumentException();
    if (coarsenFrame2 == null)
      throw new IllegalArgumentException();
    if (argument4 == null)
      throw new IllegalArgumentException();
    if (predicate2 == null)
      throw new IllegalArgumentException();
    this.u = u;
    this.role2 = role2;
    this.coarsenFrame2 = coarsenFrame2;
    this.argument4 = argument4;
    this.predicate2 = predicate2;
  }

  /**
   * Assumes all argument4 edges have the same properties. Copies those properties
   * to the newly created nullSpan facts.
   *
   * Does not modify {@link RelDoc} argument.
   */
  public List<HypEdge.WithProps> goldNullSpanFacts(RelDoc doc) {
    // Collect information about what arg4 facts are present
    Set<TFK> seenTF = new HashSet<>();
    Set<TFK> seenTFK = new HashSet<>();
    assert doc.items.isEmpty();
    Long properties = null;
    for (HypEdge.WithProps e : doc.facts) {
      if (e.getRelation() == predicate2) {
        if (properties == null)
          properties = e.getProperties();
        else
          assert properties.equals(e.getProperties());
        Span t = EdgeUtils.target(e);
        String f = EdgeUtils.frame(e);
        seenTF.add(new TFK(t, f, null));
      }
      if (e.getRelation() ==  argument4) {
        if (properties == null)
          properties = e.getProperties();
        else
          assert properties.equals(e.getProperties());
        Span t = EdgeUtils.target(e);
        String f = EdgeUtils.frame(e);
        String k = EdgeUtils.role(e);
        assert k != null;
        seenTFK.add(new TFK(t, f, k));
      }
    }

    List<HypEdge.WithProps> nullSpanFacts = new ArrayList<>();
    for (TFK tf : seenTF) {
      // Look up possible roles
      assert role2.getTypeForArg(0) == argument4.getTypeForArg(1);
      HypNode fNode = u.lookupNode(argument4.getTypeForArg(1), tf.f, false, true);

      // Coarsen f. Currently only relevant to PB, e.g. "propbank/kill-v-1" => "propbank/kill-v"
      HypEdge fCoarsenFact = u.getState().match1(0, coarsenFrame2, fNode);
      HypNode fcNode = fCoarsenFact.getTail(1);

      LL<HypEdge> roles = u.getState().match(0, role2, fcNode);
      // For each possible which isn't realized, instantiate nullSpan arg4 fact
      for (LL<HypEdge> cur = roles; cur != null; cur = cur.next) {
        String kRealized = (String) cur.item.getTail(1).getValue();
        TFK realized = new TFK(tf.t, tf.f, kRealized);
        if (!seenTFK.contains(realized)) {
          // Create a nullSpan arg4 fact
          HypNode tNode = u.lookupNode(argument4.getTypeForArg(0), tf.t.shortString(), false, false);
          HypNode sNode = u.lookupNode(argument4.getTypeForArg(2), Span.nullSpan.shortString());
          HypNode kNode = u.lookupNode(argument4.getTypeForArg(3), kRealized);
          HypEdge a4 = u.makeEdge(false, argument4, tNode, fNode, sNode, kNode);
          assert properties != null;
          nullSpanFacts.add(new HypEdge.WithProps(a4, properties));
        }
      }
    }
    return nullSpanFacts;
  }
}
