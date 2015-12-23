package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;

import edu.jhu.hlt.fnparse.rl.full.Beam.StateLike;
import edu.jhu.hlt.fnparse.rl.full.HowToSearch;
import edu.jhu.hlt.fnparse.rl.full.StepScores;

/**
 * Holds everything bigger than {@link Node2}, including {@link StepScores}.
 *
 * @author travis
 */
public class State2<T extends HowToSearch> implements StateLike {
  private Node2 root;
//  private StepScores<T> scores;

  public String dbgString;

  public State2(Node2 root) {
    this(root, "");
  }

  public State2(Node2 root, String dbgString) {
    if (root.prefix != null)
      throw new IllegalArgumentException("must be a root!");
    this.root = root;
    this.dbgString = dbgString;
  }

  public Node2 getRoot() {
    return root;
  }

  public StepScores<T> getStepScores() {
    return (StepScores<T>) root.getStepScores();
  }

  @Override
  public BigInteger getSignature() {
    return root.getSig();
  }

  @Override
  public String toString() {
//    return String.format("(State\n\t%s\n\t%s\n)", scores.toString(), root.toString());
    if (dbgString == null || dbgString.isEmpty())
      return "(State " + root + ")";
    return "(State " + dbgString + " " + root + ")";
  }

  public static <T extends HowToSearch> StepScores<T> safeScores(State2<T> s) {
    if (s == null)
      return null;
    return s.getStepScores();
  }

}