package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import edu.jhu.hlt.entsum.SlotsAsConcepts.StreamingDistSupFeatEx.Fact;
import edu.jhu.hlt.entsum.VwLine.Namespace;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.TopDownClustering;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.set.IntHashSet;
import edu.jhu.prim.vector.IntIntHashVector;
import edu.jhu.util.Alphabet;
import edu.jhu.util.MultiMap;

public class PmiFeatureSelection {
  
  /*
   * This class needs serious cleanup.
   * 
   * Another algorithm-ignorant method of doing this is to
   * 1) find all pos and neg files first
   * 2) count all the pos files first
   * 3) write out PMI values after [2**i - 1 for i in range(12)] neg files have been read
   * 
   * If I track all of these writes, then i=0 will be withoutNeg and
   * lim_{i->inf} will be withNeg.
   * This halves the number of jobs and gives incremental results.
   */
  
  /**
   * VDL = "Van Durme and Lall"
   * https://www.cs.jhu.edu/~vandurme/papers/VanDurmeLallNIPS09.pdf
   *
   * @author travis
   */
  static class VDL {
    // 100M f(x) values * 10 chars/feat * 2 bytes/char = 2G in just keys
    // say we store top k=10 lists for each fx, then we need 
    
    static class TopPmis {
      int cx;
      byte[] compact;             // sequence of (y:int, cyx:short) pairs
      IntIntHashVector buffer;    // temporary map from y -> cyx
      
      public TopPmis(int k) {
        cx = 0;
        compact = new byte[k * (4 + 2)];
      }
      
      public void add(int y) {
        cx++;
        if (buffer == null)
          buffer = new IntIntHashVector();
        buffer.add(y, 1);
      }
      
      public void compress(int k, IntIntHashVector cy, int n) {
        // Add the history of counts from compact to the buffer
        ByteBuffer bb = ByteBuffer.wrap(compact);
        for (int i = 0; i < k; i++) {
          int y = bb.getInt();
          int cyx = bb.getShort();
          buffer.add(y, cyx);
        }
        // Take the top-k
        List<Pmi> top = new ArrayList<>(buffer.size());
        buffer.forEach(ide -> {
          int nCooc = ide.get();
          int label = ide.index();
          int cyi = cy.getWithDefault(label, 0);
          assert nCooc <= cyi;
          assert nCooc <= cx;
          if (nCooc > 0) {
            int feature = -1; // const
            double pmi = Math.log(((double) n * nCooc) / (cyi * cx));
            top.add(new Pmi(label, feature, pmi, nCooc));
          }
        });
        Collections.sort(top, Pmi.BY_PMI_DESC);
        // Compress the top-k
        bb.position(0);
        for (int i = 0; i < k; i++) {
          Pmi p = top.get(i);
          assert p.nCooc <= Short.MAX_VALUE;
          bb.putInt(p.labelIdx);
          bb.putShort((short) p.nCooc);
        }
        buffer = null;
      }
      
      public void getTopPmis(int k, int n, int x, IntIntHashVector cy, List<Pmi> addTo) {
        ByteBuffer bb = ByteBuffer.wrap(compact);
        for (int i = 0; i < k; i++) {
          int y = bb.getInt();
          int cyx = bb.getShort();
          if (cyx == 0)
            continue;
          double pmi = Math.log(((double) cyx*n) / (cy.get(y) * cx));
          addTo.add(new Pmi(y, x, pmi, cyx));
        }
      }
    }

    IntObjectHashMap<TopPmis> x2tops;   // pruned and compacted representation of cyx and cx
    IntIntHashVector cy;
    int n;
    int k;
    int maxCyxBufSize;
    
    // e.g k=10, maxCyxBufSize=256
    public VDL(int k, int maxCyxBufSize) {
      this.n = 0;
      this.k = k;
      this.x2tops = new IntObjectHashMap<>();
      this.cy = new IntIntHashVector();
      this.maxCyxBufSize = maxCyxBufSize;
    }
    
