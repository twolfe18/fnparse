package edu.jhu.hlt.fnparse.util;

import java.util.List;

import edu.jhu.gm.inf.BeliefPropagation.Messages;
import edu.jhu.gm.model.AbstractGlobalFactor;
import edu.jhu.gm.model.ConstituencyTreeFactor;
import edu.jhu.gm.model.ConstituencyTreeFactor.SpanVar;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.Factor;
import edu.jhu.gm.model.FactorGraph.FgEdge;
import edu.jhu.gm.model.FactorGraph.FgNode;
import edu.jhu.gm.model.GlobalFactor;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.VarConfig;
import edu.jhu.gm.model.VarSet;
import edu.jhu.prim.util.math.FastMath;

/**
 * This is very similar to the Exactly1 factor (TODO cite).
 * 
 * The idea is that we are representing a factor that is connected to
 * 1) a set of binary variables c_ij, each of which represents whether the span from i to j is a
 *    constituent.
 * 2) a span-labeling variable y, which takes on values {0} \cup C, where C is the set of all spans
 *    represented by the set c_ij
 * 
 * This factor's potential function will return 1 if (y != 0 && c_y=1 && Exactly1(c_ij)) and 0
 * otherwise.
 * Let N be the number of i,j configurations possible:
 *  phi(*)	y	c(1,1)	c(1,2)			...			c(n,m)		#rows
 *  1		1	1		0				0			0			1
 *  0		1	1		all binary strings except all zeros		2^{N-1}-1
 *  0		1	0		all binary strings						2^{N-1}
 *  repeat the above pattern for y=2, etc
 *  
 * The key is that when computing the BP message, we are summing over this table,
 * weighted by messages from c_ij -> this factor (which I'll call the c_ij beliefs).
 * We only need to pay attention to the rows that satisfy the constraints.
 *  
 *  
 * The issue is that if you look at the message sent to y (marginalizing out all c_ij assignments),
 * there are far fewer satisfying assignments when y != N+1 (special value/null label) than there
 * are for y <= N.
 * 
 * FIX1: put a unary factor on y that is parametric in the number of c_ij that are touching it. This
 * lets you fix the problem without multiplying in weird values into the factor. The downside is
 * that it requires an extra factor, which is slower than need be. TODO come up with a more
 * efficient solution later.
 * 
 * TODO add a unary factor on y:
 *   phi(y = null label) = features(N)
 *   phi(y = non-null label) = 1
 * 
 * @author travis
 */
class PairedExactly1 extends AbstractGlobalFactor implements GlobalFactor {
  private static final long serialVersionUID = 1L;
  public static final boolean log = false;

  /** A SpanVar which also stores an index, which is ij in c_ij */
  public static class SpanVarHelper extends ConstituencyTreeFactor.SpanVar {
    private static final long serialVersionUID = 1L;
    public int index;	// Corresponds to values of y
    public SpanVarHelper(VarType type, String name, int start, int end, int spanIndex) {
      super(type, name, start, end);
      assert spanIndex >= 0;
      index = spanIndex;
    }
  }

  // Index of the headword of the spans (c_ij_vals)
  private int head;

  // Has N+1 values, where values <N indicate a particular c_ij (an index in c_ij* lists below)
  // and a value ==N means that none of the constituents have the label in question.
  private Var y;

  private List<SpanVarHelper> c_ij;

  // Can be derived from data above
  private VarSet varSet;		// y \cup c_ij*
  private int N;				// == c_ij.size() + 1

  public PairedExactly1(int headwordIndex, Var labelVar, List<SpanVarHelper> spanVars) {
    head = headwordIndex;
    y = labelVar;
    c_ij = spanVars;
    varSet = new VarSet();
    for (SpanVarHelper svh : spanVars)
      varSet.add(svh);
    N = c_ij.size();
    assert N > 0;
    varSet.add(labelVar);
  }

  public int getHead() { return head; }

  @Override
  public VarSet getVars() { return varSet; }

  @Override
  public Factor getClamped(VarConfig clmpVarConfig) {
    throw new RuntimeException("don't call this");
  }

  @Override
  public double getUnormalizedScore(VarConfig goldConfig) {
    throw new RuntimeException("don't call this");
  }

  @Override
  public double getUnormalizedScore(int goldConfig) {
    throw new RuntimeException("don't call this");
  }

  @Override
  public double getExpectedLogBelief(FgNode parent, Messages[] msgs, boolean logDomain) {
    throw new RuntimeException("implement me");
  }

