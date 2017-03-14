package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.entsum.CluewebLinkedPreprocess.ParsedSentenceMap;
import edu.jhu.hlt.entsum.CluewebLinkedSentence.SegmentedTextAroundLink;
import edu.jhu.hlt.entsum.CluewebLinkedSentence.ValidatorIterator;
import edu.jhu.hlt.entsum.DbpediaToken.Type;
import edu.jhu.hlt.entsum.DepNode.ShortestPath;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.ComputeIdf;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.ArgMin;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.OrderStatistics;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.util.Alphabet;
import edu.jhu.util.MultiMap;

/**
 * This module performs the distant supervision join/assumption,
 * combining facts from dbpedia with entity-linked sentences from clueweb.
 *
 * TODO
  train/dev/test splits use freebase MIDs
  go through each split file and:
  1) map MID => wikidata using /home/travis/code/data/freebase-to-wikipedia
  2) download triples of wikidata ids, e.g. from http://wikidata.dbpedia.org/page/Q41421
  3) scan sentences containing a given entity/MID for sentences containing pairs in a triple from above
 *
  Q: Why is this different than the shortest-freebase-path that Xuchen setup?
  A: Xuchen starts with text and finds shortest KB path; I SHOULD start with KB and look for "shortest" text path
  The reason being that many of these textual co-occurrences are junk
  Need to be filtered via a "related entity" step or something of the like
  I think it would in general be difficult to put lexical/syntactic qualifications on what makes a "non-junk sentence"
 *
 * @author travis
 */
public class DbpediaDistSup {
  public static final Charset UTF8 = Charset.forName("UTF-8");
  
  static class EfficientFact implements Serializable {
    private static final long serialVersionUID = 4610679485818142253L;
    int subj;
    int verb;
    // Mutually exclusive, ExactlyOne
    long objIntValue;
    int objEntityValue;
    byte[] objUtf8Value;
  }
  
  /*
   * Now have better freebase~dbpedia mapping with
   * /home/travis/code/fnparse/data/dbpedia/freebase_links_en.ttl.gz
   * 
   * HOWEVER, it is not functional when mapping into dbpedia, so I could have multiple links per mention
   * 
   * I could generate all (dbpedia:subj, dbpedia:obj, textualLocation) triples possible given freebase entlinks * mid2dbpedia
   * and intersect it with all (dbpedia:subj, dbpedia:obj, infoboxRelation) triples
   */
  public static class Join implements Serializable {
    private static final long serialVersionUID = -2148858047119667553L;

    // Dictated by context like rare4
    private Alphabet<String> relevantOrHopMids;
    private BitSet relevantMid;
    private BitSet relevantDbp;
    
    // Used to map CluewebLinkedSentence -> mid -> dbpedia
    // BUG(fixed)! we want mid->dbp mappings for either a relevant entity or a mid which co-occurrs with a relevant mid.
    private IntObjectHashMap<IntArrayList> mid2dbp; // only contains relevantMids as keys
    private Alphabet<String> dbpediaIds;
    
//    // Keys are dbpedia entity ids, values are sentences which contain both entities in the key
//    private Map<IntPair, List<Mention>> clueweb;
    
    // Keys are dbpedia ids and values are facts which mention the entity associated with their key
//    private IntObjectHashMap<List<DbpediaTtl>> infobox;
    private IntObjectHashMap<List<EfficientFact>> infobox;
    private Alphabet<String> infoboxVerbs;
    
    public Counts<String> ec = new Counts<>();
    
