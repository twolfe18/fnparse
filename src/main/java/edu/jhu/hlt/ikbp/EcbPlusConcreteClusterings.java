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
import edu.jhu.hlt.tutils.hash.Hash;

/**
 * Reads XML and {@link Communication}s (must generated these ahead of time with
 * data/parma/ecbplus/Makefile) and generates {@link Clustering}.
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
  
  private AnnotationMetadata meta;
  
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
    curDocs.clear();
    File t = topics.pop();
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

    // Build Clustering from annotations in XML
    // One Cluster per TAG_DESCRIPTOR (in "edges")
    // One ClusterMember/SituationMention per grounded "node"
    curClust = new Clustering();
    curClust.setMetadata(meta);
    Map<String, Clst> clusters = new HashMap<>();
    for (File d : docs.getDocs(t)) {
      EcbPlusXmlWrapper xml = docs.get(d);
      Communication comm = docsById.get(xml.getId());

      AnalyticUUIDGeneratorFactory f = new AnalyticUUIDGeneratorFactory(comm);
      AnalyticUUIDGenerator g = f.create();
      
      // m_id -> location
      Map<String, TokenRefSequence> groundedMentions = new HashMap<>();
      // m_id -> cluster name
      Map<String, String> abstractMentions = new HashMap<>();
      for (EcbPlusXmlWrapper.Node n : xml.getNodes()) {
        if (n.isGrounded()) {
          TokenRefSequence trs = find(comm, n.t_id);
          groundedMentions.put(n.m_id, trs);
        } else {
          Object old = abstractMentions.put(n.m_id, n.descriptor);
          assert old == null;
        }
      }
      
      // Make the mentions (on SituationMention per)
      Map<String, UUID> m_id2sitMentionId = new HashMap<>();
      SituationMentionSet sms = new SituationMentionSet();
      sms.setMetadata(meta);
      sms.setUuid(g.next());
      comm.addToSituationMentionSetList(sms);
      for (Entry<String, TokenRefSequence> x : groundedMentions.entrySet()) {
        SituationMention sm = new SituationMention();
        sm.setTokens(x.getValue());
        sm.setUuid(g.next());
        sms.addToMentionList(sm);
        Object old = m_id2sitMentionId.put(x.getKey(), sm.getUuid());
        assert old == null;
      }
      
      // Which mentions are in the same cluster?
      int mentionsCovered = 0;
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
        cm.setElementId(m_id2sitMentionId.get(e.m_id_source));
        cm.setSetId(getLastSmsId(comm));
        cm.setCommunicationId(comm.getUuid());
        
        curClust.addToClusterMemberList(cm);

        mentionsCovered++;
      }
      assert mentionsCovered == groundedMentions.size();
    }
  }
  
  public static TokenRefSequence find(Communication c, int[] tokens) {
    for (int i = 1; i < tokens.length; i++)
      if (tokens[i-1] != tokens[i]-1)
        throw new IllegalArgumentException("non-contiguous: " + Arrays.toString(tokens));
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
          if (Arrays.asList(tokens).contains(i)) {
            trs.addToTokenIndexList(sentLocal);
          } else {
            assert trs.getTokenIndexList().size() == tokens.length;
            return trs;
          }
          i++;
        }
      }
    }
    return trs;
  }
  
  public static UUID getLastSmsId(Communication c) {
    List<SituationMentionSet> smss = c.getSituationMentionSetList();
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
//  public Pair<Clustering, List<Communication>> next() {
//    return new Pair<>(curClust, curDocs);
  public Topic next() {
    return new Topic(curClust, curDocs, curPart);
  }


  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    EcbPlusXmlStore xml = new EcbPlusXmlStore(config);
    File concreteDocDir = config.getExistingDir("data.ecbplus.comms", new File("data/parma/ecbplus/ECB+_LREC2014/concrete-parsey-and-stanford/"));
    EcbPlusConcreteClusterings labels = new EcbPlusConcreteClusterings("ecbplus", xml, concreteDocDir);
    RfToConcreteClusterings.runThrough(labels);
  }

}
