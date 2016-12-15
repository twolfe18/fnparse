package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import edu.jhu.hlt.acute.archivers.tar.TarArchiver;
import edu.jhu.hlt.concrete.Cluster;
import edu.jhu.hlt.concrete.ClusterMember;
import edu.jhu.hlt.concrete.Clustering;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.access.FetchRequest;
import edu.jhu.hlt.concrete.access.FetchResult;
import edu.jhu.hlt.concrete.serialization.archiver.ArchivableCommunication;
import edu.jhu.hlt.ikbp.ConcreteIkbpAnnotations.Topic;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Result;
import edu.jhu.hlt.scion.concrete.server.FetchCommunicationServiceImpl;
import edu.jhu.hlt.scion.core.accumulo.ConnectorFactory;
import edu.jhu.hlt.scion.core.accumulo.ScionConnector;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.prim.tuple.Pair;

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
public class TacKbp {
  
  public static class KbpQuery {
    
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
    
    // Space-separated document representation, comes from source docs
    String sourceDoc;
    
    // Not set by methods in this module
    Communication sourceComm;
    // Points into sourceComm to the entity described by this query.
    EntityMention entityMention;
    

    // computed from sourceComm, typically top 20 tf-idf words, circa AccumuloIndex, StringTermDoc
    List<String> docCtxImportantTerms;
    
    
    public KbpQuery(String id) {
      this.id = id;
      beg = end = -1;
    }
    
    @Override
    public String toString() {
      return "(Query " + id + " in " + docid + " \"" + name + "\" [" + beg + ", " + end + "])";
    }
    