    public void addInstance(int[] ys, int[] xs) {
      n++;
      for (int i = 0; i < ys.length; i++) {
        n--;
        addInstance(ys[i], xs);
      }
    }
    public void addInstance(int y, int... xs) {
      cy.add(y, 1);
      n++;

      // Sort for uniq
      int[] xss = Arrays.copyOf(xs, xs.length);
      Arrays.sort(xss);
      for (int i = 0; i < xss.length; i++) {
        if (i > 0 && xss[i] == xss[i-1])
          continue;   // dup
        
        // cyx++
        TopPmis xc = x2tops.get(xss[i]);
        if (xc == null) {
          xc = new TopPmis(k);
          x2tops.put(xss[i], xc);
        }
        xc.add(y);
        
        // compress IntIntHashVector into top-k so memory doesn't grow unboundedly
        if (xc.buffer.size() > maxCyxBufSize)
          xc.compress(k, cy, n);
      }
    }

    /** returns all PMIs which are known about */
    public List<Pmi> getTopPmi(double pmiFreqDiscount) {
      List<Pmi> all = new ArrayList<>();
      IntObjectHashMap<TopPmis>.Iterator iter = x2tops.iterator();
      while (iter.hasNext()) {
        iter.advance();
        int x = iter.key();
        TopPmis cyxs = iter.value();
        cyxs.getTopPmis(k, n, x, cy, all);
      }
      return all;
    }
  }
  
  private IntObjectHashMap<IntArrayList> label2instance;      // values are sorted, uniq, ascending lists/sets
  private IntObjectHashMap<IntArrayList> feature2instance;    // values are sorted, uniq, ascending lists/sets
  private int numInstances;
  private long nnz;
  
  public PmiFeatureSelection() {
    label2instance = new IntObjectHashMap<>();
    feature2instance = new IntObjectHashMap<>();
    numInstances = 0;
    nnz = 0;
  }
  
  public void addInstance(int y, int... x) {
    int instance = numInstances++;
    getOrNew(y, label2instance).add(instance);
    Set<Integer> seen = new HashSet<>();
    for (int i = 0; i < x.length; i++) {
      if (seen.add(x[i])) {
        getOrNew(x[i], feature2instance).add(instance);
        nnz++;
      }
    }
  }
  
  private static IntArrayList getOrNew(int key, IntObjectHashMap<IntArrayList> map) {
    IntArrayList val = map.get(key);
    if (val == null) {
      val = new IntArrayList(2);
      map.put(key, val);
    }
    return val;
  }
  
  static IntArrayList intersectSortedLists(IntArrayList a, IntArrayList b) {
    IntArrayList dest = new IntArrayList();
    TopDownClustering.intersectSortedLists(a, b, dest);
    return dest;
  }

//  public double pmi(int label, int feature) {
  public Pmi pmi(int label, int feature) {
    IntArrayList instancesWithLabel = label2instance.get(label);
    if (instancesWithLabel == null)
      return null;
    IntArrayList instancesWithFeature = feature2instance.get(feature);
    if (instancesWithFeature == null)
      return null;
    double logN = Math.log(numInstances);
    Pmi p = pmi(instancesWithLabel, instancesWithFeature, logN);
    return new Pmi(label, feature, p.pmi, p.nCooc);
  }
  
//  private double pmi(IntArrayList instancesWithLabel, IntArrayList instancesWithFeature, double logN) {
  private Pmi pmi(IntArrayList instancesWithLabel, IntArrayList instancesWithFeature, double logN) {
    IntArrayList instancesWithBoth = intersectSortedLists(instancesWithLabel, instancesWithFeature);
    double logPxy = Math.log(instancesWithBoth.size()) - logN;
    double logPy = Math.log(instancesWithLabel.size()) - logN;
    double logPx = Math.log(instancesWithFeature.size()) - logN;
    return new Pmi(-1, -1, logPxy - (logPy + logPx), instancesWithBoth.size());
  }
  
