package edu.jhu.hlt.fnparse.rl.full2;

import java.math.BigInteger;

import edu.jhu.hlt.fnparse.rl.full.Beam.StateLike;
import edu.jhu.hlt.fnparse.rl.full.HowToSearch;
import edu.jhu.hlt.fnparse.rl.full.StepScores;
import edu.jhu.hlt.fnparse.rl.full2.Node2.NodeWithSignature;

/**
 * Holds everything bigger than {@link Node2}, including {@link StepScores}.
 *
 * @author travis
 */
public class State2<T extends HowToSearch> implements StateLike {
  private Node2 root;
  private StepScores<T> scores;

  public String dbgString;

  public State2(Node2 root, StepScores<T> scores) {
    this(root, scores, "");
  }

  public State2(Node2 root, StepScores<T> scores, String dbgString) {
    if (root.prefix != null)
      throw new IllegalArgumentException("must be a root!");
    if (root.getLoss() != scores.getLoss()) {
      // Can I get rid of one of these losses?
      // => Node2 needs to have loss b/c for a variety of reasons around the fact that it is the only way to compute it (it knows pruned vs children) -- and things like children:LLML which requires Node2<:HasMaxLoss
      // => StepScores needs it b/c its job is to handle search coeficients
      throw new IllegalArgumentException("losses don't match");
    }
    this.root = root;
    this.scores = scores;
    this.dbgString = dbgString;
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