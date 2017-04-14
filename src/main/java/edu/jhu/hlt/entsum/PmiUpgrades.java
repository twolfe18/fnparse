package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;
import com.google.common.hash.PrimitiveSink;

import edu.jhu.hlt.entsum.PmiFeatureSelection.LabeledPmi;
import edu.jhu.hlt.entsum.PmiFeatureSelection.Pmi;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.MultiTimer.TB;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.set.IntHashSet;
import edu.jhu.prim.vector.IntIntHashVector;
import edu.jhu.util.Alphabet;

public class PmiUpgrades {
  
  /**
   * Iterator over *.vw files which should be used for PMI computation.
   * 
   * To give an idea of scale:
   * rare4:
   *   pos: 17k files, 1.4G
   *   neg: 3.4k files, 12G
   * rare3:
   *   pos: 41k files, 13G
   *   neg: 3.5k files, 97G
   */
  static class DataSource implements Iterator<File> {
//    private File inputRoot;   // parent of pos-*.vw and neg.vw files
    private File outputDir;   // where to write output
    private Deque<File> posF;
    private Deque<File> negF;
    private int nPos, nNeg;
    
    public DataSource(File inputRoot, File outputDir) throws Exception {
//      this.inputRoot = inputRoot;
      this.outputDir = outputDir;
      List<File> ibs = FileUtil.execFind(inputRoot,
          "-path", "*train*infobox-binary*",
          "-not", "-path", "*entsum-data*",
          "-name", "*.vw",
          "-type", "f");
      
      
      // DEBUG
//      ibs = ReservoirSample.sample(ibs, 400, new Random(9001));
      
      
      Collections.shuffle(ibs, new Random(ibs.hashCode()));
      posF = new ArrayDeque<>();
      negF = new ArrayDeque<>();
      for (File f : ibs) {
        String rel = getRelation(f);
        if (rel == null)
          negF.push(f);
        else
          posF.push(f);
      }
      nPos = posF.size();
      nNeg = negF.size();
      Log.info("files: nPos=" + nPos + " nNeg=" + nNeg);
    }
    
    public int negDone() {
      return nNeg - negF.size();
    }
    public int posDone() {
      return nPos - posF.size();
    }
    
    public File saveTo() {
      return saveTo(null);
    }
    public File saveTo(String tag) {
      int nPosEmit = nPos - posF.size();
      int nNegEmit = nNeg - negF.size();
      if (tag != null)
        return new File(outputDir, "prelim-" + tag + "-pos" + nPosEmit + "-neg" + nNegEmit + ".pmi");
      return new File(outputDir, "prelim-pos" + nPosEmit + "-neg" + nNegEmit + ".pmi");
    }

    /**
     * returns a file where you should save PMIs to after reading
     * all the files for which next() has been called on.
     */
    public File maybeSaveTo() {
      if (posF.isEmpty() && negF.isEmpty())
        return new File(outputDir, "final.pmi");
      int nPosEmit = nPos - posF.size();
      int nNegEmit = nNeg - negF.size();
      if (nNegEmit == 0 && nPosEmit >= 8 && isPosPowerOfTwo(nPosEmit)) {
        return saveTo();
      } else if (nNegEmit >= 4 && isPosPowerOfTwo(nNegEmit)) {
        return saveTo();
      }
      return null;
    }

    @Override
    public boolean hasNext() {
      if (!posF.isEmpty())
        return true;
      if (!negF.isEmpty())
        return true;
      return false;
    }

    @Override
    public File next() {
      if (!posF.isEmpty())
        return posF.pop();
      if (!negF.isEmpty())
        return negF.pop();
      return null;
    }
  }
    
  public static boolean isPosPowerOfTwo(int n) {
    return (n & (n - 1)) == 0;
  }

