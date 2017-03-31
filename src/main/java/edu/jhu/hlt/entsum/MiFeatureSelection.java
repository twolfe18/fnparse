package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import edu.jhu.hlt.entsum.VwLine.Namespace;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.TopDownClustering;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.set.IntHashSet;
import edu.jhu.util.Alphabet;
import edu.jhu.util.MultiMap;

public class MiFeatureSelection {
  
  private IntObjectHashMap<IntArrayList> label2instance;      // values are sorted, uniq, ascending lists/sets
  private IntObjectHashMap<IntArrayList> feature2instance;    // values are sorted, uniq, ascending lists/sets
  private int numInstances;
  private long nnz;
  
  public MiFeatureSelection() {
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
  
  private static IntArrayList intersectSortedLists(IntArrayList a, IntArrayList b) {
    IntArrayList dest = new IntArrayList();
    TopDownClustering.intersectSortedLists(a, b, dest);
    return dest;
  }

//  public double pmi(int label, int feature) {
  public Pmi pmi(int label, int feature) {
    IntArrayList instancesWithLabel = label2instance.get(label);
    IntArrayList instancesWithFeature = feature2instance.get(feature);
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
    public final int label;
    public final int feature;
    public final double pmi;
    public final int nCooc;
    
    public Pmi(int label, int feature, double pmi, int nCooc) {
      if (Double.isNaN(pmi))
        throw new IllegalArgumentException("pmi=nan");
//      if (Double.isInfinite(pmi))
//        throw new IllegalArgumentException("pmi=" + pmi);
      this.label = label;
      this.feature = feature;
      this.pmi = pmi;
      this.nCooc = nCooc;
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
    int approxY = 5;
    int approxX = 3;
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
    private MiFeatureSelection mifs;
    private MultiTimer t;
    
    private HashFunction hash;
    private IntHashSet goodFeats;
    private MultiMap<Integer, String> inverseFeatHashForGoodFeats;
    private boolean inverseHashingVerbose = false;
    
    private Shard shard;
    
    public Adapater(Shard shard) {
      this.shard = shard;
      this.alphY = new Alphabet<>();
//      this.alphX = new Alphabet<>();
      this.mifs = new MiFeatureSelection();
      this.t = new MultiTimer();
      
      this.hash = Hashing.murmur3_32(42);
      this.goodFeats = new IntHashSet();
      this.inverseFeatHashForGoodFeats = new MultiMap<>();
    }
    
    /**
     * @param linesAsInstances if false, union all the lines and add a single instance
     * (as in one instance per entity rather than per extraction location).
     */
    @SuppressWarnings("unchecked")
    public void add(String y, File yx, boolean linesAsInstances) throws IOException {
      Log.info("y=" + y + " yx=" + yx.getPath());
      if (!yx.isFile()) {
        Log.info("warning: not a file");
        return;
      }

      t.start("addFile/" + y);
      Set<String>[] ns2fs = null;
      if (!linesAsInstances)
        ns2fs = new Set[256];
      try (BufferedReader r = FileUtil.getReader(yx)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          VwLine vw = new VwLine(line);
          if (linesAsInstances) {
            add(y, vw);
          } else {
            for (Namespace ns : vw.x) {
              if (ns2fs[ns.name] == null)
                ns2fs[ns.name] = new HashSet<>();
              for (String f : ns.features) {
                int h = f.hashCode();
                if (shard.matches(h))
                  ns2fs[ns.name].addAll(ns.features);
              }
            }
          }
        }
      }
      if (!linesAsInstances) {
        int yi = alphY.lookupIndex(y);
        IntArrayList x = new IntArrayList();
        for (int i = 0; i < ns2fs.length; i++) {
          if (ns2fs[i] == null)
            continue;
          for (String xs : ns2fs[i]) {
            int xi = lookupFeatIndex((char) i, xs);
//            int xi = alphX.lookupIndex(xs);
//            xi = xi * 256 + i;
            x.add(xi);
          }
        }
        mifs.addInstance(yi, x.toNativeArray());
      }
      t.stop("addFile/" + y);
    }
    
    private int lookupFeatIndex(char ns, String feat) {
      String xs = ns + "/" + feat;
      int xi = hash.hashString(xs, UTF8).asInt();
      if (goodFeats.contains(xi)) {
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
//          int xi = alphX.lookupIndex(xs);
//          xi = xi * 256 + ns.name;
          x.add(xi);
        }
      }
      mifs.addInstance(yi, x.toNativeArray());
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
            Log.info("adding good feat: " + p.feature);
          goodFeats.add(p.feature);
        }

//        char ns = (char) (p.feature % 256);
//        int x = p.feature / 256;
//        String feature = ns + "/" + alphX.lookupObject(x);
        List<String> fns = inverseFeatHashForGoodFeats.get(p.feature);
        String feature;
        if (fns.isEmpty()) {
          feature = "?" + p.feature;
        } else {
          feature = StringUtils.join("~OR~", fns);
        }

        out.add(new LabeledPmi<>(label, y, feature, p.feature, p.pmi, p.nCooc));
      }
      t.stop("argTopPmi/" + label);
      return out;
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
    
    public void writeout(File output, Set<String> skipRels, int topFeats, double pmiFreqDiscount, int n) {
      Map<String, List<LabeledPmi<String, String>>> m = argTopPmiAllLabels(skipRels, topFeats, pmiFreqDiscount, true);
      try (BufferedWriter w = FileUtil.getWriter(output)) {
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
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    File entityDirParent = config.getExistingDir("entityDirParent");
    List<File> ibs = FileUtil.findDirs(entityDirParent, "glob:**/infobox-binary");
    Log.info("found " + ibs.size() + " entity directories");
    
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

    int topFeats = config.getInt("topFeats", 30);
    Log.info("topFeats=" + topFeats);
    
    Shard shard = config.getShard();
    Log.info("feature shard=" + shard);

    Adapater a = new Adapater(shard);

    int n = 0;
    int ncur = 0;
    int thresh = 1;
    for (File ib : ibs) {
      if (addNeg)
        a.add("neg", new File(ib, "neg.vw"), extractionsAsInstances);
      for (File f : ib.listFiles(f -> f.getName().matches("pos-\\S+.vw")))
        a.add(f.getName(), f, extractionsAsInstances);
      
      n++;
      ncur++;
      if (ncur == thresh) {
        thresh = (int) (1.4 * thresh + 1);
        System.out.println("n=" + n + " N=" + ibs.size() + " nextThresh=" + thresh + "\t" + Describe.memoryUsage());
        System.out.println("alphY.size=" + a.alphY.size()
            + " good=" + a.goodFeats.size()
            + " inverse=" + a.inverseFeatHashForGoodFeats.numEntries()
            + " nYX=" + a.mifs.nnz
            + " nY=" + a.mifs.label2instance.size()
            + " nX=" + a.mifs.feature2instance.size());
        System.out.println("TIMER: " + a.t);
        ncur = 0;
        a.writeout(output, skipRels, topFeats, pmiFreqDiscount, ncur);
        System.out.println();
      }
    }
    Log.info("last time writing out");
    a.writeout(output, skipRels, topFeats, pmiFreqDiscount, ncur);
    Log.info("done");
  }
}
