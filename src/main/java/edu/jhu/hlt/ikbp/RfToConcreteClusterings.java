package edu.jhu.hlt.ikbp;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.uuid.AnalyticUUIDGeneratorFactory;
import edu.jhu.hlt.concrete.uuid.AnalyticUUIDGeneratorFactory.AnalyticUUIDGenerator;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.tuple.Pair;

/**
 * Reads Communications which have been pre-processed
 * (see data/parma/roth-frank/manual-alignments/Makefile)
 * and the stand-off annotation file to produce concrete {@link Clustering}s.
 * 
 * One {@link Clustering} per pair.
 * 
 * Each {@link ClusterMember} represents a {@link SituationMention}
 * (we do not attempt to figure out if the annotations refer to an entity or situation).
 *
 * @author travis
 */
public class RfToConcreteClusterings implements ConcreteIkbpAnnotations {
  public static boolean DEBUG = false;
  public static boolean DEBUG_FIND = false;
  
  private List<Link> standOffAnnotations;
  private Deque<String> pairs;
  private File commDir;   // contains <communicationId>.comm files
  private AnnotationMetadata meta;
  
  // TODO Insert a ConcreteDocumentStore or something similar if loading documents
  // multiple times across various instances is slow.
  
  public RfToConcreteClusterings(String tool, Predicate<Link> keep, ExperimentProperties config) throws IOException {
    // I'm not finding some un_aligned predicates:
    // e.g. [5 2 go 40 none] in XML/test/pair_26/  AFP_ENG_20090410.0528
//    this(config.getExistingFile("data.rf.standoff", new File("data/parma/roth-frank/manual-alignments/stand_off_annotations.txt")),
    this(tool,
        config.getExistingFile("data.rf.standoff", new File("/home/travis/code/fnparse/data/parma/roth-frank/manual-alignments/stand_off_annotations.no_unaligned.txt")),
        config.getExistingDir("data.rf.communications", new File("/home/travis/code/fnparse/data/parma/roth-frank/manual-alignments/concrete-parsey-and-stanford/")),
        keep);
  }

  public RfToConcreteClusterings(String tool, File standOffAnnoFile, File commDir, Predicate<Link> keep) throws IOException {
    Log.info("tool=" + tool + " standOff=" + standOffAnnoFile.getPath() + " communications=" + commDir.getPath());
    this.standOffAnnotations = Link.readStandOfAnnotations(standOffAnnoFile);
    this.commDir = commDir;
    this.pairs = new ArrayDeque<>();
    this.meta = new AnnotationMetadata()
        .setTool(tool)
        .setTimestamp(System.currentTimeMillis() / 1000);
    Set<String> seen = new HashSet<>();
    for (Link l : standOffAnnotations)
      if (keep.test(l) && seen.add(l.pair))
        pairs.push(l.pair);
  }

  @Override
  public String getName() {
    return meta.getTool();
  }
  
