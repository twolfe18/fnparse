package edu.jhu.hlt.uberts.auto;

import edu.jhu.hlt.tutils.Either;
import edu.jhu.hlt.uberts.HNode;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.TKey;

/**
 * The type-level analog of {@link HNode}, represents the type of a node in the
 * bi-partite graph of {@link HypNode}s and {@link HypEdge}s. Holds either a
 * {@link Relation} or a {@link NodeType}.
 *
 * Caches a {@link TKey} for building LHSs for uberts.
 *
 * TODO: Slightly overlaps with {@link TKey} when mode is RELATION or NODE_TYPE.
 *
 * @author travis
 */
public class HNodeType extends Either<NodeType, Relation> {
  private TKey tkey;
  private int argPos;

  protected HNodeType(int argPos, NodeType left, Relation right) {
    super(left, right);
    if (argPos < 0)
      throw new IllegalArgumentException("argPos=" + argPos);
    this.argPos = argPos;
    this.tkey = null;
  }

  public HNodeType(int argPos, NodeType left) {
    this(argPos, left, null);
  }

  public HNodeType(int argPos, Relation right) {
    this(argPos, null, right);
  }

  public TKey getTKey() {
    if (tkey == null) {
      if (isLeft())
        tkey = new TKey(argPos, getLeft());
      else
        tkey = new TKey(argPos, getRight());
    }
    return tkey;
  }

  public boolean isNodeType() {
    return isLeft();
  }

  public boolean isRelation() {
    return isRight();
  }
}