  public static class Pmi {
    public final int labelIdx;
    public final int featureIdx;
    public final double pmi;
    public final int nCooc;
    
    public Pmi(int label, int feature, double pmi, int nCooc) {
      if (Double.isNaN(pmi)) {
//        throw new IllegalArgumentException("pmi=nan");
        Log.info("pmi=nan! label=" + label + " feature=" + feature + " nCooc=" + nCooc);
      }
//      if (Double.isInfinite(pmi))
//        throw new IllegalArgumentException("pmi=" + pmi);
      this.labelIdx = label;
      this.featureIdx = feature;
      this.pmi = pmi;
      this.nCooc = nCooc;
    }
    
    @Override
    public String toString() {
      return "(PMI y=" + labelIdx + " x=" + featureIdx + " nCooc=" + nCooc + " pmi=" + pmi + ")";
    }
    
    public static final Comparator<Pmi> BY_PMI_DESC = new Comparator<Pmi>() {
      @Override
      public int compare(Pmi o1, Pmi o2) {
        if (o1.pmi > o2.pmi)
          return -1;
        if (o2.pmi > o1.pmi)
          return +1;
        return 0;
      }
    };
    public static final Comparator<Pmi> BY_NCOOC_DESC = new Comparator<Pmi>() {
      @Override
      public int compare(Pmi o1, Pmi o2) {
        if (o1.nCooc > o2.nCooc)
          return -1;
        if (o2.nCooc > o1.nCooc)
          return +1;
        return 0;
      }
    };
    
    public double getFrequencyDiscountedPmi(double lambda) {
      double r = pmi * (nCooc / (nCooc + lambda));
      assert !Double.isNaN(r);
      return r;
    }
    public static Comparator<Pmi> byFrequencyDiscountedPmi(double lambda) {
      return new Comparator<Pmi>() {
        @Override
        public int compare(Pmi o1, Pmi o2) {
          double s1 = o1.getFrequencyDiscountedPmi(lambda);
          double s2 = o2.getFrequencyDiscountedPmi(lambda);
          assert !Double.isNaN(s1);
          assert !Double.isNaN(s2);
          assert Double.isFinite(s1);
          assert Double.isFinite(s2);
          if (s1 > s2)
            return -1;
          if (s2 > s1)
            return +1;
          return 0;
        }
      };
    }
  }
  
  /**
   * @param pmiFreqDiscount the higher this number is the more rare features are penalized. 0 means no penalty.
   */
  public List<Pmi> argTopByPmi(int label, int k, double pmiFreqDiscount) {
//    int approxY = 5;
//    int approxX = 3;
    int approxY = 0;
    int approxX = 0;
    List<Pmi> p = new ArrayList<>();
    double logN = Math.log(numInstances);
    IntArrayList instancesWithLabel = label2instance.get(label);
    if (instancesWithLabel.size() < approxY)
      return Collections.emptyList();
    IntObjectHashMap<IntArrayList>.Iterator iter = feature2instance.iterator();
    while (iter.hasNext()) {
      iter.advance();
      int feature = iter.key();
      IntArrayList instancesWithFeature = iter.value();
      if (instancesWithFeature.size() < approxX)
        continue;
      Pmi pmi = pmi(instancesWithLabel, instancesWithFeature, logN);
      if (pmi.nCooc > 0)
        p.add(new Pmi(label, feature, pmi.pmi, pmi.nCooc));
    }
    if (pmiFreqDiscount <= 0)
      Collections.sort(p, Pmi.BY_PMI_DESC);
    else
      Collections.sort(p, Pmi.byFrequencyDiscountedPmi(pmiFreqDiscount));
    if (k > 0 && p.size() > k) {
      List<Pmi> p2 = new ArrayList<>();
      for (int i = 0; i < k; i++)
        p2.add(p.get(i));
      p = p2;
    }
    return p;
  }
  
