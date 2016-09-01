package edu.jhu.hlt.ikbp;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.ikbp.Constants.FeatureType;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.ikbp.data.PKB;
import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;
import edu.jhu.hlt.tutils.Log;

/**
 * Returns results from ECB+. These will serve as the positive results, the
 * negative will come from general search over a different dataset.
 *
 * @author travis
 */
public class EcbPlusSearch implements Search {
  
  /* A query highlights a node.
   * A node has at least one mention.
   * Return one Response for every document in that topic that
   * 1) isn't already in the query's KB
   * 2) has a keyword match with the query's subject
   * 
   * For now I'll just have a delta:PKB which only contains one edge.
   */
  
  static class Topic {

    class Mention {
      Document source;
      Node location;
      
      public Mention(Node location, Document source) {
        this.source = source;
        this.location = location;
      }
      
      public String getSourceDocId() {
        throw new RuntimeException("implement me");
      }
    }

    class Document {
      File source;
      String[] tokens;
      List<Node> nodes;
      Map<String, int[]> m_id2t_id;

      public Document(File xml) {
        source = xml;
        EcbPlusXmlWrapper x = new EcbPlusXmlWrapper(source);
        List<String> ts = x.getTokens();
        tokens = new String[ts.size()];
        for (int i = 0; i < tokens.length; i++)
          tokens[i] = ts.get(i);
        
        nodes = new ArrayList<>();
        for (EcbPlusXmlWrapper.Node n : x.getNodes()) {
          Node node = EcbPlusAnnotator.createNode(n);
          nodes.add(node);
          
          int[] t_id = new int[n.t_id.size()];
          for (int i = 0; i < t_id.length; i++)
            t_id[i] = Integer.parseInt(n.t_id.get(i));
          Object old = m_id2t_id.put(n.m_id, t_id);
          assert old == null;
        }
      }

      public List<String> getMentionWords(String m_id) {
        int[] mention = m_id2t_id.get(m_id);
        List<String> r = new ArrayList<>(mention.length);
        for (int i : mention)
          r.add(tokens[i]);
        return r;
      }
      
      public List<Mention> keywordSearch(String keyword) {
        List<Mention> m = new ArrayList<>();
        mentions:
//        for (String m_id : m_id2t_id.keySet()) {
        for (Node n : nodes) {
          String m_id = n.getId().getName();
          int[] toks = m_id2t_id.get(m_id);
          for (int i = 0; i < toks.length; i++) {
            int t = toks[i];
            if (keyword.equalsIgnoreCase(tokens[t])) {
              m.add(new Mention(n, this));
              continue mentions;
            }
          }
        }
        return m;
      }

    }

    File root;
    String id;
    Map<String, Document> documents;

    /**
     * Reads in all of the documents in this topic and builds a small index.
     */
    public Topic(File root) {
      this.root = root;
      this.id = root.getName();
      
      for (File f : root.listFiles()) {
        if (!f.getName().endsWith(".xml"))
          continue;
        Log.info("id=" + id + " f=" + f.getPath());
      }
    }
    
    public String getId() {
      return id;
    }
    
    public List<Mention> keywordSearch(String keyword) {
      throw new RuntimeException("implement me");
    }
    
    public List<String> getMentionWords(String m_id) {
      String doc = getDocument(m_id);
      Document d = documents.get(doc);
      return d.getMentionWords(m_id);
    }
  }
  
  private Map<String, Topic> topics;
  
  /**
   * @param topicParent e.g. data/parma/ecbplus/ECB+_LREC2014/ECB+
   */
  public EcbPlusSearch(File topicParent) {
    // Read in all of the topics and Nodes, index Nodes by word.
    topics = new HashMap<>();
    for (String f : topicParent.list()) {
      File ff = new File(topicParent, f);
      if (ff.getName().matches("\\d+")) {
        Topic t = new Topic(ff);
        Log.info("adding topic: " + t.getId());
        Object old = topics.put(t.getId(), t);
        assert old == null;
      } else {
//        System.out.println("skipping: " + ff.getPath());
      }
    }
  }

  @Override
  public Iterable<Response> search(Query q) {
    Log.info("q.subject=" + q.getSubject());

    // Pull the topic out of the query
    String tId = getTopic(q.getSubject().getId());
    Topic t = topics.get(tId);
    if (t == null)
      throw new RuntimeException("couldn't find topic " + tId);
    
    // Find the headword of the subject mention
    String m_id = findCanonicalMentionId(q.getSubject());
    Log.info("m_id=" + m_id);
    List<String> keywords = getMentionWords(m_id);
    Log.info("keywords=" + keywords);

    // Find all keyword mention in the same topic as the query subject
    List<Response> results = new ArrayList<>();
    for (Topic.Mention m : t.keywordSearch(keywords.get(0))) {
      Node relevant = m.location;
      String docId = m.getSourceDocId();
      
      // Filter out mentions which are already in the KB
      // TODO
      
      Response r = new Response();
      r.setId(q.getId());
      r.setScore(1);
      PKB delta = new PKB();
      delta.addToDocumentIds(docId);
      delta.addToNodes(relevant);
      r.setDelta(delta);
      r.setCenter(relevant.getId());
      results.add(r);
    }
    
    return results;
  }
  
  /**
   * Looks through this node's features for 
   */
  public static String findCanonicalMentionId(Node n) {
    // TODO Better way to choose other than "the only mention"
    for (Id f : n.getFeatures())
      if (f.getType() == FeatureType.MENTION.ordinal())
        return f.getName();
    throw new RuntimeException();
  }
  
  public List<String> getMentionWords(String m_id) {
    String t = getTopic(m_id);
    Topic topic = topics.get(t);
    return topic.getMentionWords(m_id);
  }
  
  public static String getTopic(Id nodeId) {
    return getTopic(nodeId.getName());
  }
  /** Accepts strings like "12_3ecb/26" and return "12" */
  public static String getTopic(String id) {
    String[] toks = id.split("_");
    assert toks.length == 2;
    return toks[0];
  }

  /** Accepts strings like "12_3ecb/26" and return "12_3ecb" */
  public static String getDocument(String id) {
    String[] toks = id.split("/");
    assert toks.length == 2;
    return toks[0];
  }

  public static void main(String[] args) {
    File root = new File("data/parma/ecbplus/ECB+_LREC2014/ECB+");
    Random rand = new Random(9001);
    EcbPlusAnnotator anno = new EcbPlusAnnotator(root, rand);
    Query q = anno.nextQuery();
    
    EcbPlusSearch search = new EcbPlusSearch(root);
    for (Response r : search.search(q))
      System.out.println(r);
  }

}
