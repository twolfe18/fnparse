package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.entsum.DbpediaToken.Type;
import edu.jhu.hlt.entsum.EffSent.Mention;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.util.MultiMap;

/**
 * Input (for as many entities as you want):
 *   108K    mentionLocs.txt
 * Output (puts output beside/sameDirAs input):
 *    12K    facts-rel0-types.txt
 *   460K    facts-rel1-types.txt
 *     4K    mid2dbp-rel0.txt
 *   112K    mid2dbp-rel1.txt
 * 
 * 1) Read in (a possible large amount of) mid's from mention files
 * 2) Read in relevant mid->dbpedia entries
 * 3) O(1) memory scan of infobox facts (only need to store mid->dbpedia in memory)
 *    Produces m.0gly1/facts.txt
 *    Format for lines of m.0gly1/fact-rel0-types.txt: <subj> <verb> <obj>
 *    Format for lines of m.0gly1/fact-rel0-mentions.txt: (<subj> <subjMentionIdx> <verb> <obj> <objMentionIdx>)*
 *    Both of these files must have either subj or obj be relevant0, which means the other will be (at least) relevant1
 * 4) O(1) memory scan of dbpedia types
 *    Produces m.0gly1/types.txt
 *    Format for lines of m.0gly1/types.txt: <dbpediaCorrespondingToRelevant1Mid> <superType>
 *
 * (3) is split up into:
 *   a) a pass which spits out un-grounded facts (filter),  e.g. m.0gly1/fact-types.txt    (bag of triples)
 *   b) (TODO) a pass which aligns facts to sentences,      e.g. m.0gly1/fact-mentions.txt (same lines as mentions.txt)
 *      @see SlotsAsConcepts.StreamingDistSupFeatEx is the module that does fact alignment.
 *
 * TODO: The distsup training code extraction need to look at [parsed.conll, mentions.txt, facts.txt] for all entities.
 * 
 * NOTE: The reason for creating this class is that the old way of doing it,
 * {@link DbpediaDistSup.Join}, required a single sentences and mids file, and
 * used way more memory than was needed. I'm not sure it would work (memory
 * consumption) on the full size data. This version also writes a lot more out
 * to text files for more flexible use later.
 *
 * @author travis
 */
public class DistSupSetup {
  
  private Map<String, File> mid2wdRel0;       // contains e.g. "/m/0gly1" -> tokenized-sentences/dev/m.0gly1/
  private MultiMap<String, File> mid2wdRel1;  // same values as rel0 version, but has additional edges for rel1 keys/mids
  private MultiMap<String, String> mid2dbpedia;
  private MultiMap<String, String> dbpedia2mid;
  
  // These are huge! And not currently used!
  // On rare5:
  //    15M   du -sch $(find tokenized-sentences/ -name 'facts-rel0-types.txt')
  //   767M   du -sch $(find tokenized-sentences/ -name 'parse.conll')
  // 47000M   du -sch $(find tokenized-sentences/ -name 'facts-rel1-types.txt')
  // 51000M   du -sh tokenized-sentences/
  public boolean outputRel1Facts;
  
  public DistSupSetup(boolean outputRel1Facts) {
    Log.info("outputRel1Facts=" + outputRel1Facts);
    mid2wdRel0 = new HashMap<>();
    mid2wdRel1 = new MultiMap<>();
    this.outputRel1Facts = outputRel1Facts;
  }
  
