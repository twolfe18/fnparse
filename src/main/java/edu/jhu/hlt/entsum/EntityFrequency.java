package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.MaxMinSketch.StringMaxMinSketch;

/**
 * @deprecated Not needed, use {@link CluewebLinkedPreprocess.EntCounts} instead!
 *
 * @author travis
 */
public class EntityFrequency implements Serializable {
  private static final long serialVersionUID = -8500795613001170677L;

  // Keys are mid strings like "/m/02b17t"
  private StringMaxMinSketch midFreq;
  
  public EntityFrequency(int nhash, int logb) {
    midFreq = new StringMaxMinSketch(nhash, logb);
  }
  
  public void put(String mid, int count) {
    midFreq.update(mid, count);
  }

  public int getCount(String mid) {
    return midFreq.getUpperBoundOnCount(mid);
  }
  
  public static Pair<String, Integer> parseLine(String line) {
    String[] a = line.trim().split("\\s+");
    if (a.length != 2) {
      System.out.println("warning, bad line: " + line);
      return null;
    }
//    assert a.length == 2 : "line=" + line;
    int count = Integer.parseUnsignedInt(a[0]);
    return new Pair<>(a[1], count);
  }
  
  /**
   * Makes n passes across count files, each pass only stores strings in the i^th shard,
   * after each pass put those counts into the sketch and clear the Map<String, Integer>.
   *
   * Implements an iterator which returns a pointer to the {@link EntityFrequency} after
   * all mids from shard i have been added. The last one returned is complete.
   *
   * There are 438M lines of entity counts in FACC1 (real cardinality depends on mention overlap between files).
   * If each one only took one byte (mids are at least 5, counts another 1-4), that would still be too much.
   * One option is to filter to only the relevant entities, but this requires a pass to figure out what those are, may be too big anyway.
   * Other option is count-min-sketch route.
   */
  static class Builder implements Iterator<EntityFrequency> {
    EntityFrequency approxAll;
    Map<String, Integer> exactSome;
    int curShard;
    int nShard;
    List<File> files;
    
    // Keep an exact count for the dev entities, tells you how bad the approximation is
    Map<String, Integer> debugMidFreqExact;
    Map<String, String> debugMid2Tag;
    void buildDebugMidSet() {
      debugMid2Tag = new HashMap<>();
      debugMidFreqExact = new HashMap<>();
      for (Pair<String, String> p : debugMids()) {
        String tag = p.get1();
        String mid = p.get2();
        Object old = debugMid2Tag.put(mid, tag);
        assert old == null;
        debugMidFreqExact.put(mid, 0);
      }
      Log.info("storing exact counts for " + debugMidFreqExact.size() + " mids");
    }
    public List<Feat> getDebugCountErrors() {
      List<Feat> err = new ArrayList<>();
      for (String mid : debugMid2Tag.keySet()) {
        int ce = debugMidFreqExact.get(mid);
        int ca = approxAll.getCount(mid);
//        String tag = debugMid2Tag.get(mid);
//        err.add(new Feat(tag + "-" + mid, ce - ca));
        err.add(new Feat(mid, ce - ca));
      }
      Collections.sort(err, Feat.BY_SCORE_MAG_DESC);
      return err;
    }
    
    public Builder(List<File> fs, int nShards, int nhash, int logb) {
      Log.info("nFiles=" + fs.size() + " nShard=" + nShards);
      this.curShard = 0;
      this.nShard = nShards;
      this.files = fs;
      this.curShard = 0;
      this.approxAll = new EntityFrequency(nhash, logb);
      this.exactSome = new HashMap<>();
    }

    @Override
    public boolean hasNext() {
      return curShard < nShard;
    }