    public static List<KbpQuery> parse(File f) throws Exception {
      List<KbpQuery> l = new ArrayList<>();

      DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
      Document root = dBuilder.parse(f);
      NodeList nl = root.getElementsByTagName("query");
      for (int i = 0; i < nl.getLength(); i++) {
        Node n = nl.item(i);
        String id = n.getAttributes().getNamedItem("id").getTextContent();
//        System.out.println(id);
        KbpQuery q = new KbpQuery(id);
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
  
  // TODO See CommunicationRetrieval
  static Communication getCommunication(String id) {
    throw new RuntimeException("implement me");
  }
  
  /**
   * Build one topic per entity, each of which contains one cluster
   * which all of the relevant mentions in it.
   */
  public static List<Topic> buildClusters(List<KbpQuery> resolvedQueries) {
    Map<String, List<KbpQuery>> byTopic = new HashMap<>();
    // TODO groupby
    
    List<Topic> topics = new ArrayList<>();
    for (Entry<String, List<KbpQuery>> x : byTopic.entrySet()) {
      String entityId = x.getKey();
      List<KbpQuery> mentions = x.getValue();
      List<Communication> comms = new ArrayList<>();
      Clustering clustering = new Clustering();
      
      // Add one cluster containing all mentions of this entity
      Cluster entClust = new Cluster();
      for (KbpQuery q : mentions) {
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
    
    public void add(KbpQuery q) {
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
    
    public boolean addDocumentContext(KbpQuery q) {
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
  public static class MentionFetcher {
    private FetchCommunicationServiceImpl impl;

    public MentionFetcher() throws Exception {
      ConnectorFactory cf = new ConnectorFactory();
      ScionConnector sc = cf.getConnector();
      impl = new FetchCommunicationServiceImpl(sc);
    }
    
    public Pair<Communication, EntityMention> fetch(String entityMentionUuid, String communicationId, boolean showMention) {
      Communication comm = null;
      
      FetchRequest fr = new FetchRequest();
      fr.addToCommunicationIds(communicationId);
      try {
        FetchResult r = impl.fetch(fr);
        assert r.getCommunicationsSize() == 1;
        comm = r.getCommunications().get(0);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      EntityMention emRef = null;
      for (EntityMentionSet ems : comm.getEntityMentionSetList()) {
        if (ems.isSetMentionList()) {
          for (EntityMention em : ems.getMentionList()) {
            if (entityMentionUuid.equals(em.getUuid().getUuidString())) {
              assert emRef == null;
              emRef = em;
            }
          }
        }
      }
      
      if (showMention) {
        Map<String, Tokenization> tokz = AddNerTypeToEntityMentions.buildTokzIndex(comm);
        String tid = emRef.getTokens().getTokenizationId().getUuidString();
        Tokenization tk = tokz.get(tid);
        List<Token> tokens = tk.getTokenList().getTokenList();

        List<Integer> tks = emRef.getTokens().getTokenIndexList();
        int first = tks.get(0);
        int last = tks.get(tks.size() - 1);

        // Mentions +/- 10 tokens
        StringBuilder sb = new StringBuilder();
        int i0 = Math.max(0, first - 10);
        for (int i = i0; i < Math.min(last+1+10, tokens.size()); i++) {
          if (i > i0)
            sb.append(' ');
          if (i == first)
            sb.append("[[ ");
          sb.append(tokens.get(i).getText());
          if (i == last)
            sb.append(" ]]");
        }
        System.out.println(sb.toString());
      }
      
      return new Pair<>(comm, emRef);
    }
  }
  

  public static List<KbpQuery> getKbp2013SfQueries() throws Exception {
    //File qxml = new File("/home/travis/code/fnparse/data/tackbp/TAC_2014_KBP_Slot_Filler_Validation_Evaluation_Queries_V1.1/"
    //    + "data/LDC2014R38_TAC_2014_KBP_English_Regular_Slot_Filling_Evaluation_Queries/"
    //    + "data/tac_2014_kbp_english_regular_slot_filling_evaluation_queries.xml");
    File qxml = new File("data/tackbp/TAC_2014_KBP_Slot_Filler_Validation_Evaluation_Queries_V1.1/"
        + "data/LDC2014R38_TAC_2014_KBP_English_Regular_Slot_Filling_Evaluation_Queries/"
        + "data/tac_2014_kbp_english_regular_slot_filling_evaluation_queries.xml");
    
    List<KbpQuery> queries = new ArrayList<>();
    
    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document root = dBuilder.parse(qxml);
    NodeList nl = root.getElementsByTagName("query");
    for (int i = 0; i < nl.getLength(); i++) {
      Node n = nl.item(i);
      String id = n.getAttributes().getNamedItem("id").getTextContent();
//      System.out.println(id);
      KbpQuery q = new KbpQuery(id);
      NodeList c = n.getChildNodes();
      for (int j = 0; j < c.getLength(); j++) {
        Node cc = c.item(j);
//        System.out.println(cc.getNodeName() + "\t" + cc.getTextContent());
        switch (cc.getNodeName()) {
        case "name":
          q.name = cc.getTextContent();
          break;
        case "enttype":
          q.entity_type = cc.getTextContent();
          break;
        case "docid":
          q.docid = cc.getTextContent();
          break;
        case "beg":
          q.beg = Integer.parseInt(cc.getTextContent());
          break;
        case "end":
          q.end = Integer.parseInt(cc.getTextContent());
          break;
        default:
          break;
        }
      }
      
      System.out.println(q);
      queries.add(q);
    }

    return queries;
  }
  
  public static List<KbpQuery> getKbp2014EdlQueries() throws Exception {
    List<KbpQuery> queries = KbpQuery.parse(new File("data/parma/LDC2014E81/data/tac_2014_kbp_english_EDL_evaluation_queries.xml"));

    // 1) Read in the query context documents
    AddQueryDocumentContext a = new AddQueryDocumentContext();
    List<KbpQuery> keep = new ArrayList<>();
    for (KbpQuery q : queries) {
      if (a.addDocumentContext(q))
        keep.add(q);
    }
    queries = keep;

    // 2) Figure out what the nerType is for each query
    AddKbLinks k = new AddKbLinks();
    for (KbpQuery q : queries) {
      k.add(q);
    }
    return queries;
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);

    // This will only work on the grid.
    System.setProperty("scion.accumulo.zookeepers", "r8n04.cm.cluster:2181,r8n05.cm.cluster:2181,r8n06.cm.cluster:2181");
    System.setProperty("scion.accumulo.instanceName", "minigrid");
    System.setProperty("scion.accumulo.user", "reader");
    System.setProperty("scion.accumulo.password", "an accumulo reader");

    // 1) Parse queries
    List<KbpQuery> queries = getKbp2014EdlQueries();

    // 2) Search CAG for mentions of each query
    // Write out text results as well as communications they live in
    IndexCommunications.EntitySearch s = IndexCommunications.EntitySearch.build(config);  // Long load time!
    MentionFetcher v = new MentionFetcher();  // Make sure you use this right away, will timeout after 30s
    Set<String> commsWritten = new HashSet<>();
    File searchResults = config.getFile("searchResults", new File("data/parma/tac_kbp2014_querySearchResults_nyt_eng_200909.txt"));
    File searchResultComms = config.getFile("searchResultComms", new File("data/parma/tac_kbp2014_querySearchResults_nyt_eng_200909.comms.tgz"));
    try (BufferedWriter w = FileUtil.getWriter(searchResults);
        OutputStream os = Files.newOutputStream(searchResultComms.toPath());
        BufferedOutputStream bos = new BufferedOutputStream(os, 1024 * 8 * 24);
        TarArchiver archiver = new TarArchiver(new GzipCompressorOutputStream(bos))) {
      w.write(StringUtils.join("\t", new String[] {
          "query_id", "query_entity_id", "query_entity_type",
          "response_score",
          "response_entity_mention_uuid", "response_communication_uuid", "response_communication_id",
          "query_name",
      }));
      w.newLine();
      for (KbpQuery q : queries) {
        String nerType = tacNerTypesToStanfordNerType(q.entity_type);
        String[] heads = q.name.split("\\s+");
        List<Result> rr = s.search(q.name, nerType, heads, q.sourceDoc);
        
        int lim = 1000;
        if (rr.size() > lim)
          rr = rr.subList(0, lim);
        
        System.out.println(q + "\t" + q.entity_id + "\t" + q.entity_type);
        assert q.name.indexOf('\t') < 0;
        for (Result r : rr) {
          try {
          w.write(StringUtils.join("\t", new String[] {
              q.id, q.entity_id, q.entity_type,
              String.valueOf(r.score),
//              r.entityMentionUuid, r.communicationUuid, r.communicationId,
              r.tokenizationUuid, r.communicationUuid, r.communicationId,
              q.name,
          }));
          w.newLine();
          } catch (Exception e) {
            e.printStackTrace();
          }

          boolean showMention = true;
          Pair<Communication, EntityMention> p =
//              v.fetch(r.entityMentionUuid, r.communicationId, showMention);
              v.fetch(r.tokenizationUuid, r.communicationId, showMention);
          if (commsWritten.add(r.communicationId)) {
            Log.info("finding+saving " + r.communicationId);
            try {
              archiver.addEntry(new ArchivableCommunication(p.get1()));
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        }
      }
    }

    Log.info(IndexCommunications.TIMER);
  }
}
