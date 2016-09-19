package edu.jhu.hlt.ikbp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import edu.jhu.hlt.concrete.Cluster;
import edu.jhu.hlt.concrete.ClusterMember;
import edu.jhu.hlt.concrete.Clustering;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.ikbp.ConcreteIkbpAnnotations.Topic;
import edu.jhu.hlt.ikbp.RfToConcreteClusterings.Link;
import edu.jhu.hlt.ikbp.data.Edge;
import edu.jhu.hlt.ikbp.data.FeatureType;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.ikbp.data.PKB;
import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;

/**
 * Given {@link Clustering}s, produce IKBP queries and feedback on ({@link Query}, {@link Response}).
 * See {@link QueryGenerationMode} for details on how many queries are constructed.
 *
 * @author travis
 */
public class ConcreteIkbpAnnotator implements IkbpAnnotator {
  public static boolean VERBOSE = false;
    
  /* Someone who is going to provide concrete to use my tool,
   * how do they want to use it?
   * a) they want to train by providing (sourceDoc, targetDoc, predArgAlignment) triples
   * b) they want to do retrieval by providing some s and a collection of t
   *    this includes the annotation process where a are generated
   *
   * Currently I put these both under the same back-and-fourth between services.
   * For train/test (a), need to setup two services for search and annotate, let them talk to each other and record messages
   * for regular use (b), also need to setup two 
   */

  public enum QueryGenerationMode {
    /**
     * First communication is the source, rest are targets. Emit a query for
     * every mention in the source conditioned on PKB built from just the source.
     */
    ONLY_SOURCE,

    /**
     * Emit a query for every mention in document i conditioned on PKB built
     * from cluster in documents[:i].
     */
    ADD_TARGETS,
  }
  
  public static class SingleTopicAnnotator implements IkbpAnnotator {
    private Clustering topic;
    private PKB context;                // docs and nodes up to and including curComm
    private Deque<Communication> docs;  // docs after curComm
    private Communication curComm;
    private PKB current;                // curComm and nodes in curComm
    private int curNode;                // index into current.nodes
    private Map<String, String> mention2cluster;
    private QueryGenerationMode qmode;
    
    // intersect(context, current) == {curComm}, but no nodes/edges
    
    public boolean computeDebugFeatures = true;
    
//    public SingleTopicAnnotator(Pair<Clustering, List<Communication>> t, QueryGenerationMode qmode) {
//      this(t.get1(), t.get2(), qmode);
//    }

    public SingleTopicAnnotator(Topic t, QueryGenerationMode qmode) {
      if (VERBOSE) {
        List<String> commIds = new ArrayList<>();
        for (Communication c : docs) commIds.add(c.getId());
        Log.info("loading topic containing docs: " + commIds);
      }
      if (t.comms == null || t.comms.isEmpty())
        throw new IllegalArgumentException();
      this.topic = t.clustering;
      this.docs = new ArrayDeque<>(t.comms);
      this.context = newPKB();
      this.current = newPKB();
      this.mention2cluster = new HashMap<>();
      this.qmode = qmode;
      
      // At the first step:
      // context = {}
      // current = the first document in the topic, docs = [the other comm]
      nextDocument();
    }
    
    private void nextDocument() {

      Communication prevComm = curComm;
      if (prevComm != null && qmode == QueryGenerationMode.ONLY_SOURCE) {
        context.getNodes().clear();
        context.getEdges().clear();
      }

      curComm = docs.pop();
      curNode = 0;
      Id curCommId = new Id().setName(curComm.getUuid().getUuidString());
      if (VERBOSE)
        Log.info("curComm=" + curComm.getId() + " docs.size=" + docs.size());

      if (context.getDocumentsSize() == 0 || qmode == QueryGenerationMode.ADD_TARGETS)
        context.addToDocuments(curCommId);

      current = newPKB();
      current.setDocuments(Arrays.asList(curCommId));

      // Find mentions in the current document
      for (ClusterMember cm : topic.getClusterMemberList()) {
        if (curComm.getUuid().equals(cm.getCommunicationId())) {
          
          // Each cluster item is a node
          UUID sitMentionId = cm.getElementId();
          Node n = new Node();
          n.setId(new Id().setName(sitMentionId.getUuidString()));
          n.addToFeatures(new Id()
//              .setType(FeatureType.MENTION_ID.ordinal())
              .setType(FeatureType.CONCRETE_UUID.ordinal())
              .setName(cm.getElementId().getUuidString()));
          if (computeDebugFeatures) {
            SituationMentionSet sms = ConcreteIkbpAnnotations.lookupSms(curComm, cm.getSetId());
            SituationMention sm = ConcreteIkbpAnnotations.lookup(sms, cm.getElementId());
            n.addToFeatures(new Id()
                .setType(FeatureType.REGULAR.ordinal())
                .setName("text=" + sm.getText()));
          }
          current.addToNodes(n);
          
          // No edges: for now they will just be represented outside of a PKB
        }
      }
      
      // Store the mention -> cluster mapping
      for (int cIdx = 0; cIdx < topic.getClusterListSize(); cIdx++) {
        Cluster c = topic.getClusterList().get(cIdx);
        for (int mIndex : c.getClusterMemberIndexList()) {
          ClusterMember item = topic.getClusterMemberList().get(mIndex);
          String key = item.getElementId().getUuidString();
          String value = "cluster" + cIdx;
          Object old = mention2cluster.put(key, value);
          assert old == null || old.equals(value) : "key=" + key + " has two values, old=" + old + " new=" + value;
        }
      }
    }
    
