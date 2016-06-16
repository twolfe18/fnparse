package edu.jhu.hlt.uberts;

import edu.jhu.hlt.tutils.hash.Hash;

/**
 * Every {@link HypNode} in the {@link State} hyper-graph has a {@link NodeType}.
 * For example, 'tokenIndex' or 'posTag' are {@link NodeType}.
 *
 * Right now this does not track a domain of all {@link HypNode} that have this
 * {@link NodeType}. I currently don't see a good reason to add a domain, as it
 * just makes book-keeping more difficult. May bolt on later.
 *
 * @author travis
 */
public class NodeType {
  private final String name;
  private final int hash;

  public NodeType(String name) {
    this.name = name.intern();
    this.hash = Hash.hash(name);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof NodeType)
      return name == ((NodeType) other).name;
    return false;
  }

  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return name;
  }
}
