package edu.jhu.hlt.uberts;

import edu.jhu.hlt.uberts.transition.TransitionGenerator;

/**
 * Every {@link HypNode} in the {@link State} hyper-graph has a {@link NodeType}.
 * For example, 'tokenIndex' or 'posTag' are {@link NodeType}.
 *
 * Right now this does not track a domain of all {@link HypNode} that have this
 * {@link NodeType}. This is done implicitly by those who create {@link HypNode}s,
 * presumably {@link TransitionGenerator}s. I currently don't see a good reason
 * to add a domain, as it just makes book-keeping more difficult. May bolt on
 * later.
 *
 * @author travis
 */
public class NodeType {
  private final String name;
//  private final boolean witness;
  // TODO put data type (e.g. Span) here?
  // TODO put alphabet here (e.g. Alphabet<Span>)?

  public NodeType(String name) {
//  public NodeType(String name, boolean witness) {
    this.name = name.intern();
//    this.witness = witness;
  }

//  public boolean isWitness() {
//    return witness;
//  }

  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return name;
  }
}
