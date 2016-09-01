package edu.jhu.hlt.ikbp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

public class EcbPlusXmlWrapper {
  public static final boolean VERBOSE = false;
  
  private File xml;
  private Document root;
  
  public EcbPlusXmlWrapper(File f) {
    this.xml = f;
    if (VERBOSE)
      System.out.println("parsing: " + f.getPath());
    try {
      DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
      root = dBuilder.parse(xml);
    // See http://stackoverflow.com/questions/13786607/normalization-in-dom-parsing-with-java-how-does-it-work
//    root.getDocumentElement().normalize();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  public File getXmlFile() {
    return xml;
  }
  
  public List<String> getTokens() {
    NodeList nl = root.getElementsByTagName("token");
    int n = nl.getLength();
    List<String> toks = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      org.w3c.dom.Node node = nl.item(i);
      toks.add(node.getTextContent());
    }
    return toks;
  }

  /** Union of grounded mentions and abstract entities/situations which are used for xdoc annotations */
  static class Node {
    String m_id;
    List<String> t_id;  // may be empty
    String type;        // tag type, e.g. HUMAN_PART_PER or TIME_DURATION
    String descriptor;  // only filled out for un-grounded nodes, e.g. t3b_judge_gave_life_sentence
    String instance_id; // only filled out for un-grounded nodes, e.g. ACT16364140299507831
    
    public Node(String m_id) {
      this.m_id = m_id;
    }
    
    public boolean isGrounded() {
      return !t_id.isEmpty();
    }
    
    @Override
    public String toString() {
      return "(Node m_id=" + m_id + " type=" + type + " t_id=" + t_id + " descriptor=" + descriptor + " instance_id=" + instance_id + ")";
    }
  }
  
  public List<Node> getNodes() {
    String prefix = xml.getName().replaceAll(".xml", "");
    List<Node> l = new ArrayList<>();
    NodeList markablesP = root.getElementsByTagName("Markables");
    assert markablesP.getLength() == 1;
    NodeList markables = markablesP.item(0).getChildNodes();
    for (int i = 0; i < markables.getLength(); i++) {
      org.w3c.dom.Node n = markables.item(i);
      if ("#text".equals(n.getNodeName()))
        continue;
      NamedNodeMap attr = n.getAttributes();
      Node node = new Node(
          prefix + "/" + attr.getNamedItem("m_id").getTextContent());
      node.type = n.getNodeName();
      node.t_id = extractTokensFromMarkable(n);
//      System.out.println("working on: " + node);
      if (node.t_id.isEmpty()) {
        node.descriptor = attr.getNamedItem("TAG_DESCRIPTOR").getTextContent();
        if (attr.getNamedItem("instance_id") != null) {
          node.instance_id = attr.getNamedItem("instance_id").getTextContent();
        } else if (VERBOSE) {
          System.out.println("ERROR: no instance_id for " + node);
        }
      }
      l.add(node);
    }
    return l;
  }

  public List<String> extractTokensFromMarkable(org.w3c.dom.Node n) {
    NodeList children = n.getChildNodes();
    List<String> l = new ArrayList<>();
    for (int i = 0; i < children.getLength(); i++) {
      org.w3c.dom.Node c = children.item(i);
      if ("token_anchor".equals(c.getNodeName())) {
        String s = c.getAttributes().getNamedItem("t_id").getTextContent();
        l.add(s);
        // Check that these are contiguous and ascending
        // Case where this is violated:
        // "it took most viewers by surprise"
        // There is an ACTION_OCCURRENCE for [took, surprise] which is non-contiguous
        if (l.size() > 1) {
          if (Integer.parseInt(l.get(l.size()-2)) + 1
              != Integer.parseInt(l.get(l.size()-1))) {
            if (VERBOSE)
              System.out.println("ERROR: non-contiguous mention span: " + l);
          }
        }
      }
    }
    //      assert l.size() > 0 : "no tokens in " + n.getAttributes().getNamedItem("m_id").getTextContent();
    return l;
  }
  
  /** These link grounded mentions (source) to abstract entities/situations nodes (target) */
  static class Edge {
    String r_id;
    String note;
    String m_id_source;
    String m_id_target;

    public Edge(String r_id) {
      this.r_id = r_id;
    }
    
    public Edge(Edge copy) {
      this.r_id = copy.r_id;
      this.note = copy.note;
      this.m_id_source = copy.m_id_source;
      this.m_id_target = copy.m_id_target;
    }
    
    @Override
    public String toString() {
      return "(Edge r_id=" + r_id + " note=" + note + " source=" + m_id_source + " target=" + m_id_target + ")";
    }
  }
  
  public List<Edge> getEdges() {
    String prefix = xml.getName().replaceAll(".xml", "");
    List<Edge> l = new ArrayList<>();
    NodeList markablesP = root.getElementsByTagName("Relations");
    assert markablesP.getLength() == 1;
    NodeList markables = markablesP.item(0).getChildNodes();
    for (int i = 0; i < markables.getLength(); i++) {
      org.w3c.dom.Node n = markables.item(i);
      if ("#text".equals(n.getNodeName()))
        continue;
      assert "CROSS_DOC_COREF".equals(n.getNodeName())
          || "INTRA_DOC_COREF".equals(n.getNodeName())
          : "nodeName=" + n.getNodeName();
      NamedNodeMap attr = n.getAttributes();

      Edge edge = new Edge(attr.getNamedItem("r_id").getTextContent());
      if (attr.getNamedItem("note") != null) {
        edge.note = attr.getNamedItem("note").getTextContent();
      } else if (VERBOSE) {
        System.out.println("ERROR: no note: " + edge);
      }

      // Find the target
      NodeList st = n.getChildNodes();
      for (int j = 0; j < st.getLength(); j++) {
        org.w3c.dom.Node nn = st.item(j);
        if ("target".equals(nn.getNodeName())) {
          assert edge.m_id_target == null;
          edge.m_id_target = nn.getAttributes().getNamedItem("m_id").getTextContent();
          assert edge.m_id_target != null;
          edge.m_id_target = prefix + "/" + edge.m_id_target;
        }
      }
      assert edge.m_id_target != null;

      // For every source, output an edge
      int added = 0;
      for (int j = 0; j < st.getLength(); j++) {
        org.w3c.dom.Node nn = st.item(j);
        if ("source".equals(nn.getNodeName())) {
          edge.m_id_source = nn.getAttributes().getNamedItem("m_id").getTextContent();
          assert edge.m_id_source != null;
          edge.m_id_source = prefix + "/" + edge.m_id_source;
          l.add(edge);
          added++;
          edge = new Edge(edge);
        }
      }
//      assert added > 0 : "len=" + st.getLength() + " edge=" + edge;
      if (added == 0) {
        if (VERBOSE)
          System.out.println("ERROR: coref with target but no source? " + edge);
      }
    }
    return l;
  }
}
