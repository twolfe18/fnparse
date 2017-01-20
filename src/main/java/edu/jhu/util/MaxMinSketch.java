package edu.jhu.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.Map.Entry;

import com.google.common.hash.HashFunction;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.hash.GuavaHashUtil;

/**
 * Given a whole bunch of (key, count) pairs, build a data structure
 * which supports an upperBoundOnCount(key) operation. The difference
 * between this and count-min sketch is that for this you are only
 * allowed to set the count of a key (once) rather than increment it.
 * 
 * You can think of the max-min sketch as an improvement on the max sketch.
 * A max sketch has b buckets and ensures that the entry in the i^th
 * bucket is set to max_{key : hash(key) % b == i} count(key).
 * Going from max to max-min is just like going from count to count-min:
 * take the min over a bunch of max sketches. Each max sketch ensures
 * that returned values are upper bounds, and the max-min sketch preserves
 * and tightens this guarantee.
 * 
 * When you update the max-min sketch, or ensemble of max sketches, you
 * only need to update the max sketches/rows which do not satisfy the
 * upper bound for the given (key, count), which is guaranteed to be less
 * than or equal to all of the max sketches.
 *
 * @author travis
 */
public class MaxMinSketch implements Serializable {
  private static final long serialVersionUID = -4920425819626600641L;

  // This is the seed for all the hash functions used by MaxMinSketch/CountMinSketch
  // Don't change this or else you will jumble all serialized data.
  public static final int SEED = 9001;

  private final int[][] z;
  protected final int logb;   // log2(numberOfBucketsPerSketch)
  private long nkey;
  
  public MaxMinSketch(int numSketches, int logNumberOfBucketsPerSketch) {
    int b = 1<<logNumberOfBucketsPerSketch;
    this.logb = logNumberOfBucketsPerSketch;
    this.z = new int[numSketches][b];
    this.nkey = 0;
//    Log.info("using " + (getNumberOfBytes()/(1L<<20)) + " MB");
  }
  
  public long getNumberOfKeys() {
    return nkey;
  }
  
  public long getNumberOfBytes() {
    return getNumberOfSketches() * (getNumberOfBucketsPerSketch() * 4L);
  }
  
  public int getNumberOfSketches() {
    return z.length;
  }
  
  public int getNumberOfBucketsPerSketch() {
    return z[0].length;
  }
  
  public void update(byte[] keyHash, int count) {
    int nhash = getNumberOfSketches();
    if (keyHash.length*8 < nhash * logb)
      throw new IllegalArgumentException("hashes.length=" + keyHash.length + " nhash=" + nhash + " logb=" + logb);
    for (int i = 0; i < nhash; i++) {
      int hi = CountMinSketch.extractHash(i, keyHash, logb);
      z[i][hi] = Math.max(z[i][hi], count);
    }
    nkey++;
  }
  
  public int getUpperBoundOnCount(byte[] keyHash) {
    int nhash = getNumberOfSketches();
    int min = Integer.MAX_VALUE;
    for (int i = 0; i < nhash; i++) {
      int hi = CountMinSketch.extractHash(i, keyHash, logb);
      min = Math.min(min, z[i][hi]);
    }
    return min;
  }

  public static class StringMaxMinSketch extends MaxMinSketch {
    private static final long serialVersionUID = 3346679142656988448L;

    private transient HashFunction hf;
    private transient Charset cs;

    public StringMaxMinSketch(int nHash, int logCountersPerHash) {
      super(nHash, logCountersPerHash);
//      hf = Hashing.goodFastHash(nHash * logb);
      hf = GuavaHashUtil.goodFastHash(nHash * logb, SEED);
    }
    
    public byte[] getHashes(String key) {
      if (cs == null)
        cs = Charset.forName("UTF-8");
      if (hf == null) {
        int nhash = getNumberOfSketches();
//        hf = Hashing.goodFastHash(nhash * logb);
        hf = GuavaHashUtil.goodFastHash(nhash * logb, SEED);
      }
      return hf.hashString(key, cs).asBytes();
    }

    public void update(String item, int count) {
      update(getHashes(item), count);
    }
    
    public int getUpperBoundOnCount(String key) {
      return getUpperBoundOnCount(getHashes(key));
    }
  }

  public static void main(String[] args) throws Exception {

    Counts<String> exact = new Counts<>();
    File f = new File("data/english.txt");
    Log.info("reading " + f.getPath());
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] toks = line.split("\\s+");
        for (int i = 0; i < toks.length; i++)
          exact.increment(toks[i]);
      }
    }

    for (int logCountersPerHash = 8; logCountersPerHash <= 22; logCountersPerHash += 2) {
      for (int nHash = 1; nHash <= 16; nHash*=2) {
        //    int nHash = 8;     // higher values tighten probabilistic bound on relative error
        //      int logCountersPerHash = 12;    // higher values tighten bias (expected absolute error)

        StringMaxMinSketch cms = new StringMaxMinSketch(nHash, logCountersPerHash);
        if (cms.getNumberOfBytes() > 2 * (1L<<20))  // too big to matter
          continue;

        // Add to sketch (order might matter)
        for (String key : exact.getKeysSorted())
          cms.update(key, exact.getCount(key));
        //    for (String key : exact.getKeysSortedByCount(false))    // worst ordering
        //      cms.update(key, exact.getCount(key));
        //    for (String key : exact.getKeysSortedByCount(true))     // optimal ordering
        //      cms.update(key, exact.getCount(key));

        // Estimate error
        long tae = 0;
        long[] maeN = new long[10];
        double tre = 0;
        //      double[] mreN = new double[10];
        double[] mreN = new double[4];
        for (Entry<String, Integer> e : exact.entrySet()) {
          int ae = cms.getUpperBoundOnCount(e.getKey()) - e.getValue();
          assert ae >= 0;
          tae += ae;
          double re = ae / ((double) e.getValue());
          tre += re;

          for (int i = 0; i < mreN.length; i++) {
            int thresh = 1<<i;
            if (e.getValue() <= thresh)
              break;
            mreN[i] = Math.max(mreN[i], re);
            maeN[i] = Math.max(maeN[i], ae);
          }

          //        if (Hash.hash(e.getKey()) % 100 == 0) {
          //          System.out.printf("%-24s %.2f % 3d % 5d\n", e.getKey(), re, ae, e.getValue());
          //        }
        }

        Log.info("nHash=" + nHash + " logCountersPerHash=" + logCountersPerHash + " size=" + (cms.getNumberOfBytes()/(1L<<20)) + "MB");
        System.out.printf("avgAbsErr=%.3f avgRelErr=%.3f\n",
            ((double) tae) / exact.numNonZero(), tre / exact.numNonZero());

//        for (int i = 0; i < mreN.length; i++)
//          System.out.printf("maxRelErr|c>%d\t%.3f\n", 1<<i, mreN[i]);

        for (int i = 0; i < mreN.length; i++)
          System.out.printf("maxAbsErr|c>%d\t% 3d\n", 1<<i, maeN[i]);
      }
    }
    Log.info("done");
  }
}
