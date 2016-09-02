package edu.jhu.hlt.ikbp;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.ikbp.data.Edge;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.ikbp.data.PKB;
import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.hash.Hash;

/**
 * Provides supervision for {@link Response}s which are consistent with
 * the ECB+ data.
 * 
 * One Query per topic.
 * nextQuery can do all the work of loading in a topic.
 *
 * This class creates a lot of {@link Node}s and {@link Edge}s, but this is not
 * typical for {@link Annotator}s. Normally a {@link Search} service would create
 * them and provide them to the {@link Annotator} via {@link Response}s.
 *
 * @author travis
 */
public class EcbPlusAnnotator implements Annotator {
  public static final boolean VERBOSE = false;

  /*
   * How do we maintain consistent IDs between the Annotator and Search?
   * Have to use hashing of a stable identifier, e.g. "ecb+/t4/d12/m4" for topic, doc, mention
   */

  // A list of topic directories like /home/travis/code/fnparse/data/parma/ecbplus/ECB+_LREC2014/ECB+/3
  private Deque<File> topicDirs;
  
  // Keys are m_id and values are the cluster they belong to.
  private Map<String, String> mention2Cluster;

  // Keys are m_id and values are a mention type like HUMAN_PART_PER or ACTION_OCCURRENCE
  private Map<String, String> mention2Type;
  
  private Map<String, Node> mention2Node;
  
  private Random rand;

  /**
   * @param topicParent e.g. data/parma/ecbplus/ECB+_LREC2014/ECB+
   */
  public EcbPlusAnnotator(File topicParent, Random rand) {
    Log.info("topicParent=" + topicParent.getPath());
    if (!topicParent.isDirectory())
      throw new IllegalArgumentException();
    this.rand = rand;
    this.topicDirs = new ArrayDeque<>();
    for (String f : topicParent.list()) {
      File ff = new File(topicParent, f);
      if (ff.getName().matches("\\d+")) {
        topicDirs.push(ff);
      } else if (VERBOSE) {
        System.out.println("skipping: " + ff.getPath());
      }
    }
  }
  
  @Override
  public Query nextQuery() {
    if (topicDirs.isEmpty())
      return null;
    
    // Read in the relevant data from the topic XML files
    File topic = topicDirs.pop();
    if (VERBOSE)
      System.out.println("topic=" + topic);
    mention2Cluster = new HashMap<>();
    mention2Type = new HashMap<>();
    mention2Node = new HashMap<>();
    PKB kb = new PKB();
    
    // Choose two documents which will serve as the seed KB which is being searched upon
    List<File> xmlFiles = new ArrayList<>();
    for (File f : topic.listFiles()) {
      if (!f.getPath().endsWith(".xml"))
        continue;
      xmlFiles.add(f);
    }
    Collections.shuffle(xmlFiles, rand);
    while (xmlFiles.size() > 2)
      xmlFiles.remove(xmlFiles.size()-1);
    
    // Loop over documents/stories in the topic
    for (File f : xmlFiles) {
      if (VERBOSE)
        System.out.println("f=" + f.getPath());
      EcbPlusXmlWrapper xml = new EcbPlusXmlWrapper(f);
      String[] tokens = xml.getTokensArray();
      
      kb.addToDocumentIds(f.getName().replaceAll(".xml", ""));
      
      // Loop over mentions in the document/story
      for (EcbPlusXmlWrapper.Node n : xml.getNodes()) {
        if (n.isGrounded()) {
//          Object old = mention2Cluster.put(n.m_id, null);
//          assert old == null : "1) double add, m_id=" + n.m_id + " old=" + old;
        } else {
          assert n.descriptor != null;
          Object old = mention2Cluster.put(n.m_id, n.descriptor);
          assert old == null || old.equals(n.descriptor) : "2) double add, m_id=" + n.m_id + " old=" + old + " new=" + n.descriptor;
        }
        Object old = mention2Type.put(n.m_id, n.type);
        assert old == null || n.type.equals(old) : "old=" + old + " n=" + n;
        
        // Add this mention to the PKB as a Node
        Node kbNode = EcbPlusUtil.createNode(n, tokens);
        kb.addToNodes(kbNode);
        old = mention2Node.put(n.m_id, kbNode);
        assert old == null;
//        System.out.println("adding to pkb: " + kbNode);
        if (VERBOSE)
          System.out.printf("adding to pkb: %-16s %-20s %-28s %s\n", n.m_id, n.type, n.descriptor, n.showMention(tokens));
      }
      
      // Add cluster labels for all (grounded) mentions.
      for (EcbPlusXmlWrapper.Edge e : xml.getEdges()) {
        String desc = mention2Cluster.get(e.m_id_target);
        assert null == mention2Cluster.get(e.m_id_source);
        Object old = mention2Cluster.put(e.m_id_source, desc);
        assert old == null;
        
        // Copy features from grounded -> abstract
        Node grounded = mention2Node.get(e.m_id_source);
        Node abs = mention2Node.get(e.m_id_target);
        assert grounded != null : "couldnt find grounded: " + e.m_id_source;
        assert abs != null : "couldn't find abs: " + e.m_id_target;
        for (Id feat : grounded.getFeatures())
          abs.addToFeatures(feat);
      }
    }
    
    // Build the query
    Query q = new Query();
    Id qid = new Id();
    qid.setName("query" + rand.nextInt());
    qid.setId((int) Hash.sha256(qid.getName()));
    q.setId(qid);
    q.setContext(kb);
    q.setSubject(chooseRandomSubject(kb, rand));
    return q;
  }

