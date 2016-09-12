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
import edu.jhu.hlt.ikbp.data.FeatureType;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.ikbp.data.PKB;
import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.hash.Hash;

/**
 * Provides supervision for {@link Response}s which are consistent with
 * the ECB+ data.
 *
 * Assumes that every {@link Response} will have exactly one edge representing
 * coreference with the {@link Query}'s subject.
 *
 * Creates one query for every mention. There are various options below which let
 * you skip certain mentions or topics. Query generation is done document by document
 * where the context:KB accumulates Nodes according to the pseudo-code below:
 * foreach topic t:
 *   permute the documents in t (default to the order given by file name)
 *   kb = {}
 *   for document d in t:
 *     if d is not the first document:
 *       for mention m in d:
 *         emit Query(subj=m, context=kb)
 *     kb += {nodes and edges required to cover the mentions in d}
 *
 * This class creates a lot of {@link Node}s and {@link Edge}s, but this is not
 * typical for {@link IkbpAnnotator}s. Normally a {@link IkbpSearch} service would create
 * them and provide them to the {@link IkbpAnnotator} via {@link Response}s.
 *
 * @author travis
 */
public class EcbPlusAnnotator implements IkbpAnnotator {
  public static boolean VERBOSE = false;
  
  public static final String NIL_CLUSTER = "nil";

  /*
   * How do we maintain consistent IDs between the Annotator and Search?
   * Have to use hashing of a stable identifier, e.g. "ecb+/t4/d12/m4" for topic, doc, mention
   * NOTE: The format we are currently using is closer to "33_12ecbplus/15" for the 15th mention in doc 33_12ecbplus
   */
  private EcbPlusXmlStore xmlDocs;

  // A list of topic directories like /home/travis/code/fnparse/data/parma/ecbplus/ECB+_LREC2014/ECB+/3
  // The directory at the head of the queue is the "current" topic.
  private Deque<File> topics;
  
  // The documents in the current topic.
  private Deque<EcbPlusXmlWrapper> docs;

  // PKB nodes and edges for the current document. This is added to context when
  // the document is no longer being used to generate queries.
  private PKB delta;
  
  // Index of the current node in delta.nodes
  private int curDeltaNodeIndex;
  
  // Represents context for the current query. Grows every time a document is processed.
  private PKB context;
  

  // NOTE: These maps are all ACCUMULATIVE, cleared once the topic changes.
  // Keys are m_id and values are the cluster they belong to.
  private Map<String, String> mention2Cluster;
  // Keys are m_id and values are a mention type like HUMAN_PART_PER or ACTION_OCCURRENCE
  private Map<String, String> mention2Type;
  private Map<String, Node> mention2Node;
  
  private Random rand;


  /**
   * @param topicParent e.g. data/parma/ecbplus/ECB+_LREC2014/ECB+
   */
  public EcbPlusAnnotator(EcbPlusXmlStore xmlDocs, Random rand) {
    this.xmlDocs = xmlDocs;
    this.context = newPKB();
    this.delta = newPKB();
    this.docs = new ArrayDeque<>();
    this.topics = new ArrayDeque<>();
    this.curDeltaNodeIndex = -1;

    this.mention2Cluster = new HashMap<>();
    this.mention2Type = new HashMap<>();
    this.mention2Node = new HashMap<>();

    this.rand = rand;
    this.topics = new ArrayDeque<>();
    for (File f : xmlDocs.getTopicDirs())
      topics.push(f);
  }
  
  /** Performs a += b on nodes, edge, and docs */
  public static void kbAdd(PKB a, PKB b) {
    for (Id docId : b.getDocuments())
      a.addToDocuments(docId);
    assert DataUtil.uniq(a.getDocuments());
    for (Node n : b.getNodes())
      a.addToNodes(n);
    for (Edge e : b.getEdges())
      a.addToEdges(e);
  }
  
  public static PKB newPKB() {
    return new PKB()
        .setDocuments(new ArrayList<>())
        .setEdges(new ArrayList<>())
        .setNodes(new ArrayList<>());
  }
  
  private boolean nextDocument() {
    // context += delta
    kbAdd(context, delta);
    
    // Populate delta:PKB from the mentions in the current document
    if (docs.isEmpty()) {
      boolean nt = nextTopic();
      if (!nt)
        return false;
    }
    curDeltaNodeIndex = 0;
    EcbPlusXmlWrapper xml = docs.pop();
    File f = xml.getXmlFile();

    if (VERBOSE)
      Log.info("just popped: " + f.getPath());

    delta = newPKB();
    Id ndid = new Id();
    ndid.setName(f.getName().replaceAll(".xml", ""));
    context.addToDocuments(ndid);

    String[] tokens = xml.getTokensArray();
    for (EcbPlusXmlWrapper.Node n : xml.getNodes()) {
      if (n.isGrounded()) {
//        Node kbNode = EcbPlusUtil.createNode(n, tokens);
        Node kbNode = mention2Node.get(n.m_id);
        assert kbNode != null;
        assert DataUtil.isGround(kbNode) : "m_id=" + kbNode.getId().getName() + " feats: " + kbNode.getFeatures();
        delta.addToNodes(kbNode);
        if (VERBOSE)
          System.out.printf("adding to pkb: %-16s %-20s %-28s %s\n", n.m_id, n.type, n.descriptor, n.showMention(tokens));
      }
    }
    
    return true;
  }
  
