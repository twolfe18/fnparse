package edu.jhu.hlt.uberts.auto;

import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.TKey;

/**
 * When typing {@link Relation}s, this describes the thing to type.
 * This was created for {@link TypeInference}, and is newer than things like
 * {@link HNodeType} and {@link TKey}.
 *
 * @author travis
 */
public class Arg {
  // Index of the head/fact/witness NodeType w.r.t. a HypEdge/Relation.
  // Args with argPos=WITNESS_ARGPOS may only have their type READ, not set,
  // since the head NodeType is determined by the Relation's arguments.
  public static final int WITNESS_ARGPOS = -1;

  // Don't use Relation! Multiple Terms/Rules may have different Relation
  // instances. TypeInference maintains maps keyed on relNameString to de-dup.
  public final String relation;
  public final int argPos;      // -1 for fact/head/witness
  public final int hash;

  public Arg(String relation, int argPos) {
    if (relation == null)
      throw new IllegalArgumentException();
    if (argPos < 0 && argPos != WITNESS_ARGPOS)
      throw new IllegalArgumentException();
    this.relation = relation;
    this.argPos = argPos;
    this.hash = Hash.mix(relation.hashCode(), argPos);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof Arg) {
      Arg a = (Arg) other;
      return argPos == a.argPos && relation.equals(a.relation);
    }
    return false;
  }

  @Override
  public String toString() {
    if (argPos < 0)
      return relation + "-head";
    return relation + "-arg" + argPos;
  }
}