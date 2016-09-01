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

import edu.jhu.hlt.ikbp.Constants.FeatureType;
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
  public static final boolean VERBOSE = true;

  /*
   * How do we maintain consistent IDs between the Annotator and Search?
   * Have to use hashing of a stable identifier, e.g. "ecb+/t4/d12/m4" for topic, doc, mention
   * 
   * What needs ids?
   * - Node: Entities and Situations
   *   Entity id: description of the first mention? define mention format as above, first is lexicographically first?
   *   Situation id:  
   */
  enum EcbPlusMentionType {
    NON_HUMAN_PART,
    NON_HUMAN_PART_GENERIC,
    HUMAN_PART,
    HUMAN_PART_MET,
    HUMAN_PART_PER,
    HUMAN_PART_VEH,
    HUMAN_PART_ORG,
    HUMAN_PART_GPE,
    HUMAN_PART_FAC,
    HUMAN_PART_GENERIC,
    ACTION_CAUSATIVE,
    ACTION_OCCURRENCE,
    ACTION_REPORTING,
    ACTION_PERCEPTION,
    ACTION_ASPECTUAL,
    ACTION_STATE,
    ACTION_GENERIC,
    NEG_ACTION_CAUSATIVE,
    NEG_ACTION_OCCURRENCE,
    NEG_ACTION_REPORTING,
    NEG_ACTION_PERCEPTION,
    NEG_ACTION_ASPECTUAL,
    NEG_ACTION_STATE,
    NEG_ACTION_GENERIC,
    LOC_GEO,
    LOC_FAC,
    LOC_OTHER,
    TIME_DATE,
    TIME_OF_THE_DAY,
    TIME_DURATION,
    TIME_REPETITION,
    UNKNOWN_INSTANCE_TAG,
  }
  

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
  
  /** Before calling, you should ensure that the given node's m_id has the document prefix */
  public static Node createNode(EcbPlusXmlWrapper.Node n) {
    EcbPlusMentionType t;
    try {
      t = EcbPlusMentionType.valueOf(n.type);
    } catch (IllegalArgumentException e) {
      if (VERBOSE)
        System.out.println("ERROR: TODO add this enum instance: " + n.type);
      t = EcbPlusMentionType.ACTION_OCCURRENCE;
    }

    // Add this mention to the PKB as a Node
    Id id = new Id();
    id.setType(t.ordinal());
    id.setName(n.m_id);
    id.setId((int) Hash.sha256(n.type + "/" + n.m_id));
    Node kbNode = new Node();
    kbNode.setId(id);
    kbNode.addToFeatures(s2f("type=" + n.type));
    kbNode.addToFeatures(s2f("ground=" + n.isGrounded()));
    if (n.isGrounded()) {
      kbNode.addToFeatures(s2f(n.m_id, FeatureType.MENTION));
    } else {
      //          kbNode.addToFeatures(s2f("desc=" + n.descriptor));
    }
    return kbNode;
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
    
    // Loop over documents/stories in the topic
    for (File f : topic.listFiles()) {
      if (!f.getPath().endsWith(".xml"))
        continue;
      if (VERBOSE)
        System.out.println("f=" + f.getPath());
      EcbPlusXmlWrapper xml = new EcbPlusXmlWrapper(f);
      
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
        Node kbNode = createNode(n);
        kb.addToNodes(kbNode);
        old = mention2Node.put(n.m_id, kbNode);
        assert old == null;
//        System.out.println("adding to pkb: " + kbNode);
      }
      
      // Add cluster labels for all (grounded) mentions.
      for (EcbPlusXmlWrapper.Edge e : xml.getEdges()) {
//        System.out.println("adding to pkb: " + e);
//        assert mention2Type.get(e.m_id_source).equals(mention2Type.get(e.m_id_target));
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
    q.setId(new Id().setId(rand.nextInt()));
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
      if (n.getId().getType() == EcbPlusMentionType.HUMAN_PART_PER.ordinal())
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
