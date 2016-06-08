package edu.jhu.hlt.uberts.transition;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.Agenda;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.prim.tuple.Pair;
import edu.stanford.nlp.util.Iterables;

/**
 * Given that some graph fragment matched the current state, and its bindings
 * are stored in a {@link GraphTraversalTrace}, generate new {@link HypEdge}s
 * that will be added to the {@link Agenda}.
 *
 * @author travis
 *
 * @deprecated See {@link TransGen} instead.
 */
public interface TransitionGenerator {
  Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues);

  /**
   * Lets you chain together {@link TransitionGenerator}s.
   */
  public static class Composite implements TransitionGenerator {
    private TransitionGenerator left, right;
    public Composite(TransitionGenerator left, TransitionGenerator right) {
      this.left = left;
      this.right = right;
    }
    @SuppressWarnings("unchecked")
    @Override
    public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
      return Iterables.chain(
          left.generate(lhsValues),
          right.generate(lhsValues));
    }
  }
}