  /**
   * \mu_{* -> y} = sum_over_values_of_y { phi(y, c_ij*) * b(c_ij*) }
   *   (this is a length N+1 vector)
   * \mu_{* -> c_ij} = sum_over_binary_vals { phi(y, c_ij*) * b(y) * b(c_xy* except c_ij) }
   *   (each of these is a length 2 vector)
   *
   * Computations done in log domain.
   */
  @Override
  protected void createMessages(FgNode parent, Messages[] msgs, boolean logDomain, boolean normalizeMessages) {
    assert this == parent.getFactor();
    assert y.getNumStates() == N + 1;
    assert c_ij.size() == N;
    if (log) System.err.println("");

    // Cache product of beliefs
    DenseFactor yBeliefs = null;
    DenseFactor[] c_ij_beliefs = new DenseFactor[N];
    double prodTrueBeliefs = logDomain ? 0d : 1d;	// Multiplicative identity
    double prodFalseBeliefs = logDomain ? 0d : 1d;	// Multiplicative identity
    for(FgEdge inEdge : parent.getInEdges()) {		// var -> factor edges
      DenseFactor beliefs = msgs[inEdge.getId()].message;
      if(inEdge.getVar() instanceof SpanVarHelper) {
        SpanVarHelper c_ij = (SpanVarHelper) inEdge.getVar();
        c_ij_beliefs[c_ij.index] = beliefs;
        assert inEdge.getVar().getNumStates() == 2;
        double pTrue = beliefs.getValue(SpanVar.TRUE);
        double pFalse = beliefs.getValue(SpanVar.FALSE);
        if (logDomain) {
          double z = FastMath.logAdd(pFalse, pTrue);
          prodTrueBeliefs += pTrue - z;
          prodFalseBeliefs += pFalse - z;
        } else {
          double z = pTrue + pFalse;
          prodTrueBeliefs *= pTrue / z;
          prodFalseBeliefs *= pFalse / z;
        }
      } else {
        assert yBeliefs == null;
        assert beliefs.size() == N + 1;
        yBeliefs = beliefs;
      }
    }
    assert yBeliefs != null : "we didn't see y?";
    double yZ = logDomain ? yBeliefs.getLogSum() : yBeliefs.getSum();

    if (log) {
      System.err.println("yBeliefs = " + yBeliefs);
      System.err.printf("yZ=%.3f\n", yZ);
      System.err.printf("prodTrueBeliefs=%.3f\n", prodTrueBeliefs);
    }

    // Send messages
    for(FgEdge outEdge : parent.getOutEdges()) {
      DenseFactor divOut = msgs[outEdge.getOpposing().getId()].message;
      DenseFactor outMsg = msgs[outEdge.getId()].newMessage;
      if(outEdge.getVar() instanceof SpanVarHelper) {
        // Send messages to c_ij
        SpanVarHelper c_ij = (SpanVarHelper) outEdge.getVar();
        double bY = div(yBeliefs.getValue(c_ij.index), yZ, logDomain);
        double bC = div(prodTrueBeliefs, divOut.getValue(SpanVar.TRUE), logDomain);
        double mu_c_ij_true = prod(bY, bC, logDomain);
        if (log)
          System.err.printf("bY=%.3f bC=%.3f mu_c_ij(true)=%.3f\n", bY, bC, mu_c_ij_true);
        double mu_c_ij_false = oneMinus(mu_c_ij_true, logDomain);
        assert outMsg.size() == 2;
        outMsg.setValue(SpanVar.TRUE, mu_c_ij_true);
        outMsg.setValue(SpanVar.FALSE, mu_c_ij_false);
      } else  {
        // Send messages to y
        if (log) System.err.println("\ndivOut = " + divOut);
        assert outEdge.getVar() == y;
        assert outMsg.size() == N + 1;
        for (int i = 0; i < N; i++) {
          // b(c(i)==1) * \prod_{j != i} b(c(j)==0)
          double allButOneFalse = div(prodFalseBeliefs,
              c_ij_beliefs[i].getValue(SpanVar.FALSE), logDomain);
          outMsg.setValue(i, prod(c_ij_beliefs[i].getValue(SpanVar.TRUE),
              allButOneFalse, logDomain));
          if (log) {
            System.err.printf("i=%d b(c_i=T)=%.3f * prod(b(c_j=F))=%.3f = %.3f\n",
                i, c_ij_beliefs[i].getValue(SpanVar.TRUE), allButOneFalse, outMsg.getValue(i));
          }
        }

        // y=N has a positive bias because there are N solutions to the Exactly1
        // factor without the constraint that y=x s.t. c_x=1 and c_{y!=x}=0.
        // This means that as N grows, this factor will more heavily bias towards
        // y=N, which is not desirable.
        // This is just an approximate correction to account for this problem.
        // A better way to fix this would be to write a factor that is parametric
        // in N, so the model can learn how to adapt to this bias.
        double noLabelPotential = 1d / N;
        if (logDomain)
          noLabelPotential = FastMath.log(noLabelPotential);
        outMsg.setValue(N, noLabelPotential);
        if (log)
          System.err.printf("i=%d msg=%.3f\n", N, outMsg.getValue(N));
      }
    }
  }

  private static double prod(double a, double b, boolean logDomain) {
    return logDomain ? a + b : a * b;
  }

  private static double div(double a, double b, boolean logDomain) {
    return logDomain ? a - b : a / b;
  }

  private static double oneMinus(double value, boolean logDomain) {
    if (logDomain)
      return FastMath.log(1d - FastMath.exp(value));
    else
      return 1d - value;
  }
}