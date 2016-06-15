package edu.jhu.hlt.uberts;

import java.util.HashMap;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.util.HPair;
import edu.stanford.nlp.util.Iterables;

public interface NodeStore {
  public static int DEBUG = 0;

  public HypNode lookupNode(NodeType nodeType, Object value, boolean addIfNotPresent);
  public void clear();
  public Iterable<HypNode> getNodes();

  /**
   * Similar to Alphabet
   */
  public static class Regular implements NodeStore {
    private HashMap<HPair<NodeType, Object>, HypNode> nodes;
    private String name;
    public Regular(String name) {
      this.name = name;
      nodes = new HashMap<>();
    }
    @Override
    public HypNode lookupNode(NodeType nodeType, Object value, boolean addIfNotPresent) {
      //    Pair<NodeType, Object> key = new Pair<>(nodeType, value);
      HPair<NodeType, Object> key = new HPair<>(nodeType, value);
      HypNode v = nodes.get(key);
      if (DEBUG > 0 && "temporary".equals(name))
        System.out.println("[lookupNode] " + name + ": " + nodeType + " " + value + " addIfNotPresent=" + addIfNotPresent + " found=" + (v != null));
      if (v == null && addIfNotPresent) {
        v = new HypNode(nodeType, value);
        nodes.put(key, v);
        if (nodes.size() % 25000 == 0)
          Log.info("[memLeak] " + name + " nodes.size=" + nodes.size() + " node=" + v);
      }
      return v;
    }
    @Override
    public void clear() {
      if (DEBUG > 0)
        System.out.println("[clear] " + this);
      nodes.clear();
    }
    @Override
    public String toString() {
      return "(NodeStore.Regular " + name + " size=" + nodes.size() + ")";
    }
    @Override
    public Iterable<HypNode> getNodes() {
      return nodes.values();
    }
  }

  /**
   * Analogous to {@link State.Split}, allows you to remove "non-schema nodes"
   * efficiently. A schema node is one which is an argument (or head of) at least
   * one schema edge.
   */
  public static class Composite implements NodeStore {
    private NodeStore schemaNodes;
    private NodeStore temporaryNodes;

    public Composite(NodeStore schemaNodes, NodeStore temporaryNodes) {
      this.schemaNodes = schemaNodes;
      this.temporaryNodes = temporaryNodes;
    }

    public NodeStore getSchemaNodes() {
      return schemaNodes;
    }

    public NodeStore getTemporaryNodes() {
      return temporaryNodes;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Iterable<HypNode> getNodes() {
      return Iterables.chain(temporaryNodes.getNodes(), schemaNodes.getNodes());
    }

    @Override
    public String toString() {
      return "(NodeStore.Composite temp=" + temporaryNodes + " schema=" + schemaNodes + ")";
    }

    @Override
    public HypNode lookupNode(NodeType nodeType, Object value, boolean addIfNotPresent) {
      if (addIfNotPresent) {
        throw new IllegalArgumentException("if you wish to add, you must use "
            + "the other version of this method which says whether this node "
            + "touches a schema edge or not");
      }
      HypNode n = schemaNodes.lookupNode(nodeType, value, false);
      if (n == null) {
        n = temporaryNodes.lookupNode(nodeType, value, false);
        if (DEBUG > 0)
          System.out.println("[composite lookupNode readonly] " + nodeType + " " + value + " found=" + (n == null ? "none" : "temporary"));
      } else {
        if (DEBUG > 1)
          System.out.println("[composite lookupNode readonly] " + nodeType + " " + value + " found=schema");
      }
      return n;
    }

    public HypNode lookupNode(NodeType nodeType, Object value, boolean addIfNotPresent, boolean isSchemaNode) {
      if (DEBUG > 1 || (DEBUG > 0 && !isSchemaNode))
        System.out.println("[composite lookupNode] " + nodeType + " " + value + " addIfNotPresent=" + addIfNotPresent + " isSchema=" + isSchemaNode);
      // !inReg && !inSchema => add to proper set of nodes
      // inReg && !inSchema => if isSchema move to schema, else return existing
      // !inReg && inSchema => return node from schema
      // can't be in both
      HypNode s = schemaNodes.lookupNode(nodeType, value, isSchemaNode);
      if (s != null)
        return s;
      assert !isSchemaNode;
      return temporaryNodes.lookupNode(nodeType, value, addIfNotPresent);
    }

    public void clearNonSchema() {
      temporaryNodes.clear();
    }

    @Override
    public void clear() {
      if (DEBUG > 0)
        System.out.println("[composite clear] " + this);
      schemaNodes.clear();
      temporaryNodes.clear();
    }

    public boolean contains(HypNode n) {
      HypNode r = lookupNode(n.getNodeType(), n.getValue(), false);
      if (r != n) {
        System.out.println("about to crash");
        for (HypNode rr : temporaryNodes.getNodes())
          if (rr.toString().contains("word2") || rr.toString().contains("docid"))
            System.out.println(rr);
        System.out.println("r=" + r + " n=" + n);
      }
      return r == n;
    }
  }

}
