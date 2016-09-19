package edu.jhu.hlt.ikbp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import edu.jhu.hlt.ikbp.data.Edge;
import edu.jhu.hlt.ikbp.data.FeatureType;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.ikbp.data.NodeType;
import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;
import edu.jhu.hlt.tutils.Log;

public class DataUtil {
  
  /**
   * T must have hashCode/equals implemented.
   */
  public static <T> boolean uniq(List<T> items) {
    HashSet<T> s = new HashSet<>();
    for (T i : items)
      if (!s.add(i))
        return false;
    return true;
  }
  
  public static String showFeatures(List<Id> feats) {
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    if (feats == null) {
      sb.append("NULL");
    } else {
      for (int i = 0; i < feats.size(); i++) {
        if (i > 0) sb.append(", ");
        Id f = feats.get(i);
        sb.append(FeatureType.findByValue(f.getType()));
        sb.append(':');
        sb.append(f.getName());
      }
    }
    sb.append(']');
    return sb.toString();
  }

  public static String showNames(List<Id> ids) {
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < ids.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(ids.get(i).getName());
    }
    sb.append(']');
    return sb.toString();
  }
  
  public static String showEdges(List<Edge> edges) {
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < edges.size(); i++) {
      if (i > 0) sb.append(", ");
      sb.append(edges.get(i).getId().getName());
    }
    sb.append(']');
    return sb.toString();
  }

  public static void showQuery(Query q) {
    for (int i = 0; i < 100; i++) System.out.print('#');
    System.out.println("\nquery: " + q.getId());
    System.out.println("\ttype:          " + getType(q.getSubject()));
//    System.out.println("\tmentions:      " + getMentions(q.getSubject()));
    System.out.println("\tfeatures:      " + showFeatures(q.getSubject()));
    System.out.println("\tcontext.docs:  " + showNames(q.getContext().getDocuments()));
    System.out.println("\tcontext.nodes: " + q.getContext().getNodes());
    System.out.println("\tcontext.edges: " + showEdges(q.getContext().getEdges()));
  }
  
  public static void showResponse(Response r) {
    System.out.println("response: " + r.getId());
    System.out.println("\tscore:       " + r.getScore());
    System.out.println("\tdelta.nodes: " + r.getDelta().getNodes());
    System.out.println("\tdelta.edges: " + showEdges(r.getDelta().getEdges()));
  }
  
  public static boolean isGround(Node n) {
    Id g = null;
    String prefix = "ground=";
    for (Id f : n.getFeatures()) {
      if (f.getName().startsWith(prefix)) {
        assert g == null;
        g = f;
      }
    }
    boolean b = Boolean.parseBoolean(g.getName().substring(prefix.length()));
    return b;
  }

  public static String showFeatures(Node n) {
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    int adds = 0;
    for (int i = 0; i < n.getFeaturesSize(); i++) {
      Id f = n.getFeatures().get(i);
      if (f.getType() == FeatureType.REGULAR.ordinal()) {
        if (adds > 0) sb.append(", ");
        sb.append(n.getFeatures().get(i).getName());
        adds++;
      }
    }
    sb.append(']');
    return sb.toString();
  }
  
//  public static List<String> getMentions(Node n) {
//    if (!n.isSetFeatures())
//      return Arrays.asList("NULL");
//    Set<String> uniq = new HashSet<>();
//    List<String> m_id = new ArrayList<>();
//    for (Id f : n.getFeatures()) {
//      if (f.getType() == FeatureType.MENTION_ID.ordinal()) {
//        assert uniq.add(f.getName());
//        m_id.add(f.getName());
//      }
//    }
//    return m_id;
//  }
  
  public static List<Id> filterByFeatureType(List<Id> features, FeatureType t) {
    List<Id> f = new ArrayList<>();
    for (Id i : features)
      if (i.isSetType() && i.getType() == t.ordinal())
        f.add(i);
    return f;
  }

  public static String getType(Node n) {
    
    if (!n.getId().isSetType())
      return "NULL";
    return NodeType.findByValue(n.getId().getType()).name();

//    return n.getId().getName();

//    for (Id feature : n.getFeatures())
//      if (feature.getType() == FeatureType.NODE_TYPE.ordinal())
//        return feature.getName();
//    return null;
  }

  @SafeVarargs
  public static Node lookup(Id id, List<Node>... nodes) {
    for (List<Node> ln : nodes)
      for (Node n : ln)
        if (id.equals(n.getId()))
          return n;
    Log.info("WARNING: Failed to find node matching " + id);
    for (List<Node> ns : nodes) {
      Log.info("  in " + ns);
    }
    return null;
  }
}
