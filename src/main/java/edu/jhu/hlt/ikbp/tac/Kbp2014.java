package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import edu.jhu.hlt.concrete.Cluster;
import edu.jhu.hlt.concrete.ClusterMember;
import edu.jhu.hlt.concrete.Clustering;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.access.FetchRequest;
import edu.jhu.hlt.concrete.access.FetchResult;
import edu.jhu.hlt.ikbp.ConcreteIkbpAnnotations.Topic;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Result;
import edu.jhu.hlt.scion.concrete.server.FetchCommunicationServiceImpl;
import edu.jhu.hlt.scion.core.accumulo.ConnectorFactory;
import edu.jhu.hlt.scion.core.accumulo.ScionConnector;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;

/**
 * KBP 2014 will serve as the base for the retrieval annotations
 * I'm putting together for my thesis. Each is an entity where we have a
 * small number of mentions (like 2-20) in
 * LDC2014E87 \subset LDC2014E13 \notsubset gigaword
 *
 * To generate the corpus to annotate, query on every entity, get back a
 * bunch of mentions which are co-located with events extracted from a
 * system like fnparse/semafor/serif. Run all of these events, grouped by
 * the entity they're associated with, through a PARMA-like deduplicator
 * to produce a greedy clustering.
 *
 * 
 *
 * @author travis
 */
public class Kbp2014 {
  
  static class Query {
    
    // Come from queries XML file
    String id;
    String name;
    String docid;
    /** character offsets, both inclusive */
    int beg, end;
    
    // Come from links TSV file
    String entity_id;
    String entity_type;
    String genre;
    String web_search, wiki_text, unknown;
    
    // Come from source docs
    String sourceDoc;
    
    public Query(String id) {
      this.id = id;
      beg = end = -1;
    }
    
    @Override
    public String toString() {
      return "(Query " + id + " in " + docid + " \"" + name + "\" [" + beg + ", " + end + "])";
    }
    
    public static List<Query> parse(File f) throws Exception {
      List<Query> l = new ArrayList<>();

      DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
      Document root = dBuilder.parse(f);
      NodeList nl = root.getElementsByTagName("query");
      for (int i = 0; i < nl.getLength(); i++) {
        Node n = nl.item(i);
        String id = n.getAttributes().getNamedItem("id").getTextContent();
//        System.out.println(id);
        Query q = new Query(id);
        NodeList children = n.getChildNodes();
        for (int j = 0; j < children.getLength(); j++) {
          Node c = children.item(j);
//          System.out.println(c.getNodeName() + "\t" + c.getTextContent());
          switch (c.getNodeName()) {
          case "name":
            q.name = c.getTextContent();
            break;
          case "docid":
            q.docid = c.getTextContent();
            break;
          case "beg":
            q.beg = Integer.parseInt(c.getTextContent());
            break;
          case "end":
            q.end = Integer.parseInt(c.getTextContent());
            break;
          default:
//            System.err.println("skipping: " + c.getNodeName());
            continue;
          }
        }
//        System.out.println(q);
        l.add(q);
      }
      
      return l;
    }
  }
  
  static Communication getCommunication(String id) {
    throw new RuntimeException("implement me");
  }
  
  /**
   * Build one topic per entity, each of which contains one cluster
   * which all of the relevant mentions in it.
   */
  public static List<Topic> buildClusters(List<Query> resolvedQueries) {
    Map<String, List<Query>> byTopic = new HashMap<>();
    // TODO groupby
    
    List<Topic> topics = new ArrayList<>();
    for (Entry<String, List<Query>> x : byTopic.entrySet()) {
      String entityId = x.getKey();
      List<Query> mentions = x.getValue();
      List<Communication> comms = new ArrayList<>();
      Clustering clustering = new Clustering();
      
      // Add one cluster containing all mentions of this entity
      Cluster entClust = new Cluster();
      for (Query q : mentions) {
        Communication c = getCommunication(q.docid);
        comms.add(c);
        
        ClusterMember cm = new ClusterMember();
        entClust.addToChildIndexList(clustering.getClusterMemberListSize());
        clustering.addToClusterMemberList(cm);
      }
      clustering.addToClusterList(entClust);
      
      // TODO Add a cluster for every other mention in these documents
      // TODO maybe make them separate clusters based on NER type?
      
      String name = "topic_for_entity:" + entityId;
      Topic t = new Topic(clustering, comms, "test", name);
      topics.add(t);
    }
    return topics;
  }
  
  public static class AddKbLinks {
    File home = new File("data/parma/LDC2014E81/data");
    File linkFile = new File(home, "tac_2014_kbp_english_EDL_evaluation_KB_links.tab");
    
    Map<String, String> q2entity_id = new HashMap<>();
    Map<String, String> q2entity_type = new HashMap<>();
    Map<String, String> q2genre = new HashMap<>();
    Map<String, String> q2web_search = new HashMap<>();
    Map<String, String> q2wiki_text = new HashMap<>();
    Map<String, String> q2unknown = new HashMap<>();
    
    public AddKbLinks() throws IOException {
      boolean first = true;
      for (String line : FileUtil.getLines(linkFile)) {
        if (first) {
          assert line.startsWith("query_id\t");
          first = false;
          continue;
        }
        String[] ar = line.split("\t");
        q2entity_id.put(ar[0], ar[1]);
        q2entity_type.put(ar[0], ar[2]);
        q2genre.put(ar[0], ar[3]);
        q2web_search.put(ar[0], ar[4]);
        q2wiki_text.put(ar[0], ar[5]);
        q2unknown.put(ar[0], ar[6]);
      }
    }
    
