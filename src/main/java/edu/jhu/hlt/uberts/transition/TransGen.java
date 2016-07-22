package edu.jhu.hlt.uberts.transition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypEdge.HashableHypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.Rule;
import edu.jhu.hlt.uberts.factor.LocalFactor;
import edu.jhu.prim.tuple.Pair;

public interface TransGen {

  public List<Pair<HypEdge, Adjoints>> match(HypEdge[] trigger, Uberts u);

  /**
   * Creates actions from a {@link Rule}, uses a {@link LocalFactor}.
   *
   * Currently outputs length-1 Lists every time because it accepts HypEdge[]
   * from Trie3. I could add a combiner phase to get them all into one list.
   */
  public static class Regular implements TransGen {
    private Rule rule;
    private LocalFactor score;

    // Pull in score from previous edges
    public boolean addLhsToRhsScore = false;
    public static final List<String> RELEVANT_RELATIONS =
        Arrays.asList("predicate2", "srl2", "srl3");

    private boolean pruneFactsWithNullScore;

    public Regular(Rule rule, LocalFactor score, boolean pruneFactsWithNullScore) {
      this.rule = rule;
      this.score = score;
      this.pruneFactsWithNullScore = pruneFactsWithNullScore;
      ExperimentProperties config = ExperimentProperties.getInstance();
      addLhsToRhsScore = config.getBoolean("addLhsScoreToRhsScore", false);
      Log.info("[main] addLhsToRhs=" + addLhsToRhsScore
          + " " + rule
          + " " + score
          + " pruneFactsWithNullScore=" + pruneFactsWithNullScore);
    }

    @Override
    public List<Pair<HypEdge, Adjoints>> match(HypEdge[] trigger, Uberts u) {
      HypEdge e = instantiateRule(trigger, rule, u);
      Adjoints s = score.score(e, u);

      if (s == null) {
        assert pruneFactsWithNullScore;
        return Collections.emptyList();
      }

      if (addLhsToRhsScore) {
        State st = u.getState();
        for (int i = 0; i < trigger.length; i++) {
          HypEdge fact = trigger[i];
          if (RELEVANT_RELATIONS.contains(fact.getRelation().getName())) {
            Adjoints fs = st.getScore(new HashableHypEdge(fact));
            s = Adjoints.sum(s, new Adjoints.Constant(fs.forwards()));
          }
        }
      }

      return Arrays.asList(new Pair<>(e, s));
    }
  }

  public static HypEdge instantiateRule(HypEdge[] boundLhsValues, Rule rule, Uberts u) {
    // For every variable in the RHS Term, ask for its binding in the LHS.
    int n = rule.rhs.getNumArgs();
    HypNode[] tail = new HypNode[n];
    for (int rhsArg = 0; rhsArg < n; rhsArg++) {
      IntPair lhsBinding = rule.getBindingOfRhsArg(rhsArg);
      if (lhsBinding.second == State.HEAD_ARG_POS)
        tail[rhsArg] = boundLhsValues[lhsBinding.first].getHead();
      else
        tail[rhsArg] = boundLhsValues[lhsBinding.first].getTail(lhsBinding.second);
    }
    // TODO I currently don't see any reason why isSchema should be true...
    boolean isSchema = false;
    return u.makeEdge(isSchema, rule.rhs.rel, tail);
  }

  public static class Composite implements TransGen {
    private TransGen left, right;
    public Composite(TransGen left, TransGen right) {
      this.left = left;
      this.right = right;
    }
    @Override
    public List<Pair<HypEdge, Adjoints>> match(HypEdge[] trigger, Uberts u) {
      List<Pair<HypEdge, Adjoints>> l = left.match(trigger, u);
      List<Pair<HypEdge, Adjoints>> r = right.match(trigger, u);
      if (l.isEmpty())
        return r;
      if (r.isEmpty())
        return l;
      List<Pair<HypEdge, Adjoints>> lr = new ArrayList<>();
      lr.addAll(l);
      lr.addAll(r);
      return lr;
    }
  }
}
