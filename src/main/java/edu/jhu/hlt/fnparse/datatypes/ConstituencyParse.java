package edu.jhu.hlt.fnparse.datatypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;

/**
 * Wraps edu.jhu.hlt.concrete.Parse and builds a tree (with pointers)
 * 
 * @author travis
 */
public class ConstituencyParse {
  public static Logger LOG = Logger.getLogger(ConstituencyParse.class);
  //public static Timer TIMER = new Timer("ConstituencyParse.buildPointers", 1_500_000, false);

  public static class NodePathPiece {
    private Node node;
    private String edge;
    public NodePathPiece(Node n, String e) {
      node = n;
      edge = e;
    }
    public Node getNode() { return node; }
    public String getEdge() { return edge; }
  }

  public static class Node {
    edu.jhu.hlt.concrete.Constituent base;
    Span span;
    Node parent;
    Node headChild;
    List<Node> children;
    private int depth = -1;

    public Node(edu.jhu.hlt.concrete.Constituent c) {
      base = c;
      span = ConcreteStanfordWrapper.constituentToSpan(c);
      parent = null;
      children = new ArrayList<>();
    }

    public String toString() {
      return String.format("<Node %s @%d-%d D=%d>",
          getTag(), span.start, span.end, depth);
    }

    public Node getParent() {
      return parent;
    }

    public List<Node> getChildren() {
      return children;
    }

    public Node getHeadChild() {
      return headChild;
    }

    public Span getSpan() {
      return span;
    }

    public String getTag() {
      return base.getTag();
    }

    private transient String rule;
    public String getRule() {
      if (rule == null) {
        if (children.size() == 0) {
          rule = getParent().getTag() + "->" + getTag();
        } else {
          StringBuilder sb = new StringBuilder();
          sb.append(getTag());
          sb.append("->");
          boolean first = true;
          for (Node n : children) {
            if (first) first = false;
            else sb.append(",");
            sb.append(n.getTag());
          }
          rule = sb.toString();
        }
      }
      return rule;
    }

    public int getDepth() {
      if (depth < 0) {
        // Compute depth
        if (parent == null)
          depth = 0;
        else
          depth = parent.getDepth() + 1;
      }
      return depth;
    }

    public List<Node> getSiblings() {
      if (parent == null)
        return Collections.emptyList();
      Node parentish = parent;
      while (parentish.children.size() == 1) {
        parentish = parentish.parent;
        if (parentish == null)
          return Collections.emptyList();
      }
      List<Node> sib = new ArrayList<>();
      for (Node n : parent.children)
        if (n != this)
          sib.add(n);
      return sib;
    }
  }

  //private List<Node> nodes = new ArrayList<>();
  private Node[] nodes;
  private Map<Span, List<Node>> index;
  private boolean builtPointers = false;

  public ConstituencyParse(edu.jhu.hlt.concrete.Parse parse) {
    for (edu.jhu.hlt.concrete.Constituent c : parse.getConstituentList())
      addConstituent(c);
  }

  public void addConstituent(edu.jhu.hlt.concrete.Constituent c) {
    if (nodes == null)
      nodes = new Node[c.getId() + 1];
    else if (c.getId() >= nodes.length)
      nodes = Arrays.copyOf(nodes, c.getId() + 1);
    Node n = new Node(c);
    //LOG.info("[addConstituent] " + c.getId() + " = " + n);
    nodes[c.getId()] = n;
  }

  public void getSpans(Collection<Span> addTo) {
    for (Node n : nodes)
      addTo.add(n.span);
  }

  /**
   * If there are multiple constituents at this span, this will return the
   * shallower (closer to root) node in the tree.
   */
  public Node getConstituent(Span s) {
    indexBySpan();
    buildPointers();
    List<Node> nodes = index.get(s);
    if (nodes == null)
      return null;
    assert nodes.size() > 0;
    if (nodes.size() == 1)
      return nodes.get(0);
    Node best = nodes.get(0);
    for (int i = 1; i < nodes.size(); i++) {
      Node n = nodes.get(i);
      if (n.getDepth() < best.getDepth())
        best = n;
    }
    return best;
  }

  public void indexBySpan() {
    if (index != null)
      return;
    index = new HashMap<>();
    for (Node n : nodes) {
      List<Node> nodes = index.get(n.span);
      if (nodes == null) {
        nodes = new ArrayList<>();
        nodes.add(n);
        index.put(n.span, nodes);
      } else {
        nodes.add(n);
      }
    }
  }

  public void buildPointers() {
    if (builtPointers)
      return;
    //LOG.info("building pointers");
    //TIMER.start();
    for (int i = 0; i < nodes.length; i++) {
      Node cur = nodes[i];
      if (cur == null) {
        LOG.warn("gap in the nodes?");
        assert false;
        continue;
      }
      //LOG.info("[buildPointers] " + i + " = " + cur);
      if (cur.base.getHeadChildIndex() >= 0)
        cur.headChild = nodes[cur.base.getHeadChildIndex()];
      for (int childIdx : cur.base.getChildList()) {
        Node child = nodes[childIdx];
        assert child.parent == null || child.parent == cur;
        child.parent = cur;
        cur.children.add(child);
      }
    }
    Comparator<Node> order = new Comparator<Node>() {
      public int compare(Node o1, Node o2) {
        return o1.getSpan().start - o2.getSpan().start;
      }
    };
    for (Node n : nodes)
      Collections.sort(n.children, order);
    builtPointers = true;
    //TIMER.stop();
  }
}
