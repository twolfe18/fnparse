package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;

import edu.jhu.hlt.fnparse.rl.full.Beam.StateLike;
import edu.jhu.hlt.fnparse.rl.full.State.HowToSearch;
import edu.jhu.hlt.fnparse.rl.full.State.StepScores;
import edu.jhu.hlt.fnparse.rl.full2.Node2.NodeWithSignature;

/**
 * Holds everything bigger than {@link Node2}, including {@link StepScores}.
 *
 * @author travis
 */
public class State2<T extends HowToSearch> implements StateLike {
  private Node2 root;
  private StepScores<T> scores;

  public State2(Node2 root, StepScores<T> scores) {
    if (root.prefix != null)
      throw new IllegalArgumentException("must be a root!");
    this.root = root;
    this.scores = scores;
  }

  public Node2 getRoot() {
    return root;
  }

  public StepScores<T> getStepScores() {
    return scores;
  }

  @Override
  public BigInteger getSignature() {
    return ((NodeWithSignature) root).getSignature();
  }

  @Override
  public String toString() {
    return String.format("(State\n\t%s\n\t%s\n)", scores.toString(), root.toString());
  }

  public static <T extends HowToSearch> StepScores<T> safeScores(State2<T> s) {
    if (s == null)
      return null;
    return s.scores;
  }

}