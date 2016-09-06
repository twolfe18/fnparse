package edu.jhu.hlt.ikbp;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.ikbp.Constants.EdgeRelationType;
import edu.jhu.hlt.ikbp.Constants.FeatureType;
import edu.jhu.hlt.ikbp.data.Edge;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.ikbp.data.PKB;
import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.hash.Hash;

/**
 * Returns results from ECB+. These will serve as the positive results, the
 * negative will come from general search over a different dataset.
 *
 * @author travis
 */
public class EcbPlusSearch implements IkbpSearch {
  
  static class Topic {

    class Mention {
      Document source;
      Node location;
      
      public Mention(Node location, Document source) {
        this.source = source;
        this.location = location;
      }
      
      public String getSourceDocId() {
        return source.getId();
      }
    }

    class Document {
      File source;
      String id;
      String[] tokens;
      List<Node> nodes;
      Map<String, int[]> m_id2t_id;

      public Document(File xml) {
        source = xml;
        id = xml.getName().replace(".xml", "");
        EcbPlusXmlWrapper x = new EcbPlusXmlWrapper(source);
        tokens = x.getTokensArray();
        
        nodes = new ArrayList<>();
        m_id2t_id = new HashMap<>();
        for (EcbPlusXmlWrapper.Node n : x.getNodes()) {
          Node node = EcbPlusUtil.createNode(n, tokens);
          nodes.add(node);
          Object old = m_id2t_id.put(n.m_id, n.t_id);
          assert old == null;
        }
      }
      
      public String getId() {
        return id;
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
      this.documents = new HashMap<>();
      for (File f : root.listFiles()) {
        if (!f.getName().endsWith(".xml"))
          continue;
//        Log.info("id=" + id + " f=" + f.getPath());
        Document d = new Document(f);
        documents.put(d.getId(), d);
      }
    }
    
    public String getId() {
      return id;
    }
    
    public List<Mention> keywordSearch(String keyword) {
      List<Mention> m = new ArrayList<>();
      for (Document d : documents.values())
        m.addAll(d.keywordSearch(keyword));
      return m;
    }
    
    public List<String> getMentionWords(String m_id) {
      String doc = EcbPlusUtil.getDocumentId(m_id);
//      Log.info("doc=" + doc);
      Document d = documents.get(doc);
      return d.getMentionWords(m_id);
    }
  }
  
  private Map<String, Topic> topics;
  
  /**
   * @param topicParent e.g. data/parma/ecbplus/ECB+_LREC2014/ECB+
   */
  public EcbPlusSearch(File topicParent) {
    if (!topicParent.isDirectory())
      throw new IllegalArgumentException("not a dir: " + topicParent.getPath());
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
//    Log.info("q.subject=" + q.getSubject());

    // Pull the topic out of the query
    String tId = EcbPlusUtil.getTopic(q.getSubject().getId().getName());
    Topic t = topics.get(tId);
    if (t == null)
      throw new RuntimeException("couldn't find topic " + tId);
    
    // Find the headword of the subject mention
    String m_id = findCanonicalMentionId(q.getSubject());
    List<String> keywords = getMentionWords(m_id);
    
    Set<String> queryKbDocs = new HashSet<>(q.getContext().getDocumentIds());

    // Find all keyword mention in the same topic as the query subject
    List<Response> results = new ArrayList<>();
    for (Topic.Mention m : t.keywordSearch(keywords.get(0))) {
      Node relevant = m.location;
      String docId = m.getSourceDocId();
      
      // Filter out mentions which are already in the KB
      if (queryKbDocs.contains(docId)) {
//        System.out.println("skipping since its already in the query: " + docId);
        continue;
      }
      
      Edge e = new Edge();
      Id eid = new Id()
          .setType(EdgeRelationType.COREF_SIT.ordinal())
          .setName(EdgeRelationType.COREF_SIT + "("
              + q.getSubject().getId().getName()
              + "," + relevant.getId().getName() + ")");
      eid.setId((int) Hash.sha256(eid.getName()));
      e.setRelation(eid);
      e.addToArguments(q.getSubject().getId());
      e.addToArguments(relevant.getId());
      
      Response r = new Response();
      r.setId(q.getId());
      r.setScore(1);
      PKB delta = new PKB();
      delta.addToDocumentIds(docId);
      delta.addToNodes(relevant);
      delta.addToEdges(e);
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
    throw new RuntimeException("no mention features: " + n);
  }
  
  public List<String> getMentionWords(String m_id) {
    String t = EcbPlusUtil.getTopic(m_id);
    Topic topic = topics.get(t);
    return topic.getMentionWords(m_id);
  }
  
  public String show(Node n) {
    StringBuilder sb = new StringBuilder();
    sb.append("Node id=" + n.getId().getName());
    for (String m_id : EcbPlusUtil.getMentionIds(n))
      sb.append(" " + getMentionWords(m_id));
    return sb.toString();
  }
  
  public static EcbPlusSearch build(ExperimentProperties config) {
    File root = config.getExistingDir("data.ecbplus", new File("data/parma/ecbplus/ECB+_LREC2014/ECB+"));
    return new EcbPlusSearch(root);
  }
  
  public static void main(String[] args) {
    File root = new File("data/parma/ecbplus/ECB+_LREC2014/ECB+");
    Random rand = new Random(9001);
    EcbPlusAnnotator anno = new EcbPlusAnnotator(root, rand);
    EcbPlusSearch search = new EcbPlusSearch(root);
    for (Query q = anno.nextQuery(); q != null; q = anno.nextQuery()) {
      DataUtil.showQuery(q);
      
      // Show mentions of the query
      List<String> queryMentions = EcbPlusUtil.getMentionIds(q.getSubject());
      for (String m_id : queryMentions) {
        List<String> words = search.getMentionWords(m_id);
        System.out.println("\tmention " + m_id + "\t" + words);
      }
      System.out.println();

      // Show each response and their mentions
      int i = 0;
      for (Response r : search.search(q)) {
        Node n = DataUtil.lookup(r.getCenter(), r.getDelta().getNodes());
        System.out.printf("result[%d]: %s\n", i, n.getId());
        List<String> mentions = EcbPlusUtil.getMentionIds(n);
        for (String m_id : mentions) {
          List<String> words = search.getMentionWords(m_id);
          System.out.println("\tmention " + m_id + "\t" + words);
        }
        i++;
      }
      
      System.out.println();
      System.out.println();
    }

  }

}
