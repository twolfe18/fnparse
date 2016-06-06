package edu.jhu.hlt.fnparse.features;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.Path.EdgeType;
import edu.jhu.hlt.fnparse.features.Path.NodeType;
import edu.jhu.hlt.tutils.hash.Hash;

public class Path2 {

  interface Entry {
    public boolean isEdge();
    public String show(NodeType nt, EdgeType et);
  }

  class Node implements Entry {
    public final int tokenIndex;
    public Node(int tokenIndex) {
      this.tokenIndex = tokenIndex;
    }
    public boolean isEdge() {
      return false;
    }
    public String show(NodeType nt, EdgeType et) {
      switch (nt) {
      case WORD:
        return sent.getWord(tokenIndex);
      case LEMMA:
        return sent.getLemma(tokenIndex);
      case POS:
        return sent.getPos(tokenIndex);
      case NONE:
        return "*";
      default:
        throw new RuntimeException("unknown nodeType: " + nt);
      }
    }
    public String toString() {
      return show(NodeType.WORD, EdgeType.DEP);
    }
  }

  static class Edge implements Entry {
    public final String deprel;
    public final boolean up;
    public final int hc;
    public Edge(String deprel, boolean up) {
      this.deprel = deprel;
      this.up = up;
      hc = Hash.mix(Hash.hash(deprel), up ? 9001 : 42);
    }
    public int hashCode() {
      return hc;
    }
    public boolean equals(Object other) {
      if (other instanceof Edge) {
        Edge e = (Edge) other;
        return hc == e.hc && up == e.up && deprel.equals(e.deprel);
      }
      return false;
    }
    public boolean isEdge() {
      return true;
    }
    public String show(NodeType nt, EdgeType et) {
      switch (et) {
      case DEP:
        return deprel + (up ? "<" : ">");
      case DIRECTION:
        return up ? "<" : ">";
      default:
        throw new RuntimeException("unknown edgeType: " + et);
      }
    }
    public String toString() {
      return show(NodeType.WORD, EdgeType.DEP);
    }

    public static Edge fromString(String e) {
      int nn = e.length() - 1;
      String deprel = e.substring(0, nn);
      boolean up = e.charAt(nn) == '<';
      assert up || e.charAt(nn) == '>';
      return new Edge(deprel, up);
    }
  }

  private List<Entry> entries;
  private Sentence sent;
  private int meet;

  public Path2(int start, int end, DependencyParse deps, Sentence sent) {
    this.sent = sent;

    BitSet seen = new BitSet();
    for (int cur = end; cur >= 0 && !seen.get(cur); cur = deps.getHead(cur))
      seen.set(cur);

    meet = start;
    while (meet >= 0 && !seen.get(meet))
      meet = deps.getHead(meet);

    entries = new ArrayList<>();
    if (meet >= 0) {
      for (int cur = start; cur != meet; cur = deps.getHead(cur)) {
        entries.add(new Node(cur));
        entries.add(new Edge(deps.getLabel(cur), true));
      }
      entries.add(new Node(meet));
      List<Entry> other = new ArrayList<>();
      for (int cur = end; cur != meet; cur = deps.getHead(cur)) {
        other.add(new Node(cur));
        other.add(new Edge(deps.getLabel(cur), false));
      }
      Collections.reverse(other);
      entries.addAll(other);
    }
  }

  public String getPath(NodeType nt, EdgeType et) {
    if (!connected())
      return "noPath";
    StringBuilder sb = new StringBuilder();
    for (Entry e : entries) {
      if (sb.length() > 0)
        sb.append('/');
      sb.append(e.show(nt, et));
    }
    return sb.toString();
  }

  public boolean connected() {
    return meet >= 0;
  }

  public List<Entry> getEntries() {
    return entries;
  }
}
