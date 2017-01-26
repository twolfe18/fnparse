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
import edu.jhu.hlt.tutils.hash.Hash;

/**
 * Compactly represents an upper bound on the count of elements
 * where you are only allowed to increment counts.
 *
 * http://theory.stanford.edu/~tim/s15/l/l2.pdf
 * Note: This contains a good description of how to implement
 * epsilon-heavy-hitters on top of count-min-sketch, where you
 * maintain approximate counts for every item in the stream,
 * and promote HHs in and out of a heap (which I believe also
 * needs a key->location map/index).
 * 
 * Conservative updates (Estan and Varghese, 2002)
 * https://www.umiacs.umd.edu/~jags/pdfs/LSNLPsketchWAC10.pdf
 * Briefly: say an item hashes to counters with values {4,2,1}.
 * You would say that its sketch count is 1. With a normal
 * increment, you would update all the counters to {5,3,2}, but
 * this is un-necessary: you could just as well only update the
 * min to get to {4,2,2}.
 * 
 * @author travis
 */
public class CountMinSketch implements Serializable {
  private static final long serialVersionUID = -4304140822737269498L;

  // This is the seed for all the hash functions used by MaxMinSketch/CountMinSketch
  // Don't change this or else you will jumble all serialized data.
  public static final int SEED = 9001;

  protected int nhash;
  protected int logb;
  protected int[][] z;
  protected long ninc;
  protected boolean conservativeUpdates;
  
  /**
   * @param nHash higher values tighten probabilistic bound on relative error
   * @param logCountersPerHash higher values tighten bias (expected absolute error)
   * @param conservativeUpdates should basically almost always be true
   */
  public CountMinSketch(int nHash, int logCountersPerHash, boolean conservativeUpdates) {
    if (logCountersPerHash < 0)
      throw new IllegalAccessError();
    z = new int[nHash][1<<logCountersPerHash];
    logb = logCountersPerHash;
    nhash = nHash;
    ninc = 0;
    this.conservativeUpdates = conservativeUpdates;
    
    long bytes = 4 * nHash * (1L<<logCountersPerHash);
    Log.info("using " + (bytes/(1L<<20)) + " MB, requires " + (nhash*logb) + " bits of hash per element");
  }
  
  static int extractHash(int i, byte[] hasheBytes, int logb) {
    int hi = 0;
    int bitStart = logb * i;
    int bitStop = logb * (i+1);
    for (int j = bitStart; j < bitStop; j++) {
      byte b = hasheBytes[j/8];
      b = (byte) ((b >> (j%8)) & 1);
      hi = (hi<<1) + b;
    }
    return hi;
  }
  
  /**
   * @param hashes is the hash of this item, must have at least nHash*logCountersPerHash bits
   * @param increment is whether to increment the count of this item
   * @return the count of the hashed item, after incrementing (i.e. ++x not x++).
   */
  public int apply(byte[] hashes, boolean increment) {
    if (hashes.length*8 < nhash * logb)
      throw new IllegalArgumentException("hashes.length=" + hashes.length + " nhash=" + nhash + " logb=" + logb);
    int m = Integer.MAX_VALUE;
    for (int i = 0; i < nhash; i++) {
      int hi = extractHash(i, hashes, logb);
      if (increment && !conservativeUpdates)
        z[i][hi]++;
      m = Math.min(m, z[i][hi]);
    }
    if (increment && conservativeUpdates) {
      for (int i = 0; i < nhash; i++) {
        int hi = extractHash(i, hashes, logb);
        if (z[i][hi] == m)
          z[i][hi]++;
      }
    }
    if (increment)
      ninc++;
    return m;
  }
  
  public long numIncrements() {
    return ninc;
  }
  
  public int numIncrementsInt() {
    if (ninc > Integer.MAX_VALUE)
      throw new RuntimeException();
    return (int) ninc;
  }
  
  public int numHashFunctions() {
    return nhash;
  }
  
  public int logNumBuckets() {
    return logb;
  }
  
  public static class StringCountMinSketch extends CountMinSketch {
    private static final long serialVersionUID = 3346679142656988448L;

    private transient HashFunction hf;
    private transient Charset cs;

    public StringCountMinSketch(int nHash, int logCountersPerHash, boolean conservativeUpdates) {
      super(nHash, logCountersPerHash, conservativeUpdates);
//      hf = Hashing.goodFastHash(nhash * logb);
      hf = GuavaHashUtil.goodFastHash(nhash * logb, SEED);
    }

    public int apply(String item, boolean increment) {
      if (cs == null)
        cs = Charset.forName("UTF-8");
      if (hf == null) {
//        hf = Hashing.goodFastHash(nhash * logb);
        hf = GuavaHashUtil.goodFastHash(nhash * logb, SEED);
      }
      byte[] h = hf.hashString(item, cs).asBytes();
      return apply(h, increment);
    }
  }
  
  public static void main(String[] args) throws Exception {

    int nHash = 8;     // higher values tighten probabilistic bound on relative error
    int logCountersPerHash = 16;    // higher values tighten bias (expected absolute error)
    boolean conservativeUpdates = true;
    StringCountMinSketch cms = new StringCountMinSketch(nHash, logCountersPerHash, conservativeUpdates);
    Counts<String> exact = new Counts<>();

//    File f = new File("/tmp/english.txt");
    File f = new File("data/english.txt");
    Log.info("reading " + f.getPath());
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] toks = line.split("\\s+");
        for (int i = 0; i < toks.length; i++) {
          cms.apply(toks[i], true);
          exact.increment(toks[i]);
        }
      }
    }
    
    // Estimate error
    long tae = 0;
    long[] maeN = new long[10];
    double tre = 0;
    double[] mreN = new double[10];
    for (Entry<String, Integer> e : exact.entrySet()) {
      int ae = cms.apply(e.getKey(), false) - e.getValue();
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
      
      if (Hash.hash(e.getKey()) % 100 == 0) {
        System.out.printf("%-24s %.2f % 3d % 5d\n", e.getKey(), re, ae, e.getValue());
      }
    }

    System.out.printf("avgAbsErr=%.3f avgRelErr=%.3f\n",
        ((double) tae) / exact.numNonZero(), tre / exact.numNonZero());
    
    for (int i = 0; i < mreN.length; i++)
      System.out.printf("maxRelErr|c>%d\t%.3f\n", 1<<i, mreN[i]);

    for (int i = 0; i < mreN.length; i++)
      System.out.printf("maxAbsErr|c>%d\t% 3d\n", 1<<i, maeN[i]);
    
    Log.info("done");
  }
}
