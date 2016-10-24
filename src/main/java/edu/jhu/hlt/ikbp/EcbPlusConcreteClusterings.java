package edu.jhu.hlt.ikbp;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.transport.TIOStreamTransport;

import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Cluster;
import edu.jhu.hlt.concrete.ClusterMember;
import edu.jhu.hlt.concrete.Clustering;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.concrete.uuid.AnalyticUUIDGeneratorFactory;
import edu.jhu.hlt.concrete.uuid.AnalyticUUIDGeneratorFactory.AnalyticUUIDGenerator;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.prim.tuple.Pair;

/**
 * Reads XML and {@link Communication}s (must generated these ahead of time with
 * data/parma/ecbplus/Makefile) and generates {@link Clustering}.
 *
 * TODO Split into {@link SituationMention}s and {@link EntityMention}s
 *
 * @author travis
 */
public class EcbPlusConcreteClusterings implements ConcreteIkbpAnnotations {
  
  private File concreteDocDir;    // contains <communicationId>.comm files with parsey and stanford annotations
  private EcbPlusXmlStore docs;
  private Deque<File> topics;
  
  private Clustering curClust;
  private List<Communication> curDocs;
  private String curPart; // train|dev|test
  private String curName;
  
  private AnnotationMetadata meta;
  
  
  
  
  
  public boolean verifyIntegrity() {

    for (Cluster c : curClust.getClusterList()) {
      for (int mIdx : c.getClusterMemberIndexList()) {
        ClusterMember cm = curClust.getClusterMemberList().get(mIdx);
        Communication comm = ConcreteIkbpAnnotations.lookup(curDocs, cm.getCommunicationId());
        if (comm == null)
          return false;
        SituationMentionSet sms = ConcreteIkbpAnnotations.lookupSms(comm, cm.getSetId());
        EntityMentionSet ems = ConcreteIkbpAnnotations.lookupEms(comm, cm.getSetId());
        if (ems == null && sms == null)
          return false;
        if (sms != null && ConcreteIkbpAnnotations.lookup(sms, cm.getElementId()) == null)
          return false;
        if (ems != null && ConcreteIkbpAnnotations.lookup(ems, cm.getElementId()) == null)
          return false;
      }
    }
    
    return true;
  }
  
  
  
  
  public EcbPlusConcreteClusterings(String tool, EcbPlusXmlStore xmlDocs, File concreteDocDir) {
    this.docs = xmlDocs;
    this.concreteDocDir = concreteDocDir;
    this.topics = new ArrayDeque<>(xmlDocs.getTopicDirs());
    this.curDocs = new ArrayList<>();
    this.meta = new AnnotationMetadata()
        .setTool(tool)
        .setTimestamp(System.currentTimeMillis() / 1000);
    bump();
  }