    public void add(Query q) {
      q.entity_id = q2entity_id.get(q.id);
      q.entity_type = q2entity_type.get(q.id);
      q.genre = q2genre.get(q.id);
      q.web_search = q2web_search.get(q.id);
      q.wiki_text = q2wiki_text.get(q.id);
      q.unknown = q2unknown.get(q.id);
    }
  }
  
  public static class AddQueryDocumentContext {
    File home = new File("data/parma/LDC2014E81/data");
    
    public boolean addDocumentContext(Query q) {
      assert q.sourceDoc == null;
      File f = new File(home, "source_docs/" + q.docid);
      if (!f.isFile()) {
//        throw new RuntimeException("can'nt find source: " + f.getPath());
        Log.info("WARNING: can'nt find source: " + f.getPath());
        return false;
      }
      
      q.sourceDoc = FileUtil.getContents(f, true);
      return true;
    }
  }
  
  public static String tacNerTypesToStanfordNerType(String nerType) {
    switch (nerType) {
    case "PER":
      return "PERSON";
    case "ORG":
    case "GPE":
      return "ORGANIZATION";
    case "LOC":
      return "LOCATION";
    default:
      throw new RuntimeException("unknown type: " + nerType);
    }
  }
  
  /**
   * The point of this class is to take an (EntityMention UUID, Communication UUID),
   * retrieve the Communication from scion/accumulo, and then show the results.
   */
  public static class ViewResponse {
    private FetchCommunicationServiceImpl impl;

    public ViewResponse() throws Exception {
      ConnectorFactory cf = new ConnectorFactory();
      ScionConnector sc = cf.getConnector();
      impl = new FetchCommunicationServiceImpl(sc);
//      int redisPort = 9090;
//      FetchCommunicationServiceWrapper wrapper = new FetchCommunicationServiceWrapper(impl, redisPort);
//      Thread srvThread = new Thread(wrapper);
//      srvThread.start();
//      srvThread.join();
    }
    
    public void showMention(String entityMentionUuid, String communicationUuid) {
      Communication comm = null;
      
      FetchRequest fr = new FetchRequest();
      fr.addToCommunicationIds(communicationUuid);
      try {
        FetchResult r = impl.fetch(fr);
        assert r.getCommunicationsSize() == 1;
        comm = r.getCommunications().get(0);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      
      EntityMention emRef = null;
      for (EntityMentionSet ems : comm.getEntityMentionSetList()) {
        for (EntityMention em : ems.getMentionList()) {
          if (entityMentionUuid.equals(em.getUuid().getUuidString())) {
            assert emRef == null;
            emRef = em;
          }
        }
      }
      
      Map<String, Tokenization> tokz = AddNerTypeToEntityMentions.buildTokzIndex(comm);
      String tid = emRef.getTokens().getTokenizationId().getUuidString();
      Tokenization tk = tokz.get(tid);
      
      System.out.println(emRef.getText());
      for (int t : emRef.getTokens().getTokenIndexList())
        System.out.println(tk.getTokenList().getTokenList().get(t).getText());
      System.out.println();
    }
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    List<Query> queries = Query.parse(new File("data/parma/LDC2014E81/data/tac_2014_kbp_english_EDL_evaluation_queries.xml"));
    
    /*
     * 2016-10-25
     * I am having problems getting the TAC KBP datasets ingested,
     * so I'm going to generate queries by searching through CAG.
     */

    // 1) Read in the query context documents
    AddQueryDocumentContext a = new AddQueryDocumentContext();
    List<Query> keep = new ArrayList<>();
    for (Query q : queries) {
      if (a.addDocumentContext(q))
        keep.add(q);
    }
    queries = keep;

    // 2) Figure out what the nerType is for each query
    AddKbLinks k = new AddKbLinks();
    for (Query q : queries) {
      k.add(q);
    }

    // 3) Search CAG for mentions of each query
    ViewResponse v = new ViewResponse();
    IndexCommunications.Search s = IndexCommunications.Search.build(config);
    try (BufferedWriter w = FileUtil.getWriter(new File("data/parma/tac_kbp2014_querySearchResults_nyt_eng_200909.txt"))) {
      w.write(StringUtils.join("\t", new String[] {
          "query_id", "query_entity_id", "query_entity_type",
          "response_score",
          "response_entity_mention_uuid", "response_communication_uuid", "response_communication_id",
          "query_name",
      }));
      w.newLine();
      for (Query q : queries) {
        String nerType = tacNerTypesToStanfordNerType(q.entity_type);
        List<Result> rr = s.search(q.name, nerType, q.sourceDoc);
        
        int lim = 1000;
        if (rr.size() > lim)
          rr = rr.subList(0, lim);
        
        System.out.println(q + "\t" + q.entity_id + "\t" + q.entity_type);
        assert q.name.indexOf('\t') < 0;
//        for (int i = 0; i < 10 && i < rr.size(); i++) {
//          System.out.println(rr.get(i));
//        }
//        System.out.println();
        for (Result r : rr) {
//          w.write(q.id);
          w.write(StringUtils.join("\t", new String[] {
              q.id, q.entity_id, q.entity_type,
              String.valueOf(r.score),
              r.entityMentionUuid, r.communicationUuid, r.communicationId,
              q.name,
          }));
          w.newLine();
          
          v.showMention(r.entityMentionUuid, r.communicationUuid);
        }
      }
    }

    Log.info(IndexCommunications.TIMER);
  }
}
