package edu.jhu.hlt.uberts;

import java.util.Comparator;

public class Relation {

  private String name;
  private NodeType[] domain;

  public Relation(String name, NodeType... domain) {
    this.name = name;
    this.domain = domain;
  }

  public String getName() {
    return name;
  }

  public int getNumArgs() {
    return domain.length;
  }

  public NodeType getTypeForArg(int i) {
    return domain[i];
  }

  @Override
  public String toString() {
    return "(Relation " + name + ")";
  }

  public static Comparator<Relation> BY_NAME = new Comparator<Relation>() {
    @Override
    public int compare(Relation o1, Relation o2) {
      return o1.getName().compareTo(o2.getName());
    }
  };
}