  public static class LabeledPmi<Y, X> extends Pmi {
    public final Y label;
    public final X feature;
    public LabeledPmi(Y label, int labelIdx, X feature, int featureIdx, double pmi, int nCooc) {
      super(labelIdx, featureIdx, pmi, nCooc);
      this.label = label;
      this.feature = feature;
    }
    
    public String toString() {
      return String.format("(PMI y=%s x=%s pmi=%.3f cooc=%d)", label, feature, pmi, nCooc);
    }
  }
  
  static class Adapater {
    public static final Charset UTF8 = Charset.forName("UTF8");

    private Alphabet<String> alphY;
//    private Alphabet<String> alphX;
    private PmiFeatureSelection mifs;
    private VDL mifsApprox;
    boolean transposeApprox = false;

    private MultiTimer t;
    
    private HashFunction hash;
    private IntHashSet goodFeats;
    MultiMap<Integer, String> inverseFeatHashForGoodFeats;
    private boolean inverseHashingVerbose = false;
    
    private Shard shard;
    
    public Adapater(Shard shard, boolean exact, boolean approx) {
      this.shard = shard;
      this.alphY = new Alphabet<>();
//      this.alphX = new Alphabet<>();

      if (exact)
        this.mifs = new PmiFeatureSelection();
      if (approx)
        this.mifsApprox = new VDL(16, 128);

      this.t = new MultiTimer();
      
      this.hash = Hashing.murmur3_32(42);
      this.goodFeats = new IntHashSet();
      this.inverseFeatHashForGoodFeats = new MultiMap<>();
    }
    
    public void addToGoodFeatures(Iterable<Integer> gf) {
      int g = goodFeats.size();
      int n = 0;
      for (int i : gf) {
        n++;
        goodFeats.add(i);
      }
      Log.info("added " + n + " features, goodFeats.size: " + g + " => " + goodFeats.size());
    }
    
    public Set<Integer> goodFeaturesWithMissingNames() {
      Set<Integer> missing = new HashSet<>();
      for (int g : goodFeats.toNativeArray())
        missing.add(g);
      for (int f : inverseFeatHashForGoodFeats.keySet())
        missing.remove(f);
      return missing;
    }
    
    /**
     * Find all feature hashes in goodFeats which aren't in inverseFeatHashingForGoodFeats
     * in the given feature files and add them.
     */
    public void resolveAllGoodFeatures(List<File> yx) throws IOException {
      Set<Integer> missing = goodFeaturesWithMissingNames();
      int lf = missing.size();
      Log.info("looking for " + lf + " missing features in " + yx.size() + " files");
      int nf = 0, nl = 0;
      for (File y : yx) {
        nf++;
        try (BufferedReader r = FileUtil.getReader(y)) {
          for (String line = r.readLine(); line != null; line = r.readLine()) {
            nl++;
            VwLine vw = new VwLine(line);
            for (Namespace ns : vw.x) {
              for (String f : ns.features) {
                int i = lookupFeatIndex(ns.name, f);
                missing.remove(i);
                if (missing.isEmpty()) {
                  Log.info("found all " + lf + " missing features after looking through " + nf + " files and " + nl + " lines");
                  return;
                }
              }
            }
          }
        }
      }
      Log.info("warning: failed to find " + missing.size() + " after looking through " + nf + " files and " + nl + " lines");
    }
    
