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

  protected HNodeType(NodeType left, Relation right) {
    super(left, right);
    tkey = null;
  }

  public HNodeType(NodeType left) {
    this(left, null);
  }

  public HNodeType(Relation right) {
    this(null, right);
  }

  public TKey getTKey() {
    if (tkey == null) {
      if (isLeft())
        tkey = new TKey(getLeft());
      else
        tkey = new TKey(getRight());
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