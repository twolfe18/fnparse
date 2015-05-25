package edu.jhu.hlt.fnparse.datatypes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
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
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.Document.Constituent;

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
    edu.jhu.hlt.tutils.Document.Constituent base2;
    Span span;
    Node parent;
    Node headChild;
    List<Node> children;
    private int depth = -1;

    public Node(edu.jhu.hlt.concrete.Constituent c) {
      base = c;
      base2 = null;
      span = null;
      parent = null;
      children = new ArrayList<>();
    }

    public Node(edu.jhu.hlt.tutils.Document.Constituent c) {
      base = null;
      base2 = c;
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

  //private Node[] nodes;
  private Map<Integer, Node> nodes;
  private Map<Span, List<Node>> index;
  private boolean builtPointers = false;
  private boolean usingTutils = false;
  private boolean usingConcrete = false;
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
    this.nodes = new HashMap<>();
    this.usingConcrete = true;
    this.createdFrom = parse;
    for (edu.jhu.hlt.concrete.Constituent c : parse.getConstituentList())
      addConstituent(c);
  }

  public ConstituencyParse(edu.jhu.hlt.concrete.Parse parse, int n) {
    this.nodes = new HashMap<>();
    this.usingConcrete = true;
    this.createdFrom = parse;
    for (edu.jhu.hlt.concrete.Constituent c : parse.getConstituentList())
      addConstituent(c);
    checkSpans(n);
  }

  public ConstituencyParse(edu.jhu.hlt.tutils.Document.Constituent parse) {
    this.nodes = new HashMap<>();
    this.usingTutils = true;
    helper(parse);
  }
  private void helper(edu.jhu.hlt.tutils.Document.Constituent node) {
    addConstituent(node);
    Document d = node.getDocument();
    int child = node.getLeftChild();
    while (child >= 0) {
      Constituent c = d.getConstituent(child);
      helper(c);
      child = c.getRightSib();
    }
  }

  /**
   * Converts a labeled parse tree to an unlabeled one.
   * Before: (S (NP John) (VP (V loves) (NP Mary)))
   * After:  (X (X John) (X (X loves) (X Mary)))
   */
  public void stripCategories() {
    for (Node n : nodes.values())
      n.clearTag();
  }

  public void checkSpans(int sentSize) {
    buildPointers();
    for (Node n : nodes.values()) {
      assert n.getSpan().end <= sentSize;
      assert n.getSpan().start >= 0;
      assert n.getSpan().start < n.getSpan().end;
    }
  }

  public void addConstituent(edu.jhu.hlt.tutils.Document.Constituent c) {
    assert usingTutils;
    Node old = nodes.put(c.getIndex(), new Node(c));
    if (old != null) throw new RuntimeException();
  }

  public void addConstituent(edu.jhu.hlt.concrete.Constituent c) {
    assert usingConcrete;
    Node old = nodes.put(c.getId(), new Node(c));
    if (old != null) throw new RuntimeException();
  }

  public edu.jhu.hlt.concrete.Parse getConcreteParse() {
    assert usingConcrete;
    return createdFrom;
  }

  public void getSpans(Collection<Span> addTo) {
    buildPointers();
    for (Node n : nodes.values())
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
    for (Node n : nodes.values()) {
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
    if (n.isLeaf()) {
      if (n.base2 != null) {
        assert n.base2.getFirstToken() >= 0;
        assert n.base2.getLastToken() >= 0;
        n.span = Span.getSpan(n.base2.getFirstToken(), n.base2.getLastToken() + 1);
      } else {
        n.span = ConcreteStanfordWrapper.constituentToSpan(n.base);
      }
    }
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
    assert usingTutils != usingConcrete;
    if (builtPointers)
      return;

    //LOG.info("building pointers");
    //TIMER.start();

    if (usingTutils) {
      // Use tutils representation
      Document d = nodes.values().iterator().next().base2.getDocument();
      for (Node cur : nodes.values()) {
//      for (int i = 0; i < nodes.length; i++) {
        // Parent
//        Node cur = nodes[i];
        if (cur.base2.getParent() != Document.NONE) {
//          Node parent = nodes[cur.base2.getParent()];
          Node parent = nodes.get(cur.base2.getParent());
          assert parent != null;
          cur.parent = parent;
        }

        // Children
        cur.children = new ArrayList<>();
        int child = cur.base2.getLeftChild();
        while (child >= 0) {
          Node c = nodes.get(child);
          assert c != null;
          cur.children.add(c);
          child = d.getRightSib(child);
        }
      }
    } else {
      // Use concrete representation
//      for (int i = 0; i < nodes.length; i++) {
//        Node cur = nodes[i];
      for (Node cur : nodes.values()) {
        if (cur == null) {
          LOG.warn("gap in the nodes?");
          assert false;
          continue;
        }
        //LOG.info("[buildPointers] " + i + " = " + cur);
        if (cur.base.getHeadChildIndex() >= 0)
          cur.headChild = nodes.get(cur.base.getHeadChildIndex());
        for (int childIdx : cur.base.getChildList()) {
          Node child = nodes.get(childIdx);
          assert child != null;
          assert child.parent == null || child.parent == cur;
          child.parent = cur;
          cur.children.add(child);
        }
      }
    }

    // Propagate spans (TokenRefSequence)
    for (Node n : nodes.values())
      if (n.isLeaf())
        percolateUp(n);

    // Sort children into sentence order
    Comparator<Node> order = new Comparator<Node>() {
      public int compare(Node o1, Node o2) {
        return o1.getSpan().start - o2.getSpan().start;
      }
    };
    for (Node n : nodes.values())
      Collections.sort(n.children, order);

    builtPointers = true;
    //TIMER.stop();
  }
}