  /**
   * @param mid e.g. "/m/0gly1", counts towards relevant0
   * @param mentionsDotTxt e.g. tokenized-sentences/dev/m.0gly1/mentions.txt
   *        other mids in this file are at least relevant1
   */
  public void addMentions(String mid, File mentionsDotTxt) throws IOException {
    Log.info("adding mid=" + mid + " mentionsDotTxt=" + mentionsDotTxt.getPath());
    File wd = mentionsDotTxt.getParentFile();
    Object old = mid2wdRel0.put(mid, wd);
    assert old == null;
    assert mid2dbpedia == null;
    assert dbpedia2mid == null;
    
    // Add relevant1 mids which appear in this file
    try (BufferedReader r = FileUtil.getReader(mentionsDotTxt)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        Mention[] ms = EffSent.parseMentions(line);
        for (int i = 0; i < ms.length; i++) {
          String m = ms[i].getFullMid();
          if (!mid2wdRel0.containsKey(m))
            mid2wdRel0.put(m, null);
          if (!m.equals(mid))
            mid2wdRel1.addIfNotPresent(m, wd);
        }
      }
    }
  }
  
  /** relevant1 means 1-hop from (appeared in the same sentence as) a relevant0 entity */
  public boolean isRelevant1Mid(String mid) {
    return mid2wdRel0.containsKey(mid);
  }
  
  /** relevant0 means an entity which was added via the {@link DistSupSetup#addMentions(String, File)} method */
  public boolean isRelevant0Mid(String mid) {
    File f = mid2wdRel0.get(mid);
    return mid2wdRel0.containsKey(mid) && f != null;
  }
  
  List<File> getMid2DbpMappingFilesForMid(String mid) {
    List<File> out = new ArrayList<>();
    File wd0 = mid2wdRel0.get(mid);
    if (wd0 != null)
      out.add(new File(wd0, "mid2dbp-rel0.txt"));
    for (File wd1 : mid2wdRel1.get(mid))
      out.add(new File(wd1, "mid2dbp-rel1.txt"));
    return out;
  }
  
  /**
   * @param freebaseLinks e.g. data/dbpedia/freebase_links_en.ttl.gz
   */
  public void addMid2Dbpedia(File freebaseLinks) throws Exception {
    Log.info("reading " + freebaseLinks.getPath());
    Map<String, BufferedWriter> open = new HashMap<>();
    mid2dbpedia = new MultiMap<>();
    dbpedia2mid = new MultiMap<>();
    TimeMarker tm = new TimeMarker();
    int n = 0;
    boolean keepLines = true;
    try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(freebaseLinks, keepLines)) {
      while (iter.hasNext()) {
        DbpediaTtl x = iter.next();
        n++;
        String mid = DbpediaTtl.extractMidFromTtl(x.object().getValue());
        if (isRelevant1Mid(mid)) {
          String dbp = x.subject().getValue();
          mid2dbpedia.addIfNotPresent(mid, dbp);
          dbpedia2mid.addIfNotPresent(dbp, mid);
        }
        
        for (File f : getMid2DbpMappingFilesForMid(mid)) {
          BufferedWriter w = getOrOpen(f.getPath(), open, f);
          w.write(x.line);
          w.newLine();
        }
        
        if (tm.enoughTimePassed(3)) {
          Log.info("lines=" + n
              + " nMid=" + mid2dbpedia.numKeys()
              + " nDbp=" + dbpedia2mid.numKeys()
              + " nMap=" + mid2dbpedia.numEntries()
              + "\t" + Describe.memoryUsage());
          assert mid2dbpedia.numEntries() == dbpedia2mid.numEntries();
        }
      }
    }
    closeAll(open);
    Log.info("done, lines=" + n
        + " nMid=" + mid2dbpedia.numKeys()
        + " nDbp=" + dbpedia2mid.numKeys()
        + " nMap=" + mid2dbpedia.numEntries()
        + "\t" + Describe.memoryUsage());
  }
  
  public static BufferedWriter getOrOpen(String key, Map<String, BufferedWriter> open, File ifNotOpen) throws IOException {
    BufferedWriter w = open.get(key);
    if (w == null) {
      w = FileUtil.getWriter(ifNotOpen);
      open.put(key, w);
    }
    return w;
  }
  
  public static void closeAll(Map<?, BufferedWriter> open) {
    for (Entry<?, BufferedWriter> e : open.entrySet()) {
      try {
        e.getValue().close();
      } catch (Exception ex) {
//        ex.printStackTrace();
        Log.info("WARNING: error closing " + e.getKey());
      }
    }
  }

  List<File> minRelDbpF(String dbp) {
    List<File> rel1 = new ArrayList<>();
    for (String mid : dbpedia2mid.get(dbp)) {
      File wd0 = mid2wdRel0.get(mid);
      if (wd0 != null)
        rel1.add(new File(wd0, "facts-rel0-types.txt"));
      if (outputRel1Facts) {
        for (File wd : mid2wdRel1.get(mid))
          rel1.add(new File(wd, "facts-rel1-types.txt"));
      }
    }
    return rel1;
  }
  
  /**
   * "scan*" means does not increase memory usage.
   * @param infobox e.g. data/dbpedia/infobox_properties_en.ttl.gz
   */
  public void scanInfoboxFacts(File infobox) throws Exception {
    Log.info("reading " + infobox.getPath());
    Map<String, BufferedWriter> factTypes = new HashMap<>();
    TimeMarker tm = new TimeMarker();
    int n = 0, k = 0;
    boolean keepLines = true;
    try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(infobox, keepLines)) {
      while (iter.hasNext()) {
        DbpediaTtl x = iter.next();
        n++;

        List<String> dbps = new ArrayList<>();
        if (x.subject().type == Type.DBPEDIA_ENTITY)
          dbps.add(x.subject().getValue());
        if (x.object().type == Type.DBPEDIA_ENTITY)
          dbps.add(x.object().getValue());

        for (String dbp : dbps) {
          List<File> fs = minRelDbpF(dbp);
          for (File f : fs) {
            BufferedWriter w = getOrOpen(f.getPath(), factTypes, f);
            w.write(x.line);
            w.newLine();
            k++;
          }
        }
        
        if (tm.enoughTimePassed(3)) {
          Log.info("lines=" + n
              + " kept=" + k
              + " open=" + factTypes.size()
              + "\t" + Describe.memoryUsage());
        }
      }
    }
    closeAll(factTypes);
    Log.info("done, lines=" + n
        + " kept=" + k
        + " open=" + factTypes.size()
        + "\t" + Describe.memoryUsage());
  }

  List<File> getTypeFiles(String dbp) {
    List<File> rel1 = new ArrayList<>();
    for (String mid : dbpedia2mid.get(dbp)) {
      File wd0 = mid2wdRel0.get(mid);
      if (wd0 != null)
        rel1.add(new File(wd0, "entity-types-rel0.txt"));
      for (File wd : mid2wdRel1.get(mid))
        rel1.add(new File(wd, "entity-types-rel1.txt"));
    }
    return rel1;
  }
  
  /**
   * "scan*" means does not increase memory usage.
   * @param instanceTypes e.g. instance_types_transitive_en.ttl.gz
   */
  public void scanDbpediaTypes(File instanceTypes) throws Exception {
    Log.info("reading " + instanceTypes.getPath());
    TimeMarker tm = new TimeMarker();
    Map<String, BufferedWriter> open = new HashMap<>();
    int lineOut = 0, lineIn = 0;
    boolean keepLines = true;
    try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(instanceTypes, keepLines)) {
      while (iter.hasNext()) {
        lineIn++;
        DbpediaTtl x = iter.next();
        assert x.subject().type == Type.DBPEDIA_ENTITY;
        String subj = x.subject().getValue();
        for (File f : getTypeFiles(subj)) {
          lineOut++;
          BufferedWriter w = getOrOpen(f.getPath(), open, f);
          w.write(x.line);
          w.newLine();
        }
        
        if (tm.enoughTimePassed(4))
          Log.info("lineIn=" + lineIn + " lineOut=" + lineOut);
      }
    }
    Log.info("done, lineIn=" + lineIn + " lineOut=" + lineOut);
    closeAll(open);
  }
  
  public static String getMidFromMentionFile(File mentions) {
    String m = mentions.getParentFile().getName();
    assert m.startsWith("m.");
    return "/m/" + m.substring(2);
  }
  
  public static void buildFactTypesAndEntityTypes(ExperimentProperties config) throws Exception {
    // 1) Read in (a possible large amount of) mid's from mention files
    File mentionsParent = config.getExistingDir("mentionsParent");  //, new File("data/facc1-entsum/code-testing-data/tokenized-sentences/dev"));
//    String mentionsGlob = config.getString("mentionsGlob", "glob:**/mentionLocs.txt");
//    List<File> mentions = FileUtil.find(mentionsParent, mentionsGlob);
    List<File> mentions = FileUtil.execFind(mentionsParent, "-type", "f", "-name", "mentionLocs.txt");
    Log.info("found " + mentions.size() + " mentions files");
    if (mentions.isEmpty())
      return;
    boolean outputRel1Facts = config.getBoolean("outputRel1Facts", false);
    DistSupSetup j = new DistSupSetup(outputRel1Facts);
    for (File m : mentions) {
      String mid = getMidFromMentionFile(m);
      j.addMentions(mid, m);
    }
    
    // 2) Read in relevant mid->dbpedia entries
    File freebaseLinks = config.getExistingFile("freebaseLinks", new File("data/dbpedia/freebase_links_en_sortu.ttl.gz"));
    j.addMid2Dbpedia(freebaseLinks);
    
    // 3) O(1) memory scan of infobox facts (only need to store mid->dbpedia in memory)
    //    a) a pass which spits out un-grounded facts (filter),  e.g. m.0gly1/fact-rel0-types.txt    (bag of triples)
    File infobox = config.getExistingFile("infobox", new File("data/dbpedia/infobox_properties_en.ttl.gz"));
    j.scanInfoboxFacts(infobox);
    //    b) a pass which aligns facts to sentences,             e.g. m.0gly1/fact-rel0-mentions.txt (same lines as mentions.txt)
    // NOTE: This is done as a part of the feature extraction step, see SlotsAsConcepts
    
    // 4) O(1) memory scan of dbpedia types
    //    Produces m.0gly1/types.txt
    //    Format for lines of m.0gly1/types.txt: <dbpediaCorrespondingToRelevant1Mid> <superType>
    File dbpediaTypes = config.getExistingFile("dbpediaTypes", new File("data/dbpedia/instance_types_transitive_en.ttl.gz"));
    j.scanDbpediaTypes(dbpediaTypes);
    
    Log.info("done");
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    Log.info("starting...");
    
//    Map<String, String> m = new HashMap<>();
//    m.put("a", "A");
//    m.put("b", null);
//    System.out.println(m.containsKey("a"));
//    System.out.println(m.containsKey("b"));
//    System.out.println(m.containsKey("c"));
//    System.out.println(m.containsKey("a") && m.get("a") != null);
//    System.out.println(m.containsKey("b") && m.get("b") != null);
//    System.out.println(m.containsKey("c") && m.get("c") != null);

    buildFactTypesAndEntityTypes(config);
  }
}
