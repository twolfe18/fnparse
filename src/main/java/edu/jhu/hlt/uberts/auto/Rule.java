package edu.jhu.hlt.uberts.auto;

import java.util.ArrayList;
import java.util.List;

/**
 * e.g. event2(t,f) & srl2(t,s) & role(f,k) => srl3(t,f,s,k)
 *
 * @author travis
 */
public class Rule {
  public final Term rhs;
  public final Term[] lhs;

  // lhs2rhs[1][0] = 0, second occurrence of t in lhs => location of t in rhs
  int[][] lhs2rhs;  // [termIdx][argIdx] => location in rhs.args, or -1 if not in rhs.

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
    this.lhs2rhs = new int[lhs.length][];
    for (int i = 0; i < lhs2rhs.length; i++) {
      int lhsN = this.lhs[i].argNames.length;
      lhs2rhs[i] = new int[lhsN];
      for (int j = 0; j < lhsN; j++) {
        String varName = this.lhs[i].argNames[j];
        lhs2rhs[i][j] = indexOf(varName, rhs.argNames);
      }
    }
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

}