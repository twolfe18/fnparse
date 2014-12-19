package edu.jhu.hlt.fnparse.util;

import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.parse.cky.data.BinaryTree;

public class BinaryTreeWithParent {
  private BinaryTreeWithParent parent;
  private BinaryTreeWithParent left, right;
  private Span span;

  public BinaryTreeWithParent(BinaryTree tree, BinaryTreeWithParent parent) {
    this.parent = parent;
    this.span = Span.getSpan(tree.getStart(), tree.getEnd());
    this.left = new BinaryTreeWithParent(tree.getLeftChild(), this);
    this.right = new BinaryTreeWithParent(tree.getRightChild(), this);
  }

  /** Root constructor */
  public BinaryTreeWithParent(BinaryTree tree) {
    this(tree, null);
  }

  public Span getSpan() {
    return span;
  }

  public BinaryTreeWithParent getSibling() {
    if (parent == null)
      return null;
    if (this == parent.left)
      return parent.right;
    else
      return parent.left;
  }
}
