package edu.jhu.hlt.fnparse.datatypes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.apache.log4j.Logger;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TIOStreamTransport;

import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;

/**
 * Wraps edu.jhu.hlt.concrete.Parse and builds a tree (with pointers)
 * 
 * @author travis
 */
public class ConstituencyParse {
  public static Logger LOG = Logger.getLogger(ConstituencyParse.class);

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
      span = null;
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

    public boolean isLeaf() {
      return children.size() == 0;
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

    public void clearTag() {
      base.setTag("X");
      rule = null;
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
  private transient edu.jhu.hlt.concrete.Parse createdFrom;

  public static final Function<DataInputStream, ConstituencyParse> DESERIALIZATION_FUNC = dis -> {
    edu.jhu.hlt.concrete.Parse parse = new edu.jhu.hlt.concrete.Parse();
    try {
      parse.read(new TBinaryProtocol(new TIOStreamTransport(dis)));
      return new ConstituencyParse(parse);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  };
  public static final BiConsumer<ConstituencyParse, DataOutputStream> SERIALIZATION_FUNC = (cparse, dos) -> {
    try {
      cparse.createdFrom.write(new TBinaryProtocol(new TIOStreamTransport(dos)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  };

  public ConstituencyParse(edu.jhu.hlt.concrete.Parse parse) {
    this.createdFrom = parse;
    for (edu.jhu.hlt.concrete.Constituent c : parse.getConstituentList())
      addConstituent(c);
  }

  public ConstituencyParse(edu.jhu.hlt.concrete.Parse parse, int n) {
    this.createdFrom = parse;
    for (edu.jhu.hlt.concrete.Constituent c : parse.getConstituentList())
      addConstituent(c);
    checkSpans(n);
  }

  /**
   * Converts a labeled parse tree to an unlabeled one.
   * Before: (S (NP John) (VP (V loves) (NP Mary)))
   * After:  (X (X John) (X (X loves) (X Mary)))
   */
  public void stripCategories() {
    for (Node n : nodes)
      n.clearTag();
  }

  public void checkSpans(int sentSize) {
    buildPointers();
    for (Node n : nodes) {
      assert n.getSpan().end <= sentSize;
      assert n.getSpan().start >= 0;
      assert n.getSpan().start < n.getSpan().end;
    }
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

  public edu.jhu.hlt.concrete.Parse getConcreteParse() {
    return createdFrom;
  }

  public void getSpans(Collection<Span> addTo) {
    buildPointers();
    for (Node n : nodes)
      addTo.add(n.span);
  }

  /**
   * If there are multiple constituents at this span, this will return the
   * shallower (closer to root) node in the tree.
   */
  public Node getConstituent(Span s) {
    buildPointers();
    indexBySpan();
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
    assert builtPointers;
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

  private void percolateUp(Node n) {
    if (n.parent == null)
      return;
    if (n.isLeaf())
      n.span = ConcreteStanfordWrapper.constituentToSpan(n.base);
    if (n.parent.span == null) {
      assert n.span != null;
      n.parent.span = n.span;
    } else {
      int s = Math.min(n.span.start, n.parent.span.start);
      int e = Math.max(n.span.end, n.parent.span.end);
      n.parent.span = Span.getSpan(s, e);
    }
    percolateUp(n.parent);
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

    // Propagate spans (TokenRefSequence)
    for (Node n : nodes)
      if (n.isLeaf())
        percolateUp(n);

    // Sort children into sentence order
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