    /**
     * @param freebaseLinks e.g. /home/travis/code/fnparse/data/dbpedia/freebase_links_en.ttl.gz
     * @param infoboxFacts e.g. /home/travis/code/fnparse/data/dbpedia/infobox_properties_en.ttl.gz
     */
    public Join(File relevantMids, File relevantSentences, File freebaseLinks, File infoboxFacts) throws Exception {
      TimeMarker tm = new TimeMarker();
      this.infoboxVerbs = new Alphabet<>();
      this.relevantOrHopMids = new Alphabet<>();
      this.relevantMid = new BitSet();
      this.relevantDbp = new BitSet();
      for (String mid : FileUtil.getLines(relevantMids)) {
        int midi = relevantOrHopMids.lookupIndex(mid);
        this.relevantMid.set(midi);
      }
      Log.info("there are " + relevantOrHopMids.size() + " centrally relevant mids from " + relevantMids.getPath());
      try (ValidatorIterator iter = new ValidatorIterator(relevantSentences)) {
        while (iter.hasNext()) {
          CluewebLinkedSentence s = iter.next();
          int nl = s.numLinks();
          for (int i = 0; i < nl; i++) {
            String mid = s.getLink(i).getMid(s.getMarkup());
            ec.increment("entity/mid");
            this.relevantOrHopMids.lookupIndex(mid);
          }
        }
      }
      Log.info("including mentions which co-occur with centrally relevant mids, there are "
          + this.relevantOrHopMids.size() + " known mids");

      Log.info("reading mid->dbpedia mapping from " + freebaseLinks.getPath());
      this.dbpediaIds = new Alphabet<>();
      this.mid2dbp = new IntObjectHashMap<>();
      try (DbpediaTtl.LineIterator lines = new DbpediaTtl.LineIterator(freebaseLinks)) {
        while (lines.hasNext()) {
          DbpediaTtl x = lines.next();
          String mid = DbpediaTtl.extractMidFromTtl(x.object().getValue());
          int midi = this.relevantOrHopMids.lookupIndex(mid, false);
          if (midi >= 0) {
            String dbp = x.subject().getValue();
            int dbpi = dbpediaIds.lookupIndex(dbp);
//            int dbpi = lookupDbpInt(dbp, true);
            if (this.relevantMid.get(midi))
              this.relevantDbp.set(dbpi);
            IntArrayList ia = this.mid2dbp.get(midi);
            if (ia == null) {
              ia = new IntArrayList();
              this.mid2dbp.put(midi, ia);
            }
            // If its already there, skip
            boolean found = false;
            for (int i = 0; i < ia.size() && !found; i++)
              found |= dbpi == ia.get(i);
            if (!found)
              ia.add(dbpi);
            ec.increment("entity/dbpedia");
          }
          ec.increment("freebaseMapping/line");
          if (tm.enoughTimePassed(2)) {
            Log.info("nMID=" + this.relevantOrHopMids.size()
                + " nDBpedia=" + this.dbpediaIds.size()
                + " mappingLines/percentSkip=" + lines.getProportionSkipped()*100d
                + "\t" + Describe.memoryUsage()
                + "\t" + ec);
          }
        }
      }
      Log.info("done, nMID=" + this.relevantOrHopMids.size() + " nDBpedia=" + this.dbpediaIds.size());

      // TODO Only keep facts for centrally relevant entities
      Log.info("reading in infobox facts in " + infoboxFacts.getPath());
      this.infobox = new IntObjectHashMap<>();
      try (DbpediaTtl.LineIterator lines = new DbpediaTtl.LineIterator(infoboxFacts)) {
        while (lines.hasNext()) {
          DbpediaTtl x = lines.next();
          ec.increment("infobox/fact");
          if (x.object().type != Type.DBPEDIA_ENTITY)
            continue;
//          int obj = lookupDbpInt(x.object().getValue(), false);
          int obj = dbpediaIds.lookupIndex(x.object().getValue(), false);

          assert x.subject().type == Type.DBPEDIA_ENTITY;
//          int subj = lookupDbpInt(x.subject().getValue(), false);
          int subj = dbpediaIds.lookupIndex(x.subject().getValue(), false);

          // Keep this fact since it references a relevant entity
          if (subj >= 0 && this.relevantDbp.get(subj))
            addInfoboxFact(subj, x);
          if (obj >= 0 && this.relevantDbp.get(obj) && obj != subj)
            addInfoboxFact(obj, x);
          
          if (tm.enoughTimePassed(5)) {
            Log.info("infobox/percentSkip=" + lines.getProportionSkipped()*100d
                + "\t" + Describe.memoryUsage() + "\t" + ec);
          }
        }
      }
      
      Log.info("done setup, " + ec);
    }
    
    public <T extends Collection<String>> T findMidsWithNoDbpediaId(T addTo) {
      int n = relevantOrHopMids.size();
      for (int i = 0; i < n; i++) {
        IntArrayList x = mid2dbp.get(i);
        if (x == null || x.size() == 0)
          addTo.add(relevantOrHopMids.lookupObject(i));
      }
      return addTo;
    }
    
    public OrderStatistics<Integer> numFactsPerMid() {
      OrderStatistics<Integer> c = new OrderStatistics<>();
      int n = relevantOrHopMids.size();
      for (int i = 0; i < n; i++) {
        IntArrayList x = mid2dbp.get(i);
        if (x == null || x.size() == 0)
          continue;
        int cc = 0;
        int m = x.size();
        for (int j = 0; j < m; j++) {
          int dbpi = x.get(j);
          List<EfficientFact> ff = infobox.get(dbpi);
          if (ff != null)
            cc += ff.size();
        }
        c.add(cc);
      }
      return c;
    }
    
    public EfficientFact compress(DbpediaTtl f) {
      EfficientFact ef = new EfficientFact();
      ef.subj = dbpediaIds.lookupIndex(f.subject().getValue());
      ef.verb = infoboxVerbs.lookupIndex(f.verb().getValue());
      
      switch (f.object().type) {
      case DBPEDIA_ENTITY:
        ef.objEntityValue = dbpediaIds.lookupIndex(f.object().getValue());
        break;
      case INTEGER:
        ef.objIntValue = Long.parseLong(f.object().getValue());
        break;
      case STRING_ENGLISH:
        ef.objUtf8Value = f.object().getValue().getBytes(UTF8);
        break;
      }
      
//      if (DbpediaTtl.isKbNode(f.object())) {
//        ec.increment("obj/ent");
//        ef.objEntityValue = dbpediaIds.lookupIndex(f.object().getValue());
//      } else {
//        int i = f.object().indexOf("^^<http://www.w3.org/2001/XMLSchema#integer>");
//        if (i > 0) {
//          ec.increment("obj/int");
//          String s = f.object().substring(0, i);
//          ef.objIntValue = Long.parseLong(s.replaceAll("\"", ""));
//        } else {
//          ec.increment("obj/str");
//          ef.objUtf8Value = f.object().getBytes(UTF8);
//        }
//      }

      return ef;
    }
    
    private List<DbpediaTtl> expand(List<EfficientFact> fs) {
      List<DbpediaTtl> out = new ArrayList<>();
      for (EfficientFact f : fs)
        out.add(expand1(f));
      return out;
    }