  /*
   * Before:
   * - shard fx
   * - store inverted indices for shard(fx) [large] and fy [relatively small]
   * 
   * => Do I need to store the LOCATION of negative features?
   *    I believe no, since we only ask for PMI(y_{pos}, x), so cx = cx_{pos} + cx_{neg}
   *    cyx is found by intersecting inverted indices
   *    cx_{pos} is the length of an inverted index build on y_{pos} instances
   *    cx_{neg} can just be a count!
   */
  /**
   * Pseudo-memory efficient (but pseudo-slow) exact method for computing PMI.
   * Does this by storing inverted indices of instances for features and labels,
   * computing the joint counts by intersection on the fly.
   * You can shard x (features) to distribute this computation across multiple instances,
   * i.e. PMI(Y,X) = computeOnSeparateMachines([PMI(y_i,X) for y_I in Y])
   * You can also shard the positive values in Y.
   * 
   * Does not require that x or y indices be dense (e.g. you could use a hash function
   * or only populate with a slice of dense indices).
   */
  public static class PosNegPmi {
    // Location (instance indices) of feature extracted from positive instances
    private IntObjectHashMap<IntArrayList> y2posI;
    private IntObjectHashMap<IntArrayList> x2posI;
    // Counts of features on negative instances
    private IntIntHashVector x2negI;
    // Total number of instances
    private int n;
    
    private BloomFilter<IntPair> obsPosYX;
    private int obsPosYXSize;
    
    public PosNegPmi() {
      y2posI = new IntObjectHashMap<>();
      x2posI = new IntObjectHashMap<>();
      x2negI = new IntIntHashVector();
      obsPosYXSize = 4096;
      obsPosYX = BloomFilter.create(new IntPairFunnel(), obsPosYXSize);
    }
    
    static IntArrayList getOrNew(int key, IntObjectHashMap<IntArrayList> map) {
      IntArrayList val = map.get(key);
      if (val == null) {
        val = new IntArrayList();
        map.put(key, val);
      }
      return val;
    }
    
    /** ys and xs should either be sorted or uniq */
    public void addPos(IntArrayList ys, IntArrayList xs) {
      int instance = n++;
      int ny = ys.size();
      for (int i = 0; i < ny; i++) {
        if (i > 0 && ys.get(i) == ys.get(i-1))
          continue;
        getOrNew(ys.get(i), y2posI).add(instance);
      }
      int nx = xs.size();
      for (int i = 0; i < nx; i++) {
        if (i > 0 && xs.get(i) == xs.get(i-1))
          continue;
        getOrNew(xs.get(i), x2posI).add(instance);
      }
      
      
      // Update bloom filter for observed features
      for (int i = 0; i < ny; i++) {
        if (i > 0 && ys.get(i) == ys.get(i-1))
          continue;
        for (int j = 0; j < nx; j++) {
          if (j > 0 && xs.get(j) == xs.get(j-1))
            continue;
          
          if (obsPosYX.expectedFpp() > 0.0001d) {
            obsPosYXSize *= 2;
            Log.info("allocating yx bloom filter with size=" + obsPosYXSize);
            // TODO This technically could be a bug: I should be copying over the
            // values in the old set to the new one. Otherwise we could get a false
            // negative and have a broken optimization on what to skip over.
            obsPosYX = BloomFilter.create(new IntPairFunnel(), obsPosYXSize);
          }
          obsPosYX.put(new IntPair(ys.get(i), xs.get(j)));
        }
      }
    }
    
    /** xs should either be sorted or uniq */
    public void addNeg(IntArrayList xs) {
      n++;
      int nx = xs.size();
      for (int i = 0; i < nx; i++) {
        if (i > 0 && xs.get(i) == xs.get(i-1))
          continue;
        x2negI.add(xs.get(i), 1);
      }
    }
    
    public Pmi getPmi(int y, int x) {
      IntArrayList iy = y2posI.get(y);
      IntArrayList ix = x2posI.get(x);
      return getPmi(y, iy, x, ix);
    }
    
    public Pmi getPmi(int y, IntArrayList iy, int x, IntArrayList ix) {
      IntArrayList iyx = PmiFeatureSelection.intersectSortedLists(iy, ix);
      if (iyx.size() == 0)
        return null;
      int cx = ix.size() + x2negI.getWithDefault(x, 0);
      double num = ((double) n) * iyx.size();
      double denom = ((double) iy.size()) * cx;
      double pmi = Math.log(num / denom);
      if (Double.isNaN(pmi)) {
        Log.info("wat: n=" + n
            + " iyx.size=" + iyx.size()
            + " iy.size=" + iy.size()
            + " ix.size=" + ix.size()
            + " cx=" + cx
            + " num=" + num
            + " denom=" + denom
            + " frac=" + (num/denom));
        return null;
      }
      return new Pmi(y, x, pmi, iyx.size());
    }
    
