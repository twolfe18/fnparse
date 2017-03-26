package edu.jhu.hlt.entsum;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.entsum.DbpediaToken.Type;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.util.Alphabet;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;
import edu.jhu.util.HPair;

/**
 * Counts how often a pair of subject and object argument types are associated with a verb.
 * The simplest use of this is to only consider predicting a verb for a (subj,obj) pair if
 * each match a type which has been observed at training time with the given verb.
 *
 * @author travis
 */
public class ObservedArgTypes implements Serializable {
  private static final long serialVersionUID = -5747760176617798529L;
  
  interface PlausibleMemoizer {
    ObservedArgTypes getWrapped();
    List<Verb> plausibleVerbs(List<String> subjTypes, List<String> objTypes);
  }
  
  /**
   * Memoizes calls to plausibleVerbs by storing all (subjType, objType) -> verbs
   * and unioning over (subjType, objType) pairs.
   */
  public static class PlausibleMemoizerA implements PlausibleMemoizer {
    private Map<HPair<String, String>, List<Verb>> so2v;
    private ObservedArgTypes wrapped;
    public PlausibleMemoizerA(ObservedArgTypes wrapped) {
      this.so2v = new HashMap<>();
      this.wrapped = wrapped;
    }
    @Override
    public ObservedArgTypes getWrapped() {
      return wrapped;
    }
    @Override
    public List<Verb> plausibleVerbs(List<String> subjTypes, List<String> objTypes) {
      BitSet uniq = new BitSet();
      List<Verb> out = new ArrayList<>();
      for (String st : subjTypes) {
        for (String ot : objTypes) {
          HPair<String, String> so = new HPair<>(st, ot);
          List<Verb> vs = plausibleHelper(so);
          for (Verb v : vs) {
            if (!uniq.get(v.verbIdx))
              continue;
            uniq.set(v.verbIdx);
            out.add(v);
          }
        }
      }
      return out;
    }
    List<Verb> plausibleHelper(HPair<String, String> so) {
      List<Verb> vs = so2v.get(so);
      if (vs == null) {
        vs = wrapped.plausibleVerbs(Arrays.asList(so.left), Arrays.asList(so.right));
        so2v.put(so, vs);
        if (so2v.size() % 1000 == 0)
          Log.info("so2v.size=" + so2v.size());
      }
      return vs;
    }
  }

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
  
  public Alphabet<String> getVerbs() {
    return verbs;
  }
  
  public int numObservations() {
    return svo.numIncrementsInt();
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
    List<Verb> out = null;
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
            if (out == null)
              out = new ArrayList<>();
            out.add(verb);
            continue verbs;
          }
        }
      }
    }
    if (out == null)
      out = Collections.emptyList();
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