    private DbpediaTtl expand1(EfficientFact f) {
      String subj = dbpediaIds.lookupObject(f.subj);
      String verb = infoboxVerbs.lookupObject(f.verb);
      DbpediaToken st = new DbpediaToken(Type.DBPEDIA_ENTITY, subj);
      DbpediaToken vt = new DbpediaToken(Type.DBPEDIA_ENTITY, verb);
      DbpediaToken ot;
      if (f.objUtf8Value != null) {
        ot = new DbpediaToken(Type.STRING_ENGLISH, new String(f.objUtf8Value, UTF8));
      } else if (f.objEntityValue >= 0) {
        ot = new DbpediaToken(Type.DBPEDIA_ENTITY, dbpediaIds.lookupObject(f.objEntityValue));
      } else {
        ot = new DbpediaToken(Type.INTEGER, String.valueOf(f.objIntValue));
      }
      return new DbpediaTtl(st, vt, ot);
    }
    
    private static String stripAngleBrackets(String f) {
      int n = f.length();
      if (f.charAt(0) != '<' || f.charAt(n-1) != '>')
        throw new IllegalArgumentException();
      return f.substring(0, n-1);
    }
    
//    private static final String pre = "http://dbpedia.org/resource/";
//    private int lookupDbpInt(String dbpediaId, boolean addIfNotPresent) {
//      assert dbpediaId.charAt(0) != '<';
//      assert dbpediaId.charAt(dbpediaId.length()-1) != '>';
//      boolean sw = dbpediaId.startsWith(pre);
//      if (addIfNotPresent)
//        assert sw;
//      if (!sw && !addIfNotPresent)
//        return -1;
//      String post = dbpediaId.substring(pre.length());
//      return dbpediaIds.lookupIndex(post, addIfNotPresent);
//    }
//    
//    private String lookupDbpStr(int dbpediaId) {
//      String post = dbpediaIds.lookupObject(dbpediaId);
//      assert post != null;
//      return pre + post;
//    }
    
    private void addInfoboxFact(int entity, DbpediaTtl fact) {
      List<EfficientFact> facts = this.infobox.get(entity);
      if (facts == null) {
        ec.increment("infobox/entity");
        facts = new ArrayList<>();
        this.infobox.put(entity, facts);
      }
      EfficientFact ef = compress(fact);
      assert ef != null;
      facts.add(ef);
      ec.increment("infobox/fact/kept");
    }
    
    public LinkedSent scan2(CluewebLinkedSentence sent) {
      LinkedSent s = new LinkedSent(sent);
      int nl = sent.numLinks();
      for (int i = 0; i < nl; i++) {
        String mid = sent.getLink(i).getMid(sent.getMarkup());
        int midi = this.relevantOrHopMids.lookupIndex(mid, false);
        if (midi < 0) {
          s.empty(i);
          continue;
        }
        IntArrayList dbpediaEntities = this.mid2dbp.get(midi);
        if (dbpediaEntities == null) {
          s.empty(i);
          continue;
        }
        int n = dbpediaEntities.size();
        s.alloc(i, n);
        for (int j = 0; j < n; j++) {
          int dbpi = dbpediaEntities.get(j);
          String dbp = dbpediaIds.lookupObject(dbpi);
//          String dbp = lookupDbpStr(dbpi);
          List<EfficientFact> ri = infobox.get(dbpi);
          if (ri == null) ri = Collections.emptyList();
          s.fill(i, j, dbp, expand(ri));
        }
      }
      return s;
    }
  }
  
  /**
   * Given an input {@link CluewebLinkedSentence} with freebase mids,
   * this represents the corresponding dbpedia ids (there may be more than one per mid)
   * and a set of infobox facts associated with these dbpedia ids.
   */
  static class LinkedSent {
    CluewebLinkedSentence sent;
    String[] mids;
    String[][] dbpediaIds;
    DbpediaTtl[][][] facts;
    
    public LinkedSent(CluewebLinkedSentence sent) {
      this.sent = sent;
      this.mids = new String[sent.numLinks()];
      this.dbpediaIds = new String[mids.length][];
      this.facts = new DbpediaTtl[mids.length][][];
      for (int i = 0; i < mids.length; i++)
        mids[i] = sent.getLink(i).getMid(sent.getMarkup());
    }
    
//    /**
//     * Returns an array indexed by link/mid which is true if it corresponds
//     * to a dbpedia id use in the subject or object of this fact.
//     */
//    public boolean[] aligned(DbpediaTtl fact) {
//      boolean[] a = new boolean[mids.length];
//      for (int i = 0; i < a.length; i++) {
//        for (int j = 0; j < dbpediaIds[i].length && !a[i]; j++) {
//          a[i] |= dbpediaIds[i][j].equals(fact.subject().getValue());
//          a[i] |= dbpediaIds[i][j].equals(fact.object().getValue());
//        }
//      }
//      return a;
//    }
    
    /**
     * Returns a list of "[svo]=\d+" where the key is short for either
     * subj/verb/obj in the given fact and the value is link/mid index
     * in this sentence.
     * In practice you will probably never get a "v=*" key since verbs
     * wont match the entities in the sentence.
     */
    public List<String> alignments(DbpediaTtl fact) {
      List<String> out = new ArrayList<>();
      for (int i = 0; i < dbpediaIds.length; i++) {
        if (contains(fact.subject(), dbpediaIds[i]))
          out.add("s=" + i);
        if (contains(fact.verb(), dbpediaIds[i]))
          out.add("v=" + i);
        if (contains(fact.object(), dbpediaIds[i]))
          out.add("o=" + i);
      }
      return out;
    }
    private static boolean contains(DbpediaToken tok, String[] dbpediaIds) {
      String v = tok.getValue();
      for (int i = 0; i < dbpediaIds.length; i++)
        if (v.equals(dbpediaIds[i]))
          return true;
      return false;
    }
    
