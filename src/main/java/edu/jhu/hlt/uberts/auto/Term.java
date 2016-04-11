package edu.jhu.hlt.uberts.auto;

import java.util.Arrays;

import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
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
    for (int i = 0; i < argTypes.length; i++)
      if (argTypes[i] == null)
        return false;
    return true;
  }
  public NodeType[] getDerivedArgtTypes() {
    assert argTypes != null && allArgsAreTyped();
    return argTypes;
  }

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
    this.argNames = argNames;
  }

  public Term(String relName, String... argNames) {
    if (relName == null)
      throw new IllegalArgumentException("must provide a valid Relation");
    this.relName = relName;
    this.rel = null;
    this.argNames = argNames;
  }

  public int getNumArgs() {
    return argNames.length;
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
    sb.append('(');
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
    Relation rel = u == null ? null : u.getEdgeType(relName);
    String args = term.substring(lrb + 1, rrb);
    String[] argNames = args.split(",");
    for (int i = 0; i < argNames.length; i++)
      argNames[i] = argNames[i].trim();
    if (rel != null)
      return new Term(rel, argNames);
    return new Term(relName, argNames);
  }
}
