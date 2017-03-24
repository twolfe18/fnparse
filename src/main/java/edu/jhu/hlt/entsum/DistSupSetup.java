package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.entsum.EffSent.Mention;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.util.MultiMap;

/**
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
 * TODO: (3) needs to be split up into:
 *   a) a pass which spits out un-grounded facts (filter),  e.g. m.0gly1/fact-types.txt    (bag of triples)
 *   b) a pass which aligns facts to sentences,             e.g. m.0gly1/fact-mentions.txt (same lines as mentions.txt)
 *
 * TODO: The distsup training code extraction need to look at [parsed.conll, mentions.txt, facts.txt] for all entities.
 * 
 * NOTE: The reason for creating this class is that the old way of doing it,
 * {@link DbpediaDistSup.Join}, required a single sentences and mids file, and used
 * way more memory than was needed. I'm not sure it would work on the full size data.
 * This version also writes a lot more out to text files for more flexible use later.
 *
 * @author travis
 */
public class DistSupSetup {
  
  // TODO Change mentions => entity home dir
  private Map<String, File> mid2mentions; // contains e.g. "/m/0gly1" -> tokenized-sentences/dev/m.0gly1/mentions.txt
  private MultiMap<String, String> mid2dbpedia;
  private MultiMap<String, String> dbpedia2mid;
  
  public DistSupSetup() {
    mid2mentions = new HashMap<>();
  }
  
  /**
   * @param mid e.g. "/m/0gly1", counts towards relevant0
   * @param mentionsDotTxt e.g. tokenized-sentences/dev/m.0gly1/mentions.txt
   *        other mids in this file are at least relevant1
   */
  public void addMentions(String mid, File mentionsDotTxt) throws IOException {
    Object old = mid2mentions.put(mid, mentionsDotTxt);
    assert old == null;
    assert mid2dbpedia == null;
    assert dbpedia2mid == null;
    
    // Add relevant1 mids which appear in this file
    try (BufferedReader r = FileUtil.getReader(mentionsDotTxt)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        Mention[] ms = EffSent.parseMentions(line);
        for (int i = 0; i < ms.length; i++) {
          String m = ms[i].getFullMid();
          if (!mid2mentions.containsKey(m))
            mid2mentions.put(m, null);
        }
      }
    }
  }
  
  /** relevant1 means 1-hop from (appeared in the same sentence as) a relevant0 entity */
  public boolean isRelevant1Mid(String mid) {
    return mid2mentions.containsKey(mid);
  }
  
//  /** relevant0 means an entity which was added via the {@link DistSupSetup#addMentions(String, File)} method */
//  public Set<String> relevant0Mids() {
//    Set<String> r = new HashSet<>();
//    for (Entry<String, File> e : mid2mentions.entrySet())
//      if (e.getValue() != null)
//        r.add(e.getKey());
//    return r;
//  }
  
  /**
   * @param freebaseLinks e.g. data/dbpedia/freebase_links_en.ttl.gz
   */
  public void addMid2Dbpedia(File freebaseLinks) throws Exception {
    mid2dbpedia = new MultiMap<>();
    dbpedia2mid = new MultiMap<>();
    try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(freebaseLinks)) {
      while (iter.hasNext()) {
        DbpediaTtl x = iter.next();
        String mid = DbpediaTtl.extractMidFromTtl(x.object().getValue());
        if (isRelevant1Mid(mid)) {
          String dbp = x.subject().getValue();
          mid2dbpedia.add(mid, dbp);
          dbpedia2mid.add(dbp, mid);
        }
      }
    }
  }
  
  /**
   * "scan*" means does not increase memory usage.
   * @param infobox e.g. data/dbpedia/infobox_properties_en.ttl.gz
   */
  public void scanInfoboxFacts(File infobox) throws Exception {
    // Keep facts for the relevant0 entities
//    Set<String> r = relevant0Mids();
    Map<String, BufferedWriter> facts = new HashMap<>();
    try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(infobox)) {
      while (iter.hasNext()) {
        DbpediaTtl x = iter.next();
        
        int rel = 2;    // not relevant, 1 means either subj or obj is relevant1, 0 means at least one is relevant0
        
        String dbp = x.subject().getValue();
        for (String mid : dbpedia2mid.get(dbp)) {
          BufferedWriter w = facts.get(mid);
          if (w == null) {
            
          }
          
        }
      }
    }
  }
  
  /**
   * "scan*" means does not increase memory usage.
   * @param instanceTypes e.g. instance_types_transitive_en.ttl.gz
   */
  public void scanDbpediaTypes(File instanceTypes) {
    throw new RuntimeException("implement me");
  }
  
  public static String getMidFromMentionFile(File mentions) {
    String m = mentions.getParentFile().getName();
    assert m.startsWith("m.");
    return "/m/" + m.substring(2);
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    Log.info("starting...");
    
    List<File> mentions = FileUtil.find(new File("TODO"), "glob:**/dev/**/mentions.txt");
    DistSupSetup j = new DistSupSetup();
    for (File m : mentions) {
      String mid = getMidFromMentionFile(m);
      j.addMentions(mid, m);
    }
    
    File freebaseLinks = config.getExistingFile("freebaseLinks");
    j.addMid2Dbpedia(freebaseLinks);
    
    File infobox = config.getExistingFile("infobox");
    j.scanInfoboxFacts(infobox);
    
    File dbpediaTypes = config.getExistingFile("dbpediaTypes");
    j.scanDbpediaTypes(dbpediaTypes);
    
    Log.info("done");
  }

}
