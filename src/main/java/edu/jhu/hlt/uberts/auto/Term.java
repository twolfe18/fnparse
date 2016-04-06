package edu.jhu.hlt.uberts.auto;

import java.util.Arrays;

import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;

/**
 * e.g. srl2(t,s)
 *
 * @author travis
 */
public class Term {
  public final Relation rel;
  public final String[] argNames;

  public Term(Relation rel, String... argNames) {
    if (rel == null)
      throw new IllegalArgumentException("must provide a valid Relation");
    if (argNames.length != rel.getNumArgs()) {
      throw new IllegalArgumentException("num args don't match"
          + " rel=" + rel.getName()
          + " rel.numArgs=" + rel.getNumArgs()
          + " args=" + Arrays.toString(argNames));
    }
    this.rel = rel;
    this.argNames = argNames;
  }

  public int getNumArgs() {
    return argNames.length;
  }

  @Override
  public String toString() {
    //      return "<Term " + rel.getName() + " " + Arrays.toString(argNames) + ">";
    StringBuilder sb = new StringBuilder();
    sb.append(rel.getName());
    sb.append('(');
    sb.append(argNames[0]);
    for (int i = 1; i < argNames.length; i++) {
      sb.append(',');
      sb.append(argNames[i]);
    }
    sb.append(')');
    return sb.toString();
  }

  public static Term parseTerm(String term, Uberts u) {
    int lrb = term.indexOf('(');
    int rrb = term.indexOf(')');
    assert lrb > 0 && rrb == term.length()-1;
    String relName = term.substring(0, lrb);
    Relation rel = u.getEdgeType(relName);
    String args = term.substring(lrb + 1, rrb);
    String[] argNames = args.split(",");
    for (int i = 0; i < argNames.length; i++)
      argNames[i] = argNames[i].trim();
    return new Term(rel, argNames);
  }
}
