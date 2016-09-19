package edu.jhu.hlt.ikbp.features;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import edu.jhu.hlt.concrete.ClusterMember;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.ikbp.ConcreteIkbpAnnotations;
import edu.jhu.hlt.ikbp.ConcreteIkbpAnnotations.Topic;
import edu.jhu.hlt.ikbp.DataUtil;
import edu.jhu.hlt.ikbp.RfToConcreteClusterings;
import edu.jhu.hlt.ikbp.RfToConcreteClusterings.Link;
import edu.jhu.hlt.ikbp.data.FeatureType;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.tutils.ConcreteDocumentMapping;
import edu.jhu.hlt.tutils.ConcreteToDocument;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.ling.Language;

/**
 * Indexes some {@link Communication}s which contain mentions which need to be featurized.
 *
 * @author travis
 */
public class ConcreteMentionFeatureExtractor implements MentionFeatureExtractor {
  public static boolean DEBUG = false;

  // How to convert to tutils.Document
  private Language lang;
  private ConcreteToDocument c2d;
  private MultiAlphabet alph;
  
  // Documents and mentions being indexed
  private List<ConcreteDocumentMapping> cdocs;
  private Map<String, IntPair> mentionLocations;  // keys are SituationMention UUIDs, values are (docIndex, consIndex)
  
  // Misc
  private Counts<String> events = new Counts<>();
  public boolean requireAtLeastOneMention = true;
  
  /**
   * @param mentionToolName is the name of the tool which creates {@link SituationMention}s and {@link EntityMention}s in the given {@link Communication}s
   * @param comms is a set of documents which contain (Situation|Entity)Mentions to index.
   */
  public ConcreteMentionFeatureExtractor(String mentionToolName, Iterable<Communication> comms) {
    Log.info("mentionToolName=" + mentionToolName);
    lang = Language.EN;
    c2d = new ConcreteToDocument(null, null, null, lang);
    c2d.readConcreteStanford();
    c2d.situationMentionToolAuto = mentionToolName;
//    c2d.corefMentionToolAuto = mentionToolName;   // TODO enable this later!
    alph = new MultiAlphabet();
    
    cdocs = new ArrayList<>();
    mentionLocations = new HashMap<>();
    set(comms);
  }
  
  public void setTopic(Topic t) {
    set(t.comms);
  }
  
  public void set(Iterable<Communication> comms) {
    cdocs.clear();
    mentionLocations.clear();
    for (Communication c : comms) {
      
      int docIndex = cdocs.size();
      ConcreteDocumentMapping m = c2d.communication2Document(c, docIndex, alph, lang);
      
      // Add SituationMentions
      SituationMentionSet sms = findS(c2d.situationMentionToolAuto, c);
//      Log.info("sms=" + sms + " smss=" + c.getSituationMentionSetList());
      for (SituationMention sm : sms.getMentionList()) {
        if (DEBUG)
          System.out.println("adding SituationMention.uuid=" + sm.getUuid().getUuidString());
        int consId = m.get(sm.getUuid()).getIndex();
        IntPair location = new IntPair(docIndex, consId);
        String key = sm.getUuid().getUuidString();
        Object old = mentionLocations.put(key, location);
        assert old == null : "key=" + key + " old=" + old + " new=" + location;
        events.increment("mention/situation");
      }
      
      // Add EntityMentions
      EntityMentionSet ems = findE(c2d.corefMentionToolAuto, c);
      if (ems == null) {
        Log.info("WARNING: skipping, no EntityMentionSet matching " + c2d.corefMentionToolAuto);
      } else {
        for (EntityMention em : ems.getMentionList()) {
          int consId = m.get(em.getUuid()).getIndex();
          IntPair location = new IntPair(docIndex, consId);
          String key = em.getUuid().getUuidString();
          Object old = mentionLocations.put(key, location);
          assert old == null : "key=" + key + " old=" + old + " new=" + location;
          events.increment("mention/entity");
        }
      }
      
      cdocs.add(m);
    }
    Log.info("added " + mentionLocations.size() + " mentions in " + cdocs.size() + " docs, " + events.toStringWithEq());
  }

  @Override
  public void extract(Node n, List<Id> addTo) {
    
    List<Id> concreteMentions = DataUtil.filterByFeatureType(n.getFeatures(), FeatureType.CONCRETE_UUID);
    if (DEBUG)
      Log.info("found " + concreteMentions.size() + " mentions for node.id=" + n.getId());
    
    if (requireAtLeastOneMention && concreteMentions.isEmpty())
      throw new IllegalArgumentException("mentions required for " + n);

    for (Id mid : concreteMentions) {
      IntPair loc = mentionLocations.get(mid.getName());
      ConcreteDocumentMapping cd = cdocs.get(loc.first);
      Document d = cd.getDocument();
      Document.Constituent mention = d.getConstituent(loc.second);

      addTo.add(f("firstToken=" + d.getWordStr(mention.getFirstToken())));
      addTo.add(f("lastToken=" + d.getWordStr(mention.getLastToken())));
      addTo.add(f("lhs=" + mention.getLhs()));
      addTo.add(f("hw", FeatureType.HEADWORD));
    }
  }
  
  private static Id f(String feature) {
    return f(feature, FeatureType.REGULAR);
  }
  private static Id f(String feature, FeatureType t) {
    Id i = new Id();
    i.setName(feature);
    i.setType(t.ordinal());
    i.setId((int) Hash.sha256(feature));
    if (DEBUG)
      System.out.println("\t" + feature);
    return i;
  }

  private SituationMentionSet findS(String tool, Communication c) {
    SituationMentionSet m = null;
    for (SituationMentionSet sms : c.getSituationMentionSetList()) {
      if (sms.getMetadata().getTool().equals(tool)) {
        assert m == null;
        m = sms;
      }
    }
    if (m == null)
      Log.info("WARNING: didn't find " + tool);
    return m;
  }

  private EntityMentionSet findE(String tool, Communication c) {
    if (tool == null)
      return null;
    EntityMentionSet m = null;
    for (EntityMentionSet ems : c.getEntityMentionSetList()) {
      if (ems.getMetadata().getTool().equals(tool)) {
        assert m == null;
        m = ems;
      }
    }
    if (m == null)
      Log.info("WARNING: didn't find " + tool);
    return m;
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    Predicate<Link> dev = l -> l.pair.contains("XML/dev");
    ConcreteIkbpAnnotations labels = new RfToConcreteClusterings("rothfrank", dev, config);
    
    Topic t0 = labels.next();

    ConcreteMentionFeatureExtractor fe = new ConcreteMentionFeatureExtractor(labels.getName(), t0.comms);
    
    for (ClusterMember cm : t0.clustering.getClusterMemberList()) {
      UUID sitMentionId = cm.getElementId();
      Node n = new Node();
      n.setId(new Id().setName("this is a test"));
      n.addToFeatures(new Id()
          .setType(FeatureType.CONCRETE_UUID.ordinal())
          .setName(sitMentionId.getUuidString()));
      
      List<Id> feats = new ArrayList<>();
      fe.extract(n, feats);
      System.out.println(feats);
    }
  }
}