    private void nextMention() {
      curNode++;
      if (curNode >= current.getNodesSize() && !docs.isEmpty())
        nextDocument();
    }
    
    @Override
    public boolean hasNext() {
      return curNode < current.getNodesSize();
    }

    @Override
    public Query next() {
      Query q = new Query();
      q.setId(new Id().setName("t" + topic.getUuid().getUuidString() + "_q" + curNode));
      q.setContext(context);

      Node subj = current.getNodes().get(curNode);
      q.setSubject(subj);
      context.addToNodes(subj);

      nextMention();
      return q;
    }

    @Override
    public Response annotate(Query q, Response r) {
      String subj = q.getSubject().getId().getName();
      String anchor = r.getAnchor().getName();
      
      String subjC = mention2cluster.get(subj);
      String anchorC = mention2cluster.get(anchor);
      if (subjC == null)
        throw new RuntimeException("couldn't lookup subj cluster: " + subj);
      if (anchorC == null)
        throw new RuntimeException("couldn't lookup anchor cluster: " + anchor);
      
      Response rr = new Response(r);
      rr.setScore(subjC.equals(anchorC) ? 1 : 0);
      return rr;
    }
  }
  
  private ConcreteIkbpAnnotations labels;
  private QueryGenerationMode qmode;
  private SingleTopicAnnotator topic;
  
  public boolean computeDebugFeatures = false;

  public ConcreteIkbpAnnotator(ConcreteIkbpAnnotations labels, QueryGenerationMode qmode) {
    this.labels = labels;
    this.topic = new SingleTopicAnnotator(labels.next(), qmode);
    this.qmode = qmode;
  }
  
  @Override
  public boolean hasNext() {
    return topic.hasNext() || labels.hasNext();
  }

  @Override
  public Query next() {
    if (!labels.hasNext())
      return null;
    if (!topic.hasNext())
      topic = new SingleTopicAnnotator(labels.next(), qmode);
    return topic.next();
  }

  @Override
  public Response annotate(Query q, Response r) {
    return topic.annotate(q, r);
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
  
  public static Node find(Id id, List<Node> nodes) {
    Node m = null;
    for (Node n : nodes) {
      if (id.equals(n.getId())) {
        assert m == null : "two entities with same id! " + id + " in\n" + nodes;
        m = n;
      }
    }
    return m;
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    Predicate<Link> dev = l -> l.pair.contains("XML/dev");
    ConcreteIkbpAnnotations labels = new RfToConcreteClusterings("rothfrank", dev, config);
    ConcreteIkbpAnnotator anno = new ConcreteIkbpAnnotator(labels, QueryGenerationMode.ADD_TARGETS);
    anno.computeDebugFeatures = true;
    Query q;
    int i;
    for (i = 0; (q = anno.next()) != null; i++) {
//      System.out.println(i + "\t" + q);
      DataUtil.showQuery(q);
      
//      List<String> m_ids = DataUtil.getMentions(q.getSubject());
      List<Id> m_ids = DataUtil.filterByFeatureType(q.getSubject().getFeatures(), FeatureType.CONCRETE_UUID);
      if (m_ids.isEmpty())
        throw new RuntimeException("Each query subject must have at least one mention");
      
      if (find(q.getSubject().getId(), q.getContext().getNodes()) == null)
        throw new RuntimeException("query subject is not in context pkb");
    }
    Log.info("numQuery=" + i);
  }

}