    public void empty(int linkIdx) {
      this.dbpediaIds[linkIdx] = new String[0];
      this.facts[linkIdx] = new DbpediaTtl[0][];
    }
    
    public void alloc(int linkIdx, int numDbpediaIds) {
      this.dbpediaIds[linkIdx] = new String[numDbpediaIds];
      this.facts[linkIdx] = new DbpediaTtl[numDbpediaIds][];
    }
    
    public void fill(int linkIdx, int dbpediaIdx, String dbpediaId, List<DbpediaTtl> facts) {
      this.dbpediaIds[linkIdx][dbpediaIdx] = dbpediaId;
      this.facts[linkIdx][dbpediaIdx] = new DbpediaTtl[facts.size()];
      for (int i = 0; i < this.facts[linkIdx][dbpediaIdx].length; i++)
        this.facts[linkIdx][dbpediaIdx][i] = facts.get(i);
    }
    
    public int numDbpediaResolvedLinks() {
      int c = 0;
      for (int i = 0; i < mids.length; i++)
        if (dbpediaIds[i].length > 0)
          c++;
      return c;
    }

    public void show() {
      System.out.println(sent.getMarkup());
      int nl = sent.numLinks();
      assert nl == mids.length;
      for (int i = 0; i < nl; i++) {
        System.out.println("mention(" + i + "): " + mids[i]);//sent.getLink(i).getMid(sent.getMarkup()));
        for (int j = 0; j < dbpediaIds[i].length; j++) {
          System.out.println("\t" + dbpediaIds[i][j]);
          for (int k = 0; k < facts[i][j].length; k++) {
            System.out.println("\t\t" + facts[i][j][k]);
          }
        }
      }
      System.out.println();
    }
    
    public <T extends Collection<DbpediaTtl>> T allFacts(T addTo) {
      for (int i = 0; i < facts.length; i++)
        for (int j = 0; j < facts[i].length; j++)
          for (int k = 0; k < facts[i][j].length; k++)
            addTo.add(facts[i][j][k]);
      return addTo;
    }
    
    public <T extends Collection<String>> T allDbpediaIds(T addTo) {
      for (int i = 0; i < dbpediaIds.length; i++)
        for (int j = 0; j < dbpediaIds[i].length; j++)
          addTo.add(dbpediaIds[i][j]);
      return addTo;
    }
  }
  
  /**
   * Find facts which are either:
   * 1) have two entities mentioned in the sentence
   * 2) have one entity mentioned and one textual mention which is reasonably well matched
   */
  static class FactSelector {
    private ComputeIdf df;
    private Join j;
    
    public FactSelector(ComputeIdf df, Join j) {
      this.df = df;
      this.j = j;
    }

//    public MultiMap<DbpediaTtl, Feat> scoreFacts2(LinkedSent s) {
//      MultiMap<DbpediaTtl, Feat> fs = new MultiMap<>();
////      Set<String> mids = s.sent.getAllMids(new HashSet<>());
////      Set<String> wordsLc = s.sent.getAllWords(new HashSet<>(), true);
//      Set<Pair<String, DbpediaTtl>> used = new HashSet<>();
//      for (int i = 0; i < s.mids.length; i++) {
//        for (int j = 0; j < s.dbpediaIds[i].length; j++) {
//          for (int k = 0; k < s.facts[i][j].length; k++) {
//            
////            boolean ms = s.facts[i][j][k].subject().contains(s.mids[i]);
////            boolean mo = s.facts[i][j][k].object().contains(s.mids[i]);
//            
//            // Facts can only use a mid once
//            Pair<String, DbpediaTtl> key = new Pair<>(s.mids[i], s.facts[i][j][k]);
//            if (used.add(key))
//              fs.add(s.facts[i][j][k], new Feat(s.mids[i], 1));
//          }
//        }
//      }
//      return fs;
//    }
    
    public MultiMap<DbpediaTtl, Feat> scoreFacts3(LinkedSent s) {
      MultiMap<DbpediaTtl, Feat> fs = new MultiMap<>();
      Set<String> dbpEntsInSentence = s.allDbpediaIds(new HashSet<>());
      Collection<DbpediaTtl> facts = s.allFacts(new HashSet<>());
      for (DbpediaTtl f : facts) {
        if (dbpEntsInSentence.contains(f.subject().getValue()))
          fs.add(f, new Feat("subj/ent", 1));
        if (dbpEntsInSentence.contains(f.object().getValue()))
          fs.add(f, new Feat("obj/ent", 1));
      }
      return fs;
    }
  }
  
