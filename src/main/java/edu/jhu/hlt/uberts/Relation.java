package edu.jhu.hlt.uberts;

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
}
