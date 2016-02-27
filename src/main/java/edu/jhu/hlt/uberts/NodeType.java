package edu.jhu.hlt.uberts;

public class NodeType {
  private String name;
  // TODO put data type (e.g. Span) here?
  // TODO put alphabet here (e.g. Alphabet<Span>)?

  public NodeType(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return name;
  }
}
