package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
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
public class TFKS extends LL<TVNS> {

  public static FrameIndex dbgFrameIndex = null;

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

  public final BigInteger primesProd;

  /** Inserts a zero score to promote {@link TVN} to {@link TVNS} */
  public static TFKS lltvn2tfks(LL<TVN> l) {
    throw new RuntimeException("implement me with a deque");
  }

  public TFKS(TVNS item, TFKS next) {
    super(item, next);
    if (next == null)
      primesProd = BigInteger.valueOf(item.prime);
    else
      primesProd = BigInteger.valueOf(item.prime).multiply(next.primesProd);
    switch (item.type) {
    case T:
      t = or(safeT(next), item.value);
      f = safeF(next);
      k = safeK(next);
      s = safeS(next);
      break;
    case F:
      t = safeT(next);
      f = or(safeF(next), item.value);
      k = safeK(next);
      s = safeS(next);
      break;
    case K:
      t = safeT(next);
      f = safeF(next);
      k = or(safeK(next), item.value);
      s = safeS(next);
      break;
    case S:
      t = safeT(next);
      f = safeF(next);
      k = safeK(next);
      s = or(safeS(next), item.value);
      break;
    default:
      throw new RuntimeException();
    }
    if (dbgFrameIndex != null && f >= 0 && k >= 0) {
      Frame ff = dbgFrameIndex.getFrame(f);
      assert k < ff.numRoles() : "k=" + k + " but " + ff.getName() + " only has " + ff.numRoles() + " roles";
    }
  }

  @Override
  public TFKS cdr() {
    return (TFKS) next;
  }

  /** Only sets type and value fields */
  public TFKS dumbPrepend(int type, int value) {
    TVNS t = new TVNS(type, value, -1, -1, 1, null, Double.NaN);
    return new TFKS(t, this);
  }
  public TFKS dumbPrepend(TVN typeValue) {
    return dumbPrepend(typeValue.type, typeValue.value);
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

  @Override
  public String toString() {
    return "(TFKS " + str() + " " + super.toString() + ")";
  }
  private static String str(int i) {
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

  public static boolean isRefinement(TFKS coarse, TFKS fine) {
    if (coarse == null)
      return fine.car().type == T;
    int c = coarse.car().type;
    int f = fine.car().type;
    return f == c+1;
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

  public static BigInteger getPrimesProd(TFKS l) {
    if (l == null)
      return BigInteger.ONE;
    return l.primesProd;
  }
  public BigInteger getPrimesProd() {
    return primesProd;
  }
}