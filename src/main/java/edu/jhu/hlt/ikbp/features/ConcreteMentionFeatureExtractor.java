package edu.jhu.hlt.ikbp.features;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Predicate;

import approxlib.distance.EditDist;
import approxlib.tree.LblTree;
import edu.cmu.lti.lexical_db.ILexicalDatabase;
import edu.cmu.lti.lexical_db.NictWordNet;
import edu.cmu.lti.ws4j.impl.JiangConrath;
import edu.cmu.lti.ws4j.impl.Lin;
import edu.cmu.lti.ws4j.impl.Path;
import edu.cmu.lti.ws4j.impl.Resnik;
import edu.cmu.lti.ws4j.impl.WuPalmer;
import edu.cmu.lti.ws4j.util.WS4JConfiguration;
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
import edu.jhu.hlt.tutils.LabeledDirectedGraph;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.Document.Constituent;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.ling.DParseHeadFinder;
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
  
  // Config
  public boolean backoffDepsFeatures = false;
  
  // Misc
  public Counts<String> events = new Counts<>();
  public boolean requireAtLeastOneMention = true;
  
  /**
   * @param mentionToolName is the name of the tool which creates {@link SituationMention}s and {@link EntityMention}s in the given {@link Communication}s
   * @param comms is a set of documents which contain (Situation|Entity)Mentions to index.
   */
  public ConcreteMentionFeatureExtractor(String situationMentionToolName, String entityMentionToolName, Iterable<Communication> comms) {
    lang = Language.EN;
    c2d = new ConcreteToDocument(null, null, null, lang);
    c2d.readConcreteStanford();
    c2d.readParsey();
    c2d.situationMentionToolAuto = situationMentionToolName;
    c2d.corefMentionToolAuto = entityMentionToolName;
//    c2d.debug = true;

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
    events.increment("resets");
    for (Communication c : comms) {
      
      int docIndex = cdocs.size();
//      c2d.debug_situations_to_cons = true;
      ConcreteDocumentMapping m = c2d.communication2Document(c, docIndex, alph, lang);
      
      // Add SituationMentions
      if (c.isSetSituationMentionSetList()) {
        for (SituationMentionSet sms : c.getSituationMentionSetList()) {
          for (SituationMention sm : sms.getMentionList()) {
            // We can't expect ALL SituationMentions to be in the ConcreteDocumentMapping, since we only take a subset...
            // e.g. Comm contains [semafor, EntityEventPathExtraction] SituationMentions
            //      we only point c2d at EntityEventPathExtraction, and this loop is over all situation mentions
            if (!m.containsUuid(sm.getUuid()))
              continue;

            Constituent cons = m.get(sm.getUuid());
            IntPair location = new IntPair(docIndex, cons.getIndex());
            String key = sm.getUuid().getUuidString();
            Object old = mentionLocations.put(key, location);
            assert old == null : "key=" + key + " old=" + old + " new=" + location + " cons=" + cons;
            Log.info("adding sit " + key + " => " + location);
            events.increment("mention/situation");
          }
        }
      }
//      SituationMentionSet sms = findS(c2d.situationMentionToolAuto, c);
//      if (sms == null) {
//        Log.info("WARNING: skipping, no SituationMentionSet");
//      } else {
//        for (SituationMention sm : sms.getMentionList()) {
//          if (DEBUG)
//            System.out.println("adding SituationMention.uuid=" + sm.getUuid().getUuidString());
//          int consId = m.get(sm.getUuid()).getIndex();
//          IntPair location = new IntPair(docIndex, consId);
//          String key = sm.getUuid().getUuidString();
//          Object old = mentionLocations.put(key, location);
//          assert old == null : "key=" + key + " old=" + old + " new=" + location;
//          events.increment("mention/situation");
//        }
//      }
      
      // Add EntityMentions
      if (c.isSetEntityMentionSetList()) {
        for (EntityMentionSet ems : c.getEntityMentionSetList()) {
          for (EntityMention em : ems.getMentionList()) {
            if (!m.containsUuid(em.getUuid()))
              continue;

            Constituent cons = m.get(em.getUuid());
            IntPair location = new IntPair(docIndex, cons.getIndex());
            String key = em.getUuid().getUuidString();
            Object old = mentionLocations.put(key, location);
            assert old == null : "key=" + key + " old=" + old + " new=" + location + " cons=" + cons;
            Log.info("adding ent " + key + " => " + location);
            events.increment("mention/entity");
          }
        }
      }
//      EntityMentionSet ems = findE(c2d.corefMentionToolAuto, c);
//      if (ems == null) {
//        Log.info("WARNING: skipping, no EntityMentionSet matching " + c2d.corefMentionToolAuto);
//      } else {
//        for (EntityMention em : ems.getMentionList()) {
//          int consId = m.get(em.getUuid()).getIndex();
//          IntPair location = new IntPair(docIndex, consId);
//          String key = em.getUuid().getUuidString();
//          Object old = mentionLocations.put(key, location);
//          assert old == null : "key=" + key + " old=" + old + " new=" + location;
//          events.increment("mention/entity");
//        }
//      }
      
      cdocs.add(m);
    }
    Log.info("added " + mentionLocations.size() + " mentions in " + cdocs.size() + " docs, " + events.toStringWithEq());
  }
  
  private void show(Document.Constituent mention, int A, int B) {
    Document d = mention.getDocument();
    MultiAlphabet a = d.getAlphabet();
    LabeledDirectedGraph deps = d.parseyMcParseFace;
    int left = Math.max(0, mention.getFirstToken() - B);
    int right = Math.min(d.numTokens()-1, mention.getLastToken() + A);
    System.out.println("in doc " + d.getId());
    for (int i = left; i <= right; i++) {
      if (i == mention.getFirstToken())
        System.out.println("-----------------------------------------");
      LabeledDirectedGraph.Node n = deps.getNode(i);
      System.out.printf("%d\t%-20s\t%-10s\t%-10s\t%-10s\n",
          i,
          d.getWordStr(i),
          a.pos(d.getPosH(i)),
          n.isRoot() ? "ROOT" : a.dep(n.getParentEdgeLabel(0)),
          n.isRoot() ? "ROOT" : d.getWordStr(n.getParent(0)));
      if (i == mention.getLastToken())
        System.out.println("-----------------------------------------");
    }
  }
  
  private static void showMention(Document.Constituent mention) {
    Document doc = mention.getDocument();
    Log.info(mention + " in " + doc.getId());
    for (int i = mention.getFirstToken(); i <= mention.getLastToken() && i >= 0; i++) {
      System.out.println(i + "\t" + doc.getWordStr(i));
    }
  }

  /**
   * @param concreteMention1 should refer to an (entity|situation) mention by UUID (the Id's name)
   * @param concreteMention2 should refer to an (entity|situation) mention by UUID (the Id's name)
   */
  public List<String> pairwiseFeats(Id concreteMention1, Id concreteMention2) {
    boolean verbose = false;
    
    // Resolve both mentions (they must be in this topic)
    IntPair loc1 = mentionLocations.get(concreteMention1.getName());
    IntPair loc2 = mentionLocations.get(concreteMention2.getName());
    if (loc1 == null)
      throw new RuntimeException("couldn't lookup location of mention " + concreteMention1.getName());
    if (loc2 == null)
      throw new RuntimeException("couldn't lookup location of mention " + concreteMention2.getName());
    
    DParseHeadFinder hf = new DParseHeadFinder();
    hf.useParse(d -> d.parseyMcParseFace);

    ConcreteDocumentMapping cd1 = cdocs.get(loc1.first);
    Document d1 = cd1.getDocument();
    Document.Constituent mention1 = d1.getConstituent(loc1.second);
    int h1 = hf.head(d1, mention1.getFirstToken(), mention1.getLastToken());
    if (h1 < 0) {
      Log.info("warning: no head for " + loc1 + ": " + mention1.getFirstToken() + ", " + mention1.getLastToken());
      showMention(mention1);
      hf.head(d1, mention1.getFirstToken(), mention1.getLastToken());
      return Collections.emptyList();
    }

    ConcreteDocumentMapping cd2 = cdocs.get(loc2.first);
    Document d2 = cd2.getDocument();
    Document.Constituent mention2 = d2.getConstituent(loc2.second);
    int h2 = hf.head(d2, mention2.getFirstToken(), mention2.getLastToken());
    if (h2 < 0) {
      Log.info("warning: no head for " + loc2 + ": " + mention2.getFirstToken() + ", " + mention2.getLastToken());
      showMention(mention1);
      hf.head(d2, mention2.getFirstToken(), mention2.getLastToken());
      return Collections.emptyList();
    }
    
    // Show the mentions
    if (verbose) {
      System.out.println("################################################################################");
      show(mention1, 10, 10);
      System.out.println();
      show(mention2, 10, 10);
      System.out.println();
    }

    List<String> f = new ArrayList<>();

    // tree edit distance
    try {
      EditDist ed = new EditDist(true);
      String ts1 = makeTreeString(mention1);
      String ts2 = makeTreeString(mention2);
      if (ts1 == null || ts2 == null) {
        Log.info("WARNING: makeTreeString failed");
      } else {
        LblTree t1 = LblTree.fromString(ts1);
        LblTree t2 = LblTree.fromString(ts2);
        double dist = ed.treeDist(t1, t2);
        if (verbose) {
          System.out.println("tree1: " + ts1);
          System.out.println("tree2: " + ts2);
          System.out.println("distance=" + dist);
        }
        int distDiscrete = (int) (0.5 + 10 * dist);
        f.add("tedDist=" + distDiscrete);

        if (verbose) {
          String s = ed.printHumaneEditScript();
          System.out.println("edit script:");
          String[] st = s.split(";");
          System.out.println(StringUtils.join("\n", st));
          System.out.println();
        }

        int aligned = 0;
        for (Entry<Integer, Integer> p : ed.getAlignInWordOrder1to2().entrySet()) {
          boolean in1 = mention1.getFirstToken() <= p.getKey() && p.getKey() <= mention1.getLastToken();
          boolean in2 = mention2.getFirstToken() <= p.getValue() && p.getValue() <= mention2.getLastToken();
          if (in1 && in2)
            aligned++;
        }
        int n = Math.min(mention1.getWidth(), mention2.getWidth());
        if (verbose)
          System.out.println("aligned=" + aligned + "/" + n);
        f.add("aligned=" + aligned + "/" + n);
      }
    } catch (NullPointerException npe) {
      Log.info("WARNING: TED feature failed");
    }
    
    if (verbose) {
      System.out.println();
      System.out.println();
    }
    
    // wordnet
    WS4JConfiguration.getInstance().setMFS(true);
    String w1 = d1.getWordStr(h1);
    String w2 = d2.getWordStr(h2);
    f.add("wordnet/wuPalmer=" + d2s(new WuPalmer(db).calcRelatednessOfWords(w1, w2)));
    f.add("wordnet/lin=" + d2s(new Lin(db).calcRelatednessOfWords(w1, w2)));
    f.add("wordnet/resnik=" + d2s(new Resnik(db).calcRelatednessOfWords(w1, w2)));
    f.add("wordnet/path=" + d2s(new Path(db).calcRelatednessOfWords(w1, w2)));
    f.add("wordnet/jiangConrath=" + d2s(new JiangConrath(db).calcRelatednessOfWords(w1, w2)));
    
    // lemma match
    int l1 = d1.getLemma(h1);
    int l2 = d2.getLemma(h2);
    int p1 = d1.getPosH(h1);
    int p2 = d2.getPosH(h2);
    if (l1 == l2) {
      f.add("lemma/match");
      f.add("lemma/match/posToo_" + (p1 == p2));
    } else if (d1.getAlphabet().word(l1).equalsIgnoreCase(d2.getAlphabet().word(l2))) {
      f.add("lemma/nocase");
      f.add("lemma/nocase/posToo_" + (p1 == p2));
    } else {
      f.add("lemma/none");
      f.add("lemma/none/posToo_" + (p1 == p2));
    }
    
    return f;
  }
  private static ILexicalDatabase db = new NictWordNet();
  private static String d2s(double s) {
    if (Double.isNaN(s))
      return "nan";
    if (Double.isInfinite(s))
      return s > 0 ? "+inf" : "-inf";
    int n = (int) (0.5d + 20d * Math.log1p(s));
    if (n > 10)
      return "10+";
    if (n < 0)
      return "<0";
    return String.valueOf(n);
  }
  
  /**
   * Makes a new tree with the given headword as root.
   */
  public static LabeledDirectedGraph rotateTreeAboutHeadword(LabeledDirectedGraph deps, int head) {
    // Go up to the root, build spine
    Set<IntPair> spine = new HashSet<>();
    LabeledDirectedGraph.Node root = deps.getNode(head);
    while (!root.isRoot()) {
      if (root.numParents() != 1)
        throw new RuntimeException("only works for trees!");
      int old = root.getNodeIndex();
      root = root.getParentNode(0);
      spine.add(new IntPair(root.getNodeIndex(), old));
    }
    
    // Traversal from root, adding edges to b,
    // reversing the edges along the spine between root and head.
    LabeledDirectedGraph.Builder b = new LabeledDirectedGraph().new Builder();
    Deque<LabeledDirectedGraph.Node> q = new ArrayDeque<>();
    q.addLast(root);
    while (!q.isEmpty()) {
      LabeledDirectedGraph.Node n = q.pop();
      for (int i = 0; i < n.numChildren(); i++) {
        int c = n.getChild(i);
        int e = n.getChildEdgeLabel(i);
        // Flip edges on the spine
        if (spine.contains(new IntPair(n.getNodeIndex(), c)))
          b.add(c, n.getNodeIndex(), e);
        else
          b.add(n.getNodeIndex(), c, e);
        q.addFirst(deps.getNode(c));
      }
    }
    
    return b.freeze();
  }
  
  private static String makeTreeString(Document.Constituent mention) {
    // Find the root of the relevant sentence
//    int t = mention.getLastToken();
    Document d = mention.getDocument();

//    LabeledDirectedGraph.Node node = d.parseyMcParseFace.getNode(t);
//    while (node != null && !node.isRoot())
//      node = node.getParentNode(0);
    DParseHeadFinder hf = new DParseHeadFinder();
    hf.useParse(doc -> doc.parseyMcParseFace);
    int h = hf.head(d, mention.getFirstToken(), mention.getLastToken());
    if (h < 0)
      return null;
    LabeledDirectedGraph deps = hf.getParse(d);
    LabeledDirectedGraph rot = rotateTreeAboutHeadword(deps, h);
    LabeledDirectedGraph.Node node = rot.getNode(h);  // node indices are the same, can still use h
    
    // Recursively build the tree string
    String ts = makeTreeString(node, d);
    
    return ts;
  }
  
  private static String makeTreeString(LabeledDirectedGraph.Node node, Document doc) {
    // Node format:
    // word/lemma/pos/deprel
    // can also omit lemma
    int t = node.getNodeIndex();
    String label = escape(doc.getWordStr(t))
        + "/" + escape(doc.getAlphabet().word(doc.getLemma(t)))
        + "/" + escape(doc.getAlphabet().pos(doc.getPosH(t)))
        + "/" + (node.isRoot() ? "root" : escape(doc.getAlphabet().dep(node.getParentEdgeLabel(0))));

    String r = "{" + label;
    if (node.isRoot())
      r = node.getNodeIndex() + ":" + r;
    for (int i = 0; i < node.numChildren(); i++)
      r += makeTreeString(node.getChildNode(i), doc);
    r += "}";
    return r;
  }
  
  public static String escape(String word) {
    return word.toLowerCase()
        .replaceAll(":", "#colon#")
        .replaceAll("/", "#slash#")
        .replaceAll("\\{", "#left_curly_brace#")
        .replaceAll("\\}", "#right_curly_brace#");
  }

  public void extractSafe(Node n, List<Id> addTo) {
    try {
      extract(n, addTo);
    } catch (Exception e) {
    }
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
      if (loc == null)
        throw new RuntimeException("couldn't lookup location of mention " + mid.getName());
      ConcreteDocumentMapping cd = cdocs.get(loc.first);
      Document d = cd.getDocument();
      Document.Constituent mention = d.getConstituent(loc.second);

//      // MENTION TYPE (given by training data, e.g. EVENT or ENTITY)
//      if (mention.getLhs() >= 0)
//        addTo.add(f("lhs=" + mention.getLhs()));
      
      assert d.parseyMcParseFace != null;
      DParseHeadFinder hf = new DParseHeadFinder();
      hf.useParse(x -> x.parseyMcParseFace);
      int h = hf.head(d, mention.getFirstToken(), mention.getLastToken());

      // HEADWORD
      addTo.add(f(alph.word(d.getLemma(h)), FeatureType.HEADWORD));
      addTo.add(f("head/pos/" + alph.pos(d.getPosH(h))));
      addTo.add(f("head/ner/" + alph.ner(d.getNerH(h))));
      
      // LENGTH-1 DEPENDENCY PATHS FROM HEADWORD
      LabeledDirectedGraph deps = hf.getParse(d);
      LabeledDirectedGraph.Node hn = deps.getNode(h);
      for (int i = 0; i < hn.numParents(); i++) {
        String e = alph.dep(hn.getParentEdgeLabel(i));
        String w = alph.word(d.getLemma(hn.getParent(i)));
//        String p = alph.pos(d.getPosH(hn.getParent(i)));
//        String t = alph.ner(d.getNerH(hn.getParent(i)));
        addTo.add(f("parent/" + e + "/" + w));
//        addTo.add(f("parent/" + e + "/" + p));
//        addTo.add(f("parent/" + e + "/" + t));
        if (backoffDepsFeatures) {
          addTo.add(f("parent/" + e));
          addTo.add(f("parent/" + w));
//          addTo.add(f("parent/" + p));
//          addTo.add(f("parent/" + t));
        }
      }
      for (int i = 0; i < hn.numChildren(); i++) {
        String e = alph.dep(hn.getChildEdgeLabel(i));
        String w = alph.word(d.getLemma(hn.getChild(i)));
//        String p = alph.pos(d.getPosH(hn.getChild(i)));
//        String t = alph.ner(d.getNerH(hn.getChild(i)));
        addTo.add(f("child/" + e + "/" + w));
//        addTo.add(f("child/" + e + "/" + p));
//        addTo.add(f("child/" + e + "/" + t));
        if (backoffDepsFeatures) {
          addTo.add(f("child/" + e));
          addTo.add(f("child/" + w));
//          addTo.add(f("child/" + p));
//          addTo.add(f("child/" + t));
        }
      }
      
      // FRAME PREDICTIONS
      // TODO
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
    if (c.isSetSituationMentionSetList()) {
      for (SituationMentionSet sms : c.getSituationMentionSetList()) {
        if (sms.getMetadata().getTool().equals(tool)) {
          assert m == null;
          m = sms;
        }
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
    if (c.isSetEntityMentionSetList()) {
      for (EntityMentionSet ems : c.getEntityMentionSetList()) {
        if (ems.getMetadata().getTool().equals(tool)) {
          assert m == null;
          m = ems;
        }
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

    String situationMentionTool = labels.getName();
    String entityMentionTool = labels.getName();
    ConcreteMentionFeatureExtractor fe = new ConcreteMentionFeatureExtractor(
        situationMentionTool, entityMentionTool, t0.comms);
    
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