    /**
     * @param linesAsInstances if false, union all the lines and add a single instance
     * (as in one instance per entity rather than per extraction location).
     */
    @SuppressWarnings("unchecked")
    public void add(String y, File yx, boolean linesAsInstances, boolean onlyPreciseFeatures) throws IOException {
      Log.info("y=" + y + " yx=" + yx.getPath());
      if (!yx.isFile()) {
        Log.info("warning: not a file");
        return;
      }
      
      Set<String> keepNS = null;
      if (onlyPreciseFeatures) {
        keepNS = new HashSet<>();
        keepNS.addAll(SlotsAsConcepts.PmiSlotPredictor.PRECISE_FEATURE_NAMESPACES);
      }

      t.start("addFile/" + y);
      Set<String>[] ns2fs = null;
      if (!linesAsInstances)
        ns2fs = new Set[256];
      try (BufferedReader r = FileUtil.getReader(yx)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          VwLine vw;
          try {
            // I have not found any bugs, but sometimes there are errors on disk.
            // e.g. a file which hasn't been flushed ends on "|" will make this crash.
            vw = new VwLine(line);
          } catch (Exception e) {
            System.err.println("error while reading " + yx.getPath());
            e.printStackTrace();
            continue;
          }
          
          if (onlyPreciseFeatures)
            vw.pruneByNamespace(keepNS);
          
          if (linesAsInstances) {
            add(y, vw);
          } else {
            for (Namespace ns : vw.x) {
              if (ns2fs[ns.name] == null)
                ns2fs[ns.name] = new HashSet<>();
              for (String f : ns.features) {
                int h = f.hashCode();
                if (shard.matches(h))
                  ns2fs[ns.name].add(f);
              }
            }
          }
        }
      }

      /*
       * TODO This is WRONG
       * This creates one instance per infobox-binary/*.vw file, not one instance per entity.
       * 
       * 
       * 
       * 
       * I can use the 'w' namespace to fix this.
       * TODO use int(sha1("w//m/02_wxh/s=22/m=2-1")) as my instance index.
       * TODO requires me to sort my inverted indices after building
       */

      if (!linesAsInstances) {
        int yi = alphY.lookupIndex(y);
        IntArrayList x = new IntArrayList();
        for (int i = 0; i < ns2fs.length; i++) {
          if (ns2fs[i] == null)
            continue;
          for (String xs : ns2fs[i]) {
            int xi = lookupFeatIndex((char) i, xs);
            x.add(xi);
          }
        }
        int[] xs = x.toNativeArray();
        if (mifs != null)
          mifs.addInstance(yi, xs);
        if (mifsApprox != null) {
          if (transposeApprox) {
            // Use approx to store the transpose.
            // |Y| is small, so use those as keys and store them exactly.
            // |X| is huge, and we are sharding them, but mifsApprox still only gets an approx top-k
            mifsApprox.addInstance(xs, new int[] {yi});
          } else {
            mifsApprox.addInstance(yi, xs);
          }
        }
      }
      t.stop("addFile/" + y);
    }
    
    private int lookupFeatIndex(char ns, String feat) {
      if ("of".equals(feat))
        Log.info("checkme");
      boolean keepAll = true;
      String xs = ns + "/" + feat;
      int xi = hash.hashString(xs, UTF8).asInt();
      if (keepAll || goodFeats.contains(xi)) {
        // Store the int<->string mapping for later
        if (inverseHashingVerbose)
          Log.info("storing good feat inverse mapping " + xs + ":" + xi);
        inverseFeatHashForGoodFeats.addIfNotPresent(xi, xs);
      }
      return xi;
    }

    public void add(String y, VwLine yx) {
      int yi = alphY.lookupIndex(y);
      IntArrayList x = new IntArrayList();
      for (Namespace ns : yx.x) {
        for (String xs : ns.features) {
          int h = xs.hashCode();
          if (!shard.matches(h))
            continue;
          int xi = lookupFeatIndex(ns.name, xs);
          x.add(xi);
        }
      }
      mifs.addInstance(yi, x.toNativeArray());
    }
    
    public LabeledPmi<String, String> getPmi(String y, String x) {
      int yi = alphY.lookupIndex(y);
      int xi = hash.hashString(x, UTF8).asInt();
      Pmi p = mifs.pmi(yi, xi);
      if (p == null || p.nCooc == 0)
        return null;
      return new LabeledPmi<>(y, yi, x, xi, p.pmi, p.nCooc);
    }

