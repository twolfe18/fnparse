package edu.jhu.hlt.uberts.auto;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.prim.tuple.Pair;

/**
 * e.g. event2(t,f) & srl2(t,s) & role(f,k) => srl3(t,f,s,k)
 *
 * TODO I should probably change the names of this and {@link Term} to match
 * the Definite Clause Grammar described in:
 *   Parsing as Deduction
 *   Pereira and Warren (1983)
 *   http://www.aclweb.org/anthology/P83-1021
 * Rule => Literal
 * Term => Predicate
 * argument => Term
 * RHS => positive literal or head
 * LHS => negative literals or body
 *
 * @author travis
 */
public class Rule {
  public final Term rhs;
  public final Term[] lhs;

  public String comment;

  // See TransitionGeneratorForwardParser for why this is needed.
  // lhs2rhs[1][0] = 0, second occurrence of t in lhs => location of t in rhs
  int[][] lhs2rhs;    // [termIdx][argIdx] => location in rhs.args, or -1 if not in rhs.
  int[] lhsFact2rhs;  // [termIdx] => location in rhs.args, or -1 if the fact/witness/event variable is not used in rhs.

  // Indexed by argument position in the RHS Term.
  // Value is the (termIdx,argIdx) of a LHS variable with the same name as the RHS arg in the key.
  private IntPair[] rhsArg2LhsTermArg;

  public IntPair getBindingOfRhsArg(int argPos) {
    assert argPos >= 0 && argPos < rhs.getNumArgs();
    if (rhsArg2LhsTermArg == null)
      buildRhsArg2LhsBindings();
    return rhsArg2LhsTermArg[argPos];
  }

  /**
   * Ensures that all variables used on the RHS appear on the LHS, and builds
   * a data structure mapping out these bindings.
   */
  public void buildRhsArg2LhsBindings() {
    rhsArg2LhsTermArg = new IntPair[rhs.getNumArgs()];
    outer:
    for (int i = 0; i < rhsArg2LhsTermArg.length; i++) {
      String varName = rhs.getArgName(i);
      // Find where this variable is bound
      for (int termIdx = 0; termIdx < lhs.length; termIdx++) {
        Term lt = lhs[termIdx];
        for (int argIdx : lt.getArgIndices()) {
          if (varName.equals(lt.getArgName(argIdx))) {
            assert argIdx == State.HEAD_ARG_POS // TODO don't currently have a way to check types for this
                || lt.getArgType(argIdx) == rhs.getArgType(i);
            rhsArg2LhsTermArg[i] = new IntPair(termIdx, argIdx);
            continue outer;
          }
        }
      }
      throw new RuntimeException("unbound RHS argument: " + varName + " in " + rhs + " in " + this);
    }
  }

  /**
   * Returns a N rules, where N is the number of functors in the LHS of the
   * given rule. Produces a rule in which each functor is the first fact checked.
   */
  public static List<Rule> allLhsOrders(Rule r) {
    List<Rule> rules = new ArrayList<>();
    Term[] lhs;
    for (int i = 0; i < r.lhs.length; i++) {
      lhs = new Term[r.lhs.length];
      lhs[0] = r.lhs[i];
      for (int j = 0, c = 1; j < r.lhs.length; j++) {
        if (j == i) continue;
        lhs[c++] = r.lhs[j];
      }
      rules.add(new Rule(lhs, r.rhs));
    }
    return rules;
  }

  public Rule(Term[] lhs, Term rhs) {
    this.lhs = lhs;
    this.rhs = rhs;
    init();
  }

  public Rule(List<Term> lhs, Term rhs) {
    this.rhs = rhs;
    this.lhs = new Term[lhs.size()];
    for (int i = 0; i < this.lhs.length; i++)
      this.lhs[i] = lhs.get(i);
    init();
  }

  private void init() {
    this.lhsFact2rhs = new int[lhs.length];
    this.lhs2rhs = new int[lhs.length][];
    for (int i = 0; i < lhs2rhs.length; i++) {
      int lhsN = this.lhs[i].argNames.length;
      lhs2rhs[i] = new int[lhsN];
      for (int j = 0; j < lhsN; j++) {
        String varName = this.lhs[i].argNames[j];
        lhs2rhs[i][j] = indexOf(varName, rhs.argNames);
      }
      String f;
      if ((f = lhs[i].factArgName) != null)
        lhsFact2rhs[i] = indexOf(f, rhs.argNames);
      else
        lhsFact2rhs[i] = -1;
    }

    // Check that none of the fact var names are the same
    assert rhs.factArgName == null;
    Set<String> factArgNames = new HashSet<>();
    for (Term t : lhs)
      assert t.factArgName == null || factArgNames.add(t.factArgName);
  }

  /**
   * For every {@link Term}, looks at the Relation name and arg {@link
   * NodeType}s to create a new {@link Relation} which is set as this.rel and
   * added to {@link Uberts}. If a {@link Term}s {@link Relation} is already
   * set, this does nothing.
   */
  public void resolveRelations(Uberts u) {
    for (Term t : getAllTerms())
      t.resolveRelation(u);
  }

  public static int indexOf(String needle, String[] haystack) {
    for (int i = 0; i < haystack.length; i++)
      if (needle.equals(haystack[i]))
        return i;
    return -1;
  }

  public List<Term> getAllTerms() {
    List<Term> t = new ArrayList<>();
    for (Term tt : lhs)
      t.add(tt);
    t.add(rhs);
    return t;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(lhs[0].toString());
    for (int i = 1; i < lhs.length; i++) {
      sb.append(" & ");
      sb.append(lhs[i].toString());
    }
    sb.append(" => ");
    sb.append(rhs.toString());
    return sb.toString();
  }

  /**
   * @param u if null, will produce a Rule with rel==null.
   */
  public static Rule parseRule(String rule, Uberts u) {
    return parseRule(rule, null, u);
  }
  public static Rule parseRule(String rule, String comment, Uberts u) {
    String[] lr = rule.split("=>");
    assert lr.length == 2 : "rule must contain one instance of '=>': " + rule;
    String lhs = lr[0].trim();
    String[] lhsTermStrs = lhs.split("&");
    Term[] lhsTerms = new Term[lhsTermStrs.length];
    for (int i = 0; i < lhsTerms.length; i++)
      lhsTerms[i] = Term.parseTerm(lhsTermStrs[i].trim(), u);
    String rhs = lr[1].trim();
    Term rhsTerm = Term.parseTerm(rhs, u);
    Rule r = new Rule(lhsTerms, rhsTerm);
    r.comment = comment;
    return r;
  }

  /**
   * @param u if null, will produce a Rule with rel==null.
   */
  public static List<Rule> parseRules(File f, Uberts u) throws IOException {
    Log.info("reading transition grammar rules from " + f.getPath()
        +  " ubertsResolve=" + (u != null));
    try (BufferedReader r = FileUtil.getReader(f)) {
      return parseRules(r, u);
    }
  }

  /**
   * @param u if null, will produce a Rule with rel==null.
   */
  public static List<Rule> parseRules(BufferedReader r, Uberts u) throws IOException {
    List<Rule> rules = new ArrayList<>();
    for (String line = r.readLine(); line != null; line = r.readLine()) {
      Pair<String, String> lc = Uberts.stripComment2(line);
      line = lc.get1();
      if (line.isEmpty())
        continue;
      Rule rule = parseRule(line, lc.get2(), u);
      rules.add(rule);
    }
    return rules;
  }
}