  @Override
  public Response annotate(Query q, Response r) {
    
    Node subj = q.getSubject();
    String subjClust = mention2Cluster.get(subj.getId().getName());
    assert subjClust != null;
    PKB delta = r.getDelta();

    double dcg = 0;
    for (int i = 0; i < delta.getEdgesSize(); i++) {
      Edge e = delta.getEdges().get(i);

      // Assume this is a coref edge
      assert e.getArgumentsSize() == 2;
      assert e.getArguments().get(0).equals(subj.getId()) : "First argument should be the subject of the query";
      
      Id corefWithSubjId = e.getArguments().get(1);
      String corefWithSubjClust = mention2Cluster.get(corefWithSubjId.getName());
      assert corefWithSubjClust != null;
      
      if (subjClust.equals(corefWithSubjClust))
        dcg += 1 / dcgZ(i);
    }

    Response y = new Response(r);
    y.setScore(dcg);
    return y;
  }
  
  private static double dcgZ(int i) {
    if (i <= 2)
      return 1;
    return Math.log(i) / Math.log(2);
  }
  
  public static Id s2f(String featureName) {
    return s2f(featureName, Constants.FeatureType.REGULAR);
  }
  public static Id s2f(String featureName, Constants.FeatureType type) {
    Id f = new Id();
    f.setType(type.ordinal());
    f.setName(featureName);
    f.setId((int) Hash.sha256(featureName));
    return f;
  }
  
  public static Node chooseRandomSubject(PKB kb, Random r) {
    if (kb.getNodesSize() == 0)
      throw new IllegalArgumentException();
    List<Node> nodes = new ArrayList<>();
    for (Node n : kb.getNodes())
      if (n.getId().getType() == EcbPlusUtil.EcbPlusMentionType.HUMAN_PART_PER.ordinal())
        nodes.add(n);
    if (nodes.isEmpty()) {
      // Backoff to any random node
      nodes.addAll(kb.getNodes());
    }
    Collections.shuffle(nodes, r);
    return nodes.get(0);
  }
  
  public static void main(String[] args) {
    File root = new File("data/parma/ecbplus/ECB+_LREC2014/ECB+");
    Random r = new Random(9001);
    EcbPlusAnnotator anno = new EcbPlusAnnotator(root, r);
    
    for (Query q = anno.nextQuery(); q != null; q = anno.nextQuery()) {
      
    }
//    System.out.println("subj: " + q.getSubject());
//    for (Node n : q.getContext().getNodes())
//      System.out.println("q.pkb.node: " + n);
//    for (Edge n : q.getContext().getEdges())
//      System.out.println("q.pkb.edge: " + n);
//    for (String n : q.getContext().getDocumentIds())
//      System.out.println("q.pkb.doc: " + n);
  }
}
