package edu.jhu.hlt.uberts.transition;

import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.TNode;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;

public interface TransitionGenerator {
  Iterable<HypEdge> generate(GraphTraversalTrace lhsValues);
}