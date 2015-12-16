package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.tutils.hash.Hash;

/**
 * A special linked list which keeps track of TVs (basically an index), allowing
 * you to ask for a specific value in contant time. Has the same append
 * complexity as a LL with a slightly worse constant. Also implements a little
 * algebra which will let you automatically tell if you've seen conflicting
 * values for a given type/field.
 *
 * @author travis
 */
public class TFKS extends LL<TV> {

  public static final int UNSET = -2;
  public static final int MANY_VALS = -1;

  // The little algebra that is used for each [T,F,K,S] value
  // 0 = UNSET
  // UNSET + UNSET = UNSET
  // UNSET + MANY_VALS = MANY_VALS
  // UNSET + value = value
  // MANY_VALS + MANY_VALS = MANY_VALS
  // MANY_VALS + value = MANY_VALS
  // value + value = value
  // value + value' = MANY_VALS

  // Types
  public static final int T = 0;
  public static final int F = 1;
  public static final int K = 2;
  public static final int S = 3;

  // Values
  public final int t, f, k, s;

  public TFKS(TV item, TFKS next) {
    super(item, next);
    switch (item.getType()) {
    case T:
      t = or(safeT(next), item.getValue());
      f = safeF(next);
      k = safeK(next);
      s = safeS(next);
      break;
    case F:
      t = safeT(next);
      f = or(safeF(next), item.getValue());
      k = safeK(next);
      s = safeS(next);
      break;
    case K:
      t = safeT(next);
      f = safeF(next);
      k = or(safeK(next), item.getValue());
      s = safeS(next);
      break;
    case S:
      t = safeT(next);
      f = safeF(next);
      k = safeK(next);
      s = or(safeS(next), item.getValue());
      break;
    default:
      throw new RuntimeException();
    }
  }

  public boolean isFull() {
    if (s >= 0) {
      assert t >= 0;
      assert f >= 0;
      assert k >= 0;
      return true;
    }
    return false;
  }

  // Use super.toString to actually see the LL
  public String str() {
    return "(T=" + str(t)
      + " F=" + str(f)
      + " K=" + str(k)
      + " S=" + str(s)
      + ")";
  }

  public static String str(int i) {
    if (i == UNSET) return "UNSET";
    if (i == MANY_VALS) return "MANY_VALS";
    return String.valueOf(i);
  }

  public static int safeT(TFKS maybeNull) {
    if (maybeNull == null)
      return UNSET;
    return maybeNull.t;
  }

  public static int safeF(TFKS maybeNull) {
    if (maybeNull == null)
      return UNSET;
    return maybeNull.f;
  }

  public static int safeK(TFKS maybeNull) {
    if (maybeNull == null)
      return UNSET;
    return maybeNull.k;
  }

  public static int safeS(TFKS maybeNull) {
    if (maybeNull == null)
      return UNSET;
    return maybeNull.s;
  }

  public static int or(int valueA, int valueB) {
    if (valueA > valueB)
      return or(valueB, valueA);

    if (valueA == UNSET && valueB == UNSET)
      return UNSET;

    if (valueA == UNSET && valueB == MANY_VALS)
      return MANY_VALS;

    if (valueA == UNSET && valueB >= 0)
      return valueB;

    if (valueA == MANY_VALS)
      return MANY_VALS;

    assert valueA >= 0;

    if (valueA == valueB)
      return valueA;

    return MANY_VALS;

  // 0 = UNSET
  // UNSET + UNSET = UNSET
  // UNSET + MANY_VALS = MANY_VALS
  // UNSET + value = value
  // MANY_VALS + MANY_VALS = MANY_VALS
  // MANY_VALS + value = MANY_VALS
  // value + value = value
  // value + value' = MANY_VALS
  }

  @Override
  public int hashCode() {
    return Hash.mix(t, f, k, s);
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof TFKS) {
      TFKS a = (TFKS) other;
      return t == a.t
          && f == a.f
          && k == a.k
          && s == a.s;
    }
    return false;
  }
}