    public List<LabeledPmi<String, String>> argTopPmi(String label, int k, double pmiFreqDiscount) {
      t.start("argTopPmi/" + label);
      int y = alphY.lookupIndex(label, false);
      List<Pmi> pmi = mifs.argTopByPmi(y, k, pmiFreqDiscount);
      List<LabeledPmi<String, String>> out = new ArrayList<>();
      for (Pmi p : pmi) {
        
        // Maybe add to good features
        if (p.getFrequencyDiscountedPmi(pmiFreqDiscount) > 1d) {
          if (inverseHashingVerbose)
            Log.info("adding good feat: " + p.featureIdx);
          goodFeats.add(p.featureIdx);
        }

        String feature = getFeatureName(p.featureIdx);

        out.add(new LabeledPmi<>(label, y, feature, p.featureIdx, p.pmi, p.nCooc));
      }
      t.stop("argTopPmi/" + label);
      return out;
    }
    
    String getFeatureName(int i) {
      return getFeatureName(i, false);
    }
    String getFeatureName(int i, boolean add) {
      List<String> fns = inverseFeatHashForGoodFeats.get(i);
      String feature;
      if (fns.isEmpty()) {
        if (goodFeats.contains(i)) {
          feature = "?" + Integer.toHexString(i);
        } else {
          feature = "!" + Integer.toHexString(i);
          if (add)
            goodFeats.add(i);
        }
      } else {
        feature = StringUtils.join("~OR~", fns);
      }
      return feature;
    }

    public Map<String, List<LabeledPmi<String, String>>> argTopPmiAllLabels(Set<String> skip, int k, double pmiFreqDiscount) {
      return argTopPmiAllLabels(skip, k, pmiFreqDiscount, false);
    }
    public Map<String, List<LabeledPmi<String, String>>> argTopPmiAllLabels(Set<String> skip, int k, double pmiFreqDiscount, boolean show) {
      Map<String, List<LabeledPmi<String, String>>> out = new HashMap<>();
      for (int i = 0; i < alphY.size(); i++) {
        String label = alphY.lookupObject(i);
        if (skip.contains(label))
          continue;
        List<LabeledPmi<String, String>> at = argTopPmi(label, k, pmiFreqDiscount);
        Object old = out.put(label, at);
        assert old == null;
        if (show) {
          for (LabeledPmi<String, String> pmi : at)
            System.out.println(pmi);
        }
      }
      return out;
    }
    