  private void bump() {
    
    // Read in Communication
    Map<String, Communication> docsById = new HashMap<>();
    curDocs = new ArrayList<>();
    File t = topics.pop();
    curName = "ecbplus/" + t.getName();
    int partIdx = (int) (Hash.sha256(t.getPath()) % 8L);
    switch (partIdx) {
    case 0:
      curPart = "test";
      break;
    case 1:
      curPart = "dev";
      break;
    default:
      curPart = "train";
      break;
    }
    for (File d : docs.getDocs(t)) {
      File commFile = new File(concreteDocDir, d.getName().replaceAll(".xml", ".comm"));
      if (!commFile.isFile())
        throw new RuntimeException("couldn't find " + commFile.getPath());
      Communication c = new Communication();
      try (BufferedInputStream b = new BufferedInputStream(new FileInputStream(commFile))) {
        c.read(new TCompactProtocol(new TIOStreamTransport(b)));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      curDocs.add(c);
      Object old = docsById.put(c.getId(), c);
      assert old == null;
    }

    AnalyticUUIDGeneratorFactory f = new AnalyticUUIDGeneratorFactory();
    AnalyticUUIDGenerator g = f.create();

    // Build Clustering from annotations in XML
    // One Cluster per TAG_DESCRIPTOR (in "edges")
    // One ClusterMember/SituationMention per grounded "node"
    curClust = new Clustering();
    curClust.setMetadata(meta);
    curClust.setUuid(g.next());
    Map<String, Clst> clusters = new HashMap<>();
    for (File d : docs.getDocs(t)) {
//      Log.info("reading " + d.getPath());
      EcbPlusXmlWrapper xml = docs.get(d);
      Communication comm = docsById.get(xml.getId());

      f = new AnalyticUUIDGeneratorFactory(comm);
      g = f.create();
      
      // m_id -> location
      Map<String, Pair<TokenRefSequence, String>> groundedEntMentions = new HashMap<>();
      Map<String, Pair<TokenRefSequence, String>> groundedSitMentions = new HashMap<>();
      // m_id -> cluster name
      Map<String, String> abstractMentions = new HashMap<>();
      for (EcbPlusXmlWrapper.Node n : xml.getNodes()) {
        if (n.isGrounded()) {
          Pair<TokenRefSequence, String> trs = find(comm, n.t_id);
          if (trs == null) {
            Log.info("WARNING: couldn't find TRS for: " + n);
            continue;
          }
          if (EcbPlusUtil.isEntityMention(n.type))
            groundedEntMentions.put(n.m_id, trs);
          else
            groundedSitMentions.put(n.m_id, trs);
        } else {
          Object old = abstractMentions.put(n.m_id, n.descriptor);
          assert old == null;
        }
      }

      // Make the mentions (on SituationMention per)
      SituationMentionSet sms = new SituationMentionSet();
      sms.setMetadata(meta);
      sms.setUuid(g.next());
      comm.addToSituationMentionSetList(sms);
      
      EntityMentionSet ems = new EntityMentionSet();
      ems.setMetadata(meta);
      ems.setUuid(g.next());
      comm.addToEntityMentionSetList(ems);
      
      Map<String, Pair<UUID, Boolean>> m_id2uuidAndSit = new HashMap<>();
      for (Entry<String, Pair<TokenRefSequence, String>> x : groundedSitMentions.entrySet()) {
        SituationMention sm = new SituationMention();
        sm.setTokens(x.getValue().get1());
        sm.setText(x.getValue().get2());
        sm.setUuid(g.next());
        sms.addToMentionList(sm);
        Object old = m_id2uuidAndSit.put(x.getKey(), new Pair<>(sm.getUuid(), true));
        assert old == null;
      }
      for (Entry<String, Pair<TokenRefSequence, String>> x : groundedEntMentions.entrySet()) {
        EntityMention em = new EntityMention();
        em.setTokens(x.getValue().get1());
        em.setText(x.getValue().get2());
        em.setUuid(g.next());
        ems.addToMentionList(em);
        Object old = m_id2uuidAndSit.put(x.getKey(), new Pair<>(em.getUuid(), false));
        assert old == null;
      }
      
      // Which mentions are in the same cluster?
      for (EcbPlusXmlWrapper.Edge e : xml.getEdges()) {
        int mentionIdx = curClust.getClusterMemberListSize();
        
        String clustName = abstractMentions.get(e.m_id_target);
        Clst clst = clusters.get(clustName);
        if (clst == null) {
          clst = new Clst(clustName, curClust.getClusterListSize());
          curClust.addToClusterList(clst.cluster);
          clusters.put(clustName, clst);
        }
        clst.cluster.addToClusterMemberIndexList(mentionIdx);
        clst.cluster.addToConfidenceList(1d);
        
        ClusterMember cm = new ClusterMember();
        
        
        
        Pair<UUID, Boolean> p = m_id2uuidAndSit.remove(e.m_id_source);
        cm.setElementId(p.get1());
//        assert groundedSitMentions.containsKey(e.m_id_source) == groundedSitMentions.containsKey(e.m_id_target)
//            : "e=" + e
//            + " source/sit=" + groundedSitMentions.containsKey(e.m_id_source)
//            + " target/sit=" + groundedSitMentions.containsKey(e.m_id_target);
        if (groundedSitMentions.containsKey(e.m_id_source)) {
          assert p.get2();
//          Log.info("adding " + e + " to sms.uuid=" + getLastSmsId(comm).getUuidString());
          cm.setSetId(getLastSmsId(comm));
        } else {
          assert !p.get2();
          assert groundedEntMentions.containsKey(e.m_id_source);
//          Log.info("adding " + e + " to ems.uuid=" + getLastEmsId(comm).getUuidString());
          cm.setSetId(getLastEmsId(comm));
        }
        cm.setCommunicationId(comm.getUuid());
        
        curClust.addToClusterMemberList(cm);
      }
      
      // Create singleton clusters for any mentions which didn't appear in a cluster
      for (Entry<String, Pair<UUID, Boolean>> x : m_id2uuidAndSit.entrySet()) {
        int mentionIdx = curClust.getClusterMemberListSize();

        Clst clst = new Clst(null, curClust.getClusterListSize());
        curClust.addToClusterList(clst.cluster);
        clst.cluster.addToClusterMemberIndexList(mentionIdx);
        clst.cluster.addToConfidenceList(1d);

        ClusterMember cm = new ClusterMember();
        cm.setCommunicationId(comm.getUuid());
        cm.setElementId(x.getValue().get1());
        if (x.getValue().get2())
          cm.setSetId(getLastSmsId(comm));
        else
          cm.setSetId(getLastEmsId(comm));
        
        curClust.addToClusterMemberList(cm);
      }
    }
    assert verifyIntegrity();
  }
  
  public static Pair<TokenRefSequence, String> find(Communication c, int[] tokens) {
//    for (int i = 1; i < tokens.length; i++)
//      if (tokens[i-1] != tokens[i]-1)
//        throw new IllegalArgumentException("non-contiguous: " + Arrays.toString(tokens));
    TokenRefSequence trs = null;
    int i = 0;
    for (Section section : c.getSectionList()) {
      for (Sentence sentence : section.getSentenceList()) {
        Tokenization tokz = sentence.getTokenization();
        for (int sentLocal = 0; sentLocal < tokz.getTokenList().getTokenListSize(); sentLocal++) {
          if (i == tokens[0]) {
            trs = new TokenRefSequence();
            trs.setTokenizationId(tokz.getUuid());
          }
          if (contains(tokens, i)) {
            trs.addToTokenIndexList(sentLocal);
          } else if (trs != null) {
            if (trs.getTokenIndexList().size() != tokens.length) {
              Log.warn("dis-contiguous tokens, fixme! " + Arrays.toString(tokens));
            }
            
            StringBuilder t = new StringBuilder();
            for (int ti : trs.getTokenIndexList()) {
              if (t.length() > 0) t.append(' ');
              t.append(tokz.getTokenList().getTokenList().get(ti).getText());
            }
            return new Pair<>(trs, t.toString());
          }
          i++;
        }
        if (trs != null) {
          if (trs.getTokenIndexListSize() != tokens.length) {
            // Known issue
            // I sent an email (subject: "ECB+ Data Issue" date:  8/31/2016) to them about this, no reply.
            assert c.getId().equals("36_4ecbplus") && Arrays.equals(tokens, new int[] {124, 125, 126});
            return null;
          }
          StringBuilder t = new StringBuilder();
          for (int ti : trs.getTokenIndexList()) {
            if (t.length() > 0) t.append(' ');
            t.append(tokz.getTokenList().getTokenList().get(ti).getText());
          }
          return new Pair<>(trs, t.toString());
        }
      }
    }
//    return trs;
    throw new RuntimeException("implement me");
  }
  
  public static boolean contains(int[] tokens, int ti) {
    for (int i = 0; i < tokens.length; i++)
      if (tokens[i] == ti)
        return true;
    return false;
  }
  
  public static UUID getLastSmsId(Communication c) {
    List<SituationMentionSet> smss = c.getSituationMentionSetList();
    return smss.get(smss.size() - 1).getUuid();
  }
  public static UUID getLastEmsId(Communication c) {
    List<EntityMentionSet> smss = c.getEntityMentionSetList();
    return smss.get(smss.size() - 1).getUuid();
  }
  
  static class Clst {
    Cluster cluster;
    int index;
    String name;
    public Clst(String name, int index) {
      this.name = name;
      this.cluster = new Cluster();
      this.index = index;
    }
  }

  @Override
  public String getName() {
    return meta.getTool();
  }

  @Override
  public boolean hasNext() {
    return curClust != null;
  }

  @Override
  public Topic next() {
    Topic t = new Topic(curClust, curDocs, curPart, curName);
    if (topics.isEmpty()) {
      curClust = null;
      curDocs = null;
      curPart = null;
      curName = null;
    } else {
      bump();
    }
    return t;
  }
  
  public static EcbPlusConcreteClusterings build(ExperimentProperties config) {
    EcbPlusXmlStore xml = new EcbPlusXmlStore(config);
    File concreteDocDir = config.getExistingDir("data.ecbplus.comms", new File("/home/travis/code/fnparse/data/parma/ecbplus/ECB+_LREC2014/concrete-parsey-and-stanford/"));
    EcbPlusConcreteClusterings labels = new EcbPlusConcreteClusterings("ecbplus", xml, concreteDocDir);
    return labels;
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    EcbPlusConcreteClusterings labels = build(config);
    RfToConcreteClusterings.runThrough(labels);
  }

}