  /**
   * Reads a communication with only text-level annotations, no predicates or arguments or coref.
   */
  public Communication getCommunication(String id) {
    File f = new File(commDir, id + ".comm");
    if (!f.isFile())
      throw new RuntimeException("couldn't find file: " + f.getPath());
    Communication c = new Communication();
    try (BufferedInputStream b = new BufferedInputStream(new FileInputStream(f))) {
      c.read(new TCompactProtocol(new TIOStreamTransport(b)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return c;
  }

  @Override
  public boolean hasNext() {
    return !pairs.isEmpty();
  }

  @Override
//  public Pair<Clustering, List<Communication>> next() {
  public Topic next() {
    String pair = pairs.pop();
    
    // Find all links that refer to this pair
    List<Link> rel = new ArrayList<>();
    for (Link l : standOffAnnotations)
      if (pair.equals(l.pair))
        rel.add(l);
    
    // Get the relevant Communications
    Map<String, Communication> comms = new HashMap<>();
    for (Link l : rel) {
      if (!comms.containsKey(l.doc)) {
        Communication c = getCommunication(l.doc);
        
        // Make a SituationMentionSet to hold the annotations
        AnalyticUUIDGeneratorFactory f = new AnalyticUUIDGeneratorFactory(c);
        AnalyticUUIDGenerator g = f.create();
        SituationMentionSet sms = new SituationMentionSet();
        sms.setUuid(g.next());
        sms.setMetadata(meta);
        c.addToSituationMentionSetList(sms);
        if (DEBUG)
          System.out.println("created sms.id=" + sms.getUuid().getUuidString() + " " + sms.getMetadata() + " in comm.id=" + c.getUuid().getUuidString());
        
        comms.put(l.doc, c);
      }
    }
    
    // Build the Clustering
    // For now we will make every mention a SituationMention
    Clustering c = new Clustering();
    {
      AnalyticUUIDGeneratorFactory f = new AnalyticUUIDGeneratorFactory();
      AnalyticUUIDGenerator g = f.create();
      c.setUuid(g.next());
    }
    Map<String, Cluster2> clusters = new HashMap<>();
    for (Link l : rel) {
//      if (DEBUG)
//        Log.info("tracing down " + l);
      Communication comm = comms.get(l.doc);
      if (DEBUG)
        System.out.println("\nadding mentions to " + comm.getId());

      // TODO Consider whether we should store these in a map
      AnalyticUUIDGeneratorFactory f = new AnalyticUUIDGeneratorFactory(comm);
      AnalyticUUIDGenerator g = f.create();
      
//      SituationMentionSet sms = new SituationMentionSet();
//      sms.setMetadata(meta);
//      sms.setUuid(g.next());
//      System.out.println("created SMS id=" + sms.getUuid().getUuidString());
//      comm.addToSituationMentionSetList(sms);

      // Equivalent to a mention id/index
      int mentionIndex = c.getClusterMemberListSize();
      
      Cluster2 clust = clusters.get(l.cluster);
      if (clust == null) {
        clust = new Cluster2(comm, g);
        clusters.put(l.cluster, clust);
        c.addToClusterList(clust.clust);
      }
      clust.clust.addToClusterMemberIndexList(mentionIndex);
      clust.clust.addToConfidenceList(l.getNumericalConfidence());
      
      SituationMention sm = new SituationMention();
      sm.setUuid(g.next());
      Pair<TokenRefSequence, String> trs2 = findMention2(l, comm);
      sm.setTokens(trs2.get1());
      sm.setText(trs2.get2());
      addToLastSMS(sm, comm);
      
      ClusterMember cm = new ClusterMember();
      cm.setCommunicationId(comm.getUuid());
      cm.setElementId(sm.getUuid());
      cm.setSetId(getLastSMS(comm).getUuid());
      c.addToClusterMemberList(cm);
    }
    if (DEBUG)
      Log.info("created " + c.getClusterMemberListSize() + " ClusterMembers belonging to " + clusters.size() + " Clusters");
    
    String part;
    if (pair.contains("XML/test"))
      part = "test";
    else
      part = "dev";
    
//    return new Pair<>(c, new ArrayList<>(comms.values()));
    return new Topic(c, new ArrayList<>(comms.values()), part, pair);
  }
  
  public static TokenRefSequence findMention(Link mention, Communication c) {
    return findMention2(mention, c).get1();
  }
  public static Pair<TokenRefSequence, String> findMention2(Link mention, Communication c) {
    if (DEBUG && DEBUG_FIND)
      Log.info("looking for: " + mention + " in " + c.getId());
    Section section = c.getSectionList().get(mention.paragraph);
    int occ = 0;
    for (Sentence sent : section.getSentenceList()) {

      List<Token> tokz = sent.getTokenization().getTokenList().getTokenList();
      
      // Get the lemmas
      // LEMMAS AREN'T RIGHT EITHER, e.g. (Link XML/test/pair_59/ AFP_ENG_20030528.0508 0 0 carried 5 possible)
//      TokenTagging lemma = null;
//      for (TokenTagging tt : sent.getTokenization().getTokenTaggingList()) {
////        System.out.println(tt.getTaggingType());
////        System.out.println(tt.getMetadata().getTool());
//        if (tt.getTaggingType().toUpperCase().equals("LEMMA")
//            && tt.getMetadata().getTool().toLowerCase().contains("corenlp")) {
//          assert lemma == null;
//          lemma = tt;
//        }
//      }
//      assert lemma != null;
      
      for (int i = 0; i < tokz.size(); i++) {
        if (DEBUG && DEBUG_FIND)
          Log.info(tokz.get(i).getText());
//        assert lemma.getTaggedTokenList().get(i).getTokenIndex() == i;
//        if (matches(mention.word, lemma.getTaggedTokenList().get(i).getTag(), c.getId())) {
        if (matches(mention.word, tokz.get(i).getText(), c.getId())) {
//        if (mention.word.equals(tokz.get(i).getText())) {
          if (DEBUG && DEBUG_FIND)
            Log.info("^^^^^^^^^^^^^^^^ MATCH occ=" + occ + " ^^^^^^^^^^^^^^^^");
          if (occ == mention.occurrence) {
            TokenRefSequence trs = new TokenRefSequence();
            trs.setAnchorTokenIndex(i);
            trs.addToTokenIndexList(i);
            trs.setTokenizationId(sent.getTokenization().getUuid());
            return new Pair<>(trs, tokz.get(i).getText());
          }
          occ++;
        }
      }
    }
    throw new RuntimeException("couldn't find " + mention + " in " + c.getId());
//    Log.info("couldn't find " + mention + " in " + c.getId());
//    return null;
  }
  
  public static boolean matches(String rfToken, String stanfordToken, String docId) {
    if (rfToken.equals(stanfordToken))
      return true;
    
    // Stanford mis-tokenization, failed to split "U.S.-led"
    if (rfToken.equals("led") && stanfordToken.endsWith("-led") && docId.equals("APW_ENG_20030305.0460"))
      return true;
    
    // Only way I can make this work:
    if (rfToken.equals("came") && stanfordToken.equals("became") && docId.equals("AFP_ENG_20070316.0490"))
      return true;
    
    // Another bogus tokenization, this time on the RF end
    if (rfToken.equals("country's") && stanfordToken.equals("country") && docId.equals("APW_ENG_20070308.0343"))
      return true;
    
    if (rfToken.equals("film") && stanfordToken.equals("films") && docId.equals("APW_ENG_20090423.1278"))
      return true;
    
    if (rfToken.equals("match") && stanfordToken.equals("matches") && docId.equals("APW_ENG_20060105.0206"))
      return true;

    if (rfToken.equals("adopt") && stanfordToken.equals("adopting") && docId.equals("AFP_ENG_20090912.0006"))
      return true;
    
    return false;
  }
  
  public static SituationMentionSet getLastSMS(Communication c) {
    List<SituationMentionSet> smss = c.getSituationMentionSetList();
    SituationMentionSet sms = smss.get(smss.size() - 1);
    return sms;
  }
  
  public static SituationMentionSet addToLastSMS(SituationMention sm, Communication c) {
    List<SituationMentionSet> smss = c.getSituationMentionSetList();
    SituationMentionSet sms = smss.get(smss.size() - 1);
    if (DEBUG)
      System.out.println("adding sm.text=" + sm.getText() + " to sms.id=" + sms.getUuid().getUuidString());
    sms.addToMentionList(sm);
    return sms;
  }
//  public static void addToLastSS(Situation s, Communication c) {
//    List<SituationSet> sss = c.getSituationSetList();
//    SituationSet ss = sss.get(sss.size() - 1);
//    ss.addToSituationList(s);
//  }
  
  private static class Cluster2 {
    Cluster clust;
//    SituationMention sit;
    
    public Cluster2(Communication c, AnalyticUUIDGenerator g) {
      clust = new Cluster();
//      sit = new SituationMention();
//      sit.setUuid(g.next());
//      addToLastSMS(sit, c);
    }
  }
  
  /** One line/row of the stand off annotation file */
  public static class Link {
    public final String pair;
    public final String doc;
    public final int paragraph;  // or section, 0-indexed
    public final int occurrence; // 0-indexed
    public final String word;
    public final String cluster;
    public final String confidence;
    
    public Link(String pair, String doc, String[] rest) {
      if (rest.length != 5)
        throw new IllegalArgumentException("rest=" + Arrays.toString(rest));
      this.pair = pair;
      this.doc = doc;
      paragraph = Integer.parseInt(rest[0]) - 1;
      occurrence = Integer.parseInt(rest[1]) - 1;
      word = rest[2];
      cluster = rest[3];
      confidence = rest[4];
    }
    
    public double getNumericalConfidence() {
      switch (confidence) {
      case "sure":
        return 1;
      case "possible":
        return 0.5;
      default:
        return 0;
      }
    }
    
    @Override
    public String toString() {
      return "(Link " + pair + " " + doc + " " + paragraph + " " + occurrence + " " + word + " " + cluster + " " + confidence + ")";
    }
  
    public static List<Link> readStandOfAnnotations(File f) throws IOException {
      List<Link> l = new ArrayList<>();
      String pair = null;
      String doc = null;
      int docs = 0;
      try (BufferedReader r = FileUtil.getReader(f)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] toks = line.split("\\s+");
          if (toks.length == 1) {
            if (pair == null || docs == 2) {
              pair = toks[0];
              doc = null;
              docs = 0;
            } else {
              doc = toks[0];
              docs++;
            }
          } else {
            l.add(new Link(pair, doc, toks));
          }
        }
      }
      return l;
    }
  }
  
  public static void runThrough(ConcreteIkbpAnnotations labels) {
    while (labels.hasNext()) {
      Topic t = labels.next();
      Clustering c = t.clustering;
      
      // Show the Clustering id
      System.out.println("Clustering: " + c.getUuid().getUuidString() + "\tnMembers=" + c.getClusterMemberListSize());
      
      // Show the Communication ids
      for (Communication comm : t.comms)
        System.out.println("\tCommunication (Cluster): " + comm.getId());
      
      // Show the mentions
      int cid = 0;
      for (Cluster cluster : c.getClusterList()) {
        System.out.println("in Cluster " + (cid++) + ":");
        
        int n = cluster.getClusterMemberIndexListSize();
        assert n == cluster.getConfidenceListSize();
        for (int i = 0; i < n; i++) {
          int mid = cluster.getClusterMemberIndexList().get(i);
          double conf = cluster.getConfidenceList().get(i);;
          ClusterMember item = c.getClusterMemberList().get(mid);
//          System.out.println(item);
          
          Communication comm = ConcreteIkbpAnnotations.lookup(t.comms, item.getCommunicationId());
          SituationMentionSet sms = ConcreteIkbpAnnotations.lookupSms(comm, item.getSetId());
          if (sms != null) {
            SituationMention sm = ConcreteIkbpAnnotations.lookup(sms, item.getElementId());
            System.out.println("\tSIT\t" + sm.getText() + "\t" + conf);
          } else {
            EntityMentionSet ems = ConcreteIkbpAnnotations.lookupEms(comm, item.getSetId());
            EntityMention em = ConcreteIkbpAnnotations.lookup(ems, item.getElementId());
            System.out.println("\tENT\t" + em.getText() + "\t" + conf);
          }
         
//          UUID sitMentionId = item.getElementId();
        }
      }

      System.out.println();
    }
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    Predicate<Link> dev = l -> l.pair.contains("XML/dev");
    RfToConcreteClusterings r2c = new RfToConcreteClusterings("rothfrank", dev, config);
    runThrough(r2c);
  }

}
