package edu.jhu.hlt.uberts.auto;

import java.util.Arrays;

import edu.jhu.hlt.uberts.Relation;

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
}
