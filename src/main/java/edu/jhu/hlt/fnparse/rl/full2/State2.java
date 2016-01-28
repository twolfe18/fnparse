package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;

import edu.jhu.hlt.fnparse.rl.full.StateLike;
import edu.jhu.hlt.fnparse.rl.full.StepScores;

/**
 * Holds everything bigger than {@link Node2}, including {@link StepScores}.
 *
 * TODO Consider removing this. Things are pretty uniform in all being stored
 * in {@link Node2}. This would be useful for operations which may only be done
 * on the root of a tree.
 *
 * @author travis
 */
public class State2<T> implements StateLike {
  private Node2 root;
  private T info;
  private int hash;
  public String dbgString;

  public State2(Node2 root, T info) {
    this(root, info, "");
  }

  public State2(Node2 root, T info, String dbgString) {
    if (root.prefix != null)
      throw new IllegalArgumentException("must be a root!");
    this.root = root;
    this.info = info;
    this.dbgString = dbgString;
    this.hash = root.hashCode();
  }

  public T getInfo() {
    return info;
  }

  public Node2 getRoot() {
    return root;
  }

  public StepScores<?> getStepScores() {
    return root.getStepScores();
  }

  @Override
  public BigInteger getSignature() {
    return root.getSig();
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof State2) {
      State2<?> s = (State2<?>) other;
      return hash == s.hash && root.equals(s.root);
    }
    return false;
  }

  @Override
  public String toString() {
    if (dbgString == null || dbgString.isEmpty())
      return "(State " + root + ")";
    return "(State " + dbgString + " " + root + ")";
  }

  public static StepScores<?> safeScores(State2<?> s) {
    if (s == null)
      return null;
    return s.getStepScores();
  }

}