    public List<Pmi> pmiForLabel(int y) {
      IntArrayList iy = y2posI.get(y);
      if (iy == null)
        return Collections.emptyList();
      List<Pmi> a = new ArrayList<>();
      IntObjectHashMap<IntArrayList>.Iterator iter = x2posI.iterator();
      int keep = 0, skip = 0;
      while (iter.hasNext()) {
        iter.advance();
        int x = iter.key();
        IntPair yx = new IntPair(y, x);
        if (!obsPosYX.mightContain(yx)) {
          skip++;
          continue;
        }
        keep++;
        IntArrayList ix = iter.value();
        if (ix == null)
          continue;
        Pmi p = getPmi(y, iy, x, ix);
        if (p != null)
          a.add(p);
      }
      System.out.println("y=" + y
          + " ny=" + y2posI.size()
          + " iy.size=" + iy.size()
          + " skip=" + skip
          + " keep=" + keep
          + " keepAndGood=" + a.size());
      Collections.sort(a, Pmi.BY_PMI_DESC);
      return a;
    }
  }
  
  static class IntPairFunnel implements Funnel<IntPair>, Serializable {
    private static final long serialVersionUID = 6464473636624588140L;
    @Override
    public void funnel(IntPair from, PrimitiveSink into) {
      into.putInt(from.first);
      into.putInt(from.second);
    }
  }

  /**
   * Maps from strings to ints.
   */
  static class DumbAdapter {
    private Alphabet<String> alphY;
    private Alphabet<String> alphX;
    private PosNegPmi pmi;
    private Shard shard;
    private boolean linesAsInstances;
    
    public DumbAdapter(Shard shard, boolean linesAsInstances) {
      Log.info("shard=" + shard);
      alphY = new Alphabet<>();
      alphX = new Alphabet<>();
      pmi = new PosNegPmi();
      this.shard = shard;
      this.linesAsInstances = linesAsInstances;
    }
    
    static void addAll(IntArrayList x, IntHashSet ux, IntArrayList lx) {
      
    }
    
    public void addPos(String label, File vwFeatures) throws IOException {
      Log.info("label=" + label + " vwFeatures=" + vwFeatures);
      IntArrayList ys = new IntArrayList();
      ys.add(alphY.lookupIndex(label));
      IntHashSet ux = new IntHashSet();
      IntArrayList lx = new IntArrayList();
      for (InstIter i = new InstIter(vwFeatures); i.cur() != null; i.advance()) {
        if (linesAsInstances) {
          pmi.addPos(ys, i.cur());
        } else {
          addAll(i.cur(), ux, lx);
        }
      }
      if (!linesAsInstances)
        pmi.addPos(ys, lx);
    }
    
    public void addNeg(File vwFeatures) throws IOException {
      Log.info("vwFeatures=" + vwFeatures);
      IntHashSet ux = new IntHashSet();
      IntArrayList lx = new IntArrayList();
      for (InstIter i = new InstIter(vwFeatures); i.cur() != null; i.advance()) {
        if (linesAsInstances) {
          pmi.addNeg(i.cur());
        } else {
          addAll(i.cur(), ux, lx);
        }
      }
      if (!linesAsInstances)
        pmi.addNeg(lx);
    }
    
    public List<LabeledPmi<String, String>> getPmi(int y) {
      String ys = alphY.lookupObject(y);
      List<LabeledPmi<String, String>> a = new ArrayList<>();
      for (Pmi p : pmi.pmiForLabel(y)) {
        assert p.labelIdx == y;
        String xs = alphX.lookupObject(p.featureIdx);
        a.add(new LabeledPmi<>(ys, y, xs, p.featureIdx, p.pmi, p.nCooc));
      }
      return a;
    }

    public void writeoutPmi(File dest, int k, double pmiFreqDiscount) throws IOException {
      try (BufferedWriter w = FileUtil.getWriter(dest)) {
        int n = alphY.size();
        for (int y = 0; y < n; y++) {
          List<LabeledPmi<String, String>> pmi = getPmi(y);
          int kk = Math.min(k, pmi.size());
          for (int i = 0; i < kk; i++) {
            Pmi feat = pmi.get(i);
            w.write(alphY.lookupObject(feat.labelIdx));
            w.write('\t');
            w.write(alphX.lookupObject(feat.featureIdx));
            w.write('\t');
            w.write("" + feat.pmi);
            w.write('\t');
            w.write("" + feat.getFrequencyDiscountedPmi(pmiFreqDiscount));
            w.write('\t');
            w.write("" + feat.nCooc);
            w.write('\t');
            w.write("" + n);
            w.newLine();
          }
        }
      }
    }
    
