package edu.jhu.hlt.entsum;

import java.io.BufferedWriter;
import java.io.File;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.entsum.CluewebLinkedSentence.ValidatorIterator;
import edu.jhu.hlt.entsum.DbpediaToken.Type;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.ComputeIdf;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.OrderStatistics;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;
import edu.jhu.util.MultiMap;

/**
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
    public static final Charset UTF8 = Charset.forName("UTF-8");

    // Dictated by context like rare4
    private Alphabet<String> relevantOrHopMids;
    private BitSet relevantMid;
    private BitSet relevantDbp;
    
    // Used to map CluewebLinkedSentence -> mid -> dbpedia
    // TODO BUG! we want mid->dbp mappings for either a relevant entity or a mid which co-occurrs with a relevant mid.
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
//            int dbpi = dbpediaIds.lookupIndex(dbp);
            int dbpi = lookupDbpInt(dbp, true);
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
          int obj = lookupDbpInt(x.object().getValue(), false);
          assert x.subject().type == Type.DBPEDIA_ENTITY;
          int subj = lookupDbpInt(x.subject().getValue(), false);
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
    
    private static final String pre = "http://dbpedia.org/resource/";
    private int lookupDbpInt(String dbpediaId, boolean addIfNotPresent) {
      assert dbpediaId.charAt(0) != '<';
      assert dbpediaId.charAt(dbpediaId.length()-1) != '>';
      boolean sw = dbpediaId.startsWith(pre);
      if (addIfNotPresent)
        assert sw;
      if (!sw && !addIfNotPresent)
        return -1;
      String post = dbpediaId.substring(pre.length());
      return dbpediaIds.lookupIndex(post, addIfNotPresent);
    }
    
    private String lookupDbpStr(int dbpediaId) {
      String post = dbpediaIds.lookupObject(dbpediaId);
      assert post != null;
      return pre + post;
    }
    
    private void addInfoboxFact(int entity, DbpediaTtl fact) {
//      List<DbpediaTtl> facts = this.infobox.get(entity);
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
    
//    public List<DbpediaTtl> relevantToMid(String mid) {
//      int midi = this.relevantMids.lookupIndex(mid, false);
//      if (midi < 0)
//        return Collections.emptyList();
//      IntArrayList dbpediaEntities = this.mid2dbp.get(midi);
//      if (dbpediaEntities == null)
//        return Collections.emptyList();
//      List<DbpediaTtl> rel = new ArrayList<>();
//      int n = dbpediaEntities.size();
//      for (int i = 0; i < n; i++) {
//        List<DbpediaTtl> ri = infobox.get(dbpediaEntities.get(i));
//        if (ri != null)
//          rel.addAll(ri);
//      }
//      return rel;
//    }
//    
//    public Map<String, List<DbpediaTtl>> scan(CluewebLinkedSentence sent) {
//      Map<String, List<DbpediaTtl>> m = new HashMap<>();
//      int nl = sent.numLinks();
//      for (int i = 0; i < nl; i++) {
//        String mid = sent.getLink(i).getMid(sent.getMarkup());
//        List<DbpediaTtl> facts = relevantToMid(mid);
//        if (facts.isEmpty())
//          continue;
//        if (m.containsKey(mid))
//          m.get(mid).addAll(facts);
//        else
//          m.put(mid, facts);
//      }
//      return m;
//    }
    
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
//          String dbp = dbpediaIds.lookupObject(dbpi);
          String dbp = lookupDbpStr(dbpi);
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

    public MultiMap<DbpediaTtl, Feat> scoreFacts2(LinkedSent s) {
      MultiMap<DbpediaTtl, Feat> fs = new MultiMap<>();
//      Set<String> mids = s.sent.getAllMids(new HashSet<>());
//      Set<String> wordsLc = s.sent.getAllWords(new HashSet<>(), true);
      Set<Pair<String, DbpediaTtl>> used = new HashSet<>();
      for (int i = 0; i < s.mids.length; i++) {
        for (int j = 0; j < s.dbpediaIds[i].length; j++) {
          for (int k = 0; k < s.facts[i][j].length; k++) {
            
//            boolean ms = s.facts[i][j][k].subject().contains(s.mids[i]);
//            boolean mo = s.facts[i][j][k].object().contains(s.mids[i]);
            
            // Facts can only use a mid once
            Pair<String, DbpediaTtl> key = new Pair<>(s.mids[i], s.facts[i][j][k]);
            if (used.add(key))
              fs.add(s.facts[i][j][k], new Feat(s.mids[i], 1));
          }
        }
      }
      return fs;
    }
    
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
  
  /**
   * Produces two files:
   * sentences.txt := {@link CluewebLinkedSentence#hashHex()} <tab> {@link CluewebLinkedSentence#getMarkup()}
   * facts.txt: (hash, mid, infoboxSubj, infoboxVerb, infoboxObj)
   * 
   * The two may be (sequentially) joined on hash.
   * 
   * facts.txt contains all the freebase facts related to a mid mentioned in that sentence.
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
//    OrderStatistics<Integer> resolvedLinksPerSentence = new OrderStatistics<>();
    Counts<Integer> resolvedShort = new Counts<>();
    Counts<Integer> resolvedLong = new Counts<>();
    File outSent = new File(output, "sentences.txt");
    File outFact = new File(output, "facts.txt");
    int maxSentenceLength = config.getInt("maxSentenceLength", 80);
    try (ValidatorIterator iter = new ValidatorIterator(sentences, maxSentenceLength);
        BufferedWriter ws = FileUtil.getWriter(outSent);
        BufferedWriter wf = FileUtil.getWriter(outFact)) {
      while (iter.hasNext()) {
        CluewebLinkedSentence sent = iter.next();
        j.ec.increment("sent");
        String hash = sent.hashHex();

//        if ("4a090919e7dc9415c18502f3562580b8".equals(hash))
//        if ("de0bb2a78a1f43d4c98733b3fe404e69".equals(hash))
        if ("bd6644e60eb240005a86d4384a32a171".equals(hash))
          Log.info("paydirt");

//        Map<String, List<DbpediaTtl>> facts = j.scan(sent);
//        if (facts.isEmpty())
//          continue;
        LinkedSent s = j.scan2(sent);
        int nr = s.numDbpediaResolvedLinks();
//        resolvedLinksPerSentence.add(nr);
        if (s.sent.getTextTokenizedNumTokens() > 35)
          resolvedLong.increment(nr);
        else
          resolvedShort.increment(nr);
        
//        System.out.println(hash);
//        s.show();

//        MultiMap<DbpediaTtl, Feat> x = fs.scoreFacts2(s);
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
        boolean shownSentence = false;
        int k = 10, c = 0;
        for (DbpediaTtl ff : f) {
          List<Feat> r = x.get(ff);
          double sc = Feat.sum(r);
          if (sc <= 1)
            break;
          if (!shownSentence) {
            System.out.println(hash);
            s.show();
            shownSentence = true;
          }
          System.out.println("fact: " + sc + "\t" + ff + "\t" + r);
          if (++c == k)
            break;
        }
        if (c > 0)
          System.out.println();

        if (tm.enoughTimePassed(2)) {
//        System.out.println("resolvedLinksPerSentence:");
//        System.out.println(resolvedLinksPerSentence.getOrdersStr());
        System.out.println("resolvedShort: " + resolvedShort);
        System.out.println("resolvedLong: " + resolvedLong);
        }
      }
    }
    
    Log.info("done");
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    generateDistSupInstances(config);
  }
}