  private boolean nextTopic() {
    // Read in the relevant data from the topic XML files
    if (topics.isEmpty())
      return false;
    File topic = topics.pop();
    if (VERBOSE)
      System.out.println("topic=" + topic);
    
    context = newPKB();

    mention2Cluster.clear();
    mention2Type.clear();
    mention2Node.clear();
    
    List<File> xmlFiles = xmlDocs.getDocs(topic);
    Collections.shuffle(xmlFiles, rand);

    docs.clear();
    for (File f : xmlFiles)
      docs.push(xmlDocs.get(f));
    
    addTopicLabels();

    return true;
  }
  
  private void addTopicLabels() {
    // Loop over mentions in the document/story
    for (EcbPlusXmlWrapper xml : new ArrayList<>(docs)) {
      String[] tokens = xml.getTokensArray();

      for (EcbPlusXmlWrapper.Node n : xml.getNodes()) {
        if (n.isGrounded()) {
          Object old = mention2Cluster.put(n.m_id, NIL_CLUSTER);
          assert old == null : "1) double add, m_id=" + n.m_id + " old=" + old;
        } else {
          assert n.descriptor != null;
          Object old = mention2Cluster.put(n.m_id, n.descriptor);
          assert old == null || old.equals(n.descriptor) : "2) double add, m_id=" + n.m_id + " old=" + old + " new=" + n.descriptor;
        }
        Object old = mention2Type.put(n.m_id, n.type);
        assert old == null || n.type.equals(old) : "old=" + old + " n=" + n;

        // Add this mention to the PKB as a Node
        Node kbNode = EcbPlusUtil.createNode(n, tokens);
        old = mention2Node.put(n.m_id, kbNode);
        assert old == null;
      }

      // Add cluster labels for all (grounded) mentions.
      for (EcbPlusXmlWrapper.Edge e : xml.getEdges()) {

        String desc = mention2Cluster.get(e.m_id_target);
        assert desc != null;
        Object old = mention2Cluster.put(e.m_id_source, desc);
        assert old == NIL_CLUSTER || old == null : "old=" + old + " desc=" + desc + " source=" + e.m_id_source;

        // Copy features from grounded -> abstract
        Node grounded = mention2Node.get(e.m_id_source);
        Node abs = mention2Node.get(e.m_id_target);
        assert grounded != null : "couldnt find grounded: " + e.m_id_source;
        assert abs != null : "couldn't find abs: " + e.m_id_target;
        for (Id feat : grounded.getFeatures())
          abs.addToFeatures(feat);
      }
    }
  }
  
  @Override
  public Query nextQuery() {
    if (curDeltaNodeIndex < 0 || curDeltaNodeIndex >= delta.getNodesSize()) {
      boolean nd;
      
      // This adds the first document to the context:PKB
      nd = nextDocument();
      if (!nd) return null;

      // This uses the second document for delta:PKB
      nd = nextDocument();
      if (!nd) return null;
    }
    Node subj = delta.getNodes().get(curDeltaNodeIndex++);
    
    Query q = new Query();
    Id qid = new Id();
    qid.setName("q" + Integer.toHexString(rand.nextInt()).toUpperCase());
    qid.setId((int) Hash.sha256(qid.getName()));
    q.setId(qid);
    q.setContext(context);
    q.setSubject(subj);
    assert DataUtil.isGround(q.getSubject());
    return q;
  }
  
  public boolean annotateHelper(Query q, Response r) {
    if (r.getDelta().getEdgesSize() != 1)
      throw new IllegalArgumentException();
    return annotateHelper(q, r, 0);
  }
  public boolean annotateHelper(Query q, Response r, int kbDeltaIndex) {
    Edge kbDeltaEdge = r.getDelta().getEdges().get(kbDeltaIndex);
    Node subj = q.getSubject();
    return annotateHelper(subj, kbDeltaEdge);
  }
  public boolean annotateHelper(Node subj, Edge kbDeltaEdge) {
    String subjClust = mention2Cluster.get(subj.getId().getName());
    if (subjClust == null) {
      throw new RuntimeException("could not find"
          + " m_id=" + subj.getId().getName()
          + " m2c.size=" + mention2Cluster.size());
    }
    if (subjClust == NIL_CLUSTER)
      return false;

    // Assume this is a coref edge
    assert kbDeltaEdge.getArgumentsSize() == 2;
    assert kbDeltaEdge.getArguments().get(0).equals(subj.getId()) : "First argument should be the subject of the query";

    Id corefWithSubjId = kbDeltaEdge.getArguments().get(1);
    String corefWithSubjClust = mention2Cluster.get(corefWithSubjId.getName());
    if (corefWithSubjClust == null) {
      throw new RuntimeException("could not find"
          + " m_id=" + corefWithSubjId.getName()
          + " m2c.size=" + mention2Cluster.size());
    }

    return subjClust.equals(corefWithSubjClust);
  }

  @Override
  public Response annotate(Query q, Response r) {
    Node subj = q.getSubject();
    PKB delta = r.getDelta();
    double dcg = 0;
    for (int i = 0; i < delta.getEdgesSize(); i++) {
      Edge e = delta.getEdges().get(i);
      if (annotateHelper(subj, e))
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
    return s2f(featureName, FeatureType.REGULAR);
  }
  public static Id s2f(String featureName, FeatureType type) {
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
    ExperimentProperties config = ExperimentProperties.init(args);
    VERBOSE = config.getBoolean("verbose", true);
    Random r = config.getRandom();
    EcbPlusXmlStore xmlDocs = new EcbPlusXmlStore(config);
    EcbPlusAnnotator anno = new EcbPlusAnnotator(xmlDocs, r);
    
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
