package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;
import edu.jhu.util.MaxMinSketch.StringMaxMinSketch;

public class EntityFrequency {
  
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
    String[] a = line.split("\\s+");
    assert a.length == 2;
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
   * There are 438M entities in FACC1.
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
    
    public Builder(List<File> fs, int nShards, int nhash, int logb) {
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
            nl++;
            Pair<String, Integer> p = parseLine(line);
            int h = p.get1().hashCode();
            if (Math.floorMod(h, nShard) == curShard) {
              int oldCount = exactSome.getOrDefault(p.get1(), 0);
              exactSome.put(p.get1(), p.get2() + oldCount);
            }
            
            if (tm.enoughTimePassed(4)) {
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
      curShard++;
      return approxAll;
    }
  }
  
  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    File outputJser = config.getFile("outputJser");
    File countsRoot = config.getExistingDir("countsRoot");
    List<File> fs = FileUtil.find(countsRoot, "*.gz");
    int nShard = config.getInt("nShard", 16);   // 438M nLines (>nEnt) / 16 shards * (10 chars * 2 bytes/char + 4 bytes/int) * 1.5 = 940MB
    int nhash = config.getInt("nhash", 12);
    int logb = config.getInt("logb", 20);
    Builder b = new Builder(fs, nShard, nhash, logb);
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
