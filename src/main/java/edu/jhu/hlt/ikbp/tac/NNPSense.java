package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.thrift.TDeserializer;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumulo;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloConfig;
import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.hlt.utilt.AutoCloseableIterator;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.ConcreteUtil;
import edu.jhu.util.MultiMap;
import edu.jhu.util.TokenizationIter;

/**
 * The point of this class was to capture cases of having a rare last name which
 * makes the match score decent, but is obviously wrong due to a mistaken first name.
 * e.g. query is for q="Eloise Spooner" and r="Nathan Spooner".
 * I believe this also happens with first names (and different last names).
 * 
 * The point was to look at the p(x | parent(x)="Spooner", tag(x)="NNP")
 * a) across the entire corpus    -- this tells you if there are a lot of diff types of "Spooners" or not
 * b) for the query document      -- measure dist/divergence between b~c
 * c) for the response document
 * 
 * Oh, I think I want some sort of conditional entropy or something.
 * e.g. Suppose there was only one type of "Spooner" in the background dist, call this X
 * and now I tell you which type of "Spooner" it is the query
 * ...how much more information does the repsonse give you... NO THATS NOT RIGHT.
 * 
 * ==> OH, maybe:
 *   p(reponseCondDist | seeing query, background dist) / p(responseCondDist | seeing background dist)
 * So things which are already pretty common won't receive a high score.
 * 
 * The stuff I was screwing around with in main is for choosing the RHS's to condition on.
 * I think if I just start with parent and tag, that will hopefully get first/last names.
 * Worry about other stuff later.
 *
 * @author travis
 */
public class NNPSense {
  
  // Shows what is being extracted/pruned and why
  public static boolean EXTRACT_ATTR_FEAT_VERBOSE = false;
  
  
  String text;
  String nerType;

  Counts<String> features;    // for now these will be x | parent(x).word==anchor.word && ner(x)!='O'
  
  /**
   * @param text is an entity word, e.g. "Spooner"
   * @param nerType can be null, in which case ner type constraint is not enforced
   */
  public NNPSense(String text, String nerType) {
    this.text = text;
    this.nerType = nerType;
    this.features = new Counts<>();
  }
  
  // Scan f2t, take values until you have at least 1000 comms, go retrieve them and compute stats
  public void scanAccumulo() throws Exception {
    Log.info("scanning for " + text);

    TimeMarker tm = new TimeMarker();
    Instance inst = new ZooKeeperInstance("minigrid", "r8n04.cm.cluster:2181,r8n05.cm.cluster:2181,r8n06.cm.cluster:2181");
    Connector conn = inst.getConnector("reader", new PasswordToken("an accumulo reader"));
    
    Set<String> commPrefixes = new HashSet<>();
    List<Range> tokUuids = new ArrayList<>();
    
    // Retrieve Tokenization which contain the anchor
    try (Scanner s = conn.createScanner(AccumuloIndex.T_f2t.toString(), new Authorizations())) {
      s.setRange(Range.exact("h:" + text));
      for (Entry<Key, Value> e : s) {
        String tokUuid = e.getKey().getColumnQualifier().toString();
        String commUuidP = AccumuloIndex.getCommUuidPrefixFromTokUuid(tokUuid);
        commPrefixes.add(commUuidP);
        tokUuids.add(Range.exact(tokUuid));
        if (commPrefixes.size() == 10000)
          break;
        if (tm.enoughTimePassed(2))
          Log.info("found " + tokUuids.size() + " toks and " + commPrefixes + " comm uuid prefixes");
      }
    }
    if (tokUuids.isEmpty()) {
      Log.info("returning early, no toks");
      return;
    }
    
    // Retrieve the IDs of the Communications which contain those Tokenizations
    Log.info("retrieving comm ids for " + tokUuids.size() + " tokenizations");
    Set<String> commUuids = new HashSet<>();
    int numQueryThreads = 12;
    try (BatchScanner bs = conn.createBatchScanner(AccumuloIndex.T_t2c.toString(), new Authorizations(), numQueryThreads)) {
      bs.setRanges(tokUuids);
      bs.setBatchTimeout(1, TimeUnit.MINUTES);
      for (Entry<Key, Value> e : bs) {
        String commUuid = e.getValue().toString();
        commUuids.add(commUuid);
        if (tm.enoughTimePassed(2))
          Log.info("found " + commUuids.size() + " comm uuids");
      }
    }
    if (commUuids.isEmpty()) {
      Log.info("returning early, no comms");
      return;
    }
    
    // Retrieve those Communications
    // and on the fly figure out what the values are there
    Log.info("retrieving " + commUuids.size() + " communications by id");
    TDeserializer deser = new TDeserializer(SimpleAccumulo.COMM_SERIALIZATION_PROTOCOL);
    int commsProcessed = 0;
    for (String commUuid : commUuids) {
      try (Scanner s = conn.createScanner(SimpleAccumuloConfig.DEFAULT_TABLE, new Authorizations())) {
        // Find comm
        s.setRange(Range.exact(commUuid));
        Iterator<Entry<Key, Value>> iter = s.iterator();
        assert iter.hasNext();
        Entry<Key, Value> e = iter.next();
        assert !iter.hasNext();
        Communication c = new Communication();
        deser.deserialize(c, e.getValue().get());
        
        // Find anchor and feature values
        scanComm(c);

        commsProcessed++;
        if (tm.enoughTimePassed(2))
          Log.info("commsProcessed=" + commsProcessed);
      }
    }
    Log.info("done");
  }
  
