package edu.jhu.hlt.uberts;

import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;

public interface NewStateEdgeListener {
  void addedToState(HashableHypEdge e, Adjoints s, Boolean gold);
}