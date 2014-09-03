package edu.jhu.hlt.fnparse.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import edu.jhu.gm.inf.BeliefPropagation;
import edu.jhu.gm.inf.BeliefPropagation.BeliefPropagationPrm;
import edu.jhu.gm.model.ConstituencyTreeFactor.SpanVar;
import edu.jhu.gm.model.DenseFactor;
import edu.jhu.gm.model.ExplicitFactor;
import edu.jhu.gm.model.FactorGraph;
import edu.jhu.gm.model.Var;
import edu.jhu.gm.model.Var.VarType;
import edu.jhu.gm.model.VarSet;
import edu.jhu.hlt.fnparse.util.PairedExactly1.SpanVarHelper;

public class PairedExactlyOneTest {
	
	/**
	 * Lets make a test with 3 spans, each with a unary factor
	 */
	@Test
	public void simple() {
		System.out.println("setting up factor graph...");
		boolean logDomain = true;
		FactorGraph fg = new FactorGraph();

		// Create span variables
		int head = 1;
		List<SpanVarHelper> c_ij = new ArrayList<>();
		c_ij.add(new SpanVarHelper(VarType.PREDICTED, "c1", head, head, c_ij.size()));
		c_ij.add(new SpanVarHelper(VarType.PREDICTED, "c2", head-1, head, c_ij.size()));
		c_ij.add(new SpanVarHelper(VarType.PREDICTED, "c3", head, head+1, c_ij.size()));
		
		// Add a unary factor for each span variable
		for (int i = 0; i < c_ij.size(); i++) {
			DenseFactor c_ij_unary = new DenseFactor(new VarSet(c_ij.get(i)));
			double strengh = 1d / (i + 1);
			c_ij_unary.setValue(SpanVar.TRUE, strengh);
			c_ij_unary.setValue(SpanVar.FALSE, strengh / 10d);
			fg.addFactor(new ExplicitFactor(c_ij_unary));
			System.out.println(c_ij_unary);
		}
		
		// Construct a label variable and a unary factor for it
		Var y = new Var(VarType.PREDICTED, c_ij.size() + 1, "y", null);
		DenseFactor y_unary = new DenseFactor(new VarSet(y), 0.5d);
		y_unary.setValue(c_ij.size(), 0.1d);	// wants to be "off"
		fg.addFactor(new ExplicitFactor(y_unary));
		System.out.println(y_unary);
		System.out.println();
		System.out.printf("\nSpanVar.TRUE=%d SpanVar.FALSE=%d\n\n", SpanVar.TRUE, SpanVar.FALSE);
		
		// Make the factor to be tested
		PairedExactly1 phi = new PairedExactly1(head, y, c_ij);
		fg.addFactor(phi);

		// Run inference
		System.out.println("running inference...");
        BeliefPropagationPrm prm = new BeliefPropagationPrm();
        prm.logDomain = logDomain;
		BeliefPropagation inf = new BeliefPropagation(fg, prm);
		inf.run();
		
		// Show marginals
		DenseFactor y_marg = inf.getMarginals(y);
		assertEquals(y_marg.size(), c_ij.size() + 1);
		for (int i = 0; i < y_marg.size(); i++)
			System.out.printf("%s=%d  %.3f\n", y.getName(), i, prob(i, y_marg, logDomain));
		for (SpanVarHelper svh : c_ij) {
			DenseFactor c_ij_marg = inf.getMarginals(svh);
			assertEquals(c_ij_marg.size(), 2);
			System.out.printf("%s=true/false  %.3f/%.3f\n", svh.getName(),
					prob(SpanVar.TRUE, c_ij_marg, logDomain), prob(SpanVar.FALSE, c_ij_marg, logDomain));
		}
	}
	
	public static double prob(int index, DenseFactor beliefs, boolean logDomain) {
		if (logDomain) {
			// exp-it if in log domain
			beliefs = new DenseFactor(beliefs);	// don't modify in place
			for (int i = 0; i < beliefs.size(); i++)
				beliefs.setValue(i, Math.exp(beliefs.getValue(i)));
		}
		return beliefs.getValue(index) / beliefs.getSum();
	}

}