  private void scanComm(Communication c) {
    for (Tokenization t : new TokenizationIter(c)) {
      List<Token> toks = t.getTokenList().getTokenList();
      for (int i = 0; i < toks.size(); i++) {
        assert i == toks.get(i).getTokenIndex();
        if (toks.get(i).getText().equalsIgnoreCase(text))
          scanTok(i, t, c);
      }
    }
  }
  
  private void scanTok(int i, Tokenization t, Communication c) {
    DependencyParse deps = IndexCommunications.getPreferredDependencyParse(t);
    TokenTagging ner = IndexCommunications.getPreferredNerTags(t);
    
    // Only allow a match if the matched token also matches on NER type
    if (nerType != null) {
      String net = ner.getTaggedTokenList().get(i).getTag();
      if (!net.equalsIgnoreCase(nerType))
        return;
    }
    
    //Log.info("matched " + text + " in " + c.getId());
    for (Dependency d : deps.getDependencyList()) {
      if (d.isSetGov() && d.getGov() == i) {
        int j = d.getDep();
        String tag = ner.getTaggedTokenList().get(j).getTag();
        if (d.getEdgeType().equalsIgnoreCase("nn") || !tag.equalsIgnoreCase("O"))
          features.increment(d.getEdgeType() + "/" + t.getTokenList().getTokenList().get(j).getText());
      }
    }
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
//    File f = new File("data/concretely-annotated-gigaword/sample-with-semafor-nov08/nyt_eng_200909.withParsey.tar.gz");
//    Counts<String> ec = new Counts<>();
//    TimeMarker tm = new TimeMarker();
//    try (AutoCloseableIterator<Communication> iter = new IndexCommunications.FileBasedCommIter(Arrays.asList(f))) {
//      while (iter.hasNext()) {
//        Communication c = iter.next();
//        ec.increment("comm");
//      }
//    }
    
//    countCommonPathsLeavingEntities(config);
    
    Caching.test();
  }
  
  public static void countFirstNames(ExperimentProperties config) throws Exception {
    for (String x : Arrays.asList("Spooner", "Goude", "ALICO", "Mamane", "Esther-Ethy", "Denaro", "Bamba", "Youssoufou")) {
      NNPSense s = new NNPSense(x, null);
      s.scanAccumulo();

      int k = 100;
      List<String> keys = s.features.getKeysSortedByCount(true);
      if (keys.size() > k)
        keys = keys.subList(0, k);
      System.out.println("totalCount=" + s.features.getTotalCount());
      for (String c : keys) {
        System.out.printf("%20s %20s %d %.4f\n", x, c, s.features.getCount(c), s.features.getProportion(c));
      }
      System.out.println();
    }
    Log.info("done");
  }
  
  // TODO Put this in tutils
//  public static void oneHop(List<Pair<Integer, LL<Dependency>>> from, BitSet visited, DependencyParse deps) {
  public static void oneHop(List<Walk> from, BitSet visited, DependencyParse deps) {
    int n = from.size();
    for (int i = 0; i < n; i++) {
//      int f = from.get(i).get1();
      int f = from.get(i).dest;
      visited.set(f);
      for (Dependency d : deps.getDependencyList()) {
        int to = -1;
        if (d.isSetGov() && d.getGov() == f)
          to = d.getDep();
        else if (d.getDep() == f)
          to = d.getGov();
        if (to >= 0 && !visited.get(to)) {
          visited.set(to);  // You only need this line if not a tree
//          LL<Dependency> path = new LL<>(d, from.get(i).get2());
          LL<Dependency> path = new LL<>(d, from.get(i).edges);
//          from.add(new Pair<>(to, path));
          int source = from.get(0).source;
          from.add(new Walk(source, to, path));
        }
      }
    }
  }