    private class InstIter {
      private BufferedReader r;
      private List<String> fx;
      private IntArrayList fxi;
      
      public InstIter(File f) throws IOException {
        r = FileUtil.getReader(f);
        fx = new ArrayList<>();
        fxi = new IntArrayList();
        advance();
      }
      
      void advance() throws IOException {
        String line = r.readLine();
        if (line == null) {
          fxi = null;
          return;
        }
        VwLine vw = new VwLine(line);
        fx.clear();
        vw.extractAllFeatures(fx);

        // Take features from this shard, lookup ints
        fxi.clear();
        for (String f : fx)
          if (shard.matches(f.hashCode()))
            fxi.add(alphX.lookupIndex(f));

        // Add to pmi
        fxi.sortAsc();
      }
      
      public IntArrayList cur() {
        return fxi;
      }
    }
  }

  public static String getRelation(File f) {
    if (f.getName().equals("neg.vw")) {
      return null;
    } else {
      String pre = "pos-";
      String suf = ".vw";
      assert f.getName().startsWith(pre) && f.getName().endsWith(suf) : "f=" + f.getName();
      return f.getName().substring(pre.length(), f.getName().length()-suf.length());
    }
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    File entityDirParent = config.getExistingDir("entityDirParent");
    File output = config.getOrMakeDir("output");
    DataSource ds = new DataSource(entityDirParent, output);
    
    double pmiFreqDiscount = config.getDouble("pmiFreqDiscount", 2d);
    Log.info("pmiFreqDiscount=" + pmiFreqDiscount);
    
    int topFeats = config.getInt("topFeats", 300);
    Log.info("topFeats=" + topFeats);

    boolean extractionsAsInstances = config.getBoolean("extractionsAsInstances", true);
    Log.info("extractionsAsInstances=" + extractionsAsInstances);
    
    Shard shard = config.getShard();
    DumbAdapter a = new DumbAdapter(shard, extractionsAsInstances);
    
//    Timer ta = new Timer();
//    Timer tw = new Timer();
//    int nw = 0;
    
    Counts<String> c = new Counts<>();
    while (ds.hasNext()) {
      File f = ds.next();
      String rel = getRelation(f);

      if (ds.posF.isEmpty() && ds.negDone() == 1)
        a.writeoutPmi(new File(output, "after-pos.pmi"), topFeats, pmiFreqDiscount);

      c.increment(rel == null ? "neg" : rel);
//      ta.start();
      if (rel == null) {
        a.addNeg(f);
      } else {
        a.addPos(rel, f);
      }
//      ta.stop();
      
      Log.info("nPos=" + ds.nPos
          + " posDone=" + ds.posDone()
          + " nNeg=" + ds.nNeg
          + " negDone=" + ds.negDone()
          + "\t" + Describe.memoryUsage());
      
      // "Number of files" based
//      File s = ds.maybeSaveTo();
//      if (s != null) {
//        Log.info("saving " + topFeats + " features to " + s.getPath());
//        System.out.println(c);
//        System.out.println(Describe.memoryUsage());
//        a.writeoutPmi(s, topFeats, pmiFreqDiscount);
//      }
      
//      // Time based
//      if (ta.totalTimeInSeconds() / (tw.totalTimeInSeconds()+1d) > 3) {
//        String tag = "w" + (nw++);
//        File s = ds.saveTo(tag);
//        Log.info("saving " + topFeats + " features to " + s.getPath());
//        System.out.println(c);
//        System.out.println(Describe.memoryUsage());
//        tw.start();
//        a.writeoutPmi(s, topFeats, pmiFreqDiscount);
//        tw.stop();
//      }
      
      // One after all pos, then log spaced buckets thereafter
      int nd = ds.negDone();
      if (nd >= 8 && isPosPowerOfTwo(nd)) {
        a.writeoutPmi(new File(output, "after-pos-and-neg" + ds.nNeg + ".pmi"), topFeats, pmiFreqDiscount);
      }
    }
    a.writeoutPmi(new File(output, "final.pmi"), topFeats, pmiFreqDiscount);
    Log.info("done");
  }
}
