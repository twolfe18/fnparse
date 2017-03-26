package edu.jhu.hlt.entsum;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.entsum.DbpediaToken.Type;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.util.Alphabet;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;

/**
 * Counts how often a pair of subject and object argument types are associated with a verb.
 * The simplest use of this is to only consider predicting a verb for a (subj,obj) pair if
 * each match a type which has been observed at training time with the given verb.
 *
 * @author travis
 */
public class ObservedArgTypes implements Serializable {
  private static final long serialVersionUID = -5747760176617798529L;

  private StringCountMinSketch svo;
  private StringCountMinSketch sv;
  private StringCountMinSketch vo;
  private Alphabet<String> verbs;
  
  public ObservedArgTypes(int nhash, int logb) {
    this.svo = new StringCountMinSketch(nhash, logb, true);
    this.sv = new StringCountMinSketch(nhash, logb, true);
    this.vo = new StringCountMinSketch(nhash, logb, true);
    this.verbs = new Alphabet<>();
  }
  
  public void add(List<String> subjTypes, List<String> objTypes, String v) {
    verbs.lookupIndex(v);
    for (String s : subjTypes)
      for (String o : objTypes)
        svo.apply(svoKey(s, v, o), true);
    for (String s : subjTypes)
      sv.apply(svKey(s, v), true);
    for (String o : objTypes)
      vo.apply(voKey(v, o), true);
  }
  
  public void addAll(EntityTypes entityTypes, File facts) throws IOException {
    if (!facts.isFile()) {
      Log.info("WARNING: not a file: " + facts.getPath());
      return;
    }
    int nf = 0, nl = 0;
    boolean keepLines = false;
    try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(facts, keepLines)) {
      while (iter.hasNext()) {
        DbpediaTtl x = iter.next();
        nl++;
        if (x.subject().type != Type.DBPEDIA_ENTITY)
          continue;
        if (x.object().type != Type.DBPEDIA_ENTITY)
          continue;
        nf++;
        List<String> st = entityTypes.typesForDbp(x.subject().getValue());
        List<String> ot = entityTypes.typesForDbp(x.object().getValue());
        add(st, ot, x.verb().getValue());
      }
    }
    Log.info("nLines=" + nl + " nFacts=" + nf + " facts=" + facts.getPath());
  }
  
  public static class Verb {
    public final String verb;
    public final int verbIdx;
    public final int svoCount;
    public final int svCount;
    public final int voCount;
    public Verb(String verb, int verbIdx, int svoCount, int svCount, int voCount) {
      this.verb = verb;
      this.verbIdx = verbIdx;
      this.svoCount = svoCount;
      this.svCount = svCount;
      this.voCount = voCount;
    }
    public int totalCount() {
      return svoCount + svCount + voCount;
    }
  }
  
  static String svoKey(String s, String v, String o) {
    return "t=svo_s=" + s + "_v=" + v + "_o=" + o;
  }
  static String svKey(String s, String v) {
    return "t=sv_s=" + s + "_v=" + v;
  }
  static String voKey(String v, String o) {
    return "t=vo_v=" + v + "_o=" + o;
  }
  
  public List<Verb> plausibleVerbs(List<String> subjTypes, List<String> objTypes) {
    List<Verb> out = new ArrayList<>();
    int n = verbs.size();
    verbs:
    for (int i = 0; i < n; i++) {
      String v = verbs.lookupObject(i);
      for (String s : subjTypes) {
        for (String o : objTypes) {
          Verb verb = new Verb(v, i,
              svo.apply(svoKey(s, v, o), false),
              sv.apply(svKey(s, v), false),
              vo.apply(voKey(v, o), false));
          if (verb.totalCount() > 0) {
            out.add(verb);
            continue verbs;
          }
        }
      }
    }
    return out;
  }
  
  // TODO Iterate over every ($ENTITY/facts-rel1-types.txt, $ENTITY/entity-types-rel1.txt) pair and add to this instance
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    File output = config.getFile("output");
    FileUtil.VERBOSE = true;
    File entityDirParent = config.getExistingDir("entityDirParent");
    String entityDirGlob = config.getString("entityDirGlob", "glob:**/entity-types-rel1.txt");
    List<File> fs = FileUtil.find(entityDirParent, entityDirGlob);
    Log.info("found " + fs.size() + " entity directories");
    int nhash = config.getInt("nhash", 10);
    int logb = config.getInt("logb", 20);
    TimeMarker tm = new TimeMarker();
    ObservedArgTypes oat = new ObservedArgTypes(nhash, logb);
    Counts<String> ec = new Counts<>();
    for (File f : fs) {
      ec.increment("file");

      File entityDir = f.getParentFile();
      EntityTypes ts = new EntityTypes(entityDir);
      oat.addAll(ts, new File(entityDir, "facts-rel0-types.txt"));
//      oat.addAll(ts, new File(entityDir, "facts-rel1-types.txt"));

      if (tm.enoughTimePassed(5)) {
        Log.info(ec);
        if (tm.enoughTimePassed(2 * 60))
          FileUtil.serialize(oat, output);
      }
    }
    FileUtil.serialize(oat, output);
  }
}
