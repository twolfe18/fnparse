package edu.jhu.hlt.uberts.auto;

import java.util.Arrays;

import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Uberts;

/**
 * e.g. srl2(t,s)
 *
 * @author travis
 */
public class Term {
  public Relation rel;
  public final String relName;
  public final String[] argNames;

  // For Hobbs-style prime notation: event1'(e,i,j), factArgName="e"
  // For non-primed terms, this is null.
  public final String factArgName;

  public static Term uniqArguments(Relation r) {
    String[] args = new String[r.getNumArgs()];
    for (int i = 0; i < args.length; i++)
      args[i] = "a" + i;
    return new Term(r, args);
  }

  public int indexOfArg(String argName) {
    if (factArgName != null && argName.equals(factArgName))
      return State.HEAD_ARG_POS;
    for (int i = 0; i < argNames.length; i++)
      if (argName.equals(argNames[i]))
        return i;
    return -1;
  }

  /** Includes head position */
  public int[] getArgIndices() {
    int[] r = new int[argNames.length + 1];
    r[0] = State.HEAD_ARG_POS;
    for (int i = 1; i < r.length; i++)
      r[i] = i-1;
    return r;
  }

  public String getArgName(int argIdx) {
    if (argIdx == State.HEAD_ARG_POS)
      return factArgName;
    return argNames[argIdx];
  }

  // Needed to incrementally build relations from their names and the names
  // of their arguments.
  private NodeType[] argTypes;
  public void setArgType(int arg, NodeType type) {
    assert rel == null : "why?";
    if (argTypes == null)
      argTypes = new NodeType[argNames.length];
    argTypes[arg] = type;
  }
  public NodeType getArgType(int arg) {
    if (rel != null)
      return rel.getTypeForArg(arg);
    if (argTypes == null)
      return null;
    return argTypes[arg];
  }
  public boolean allArgsAreTyped() {
    if (rel != null)
      return true;
    if (argTypes == null)
      return false;
    if (factArgName != null && rel == null)
      return true;
    for (int i = 0; i < argTypes.length; i++)
      if (argTypes[i] == null)
        return false;
    return true;
  }
  public NodeType[] getDerivedArgtTypes() {
    assert argTypes != null && allArgsAreTyped();
    return argTypes;
  }

  /**
   * WARNING: You can't create primed relations with this constructor.
   */
  public Term(Relation rel, String... argNames) {
    if (rel == null)
      throw new IllegalArgumentException("must provide a valid Relation");
    if (argNames.length != rel.getNumArgs()) {
      throw new IllegalArgumentException("num args don't match"
          + " rel=" + rel.getName()
          + " rel.numArgs=" + rel.getNumArgs()
          + " args=" + Arrays.toString(argNames));
    }
    this.relName = rel.getName();
    this.rel = rel;
    this.factArgName = null;
    this.argNames = argNames;
  }

  public Term(String relName, String... argNames) {
    if (relName == null)
      throw new IllegalArgumentException("must provide a valid Relation");

    // Hobbs' prime notation
    int n = relName.length();
    if (relName.charAt(n-1) == '\'') {
      this.relName = relName.substring(0, n-1);
      this.factArgName = argNames[0];
      this.argNames = Arrays.copyOfRange(argNames, 1, argNames.length);
    } else {
      this.relName = relName;
      this.factArgName = null;
      this.argNames = argNames;
    }
    this.rel = null;
  }

  public int getNumArgs() {
    return argNames.length;
  }

  /**
   * Looks at the Relation name and arg {@link NodeType}s to create a new
   * {@link Relation} which is set as this.rel and added to {@link Uberts}.
   * If the Relation is already set, this does nothing.
   */
  public void resolveRelation(Uberts u) {
    if (rel != null)
      return;
    rel = u.getEdgeType(relName, true);
    if (rel == null) {
      if (!allArgsAreTyped())
        throw new IllegalArgumentException("not all args are typed");
      rel = u.addEdgeType(new Relation(relName, getDerivedArgtTypes()));
    }
  }

  @Override
  public String toString() {
    //      return "<Term " + rel.getName() + " " + Arrays.toString(argNames) + ">";
    StringBuilder sb = new StringBuilder();
    if (rel == null) {
      sb.append(relName);
      sb.append("_untyped");
    } else {
      sb.append(rel.getName());
    }
    if (factArgName != null)
      sb.append('\'');
    sb.append('(');
    if (factArgName != null) {
      sb.append(factArgName);
      sb.append(',');
    }
    sb.append(argNames[0]);
    for (int i = 1; i < argNames.length; i++) {
      sb.append(',');
      sb.append(argNames[i]);
    }
    sb.append(')');
    return sb.toString();
  }

  /**
   * @param u if null, relName:String => rel:Relation resolution will not happen
   */
  public static Term parseTerm(String term, Uberts u) {
    int lrb = term.indexOf('(');
    int rrb = term.indexOf(')');
    assert lrb > 0 && rrb == term.length()-1;
    String relName = term.substring(0, lrb);
    String args = term.substring(lrb + 1, rrb);
    String[] argNames = args.split(",");
    for (int i = 0; i < argNames.length; i++)
      argNames[i] = argNames[i].trim();
    Term t = new Term(relName, argNames);
    if (u != null)
      t.rel = u.getEdgeType(t.relName, true);
    return t;
  }
}