    @Override
    public EntityFrequency next() {
      Log.info("scanning " + files.size() + " files");
      exactSome.clear();
      TimeMarker tm = new TimeMarker();
      int nf = 0, nl = 0;
      for (File f : files) {
        nf++;
        try (BufferedReader r = FileUtil.getReader(f)) {
          for (String line = r.readLine(); line != null; line = r.readLine()) {
            Pair<String, Integer> p = parseLine(line);
            if (p == null)
              continue;
            nl++;
            int h = p.get1().hashCode();
            if (Math.floorMod(h, nShard) == curShard) {
              int oldCount = exactSome.getOrDefault(p.get1(), 0);
              exactSome.put(p.get1(), p.get2() + oldCount);
            }
            
            if (debugMidFreqExact != null) {
              Integer ce = debugMidFreqExact.get(p.get1());
              if (ce != null)
                debugMidFreqExact.put(p.get1(), ce + p.get2());
            }
            
            if (tm.enoughTimePassed(5)) {
              Log.info("nf=" + nf + " nl=" + nl + " exact.size=" + exactSome.size() + " curFile=" + f.getPath() + "\t" + Describe.memoryUsage());
            }
          }
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      Log.info("adding " + exactSome.size() + " mids to the max-min sketch\t" + Describe.memoryUsage());
      for (Entry<String, Integer> e : exactSome.entrySet())
        approxAll.put(e.getKey(), e.getValue());
      exactSome.clear();
      
      if (debugMidFreqExact != null) {
        Log.info("top 10 errors: ");
        List<Feat> errAll = getDebugCountErrors();
        int i = 0, te = 0;
        for (Feat m : errAll) {
          int diff = (int) m.getWeight();
          if (i < 10) {
            String mid = m.getName();
            String tag = debugMid2Tag.get(mid);
            int actual = debugMidFreqExact.get(mid);
            int approx = approxAll.getCount(mid);
            System.out.printf("t=%-12s  m=%-12s  actual=% 6d  approx=% 6d  diff=% 4d\n",
                tag, mid, actual, approx, diff);
          }
          i++;
          te += diff;
        }
        Log.info("average diff: " + (((double) te)/errAll.size()));
      }
      
      curShard++;
      return approxAll;
    }
  }
  
  public static List<Pair<String, String>> debugMids() {
//    File p = new File("/export/projects/twolfe/entity-summarization/clueweb-linked");
    File p = new File("data/facc1-entsum");
    if (!p.isDirectory()) {
      Log.info("WARNING: not a dir, skipping debug: " + p.getPath());
      return Collections.emptyList();
    }
    List<Pair<String, String>> all = new ArrayList<>();
    for (int i = 7; i >= 3; i--) {
      String tag = "rare" + i + "/dev";
      File f = new File(p, "train-dev-test/rare" + i + "/mids.dev.txt");
      if (!f.isFile()) {
        Log.info("WARNING: wrong path? " + f.getPath());
        continue;
      }
      for (String mid : FileUtil.getLines(f))
        all.add(new Pair<>(tag, mid));
    }
    return all;
  }
  
  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    File outputJser = config.getFile("outputJser");
    Log.info("outputJser=" + outputJser.getPath());
    File countsRoot = config.getExistingDir("countsRoot");
    Log.info("countsRoot=" + countsRoot.getPath());
    String countsGlob = config.getString("countsGlob", "glob:**/*.txt");
    List<File> fs = FileUtil.find(countsRoot, countsGlob);
    
    fs = ReservoirSample.sample(fs, 500, new Random(9001));

    int nShard = config.getInt("nShard", 16);   // 438M nLines (>nEnt) / 16 shards * (10 chars * 2 bytes/char + 4 bytes/int) * 1.5 = 940MB
    int nhash = config.getInt("nhash", 12);
    int logb = config.getInt("logb", 20);
    Builder b = new Builder(fs, nShard, nhash, logb);
    
    if (config.getBoolean("debug", true)) {
      b.buildDebugMidSet();
    }
    
    int iter = 0;
    while (b.hasNext()) {
      EntityFrequency c = b.next();
      iter++;
      Log.info("after " + iter + "th shard, saving to " + outputJser.getPath());
      FileUtil.serialize(c, outputJser);
    }
    Log.info("done");
  }
}