    public void writeoutWithComputeMi(File output, Set<String> skipRels, int topFeats, double pmiFreqDiscount, int n) {
      
      if ((mifs == null) == (mifsApprox == null))
        Log.info("warning: mifs==null:" + (mifs==null) + " mifsApprox==null:" + (mifsApprox==null));
      
      try (BufferedWriter w = FileUtil.getWriter(output)) {
        
        if (mifsApprox != null) {
          List<Pmi> pmi = mifsApprox.getTopPmi(pmiFreqDiscount);
          for (Pmi feat : pmi) {
            w.write(alphY.lookupObject(feat.labelIdx));
            w.write('\t');
            w.write(getFeatureName(feat.featureIdx));
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
        
        if (mifs != null) {
          Map<String, List<LabeledPmi<String, String>>> m = argTopPmiAllLabels(skipRels, topFeats, pmiFreqDiscount, false);
          for (String rel : m.keySet()) {
            for (LabeledPmi<String, String> feat : m.get(rel)) {
              w.write(feat.label);
              w.write('\t');
              w.write(feat.feature);
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
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    
    public void writeout(Map<String, List<LabeledPmi<String, String>>> m, File output, double pmiFreqDiscount, int numInstance, boolean resolveFeatureNames) {
      
      if ((mifs == null) == (mifsApprox == null))
        Log.info("warning: mifs==null:" + (mifs==null) + " mifsApprox==null:" + (mifsApprox==null));

      try (BufferedWriter w = FileUtil.getWriter(output)) {
        
        if (mifsApprox != null) {
          List<Pmi> pmi = mifsApprox.getTopPmi(pmiFreqDiscount);
          for (Pmi feat : pmi) {
            w.write(alphY.lookupObject(feat.labelIdx));
            w.write('\t');
            w.write(getFeatureName(feat.featureIdx));
            w.write('\t');
            w.write("" + feat.pmi);
            w.write('\t');
            w.write("" + feat.getFrequencyDiscountedPmi(pmiFreqDiscount));
            w.write('\t');
            w.write("" + feat.nCooc);
            w.write('\t');
            w.write("" + numInstance);
            w.newLine();
          }
        }

        if (mifs != null) {
          for (String rel : m.keySet()) {
            for (LabeledPmi<String, String> feat : m.get(rel)) {
              w.write(feat.label);
              w.write('\t');
              if (resolveFeatureNames)
                w.write(getFeatureName(feat.featureIdx));
              else
                w.write(feat.feature);
              w.write('\t');
              w.write("" + feat.pmi);
              w.write('\t');
              w.write("" + feat.getFrequencyDiscountedPmi(pmiFreqDiscount));
              w.write('\t');
              w.write("" + feat.nCooc);
              w.write('\t');
              w.write("" + numInstance);
              w.newLine();
            }
          }
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
  
  public static class InstanceIter implements Iterator<VwInstance>, AutoCloseable {
    private BufferedReader rScores;
    private BufferedReader rLocs;
    private VwInstance cur;
    
    public InstanceIter(File scores, File locations) throws IOException {
      this.rScores = FileUtil.getReader(scores);
      this.rLocs = FileUtil.getReader(locations);
      advance();
    }
    
    private void advance() throws IOException {
      String lScores = rScores.readLine();
      if (lScores == null) {
        cur = null;
        return;
      }
      String lLocs = rLocs.readLine();
      cur = new VwInstance(Fact.fromTsv(lLocs), null);
      String[] a = lScores.split("\\s+");
      assert a.length % 2 == 0;
      for (int i = 0; i < a.length; i += 2) {
        double cost = 100d / Double.parseDouble(a[i+1]);
        if (cost > 0)
          cur.add(a[i], cost);
      }
    }

    @Override
    public void close() throws IOException {
      rScores.close();
      rLocs.close();
    }

    @Override
    public boolean hasNext() {
      return cur != null;
    }

    @Override
    public VwInstance next() {
      VwInstance c = cur;
      try {
        advance();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return c;
    }
  }
  
  /*
  caligula code-testing-data $ mkdir -p testing/one/a
  caligula code-testing-data $ mkdir -p testing/one/b
  caligula code-testing-data $ mkdir -p testing/one/c
  caligula code-testing-data $ mkdir -p testing/two/a
  caligula code-testing-data $ mkdir -p testing/two/b
  caligula code-testing-data $ mkdir -p testing/two/c
  caligula code-testing-data $ for f in testing/STAR/STAR; do echo "world" >$f/hello.txt; done
  */
//  public static void test0() {
//    File parent = new File("data/facc1-entsum/code-testing-data/testing");
//    List<File> fs = FileUtil.find(parent, "glob:**/b/*.txt");
//    for (File f : fs)
//      System.out.println(f.getPath());
//  }
  
  public static void computeHighPmiFeatures(ExperimentProperties config) throws Exception {
    // TODO These should be re-named to something like "relationBinaryFeatureParent" etc
    File entityDirParent = config.getExistingDir("entityDirParent");
//    String entityDirGlob = config.getString("entityDirGlob", "glob:**/infobox-binary");
//    List<File> ibs = FileUtil.findDirs(entityDirParent, entityDirGlob);
    List<File> ibs = FileUtil.execFind(entityDirParent,
        "-path", "*/train/*",
        "-not", "-path", "*entsum-data*",
        "-type", "d",
        "-name", "infobox-binary");
    Log.info("found " + ibs.size() + " entity directories");
    
//    // DEBUG: prune dirs
//    ibs = ReservoirSample.sample(ibs, 100, new Random(9001));
    
    File output = config.getFile("output");
    Log.info("writing output to " + output.getPath());
    
    boolean extractionsAsInstances = config.getBoolean("extractionsAsInstances", false);
    Log.info("extractionsAsInstances=" + extractionsAsInstances);

    boolean addNeg = config.getBoolean("addNeg", false);
    Log.info("addNeg=" + addNeg);
    
    double pmiFreqDiscount = config.getDouble("pmiFreqDiscount", 2d);
    Log.info("pmiFreqDiscount=" + pmiFreqDiscount);
    
    Set<String> skipRels = new HashSet<>();
    skipRels.add("neg");

    int topFeats = config.getInt("topFeats", 300);
    Log.info("topFeats=" + topFeats);
    
    Shard shard = config.getShard();
    Log.info("feature shard=" + shard);

    boolean onlyPreciseFeatures = true;
    boolean exact = true;
    boolean approx = false;   // This is not complete...
    Adapater a = new Adapater(shard, exact, approx);

    int n = 0;
    int ncur = 0;
    int thresh = 1;
    List<File> allPos = new ArrayList<>();
    for (File ib : ibs) {
      if (addNeg)
        a.add("neg", new File(ib, "neg.vw"), extractionsAsInstances, onlyPreciseFeatures);
      for (File f : ib.listFiles(f -> f.getName().matches("pos-\\S+.vw"))) {
        a.add(f.getName(), f, extractionsAsInstances, onlyPreciseFeatures);
        allPos.add(f);
      }
      
      n++;
      ncur++;
      if (ncur == thresh) {
        thresh = (int) (1.6 * thresh + 1);
        System.out.println("n=" + n + " N=" + ibs.size() + " nextThresh=" + thresh + "\t" + Describe.memoryUsage());

        if (exact) {
          System.out.println("alphY.size=" + a.alphY.size()
              + " good=" + a.goodFeats.size()
              + " inverse=" + a.inverseFeatHashForGoodFeats.numEntries()
              + " nYX=" + a.mifs.nnz
              + " nY=" + a.mifs.label2instance.size()
              + " nX=" + a.mifs.feature2instance.size());
        }
        
        if (approx) {
          List<Pmi> approxAll = a.mifsApprox.getTopPmi(pmiFreqDiscount);
          Log.info("approx has " + approxAll.size() + " PMIs");
          Collections.sort(approxAll, Pmi.BY_PMI_DESC);
          int k = Math.min(60, approxAll.size());
          for (int i = 0; i < k; i++) {
            Pmi p = approxAll.get(i);
            // These are switched on purpose
            int y,x;
            if (a.transposeApprox) {
              y = p.featureIdx;
              x = p.labelIdx;
            } else {
              y = p.labelIdx;
              x = p.featureIdx;
            }
            System.out.printf("approx.best(%d): y=%-36s x=%-36s p=%.3f cyx=% 3d\n",
                i, a.alphY.lookupObject(y), a.getFeatureName(x, true), p.pmi, p.nCooc);
          }
          System.out.println();
        }
        
        System.out.println("TIMER: " + a.t);
        a.writeoutWithComputeMi(output, skipRels, topFeats, pmiFreqDiscount, n);
        System.out.println();
        ncur = 0;
      }
    }
    
    Log.info("computing MI for the last time");
    if (exact) {
      Map<String, List<LabeledPmi<String, String>>> m = a.argTopPmiAllLabels(skipRels, topFeats, pmiFreqDiscount);
      a.addToGoodFeatures(getTopFeats(m));
      a.resolveAllGoodFeatures(allPos);
      boolean resolveFeatureNames = true;
      a.writeout(m, output, pmiFreqDiscount, n, resolveFeatureNames);
    }
  }
  
  public static Set<Integer> getTopFeats(Map<String, List<LabeledPmi<String, String>>> m) {
    Set<Integer> tf = new HashSet<>();
    for (List<LabeledPmi<String, String>> lp : m.values())
      for (LabeledPmi<?, ?> p : lp)
        tf.add(p.featureIdx);
    return tf;
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    computeHighPmiFeatures(config);
    Log.info("done");
  }
}