  // TODO Put this in tutils
  /**
   * returned values are (pathEndpointTokenIndex, path)
   */
//  public static List<Pair<Integer, LL<Dependency>>> kHop(int from, int k, DependencyParse deps) {
  public static List<Walk> kHop(int from, int k, DependencyParse deps) {
//    List<Pair<Integer, LL<Dependency>>> paths = new ArrayList<>();
    List<Walk> paths = new ArrayList<>();
//    paths.add(new Pair<>(from, null));
    paths.add(new Walk(from, from, null));
    BitSet visited = new BitSet();
    for (int i = 0; i < k; i++)
      oneHop(paths, visited, deps);
    return paths;
  }

  static class Walk {
    public final int source, dest;
    public final LL<Dependency> edges;
//    private List<Dependency> edgeList;
    
    public Walk(int source, int dest, LL<Dependency> edges) {
      this.source = source;
      this.dest = dest;
      this.edges = edges;
    }
  }
  

  
  private static ArrayDeque<String> reverseDeps(LL<Dependency> deps) {
    ArrayDeque<String> d = new ArrayDeque<>();
    for (LL<Dependency> cur = deps; cur != null; cur = cur.next)
      d.push(cur.item.getEdgeType());
    return d;
  }
  
  /**
   * Accepts strings like "Barack Obama" and "University of Southern California"
   * and returns "Obama" and "University" respectively.
   */
  public static String extractNameHead(String entityFullName) {
    // TODO write a better implementation!
    String[] terms = entityFullName.split("\\s+");
    return terms[terms.length -1];
  }
  
