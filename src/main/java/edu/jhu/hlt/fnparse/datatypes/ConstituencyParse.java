package edu.jhu.hlt.fnparse.datatypes;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.Document.Constituent;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;

/**
 * Wraps edu.jhu.hlt.concrete.Parse and builds a tree (with pointers)
 * 
 * @author travis
 */
public class ConstituencyParse implements Serializable {
  private static final long serialVersionUID = 2258695730724020087L;

  public static class NodePathPiece implements Serializable {
    private static final long serialVersionUID = -7502757950662681044L;
    private Node node;
    private String edge;
    public NodePathPiece(Node n, String e) {
      node = n;
      edge = e;
    }
    public Node getNode() { return node; }
    public String getEdge() { return edge; }
  }

  public static class Node implements Serializable {
    private static final long serialVersionUID = 4924793634793234405L;
    private transient edu.jhu.hlt.concrete.Constituent base;
    private transient edu.jhu.hlt.tutils.Document.Constituent base2;
    private int start = -1;
    private int end = -2;
    private int depth = -1;
    private String tag;
    private String rule;
    private Node parent;
    private Node headChild;
    private List<Node> children;

    public Node(edu.jhu.hlt.concrete.Constituent c) {
      base = c;
      base2 = null;
      tag = base.getTag();
      parent = null;
      children = new ArrayList<>();
    }

    public Node(edu.jhu.hlt.tutils.Document.Constituent c) {
      base = null;
      base2 = c;
//      tag = "LHS-" + c.getLhs();
      tag = c.getDocument().getAlphabet().cfg(c.getLhs());
      parent = null;
      children = new ArrayList<>();
    }

    /** release pointers to derived forms (Concrete or tutils representation) */
    public void dropBase() {
      base = null;
      base2 = null;
    }

    public String toString() {
      return String.format("<Node %s %d-%d D=%d>",
          getTag(), start, end, depth);
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

    public boolean hasSpan() {
      if (start < 0) {
        assert start == -1 && end == -2;
        return false;
      }
      assert start < end;
      return true;
    }

    public Span getSpan() {
      return Span.getSpan(start, end);
    }

    public void clearTag() {
      //base.setTag("X");
      tag = "X";
      rule = null;
    }

    public String getTag() {
      //return base.getTag();
      return tag;
    }

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
  private final String sentenceId;

  public static final Function<DataInputStream, ConstituencyParse> DESERIALIZATION_FUNC = dis -> {
//    edu.jhu.hlt.concrete.Parse parse = new edu.jhu.hlt.concrete.Parse();
    try {
//      parse.read(new TBinaryProtocol(new TIOStreamTransport(dis)));
//      return new ConstituencyParse(parse);
      ObjectInputStream ois = new ObjectInputStream(dis);
      return (ConstituencyParse) ois.readObject();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  };
  public static final BiConsumer<ConstituencyParse, DataOutputStream> SERIALIZATION_FUNC = (cparse, dos) -> {
    try {
//      cparse.createdFrom.write(new TBinaryProtocol(new TIOStreamTransport(dos)));
      ObjectOutputStream oos = new ObjectOutputStream(dos);
      oos.writeObject(cparse);
      oos.flush();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  };

  public ConstituencyParse(String sentenceId, edu.jhu.hlt.concrete.Parse parse) {
    this.nodes = new HashMap<>();
    this.usingConcrete = true;
    this.createdFrom = parse;
    this.sentenceId = sentenceId;
    for (edu.jhu.hlt.concrete.Constituent c : parse.getConstituentList())
      addConstituent(c);
    buildPointers();
  }

  public ConstituencyParse(String sentenceId, edu.jhu.hlt.concrete.Parse parse, int n) {
    this.sentenceId = sentenceId;
    this.nodes = new HashMap<>();
    this.usingConcrete = true;
    this.createdFrom = parse;
    for (edu.jhu.hlt.concrete.Constituent c : parse.getConstituentList())
      addConstituent(c);
    checkSpans(n);
    buildPointers();
  }

  private transient int firstTokenIndex = -1;
  public ConstituencyParse(String sentenceId, int firstTokenIndex, edu.jhu.hlt.tutils.Document.Constituent parse) {
    this.sentenceId = sentenceId;
    this.firstTokenIndex = firstTokenIndex;
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

  public String getSentenceId() {
    return sentenceId;
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

  public <T extends Collection<? super Span>> T getSpans(T addTo) {
    buildPointers();
    for (Node n : nodes.values())
      addTo.add(n.getSpan());
    return addTo;
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

  public List<Node> getAllConstituents() {
    List<Node> cons = new ArrayList<>();
    cons.addAll(nodes.values());
    return cons;
  }

  public Node getCommonParent(Node n1, Node n2) {
    Set<Node> n1up = new HashSet<>();
    for (Node cur = n1; cur != null; cur = cur.getParent())
      n1up.add(cur);
    for (Node cur = n2; cur != null; cur = cur.getParent())
      if (n1up.contains(cur))
        return cur;
    return null;
  }

  public void indexBySpan() {
    assert builtPointers;
    if (index != null)
      return;
    index = new HashMap<>();
    for (Node n : nodes.values()) {
      List<Node> nodes = index.get(n.getSpan());
      if (nodes == null) {
        nodes = new ArrayList<>();
        nodes.add(n);
        index.put(n.getSpan(), nodes);
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
        assert firstTokenIndex >= 0;
        n.start = n.base2.getFirstToken() - firstTokenIndex;
        n.end = n.base2.getLastToken() + 1 - firstTokenIndex;
      } else {
        Span span = ConcreteStanfordWrapper.constituentToSpan(n.base);
        n.start = span.start;
        n.end = span.end;
      }
    }
    assert n.hasSpan();
    if (!n.parent.hasSpan()) {
      n.parent.start = n.start;
      n.parent.end = n.end;
    } else {
      n.parent.start = Math.min(n.start, n.parent.start);
      n.parent.end = Math.max(n.end, n.parent.end);
    }
    percolateUp(n.parent);
  }

  public void buildPointers() {
    assert usingTutils != usingConcrete;
    if (builtPointers)
      return;

    //Log.info("building pointers");
    //TIMER.start();

    if (usingTutils) {
      // Use tutils representation
      Document d = nodes.values().iterator().next().base2.getDocument();
      for (Node cur : nodes.values()) {
        // Parent
        if (cur.base2.getParent() != Document.NONE) {
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
      for (Node cur : nodes.values()) {
        if (cur == null) {
          Log.warn("gap in the nodes?");
          assert false;
          continue;
        }
        //Log.info("[buildPointers] " + i + " = " + cur);
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

  /** Free some memory */
  public void dropBase() {
    for (Node n : nodes.values())
      n.dropBase();
  }
}