  /*
   * Produces two files:
   * sentences.txt := {@link CluewebLinkedSentence#hashHex()} <tab> {@link CluewebLinkedSentence#getMarkup()}
   * facts.txt: (hash, mid, infoboxSubj, infoboxVerb, infoboxObj)
   * 
   * The two may be (sequentially) joined on hash.
   * 
   * facts.txt contains all the freebase facts related to a mid mentioned in that sentence.
   */
  /**
   * Scans through
   * rare4/mids.dev.txt
   * parsed-sentences-rare4/sentences.txt
   * parsed-sentences-rare4/hashes.txt
   * 
   */
  public static void generateDistSupInstances(ExperimentProperties config) throws Exception {
    Log.info("starting...");
    TimeMarker tm = new TimeMarker();
    File relevantMids = config.getExistingFile("relevantMids",
        new File("../data/clueweb09-freebase-annotation/gen-for-entsum/rare4/mids.dev.txt"));
    File freebaseLinks = config.getExistingFile("freebaseLinks",
        new File("data/dbpedia/freebase_links_en.ttl.gz"));
    File infoboxFacts = config.getExistingFile("infoboxFacts",
        new File("data/dbpedia/infobox_properties_en.ttl.gz"));
    File sentences = config.getExistingFile("sentences",
        new File("../data/clueweb09-freebase-annotation/gen-for-entsum/parsed-sentences-rare4/sentences.txt"));
    
    File output = config.getOrMakeDir("output");
    File jf = new File(output, "join.jser");
    Join j;
    if (jf.isFile()) {
      Log.info("reading from cache " + jf.getPath());
      j = (Join) FileUtil.deserialize(jf);
    } else {
      j = new Join(
          relevantMids,
          sentences,
          freebaseLinks,
          infoboxFacts);
      Log.info("writing to cache " + jf.getPath());
      FileUtil.serialize(j, jf);
    }
    
    if (config.getBoolean("debug", false)) {
      // Q: Are we missing mid->dbpedia mappings which mean we can't match up facts?
      // A: No. We're only missing 1/100.
      List<String> missing = j.findMidsWithNoDbpediaId(new ArrayList<>());
      Log.info("missing " + missing.size() + " mid->dbpedia entries");
      for (String m : missing)
        System.out.println("\t" + m);
      
      // How many facts do we know about every entity?
      OrderStatistics<Integer> factsPerEntity = j.numFactsPerMid();
      System.out.println("rank(factsPerEntity): " + factsPerEntity.getOrdersStr());
      System.out.println("mean(factsPerEntity): " + factsPerEntity.getMean());

      return;
    }
    
    File dff = config.getExistingFile("wordDocFreq", new File("data/idf/cms/df-cms-simpleaccumulo-twolfe-cag1-nhash12-logb20.jser"));
    ComputeIdf df = new ComputeIdf(dff);
    FactSelector fs = new FactSelector(df, j);
    
    Log.info("scanning sentences in " + sentences.getPath());
    Counts<Integer> resolvedShort = new Counts<>();
    Counts<Integer> resolvedLong = new Counts<>();
    File outFact = new File(output, "facts.txt");
    int maxSentenceLength = config.getInt("maxSentenceLength", 80);
    try (ValidatorIterator iter = new ValidatorIterator(sentences, maxSentenceLength);
        BufferedWriter wf = FileUtil.getWriter(outFact)) {
      while (iter.hasNext()) {
        CluewebLinkedSentence sent = iter.next();
        j.ec.increment("sent");
        String hash = sent.hashHex();

        LinkedSent s = j.scan2(sent);
        int nr = s.numDbpediaResolvedLinks();
        if (s.sent.getTextTokenizedNumTokens() > 35)
          resolvedLong.increment(nr);
        else
          resolvedShort.increment(nr);
        
        MultiMap<DbpediaTtl, Feat> x = fs.scoreFacts3(s);

        List<DbpediaTtl> f = new ArrayList<>();
        for (DbpediaTtl t : x.keySet())
          f.add(t);
        Collections.sort(f, new Comparator<DbpediaTtl>() {
          @Override
          public int compare(DbpediaTtl o1, DbpediaTtl o2) {
            double s1 = Feat.sum(x.get(o1));
            double s2 = Feat.sum(x.get(o2));
            if (s1 > s2)
              return -1;
            if (s1 < s2)
              return +1;
            return 0;
          }
        });
        int k = 10, c = 0;
        for (DbpediaTtl ff : f) {
          List<Feat> r = x.get(ff);
          double sc = Feat.sum(r);
          if (sc <= 1)
            break;
          
          // TODO I want to know what mentions this fact is aligned to
//          boolean[] mentionsUsedInFact = s.aligned(ff);
          List<String> mentionArgAlignment = s.alignments(ff);
          String maas = StringUtils.join(",", mentionArgAlignment);
          
          // hash, score, mentionArgAlignment, s, v, o, feat+
          wf.write(hash + "\t" + sc + "\t" + maas);
          wf.write("\t" + ff.subject().getValue());
          wf.write("\t" + ff.verb().getValue());
          wf.write("\t" + ff.object().getValue());
          for (Feat feat : r)
            wf.write("\t" + feat.getName() + ":" + feat.getWeight());
          wf.newLine();
          
          if (++c == k)
            break;
        }
        if (c > 0)
          System.out.println();

        if (tm.enoughTimePassed(2)) {
          System.out.println("resolvedShort: " + resolvedShort);
          System.out.println("resolvedLong: " + resolvedLong);
        }
      }
    }
    
    Log.info("done");
  }
  
  public static class FeatExData implements Serializable {
    private static final long serialVersionUID = -8980229091410928382L;

    private ParsedSentenceMap parses;
    public ComputeIdf df;

    // Made by join, only counts entities appearing in the rare4 sentences
    private Alphabet<String> dbpediaIds;
    
    // Contains instance_types_en.ttl.gz
    private IntObjectHashMap<IntArrayList> dbpediaEntity2dbpediaType;
    private Alphabet<String> dbpediaTypes;

    // Created by Join
    private IntObjectHashMap<IntArrayList> mid2dbp; // only contains relevantMids as keys
    private Alphabet<String> mids;
    