  private static String join(List<Token> toks) {
    StringBuilder sb = new StringBuilder();
    for (Token t : toks) {
      if (sb.length() > 0)
        sb.append(' ');
      sb.append(t.getText());
    }
    return sb.toString();
  }
  /**
   * Returns strings like "PERSON-nn-Dr." where PERSON matches the given nameHead
   * 
   * @param tokUuid is the {@link Tokenization} UUID to restrict to. If null it looks through the entire comm.
   */
  public static List<String> extractAttributeFeatures(String tokUuid, Communication c, String... nameHeads) {
    if (EXTRACT_ATTR_FEAT_VERBOSE)
      Log.info("comm=" + c.getId() + " nameHeads=" + Arrays.toString(nameHeads) + " tok=" + tokUuid);
    
    // Might want to expand this set
    // Currently it will miss "Italian" because it is an JJ
    // Perhaps I could actually take all high idf terms? This would miss titles like "Dr."
    Set<String> interestingPos = new HashSet<>();
    interestingPos.add("NNP");
    interestingPos.add("NNPS");
    interestingPos.add("CD");
    interestingPos.add("JJ");
    interestingPos.add("JJS");    // TODO might want to restrict these to one-hop paths

    List<String> attr = new ArrayList<>();
    for (Tokenization toks : new TokenizationIter(c)) {
      if (tokUuid != null && !tokUuid.equals(toks.getUuid().getUuidString()))
        continue;
      DependencyParse deps = IndexCommunications.getPreferredDependencyParse(toks);
      List<TaggedToken> pos = IndexCommunications.getPreferredPosTags(toks).getTaggedTokenList();
      List<TaggedToken> ner = IndexCommunications.getPreferredNerTags(toks).getTaggedTokenList();
      List<Token> t = toks.getTokenList().getTokenList();
      if (EXTRACT_ATTR_FEAT_VERBOSE)
        Log.info("scanning: " + join(t));
      for (Token tok : t) {
        Set<String> uniq = new HashSet<>(); // uniq per tokenization, otherwise multiple nameHeads means duplicates
        for (String nameHead : nameHeads) {
          if (nameHead.equalsIgnoreCase(tok.getText())) {
            int source = tok.getTokenIndex();
            String sourcePos = pos.get(source).getTag();
            if (!sourcePos.toUpperCase().startsWith("NNP"))
              continue;
            List<Walk> paths = kHop(source, k, deps);
            for (Walk p : paths) {
              String destPos = pos.get(p.dest).getTag();
              
              // TODO Check that path only *ends* in an interesting POS, rather that going over them.
              // e.g. we want a path that leads to "New York" to end with the head, "York", and not include "New"
              // This matters for backoff (where the path is lost) and avoiding double-counting.
              int length = 0;
              BitSet interestingOnPath = new BitSet();
              for (LL<Dependency> cur = p.edges; cur != null; cur = cur.next) {
                length++;
                Dependency d = cur.item;
                if (d.isSetGov() && interestingPos.contains(pos.get(d.getGov()).getTag()))
                  interestingOnPath.set(d.getGov());
                if (interestingPos.contains(pos.get(d.getDep()).getTag()))
                  interestingOnPath.set(d.getDep());
              }

              // Build the path
              String sourceNer = ner.get(source).getTag();
              String destWord = t.get(p.dest).getText();
              ArrayDeque<String> path = reverseDeps(p.edges);
              path.addFirst(sourceNer);
              path.addLast(destWord);
              // e.g. ORGANIZATION-appos-nn-Boston
              String x = StringUtils.join("-", path);
              // e.g. ORGANIZATION-backoff-Boston
              String xBackoff = sourceNer + "-backoff-" + destWord;

              // See if you want to keep it
              if (length == 0) {
                if (EXTRACT_ATTR_FEAT_VERBOSE)
                  Log.info("skipping b/c length=0: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
                continue;
              }
              if (interestingOnPath.cardinality() > 2) {
                if (EXTRACT_ATTR_FEAT_VERBOSE)
                  Log.info("skipping b/c mult interesting on path: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
                continue;
              }
              if (destPos.startsWith("JJ") && length > 1) {
                if (EXTRACT_ATTR_FEAT_VERBOSE)
                  Log.info("skipping b/c long JJ*: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
                continue;
              }
              if (interestingPos.contains(destPos)) {
                if (EXTRACT_ATTR_FEAT_VERBOSE)
                  Log.info("keeping: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
                if (uniq.add(x))
                  attr.add(x);
                if (uniq.add(xBackoff))
                  attr.add(xBackoff);
              } else {
                if (EXTRACT_ATTR_FEAT_VERBOSE)
                  Log.info("skipping b/c not interesting: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
              }

            }
          }
        }
      }
    }
    return attr;
  }

  public static double endpointPosScore(String pos) {
    if (pos.startsWith("NNP"))
      return 1;
    if (pos.equals("CD"))
      return 1;
    return 0.5;
  }
  
  public static double attrFeatPathScore(List<String> deps, boolean backoff) {
    if (!backoff)
      return 1;
    double k = 2;
    return (k + 1) / (k + deps.size());
  }
  
  /**
   * Caches extractAttributeFeaturesNewAndImproved in an efficient way.
   * Instantiate one of these per Communication.
   */
  public static class Caching {
    private Communication comm;
    private List<Tokenization> toks;
    private List<DependencyParse> deps;
    private List<TokenTagging> pos;
    private List<TokenTagging> ner;
    private MultiMap<String, IntPair> word2loc;
    private Map<IntPair, List<Walk>> khopCache;
    
    public Caching(Communication comm) {
      this.comm = comm;
      this.khopCache = new HashMap<>();
      // Be lazy about initializing fields
    }
    
    List<Walk> kHop(int tokenization, int token) {
      IntPair ij = new IntPair(tokenization, token);
      List<Walk> w = khopCache.get(ij);
      if (w == null) {
        w = NNPSense.kHop(token, k, deps.get(tokenization));
        khopCache.put(ij, w);
      }
      return w;
    }

    public List<Feat> extractAttributeFeaturesNewAndImproved(
        String tokUuid, Communication c, String nerType, List<String> nameHeads) {

      if (EXTRACT_ATTR_FEAT_VERBOSE)
        Log.info("starting, tokUuid=" + tokUuid + " nerType=" + nerType + " nameHeads=" + nameHeads);
      
      if (toks == null) {
        if (EXTRACT_ATTR_FEAT_VERBOSE)
          Log.info("building toks/deps/pos/ner");
        toks = new ArrayList<>();
        deps = new ArrayList<>();
        pos = new ArrayList<>();
        ner = new ArrayList<>();
        for (Tokenization t : new TokenizationIter(comm)) {
          toks.add(t);
          deps.add(IndexCommunications.getPreferredDependencyParse(t));
          pos.add(IndexCommunications.getPreferredPosTags(t));
          ner.add(IndexCommunications.getPreferredNerTags(t));
        }
      }
      
      if (word2loc == null) {
        if (EXTRACT_ATTR_FEAT_VERBOSE)
          Log.info("building word2loc");
        word2loc = new MultiMap<>();
        for (int i = 0; i < toks.size(); i++) {
          List<Token> t = toks.get(i).getTokenList().getTokenList();
          for (int j = 0; j < t.size(); j++) {
            String w = t.get(j).getText().toLowerCase();
            word2loc.add(w, new IntPair(i, j));
          }
        }
      }
      
      List<Feat> attr = new ArrayList<>();
      Set<String> uniq = new HashSet<>();
      
      // Loop over each (word, position) in the document matching any word in nameHeads
      Set<IntPair> seen = new HashSet<>();
      for (String nameHead : nameHeads) {
        for (IntPair ij : word2loc.get(nameHead.toLowerCase())) {
          boolean s = seen.add(ij);
          if (EXTRACT_ATTR_FEAT_VERBOSE)
            Log.info("sent=" + ij.first + " tok=" + ij.second + " word=" + nameHead);
          if (!s)
            continue;

          int source = ij.second;
          boolean sameTok = tokUuid != null && tokUuid.equals(toks.get(ij.first).getUuid().getUuidString());
          List<Token> t = toks.get(ij.first).getTokenList().getTokenList();
          List<TaggedToken> pos = this.pos.get(ij.first).getTaggedTokenList();
          
          // nameHead/source must be an NNP*
          String sourcePos = pos.get(source).getTag();
          if (!sourcePos.toUpperCase().startsWith("NNP"))
            continue;

          // Only walk from heads which match on NER type
          String sourceNer = ner.get(ij.first).getTaggedTokenList().get(source).getTag();
          if (!sourceNer.equalsIgnoreCase(nerType))
            continue;

          // Perform a khop walk away from this location, collecting "interesting" walks */
          List<Walk> paths = kHop(ij.first, source);
          for (Walk p : paths) {
            String destPos = pos.get(p.dest).getTag();
            
            // TODO Check that path only *ends* in an interesting POS, rather that going over them.
            // e.g. we want a path that leads to "New York" to end with the head, "York", and not include "New"
            // This matters for backoff (where the path is lost) and avoiding double-counting.
            int length = 0;
            BitSet interestingOnPath = new BitSet();
            for (LL<Dependency> cur = p.edges; cur != null; cur = cur.next) {
              length++;
              Dependency d = cur.item;
              if (d.isSetGov() && interestingPos.contains(pos.get(d.getGov()).getTag()))
                interestingOnPath.set(d.getGov());
              if (interestingPos.contains(pos.get(d.getDep()).getTag()))
                interestingOnPath.set(d.getDep());
            }
            
            // Bail out if not needed
            if (length == 0) {
              String x = "???";
              if (EXTRACT_ATTR_FEAT_VERBOSE)
                Log.info("skipping b/c length=0: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
              continue;
            }
            if (interestingOnPath.cardinality() > 2) {
              String x = "???";
              if (EXTRACT_ATTR_FEAT_VERBOSE)
                Log.info("skipping b/c mult interesting on path: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
              continue;
            }
            if (destPos.startsWith("JJ") && length > 1) {
              String x = "???";
              if (EXTRACT_ATTR_FEAT_VERBOSE)
                Log.info("skipping b/c long JJ*: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
              continue;
            }

            // Build the path
            String destWord = t.get(p.dest).getText();
            ArrayDeque<String> path = reverseDeps(p.edges);

            List<String> dp = new ArrayList<>();
            dp.addAll(path);

            path.addFirst(sourceNer);
            path.addLast(destWord);
            // e.g. ORGANIZATION-appos-nn-Boston
            String x = StringUtils.join("-", path);
            // e.g. ORGANIZATION-backoff-Boston
            String xBackoff = sourceNer + "-backoff-" + destWord;
            
            Feat xf = new Feat(x, endpointPosScore(destPos) * attrFeatPathScore(dp, false));
            Feat xbf = new Feat(xBackoff, 0.2 * endpointPosScore(destPos) * attrFeatPathScore(dp, true));
            
            if (sameTok) {
              xf.rescale("tokSpecific", 2);
              xbf.rescale("tokSpecific", 2);
            }

            // See if you want to keep it
            if (interestingPos.contains(destPos)) {
              if (EXTRACT_ATTR_FEAT_VERBOSE)
                Log.info("keeping: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
              if (uniq.add(x))
                attr.add(xf);
              if (uniq.add(xBackoff))
                attr.add(xbf);
            } else {
              if (EXTRACT_ATTR_FEAT_VERBOSE)
                Log.info("skipping b/c not interesting: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
            }
          }
        }
      }
      
      return attr;
    }
    
    /**
     * Check that static and caching methods return the same values.
     */
    public static void test() throws Exception {
//      EXTRACT_ATTR_FEAT_VERBOSE = true;
      MultiTimer mt = new MultiTimer();
      File p = new File("../data/fetch-comms-cache");
      List<String> comms = Arrays.asList(
          "Gender_bias_on_Wikipedia.comm.gz", "AFP_ENG_20060322.0643.comm.gz", "Kaisheim.comm.gz", "AFP_ENG_19970214.0628.comm.gz", "AFP_ENG_20090419.0333.comm.gz",
          "History_of_Derry_City_F.C..comm.gz", "Wilmington,_Delaware.comm.gz", "AFP_ENG_19970212.0764.comm.gz", "AFP_ENG_20041006.0637.comm.gz",
          "Chuck_Jones.comm.gz", "Malawi_Independence_Medal.comm.gz", "NYT_ENG_20050919.0269.comm.gz", "AFP_ENG_20031013.0306.comm.gz",
          "NYT_ENG_19960927.0601.comm.gz", "Ayyubid_dynasty.comm.gz", "NYT_ENG_20021028.0277.comm.gz", "2007_American_League_Division_Series.comm.gz",
          "Arnold_Orville_Beckman.comm.gz", "Pierre_Fatou.comm.gz", "XIN_ENG_19970812.0020.comm.gz", "Douglas_Gibson.comm.gz");
      for (String cf : comms) {
        File f = new File(p, cf);
        Log.info("reading from " + f.getPath());
        Communication c = ConcreteUtil.readOneComm(f);
        Caching ac = new Caching(c);

        // Get a sentence with some entities in it
        for (Tokenization t : new TokenizationIter(c)) {
          List<Integer> args = DependencySyntaxEvents.extractEntityHeads(t);
          if (args.isEmpty())
            continue;
          if (EXTRACT_ATTR_FEAT_VERBOSE)
            Log.info("tok: " + t.getUuid().getUuidString());

          // Extract attribute features for these mentions
          int w = 60;
          for (int a : args) {
            PkbpEntity.Mention m = PkbpEntity.Mention.build(a, t, c, ac);
            mt.start("optimized");
            List<Feat> f1 = m.getAttrFeatures();
            mt.stop("optimized");
            mt.start("regular");
            List<Feat> f2 = NNPSense.extractAttributeFeaturesNewAndImproved(m.tokUuid, c, m.getHeadNer(), m.getNNPWordsInSpan());
            mt.stop("regular");

            List<String> fs1 = Feat.demote(f1, false);
            List<String> fs2 = Feat.demote(f2, false);
            Collections.sort(fs1);
            Collections.sort(fs2);

            if (!fs1.equals(fs2)) {
              System.out.println(m.getContextAroundHead(w, w, true));
              System.out.println(fs1);
              System.out.println(fs2);
              System.out.println(mt);
              throw new RuntimeException();
            }
          }
        }
      }
      System.out.println(mt);
      System.out.println(mt.get("regular").totalTimeInSeconds()
          / mt.get("optimized").totalTimeInSeconds());
      Log.info("done");
    }
  }
  
  public static final int k = 4;
  static Set<String> interestingPos;
  static {
    // Might want to expand this set
    // Currently it will miss "Italian" because it is an JJ
    // Perhaps I could actually take all high idf terms? This would miss titles like "Dr."
    interestingPos = new HashSet<>();
    interestingPos.add("NNP");
    interestingPos.add("NNPS");
    interestingPos.add("CD");
    interestingPos.add("JJ");
    interestingPos.add("JJS");    // TODO might want to restrict these to one-hop paths
  }

  /**
   * Returns strings like "PERSON-nn-Dr." where PERSON matches the given nameHead
   */
  public static List<Feat> extractAttributeFeaturesNewAndImproved(String tokUuid, Communication c, String nerType, List<String> nameHeads) {
    if (EXTRACT_ATTR_FEAT_VERBOSE)
      Log.info("starting, tokUuid=" + tokUuid + " nerType=" + nerType + " nameHeads=" + nameHeads);
    
    /*
     * TODO I need to be more strict than word match. I'm getting cases like "London"
     * which is "London Fashion Week":ORGANIZATION as well as "London":LOCATION
     */

    List<Feat> attr = new ArrayList<>();
    Set<String> uniq = new HashSet<>();
    
    for (Tokenization toks : new TokenizationIter(c)) {
      boolean sameTok = tokUuid != null && !tokUuid.equals(toks.getUuid().getUuidString());
      DependencyParse deps = IndexCommunications.getPreferredDependencyParse(toks);
      List<TaggedToken> pos = IndexCommunications.getPreferredPosTags(toks).getTaggedTokenList();
      List<TaggedToken> ner = IndexCommunications.getPreferredNerTags(toks).getTaggedTokenList();
      List<Token> t = toks.getTokenList().getTokenList();
      if (EXTRACT_ATTR_FEAT_VERBOSE)
        Log.info("scanning: " + join(t));
      for (Token tok : t) {
//        Set<String> uniq = new HashSet<>(); // uniq per tokenization, otherwise multiple nameHeads means duplicates
        for (String nameHead : nameHeads) {
          if (nameHead.equalsIgnoreCase(tok.getText())) {
            int source = tok.getTokenIndex();
            String sourcePos = pos.get(source).getTag();
            if (!sourcePos.toUpperCase().startsWith("NNP"))
              continue;

            String sourceNer = ner.get(source).getTag();
            if (!sourceNer.equalsIgnoreCase(nerType))
              continue;

            List<Walk> paths = kHop(source, k, deps);
            for (Walk p : paths) {
              String destPos = pos.get(p.dest).getTag();
              
              // TODO Check that path only *ends* in an interesting POS, rather that going over them.
              // e.g. we want a path that leads to "New York" to end with the head, "York", and not include "New"
              // This matters for backoff (where the path is lost) and avoiding double-counting.
              int length = 0;
              BitSet interestingOnPath = new BitSet();
              for (LL<Dependency> cur = p.edges; cur != null; cur = cur.next) {
                length++;
                Dependency d = cur.item;
                if (d.isSetGov() && interestingPos.contains(pos.get(d.getGov()).getTag()))
                  interestingOnPath.set(d.getGov());
                if (interestingPos.contains(pos.get(d.getDep()).getTag()))
                  interestingOnPath.set(d.getDep());
              }
              
              // Bail out if not needed
              if (length == 0) {
                String x = "???";
                if (EXTRACT_ATTR_FEAT_VERBOSE)
                  Log.info("skipping b/c length=0: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
                continue;
              }
              if (interestingOnPath.cardinality() > 2) {
                String x = "???";
                if (EXTRACT_ATTR_FEAT_VERBOSE)
                  Log.info("skipping b/c mult interesting on path: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
                continue;
              }
              if (destPos.startsWith("JJ") && length > 1) {
                String x = "???";
                if (EXTRACT_ATTR_FEAT_VERBOSE)
                  Log.info("skipping b/c long JJ*: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
                continue;
              }

              // Build the path
              String destWord = t.get(p.dest).getText();
              ArrayDeque<String> path = reverseDeps(p.edges);

              List<String> dp = new ArrayList<>();
              dp.addAll(path);

              path.addFirst(sourceNer);
              path.addLast(destWord);
              // e.g. ORGANIZATION-appos-nn-Boston
              String x = StringUtils.join("-", path);
              // e.g. ORGANIZATION-backoff-Boston
              String xBackoff = sourceNer + "-backoff-" + destWord;
              
              Feat xf = new Feat(x, endpointPosScore(destPos) * attrFeatPathScore(dp, false));
              Feat xbf = new Feat(xBackoff, 0.2 * endpointPosScore(destPos) * attrFeatPathScore(dp, true));
              
              if (sameTok) {
                xf.rescale("tokSpecific", 2);
                xbf.rescale("tokSpecific", 2);
              }

              // See if you want to keep it
              if (interestingPos.contains(destPos)) {
                if (EXTRACT_ATTR_FEAT_VERBOSE)
                  Log.info("keeping: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
                if (uniq.add(x))
                  attr.add(xf);
                if (uniq.add(xBackoff))
                  attr.add(xbf);
              } else {
                if (EXTRACT_ATTR_FEAT_VERBOSE)
                  Log.info("skipping b/c not interesting: " + x + " endPos=" + pos.get(p.dest).getTag() + " head=" + nameHead);
              }

            }
          }
        }
      }
    }
    return attr;
  }


  /**
   * Perhaps I can take the 1000 most common walks out of an entity head?
   */
  public static void countCommonPathsLeavingEntities(ExperimentProperties config) throws Exception {
    Counts<String> ec = new Counts<>();

    Counts<String> common = new Counts<>();
    ReservoirSample<String> sample = new ReservoirSample<>(100, new Random(9001));
    
    Set<String> properNouns = new HashSet<>();
    properNouns.add("NNP");
    properNouns.add("NNPS");

    TimeMarker tm = new TimeMarker();
    try (AutoCloseableIterator<Communication> iter = IndexCommunications.getCommunicationsForIngest(config)) {
      while (iter.hasNext()) {
        Communication c = iter.next();
        ec.increment("comm");
        new AddNerTypeToEntityMentions(c);
        Map<String, Tokenization> tmap = AddNerTypeToEntityMentions.buildTokzIndex(c);
        for (EntityMention em : IndexCommunications.getEntityMentions(c)) {
          
          if (!em.getEntityType().equalsIgnoreCase("PERSON"))
            continue;

          Tokenization tokz = tmap.get(em.getTokens().getTokenizationId().getUuidString());
          DependencyParse deps = IndexCommunications.getPreferredDependencyParse(tokz);
          List<TaggedToken> pos = IndexCommunications.getPreferredPosTags(tokz).getTaggedTokenList();
          int h = em.getTokens().getAnchorTokenIndex();
          String p0 = pos.get(h).getTag();
          String w0 = em.getEntityType(); //tokz.getTokenList().getTokenList().get(h).getText();
          
          // New cleaner way
          List<Walk> paths = kHop(h, 3, deps);
          for (Walk p : paths) {
            String destPos = pos.get(p.dest).getTag();
            if (properNouns.contains(destPos) && p.edges != null) {
              String destWord = tokz.getTokenList().getTokenList().get(p.dest).getText();
              ArrayDeque<String> path = reverseDeps(p.edges);
              path.addFirst(w0);
              path.addLast(destWord);
              String x = StringUtils.join("-", path);
              common.increment(x);
              sample.add(x);
            }
          }
          
          
//          // Parents of w0
//          for (Dependency d1 : deps.getDependencyList()) {
//            if (d1.getDep() != h)
//              continue;
//
//            String p1, w1;
//            String e01 = d1.getEdgeType();
//            if (d1.isSetGov() && d1.getGov() >= 0) {
//              p1 = pos.get(d1.getGov()).getTag();
//              w1 = tokz.getTokenList().getTokenList().get(d1.getGov()).getText();
//              
//              // Parents of w1
//              for (Dependency d2 : deps.getDependencyList()) {
//                if (d2.getDep() != d1.getGov())
//                  continue;
//                String p2, w2;
//                String e12 = d2.getEdgeType();
//                if (d2.isSetGov() && d2.getGov() >= 0) {
//                  p2 = pos.get(d2.getGov()).getTag();
//                  w2 = tokz.getTokenList().getTokenList().get(d2.getGov()).getText();
//                } else {
//                  p2 = "ROOT";
//                  w2 = "ROOT";
//                }
////                commonWalksFromEnts.increment(p0 + "-" + e01 + "-" + p1 + "-" + e12 + "-" + p2);
//                if (properNouns.contains(p2))
//                  commonWalksFromEnts.increment(w0 + "-" + e01 + "-" + w1 + "-" + e12 + "-" + w2);
//              }
//
//            } else {
//              p1 = "ROOT";
//              w1 = "ROOT";
//            }
////            commonWalksFromEnts.increment(p0 + "-" + e01 + "-" + p1);
//            if (properNouns.contains(p1))
//              commonWalksFromEnts.increment(w0 + "-" + e01 + "-" + w1);
//          }
        }
        if (ec.getCount("comm") > 10000)
          break;
        if (tm.enoughTimePassed(2))
          Log.info("event counts: " + ec);
      }
    }
    
    System.out.println("most common:");
    int i = 0;
    for (String f : common.getKeysSortedByCount(true)) {
      System.out.println(common.getCount(f) + "\t" + f);
      if (++i == 100)
        break;
    }
    System.out.println();
    System.out.println("random sample:");
    for (String f : sample)
      System.out.println(common.getCount(f) + "\t" + f);

    Log.info("done");
  }
}
