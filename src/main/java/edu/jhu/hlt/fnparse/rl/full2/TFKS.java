package edu.jhu.hlt.fnparse.rl.full2;

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

}