    public FeatExData(ParsedSentenceMap parses, ComputeIdf df, Alphabet<String> dbpediaIds, File dbpediaEntity2Type) throws Exception {
      this.parses = parses;
      this.df = df;
      this.dbpediaIds = dbpediaIds;

      // When I looked in
      // data/dbpedia/instance_types_transitive_en.ttl.gz
      // I found <400 different entity types
      this.dbpediaTypes = new Alphabet<>();
      this.dbpediaEntity2dbpediaType = new IntObjectHashMap<>();
      int ents = 0, types = 0;
      Log.info("dbpediaIds.size=" + dbpediaIds.size()
          + " reading dbpedia entity types from " + dbpediaEntity2Type.getPath());
      try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(dbpediaEntity2Type)) {
        while (iter.hasNext()) {
          DbpediaTtl f = iter.next();
          assert f.subject().type == Type.DBPEDIA_ENTITY;
          int ent = dbpediaIds.lookupIndex(f.subject().getValue(), false);
          if (ent < 0)
            continue;
          int type = dbpediaTypes.lookupIndex(f.object().getValue());
          IntArrayList ia = dbpediaEntity2dbpediaType.get(ent);
          if (ia == null) {
            ia = new IntArrayList();
            dbpediaEntity2dbpediaType.put(ent, ia);
            ents++;
          }
          ia.add(type);
          types++;
        }
      }
      Log.info("dbpediaIds.size=" + dbpediaIds.size() + " ents=" + ents + " types=" + types);
    }
    
    public MultiAlphabet getParseAlph() {
      return parses.getAlph();
    }
    
