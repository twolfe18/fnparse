package edu.jhu.util;

import edu.jhu.hlt.tutils.hash.Hash;

/**
 * An immutable pair which caches a 64 bit hash. The "H" is for "hash-able"
 * because this pair is a little more hash-friendly than {@link edu.jhu.prim.tuple.Pair}.
 *
 * @author travis
 */
public class HPair<L, R> {
  public final L left;
  public final R right;
  public final long hash;

  public HPair(L left, R right) {
    this.left = left;
    this.right = right;
    this.hash = Hash.mix64(left.hashCode(), right.hashCode());
  }

  @Override
  public int hashCode() {
    return (int) hash;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof HPair) {
      @SuppressWarnings("unchecked")
      HPair<L, R> h = (HPair<L, R>) other;
      return hash == h.hash && eq(left, h.left) && eq(right, h.right);
    }
    return false;
  }

  private static boolean eq(Object a, Object b) {
    if (a == null)
      return b == null;
    return a.equals(b);
  }
}