    public int[][] getDbpediaIds(CluewebLinkedSentence sent) {
      int nl = sent.numLinks();
      int[][] out = new int[nl][];
      for (int i = 0; i < nl; i++) {
        String mid = sent.getLink(i).getMid(sent.getMarkup());
        int midi = mids.lookupIndex(mid, false);
        assert midi >= 0;
        IntArrayList ia = mid2dbp.get(midi);
        if (ia == null) {
          // This can actually happen, e.g. /m/0994vw is not in the dbpedia mapping
//          throw new RuntimeException("no dbpedia id for mid=" + mid + " i=" + i + " sent=" + sent.getMarkup());
          out[i] = new int[0];
        } else {
          out[i] = new int[ia.size()];
          for (int j = 0; j < out[i].length; j++)
            out[i][j] = ia.get(j);
        }
      }
      return out;
    }
  }
  
  /**
   * idf-weighted bag of words
   * NER type of another entity in the sentence, maybe binary features indexed by count
   *   (fine grain type? e.g. if the mention is linked, perhaps walk into KB and output a fine-grain type like "musician")
   * dep-path n-grams connecting entity and some other entity
   * idf-weighted dep-path walks from the entity head (lets say one word lexicalized)
   */
  public static class SentenceInterestingnessFeatures {
    private FeatExData fed;
    private CluewebLinkedSentence sent;
    private List<SegmentedTextAroundLink> segs;
    private int[][] dbpediaIds;   // dbpediaIds[linkIdx][i] is the i^th dbpedia id associated with mid[linkIdx]
    private DepNode[] parse;
    private int linkOfInterest;
    private List<Feat> features;
    private List<Feat> featuresMention;
    
    public boolean debug = false;
    
    public SentenceInterestingnessFeatures(CluewebLinkedSentence sentence, int[][] dbpediaIds, int linkOfInterest, FeatExData fed) {
      this.sent = sentence;
      this.dbpediaIds = dbpediaIds;
      this.parse = fed.parses.getParse(sentence.hashUuid());
      this.linkOfInterest = linkOfInterest;
      this.fed = fed;
      this.segs = sent.getTextTokenized();
    }
    
    /**
     * p: shortest-path 1-grams
     * q: shortest-path 2-grams
     * t: all dbpedia types for the entity link specified by linkIdx
     */
    public List<Feat> getMentionFeatures(int linkIdx) {
      if (featuresMention == null) {
        featuresMention = new ArrayList<>();
        
        // dep-path n-grams connecting linkOfInterest to other links
        int[] heads = findMentionHeads(segs, parse);
        
        // DEBUG: Show the heads
        if (debug) {
          MultiAlphabet a = fed.getParseAlph();
          // parse
          DepNode.show(parse, a);
          // spans
          Span[] sps = findMentionSpans(segs);
          System.out.println("spans: " + Arrays.toString(sps));
          for (int i = 0; i < sps.length; i++)
            DepNode.show(parse, sps[i], a);
          // heads
          System.out.println("heads: " + Arrays.toString(heads));
          for (int i = 0; i < heads.length; i++)
            DepNode.show(parse, heads[i], a);
        }
        
        // Find all the shortest paths from the current head to all other heads
        for (int i = 0; i < heads.length; i++) {
          if (i == linkIdx)
            continue;

          ShortestPath p = new ShortestPath(heads[linkIdx], heads[i], parse);
          List<DepNode.Edge> path = p.buildPath(fed.getParseAlph());
          List<DepNode.Edge[]> oneGrams = ShortestPath.ngrams(1, path);
          List<DepNode.Edge[]> twoGrams = ShortestPath.ngrams(2, path);

          // TODO should have types for endpoints? entity types? pos?
          // Start with no types.
          for (DepNode.Edge[] ng : oneGrams) {
            String feat = DepNode.Edge.ngramStr(ng);
            featuresMention.add(new Feat("p/" + feat, 1));
          }
          for (DepNode.Edge[] ng : twoGrams) {
            String feat = DepNode.Edge.ngramStr(ng);
            featuresMention.add(new Feat("q/" + feat, 1));
          }
        }

//        String pre = i == linkOfInterest ? "s/" : "c/";
        String pre = "t/";
        int i = linkIdx;
        for (int j = 0; j < dbpediaIds[i].length; j++) {
          IntArrayList ti = fed.dbpediaEntity2dbpediaType.get(dbpediaIds[i][j]);
          if (ti == null) {
            features.add(new Feat(pre + "ukn", 1));
          } else {
            for (int k = 0; k < ti.size(); k++) {
              String t = fed.dbpediaTypes.lookupObject(ti.get(k));
              assert t != null;
              features.add(new Feat(pre + t, 1));
            }
          }
        }
      }
      return featuresMention;
    }
    
    /**
     * w: bag of words (1-grams) weighted by IDF(word)
     * s: all dbpedia types for the link in question
     * c: all dbpedia types for a neighboring entity
     */
    public List<Feat> getFeatures() {
      if (features == null) {
        features = new ArrayList<>();
        
        // idf-weighted bag of words
        for (SegmentedTextAroundLink seg : segs) {
          if (seg.linkIdx == linkOfInterest)
            continue;
          for (String t : seg.outside.toks) {
            double idf = fed.df.idf(t);
            features.add(new Feat("w/" + t, idf/10));
          }
        }
        
        // Entity types of links
        for (int i = 0; i < dbpediaIds.length; i++) {
          String pre = i == linkOfInterest ? "s/" : "c/";
          for (int j = 0; j < dbpediaIds[i].length; j++) {
            IntArrayList ti = fed.dbpediaEntity2dbpediaType.get(dbpediaIds[i][j]);
            if (ti == null) {
              features.add(new Feat(pre + "ukn", 1));
            } else {
              for (int k = 0; k < ti.size(); k++) {
                String t = fed.dbpediaTypes.lookupObject(ti.get(k));
                assert t != null;
                features.add(new Feat(pre + t, 1));
              }
            }
          }
        }
        
        
        // Don't allow duplicate features
        features = Feat.dedup(features);
      }
      return features;
    }
  }
    
  /**
   * returns the token spans of the entities, indexed by mention
   */
  public static Span[] findMentionSpans(List<SegmentedTextAroundLink> segs) {
    Span[] s = new Span[segs.size()-1];
    int idx = 0;
    int pre = 0;
    for (SegmentedTextAroundLink seg : segs) {
      // Skip the last segment which doesn't contain an entity, just the tokens after the last entity
      if (seg.linkIdx < 0)
        break;
      // This is the number of tokens BEFORE the entity in this segment
      int b = seg.outside.numTokens();
      // This is the number of tokens INSIDE the entity
      int i = seg.inside.numTokens();
      pre += b;
      s[idx++] = Span.getSpan(pre, pre + i);
      pre += i;
    }
    return s;
  }

  public static int[] findMentionHeads(List<SegmentedTextAroundLink> segs, DepNode[] parse) {
    int[] h = new int[segs.size()-1];
    Span[] s = findMentionSpans(segs);
    int[] d = DepNode.depths(parse);
    for (int i = 0; i < s.length; i++) {
      ArgMin<Integer> shallow = new ArgMin<>();
      for (int j = s[i].start; j < s[i].end; j++)
        shallow.offer(j, d[i]);
      h[i] = shallow.get();
    }
    return h;
  }
  
  /**
   * Keys are sentence hashes, values are list of strings like "o=0,s=1" which correspond to
   * a fact evoked in that sentence. The keys are [svo] for (subj|verb|obj) and the values are
   * mention indices.
   * 
   * The values loose information; namely the fact for which they are alignment.
   */
  public static MultiMap<String, String> readFactAlignments(File factsDotTxt) throws IOException {
    Log.info("facts=" + factsDotTxt.getPath());
    MultiMap<String, String> sent2factAlignment = new MultiMap<>();
    try (BufferedReader r = FileUtil.getReader(factsDotTxt)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] ar = line.split("\t");
        sent2factAlignment.add(ar[0], ar[2]);
      }
    }
    Log.info("done, nSentence=" + sent2factAlignment.numKeys() + " nFact=" + sent2factAlignment.numEntries());
    return sent2factAlignment;
  }
  
  public static void writeVwInstance(BufferedWriter fvw, Boolean y, List<Feat> fx) throws IOException {
    MultiMap<String, Feat> vwFeats = Feat.groupByNamespace(fx, '/');
    if (y == null)
      fvw.write("0");
    else if (y)
      fvw.write("+1");
    else
      fvw.write("0");
    for (String ns : vwFeats.keySet()) {
      fvw.write(" |" + ns);
      for (Feat fff : vwFeats.get(ns)) {
//              int h = hf.hashString(fff.getName(), UTF8).asInt();
//              fvw.write(" " + Integer.toUnsignedString(h) + ":" + fff.getWeight());
//              fvw.write(" " + Integer.toHexString(h) + ":" + fff.getWeight());

        // 8 chars in Base64 = 8 * log2(64) = 8 * 6 = 48 bits, which is wayyy more than I need
        byte[] hb = VW_HASH.hashString(fff.getName(), UTF8).asBytes();
        String hs = Base64.getEncoder().encodeToString(hb).substring(0, 8);
        fvw.write(" " + hs + ":" + fff.getWeight());
      }
    }
    fvw.newLine();
  }
  private static final HashFunction VW_HASH = Hashing.sha256();
  
  /**
   * Reads in the output of facts.txt file which has columns for:
   * [sentHash, fact.extractionScore, fact.subject, fact.verb, fact.object, extractionFeatures+]
   */
  public static void extractFeatures(ExperimentProperties config) throws Exception {
    TimeMarker tm = new TimeMarker();
    Counts<String> ec = new Counts<>();
    File p = new File("/home/travis/code/data/clueweb09-freebase-annotation/gen-for-entsum");
    
    File outputDir = config.getOrMakeDir("outputDir", new File("../data/clueweb09-freebase-annotation/gen-for-entsum/feature-extracted"));

    FeatExData fed;
    File fedFile = new File(outputDir, "fed.jser");
    if (fedFile.isFile()) {
      Log.info("loading fed from " + fedFile.getPath());
      fed = (FeatExData) FileUtil.deserialize(fedFile);
    } else {
      ParsedSentenceMap parses = new ParsedSentenceMap(
          config.getExistingFile("hashes", new File(p, "parsed-sentences-rare4/hashes.txt")),
          config.getExistingFile("conll", new File(p, "parsed-sentences-rare4/parsed.conll")),
          new MultiAlphabet());

      ComputeIdf df = new ComputeIdf(config.getExistingFile("wordDocFreq"));

      File jf = config.getExistingFile("joinFile", new File(p, "dbpedia-distsup-rare4/join.jser"));
      Join j = (Join) FileUtil.deserialize(jf);
      Alphabet<String> dbpediaIds = j.dbpediaIds;

      File dbpediaEntity2Type = config.getExistingFile("dbpediaEntity2Type",
          new File("data/dbpedia/instance_types_transitive_en.ttl.gz"));

      fed = new FeatExData(parses, df, dbpediaIds, dbpediaEntity2Type);
      fed.mid2dbp = j.mid2dbp;
      fed.mids = j.relevantOrHopMids;
      
      Log.info("saving fed to " + fedFile.getPath());
      FileUtil.serialize(fed, fedFile);
    }
    
    // Read in the set of sentences which may contain an infobox fact (label for interestingness)
    // Using my rare4 example, 15121 out of 83323 sentences meet this criteria
    // Keys are sentence hashes, values are list of strings like "o=0,s=1" which correspond to
    // a fact evoked in that sentence. The keys are [svo] for (subj|verb|obj) and the values are
    // mention indices.
    File factsDotTxt = config.getExistingFile("facts", new File(p, "dbpedia-distsup-rare4/facts.txt"));
    MultiMap<String, String> sent2factAlignment = readFactAlignments(factsDotTxt);

    // This prints out features for every (mention, sentence)
    // [sentenceHash, mentionIdx, mentionMid, feature+]
    File outFeatsReadable = new File(outputDir, "sentence-mention-features.readable.txt");
    File outFeatsVw = new File(outputDir, "sentence-mention-features.vw.txt");
    File sentences = config.getExistingFile("hashes", new File(p, "parsed-sentences-rare4/sentences.txt"));
    int maxSentenceLength = config.getInt("maxSentenceLength", 80);
    try (BufferedWriter fw = FileUtil.getWriter(outFeatsReadable);
        BufferedWriter fvw = FileUtil.getWriter(outFeatsVw);
        CluewebLinkedSentence.ValidatorIterator iter = new CluewebLinkedSentence.ValidatorIterator(sentences, maxSentenceLength)) {
      while (iter.hasNext()) {
        CluewebLinkedSentence sent = iter.next();
        String hash = sent.hashHex();
        ec.increment("sentence");
        int[][] dbpediaIds = fed.getDbpediaIds(sent);
        int nl = sent.numLinks();
        for (int linkOfInterest = 0; linkOfInterest < nl; linkOfInterest++) {
          SentenceInterestingnessFeatures f = new SentenceInterestingnessFeatures(sent, dbpediaIds, linkOfInterest, fed);
          List<Feat> fs = f.getFeatures();
          List<Feat> fsi = f.getMentionFeatures(linkOfInterest);
          fs.addAll(fsi);

//          System.out.println(sent.getMarkup());
//          System.out.println(fs);
//          System.out.println();

          String mid = sent.getLink(linkOfInterest).getMid(sent.getMarkup());
          fw.write(sent.hashHex() + "\t" + linkOfInterest + "\t" + mid);
          for (Feat feat : fs) {
            assert feat.getName().indexOf('\t') < 0;
            fw.write("\t" + feat.getName() + ":" + feat.getWeight());
            ec.increment("feat");
          }
          fw.newLine();
          ec.increment("mention");
          
          // Need a label wrt a particular mention
//          boolean y = sentenceHashesWhichMentionInfoboxFact.contains(o)
          List<String> alignments = sent2factAlignment.get(hash);
          boolean y = false;
          for (String al : alignments) {
            for (String a : al.split(",")) {
              String[] kv = a.split("=");
              assert kv.length == 2 && Arrays.asList("s", "v", "o").contains(kv[0]);
              int linkIdx = Integer.parseInt(kv[1]);
              if (linkIdx == linkOfInterest) {
                // This mention was aligned to some infobox fact
                // (and the fact had both subj and obj aligned)
                y = true;
                break;
              }
            }
          }
          writeVwInstance(fvw, y, fs);
        }
        
        if (tm.enoughTimePassed(2)) {
          Log.info(Describe.memoryUsage() + "\t" + ec);
        }
      }
    }
    Log.info("done\t" + ec);
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
//    generateDistSupInstances(config);
    extractFeatures(config);
  }
}
