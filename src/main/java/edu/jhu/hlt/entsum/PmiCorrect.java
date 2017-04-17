package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.entsum.PmiFeatureSelection.Pmi;
import edu.jhu.hlt.entsum.VwLine.Namespace;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.prim.iter.IntIter;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.set.IntHashSet;
import edu.jhu.prim.sort.IntSort;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.prim.vector.IntIntHashVector;
import edu.jhu.util.Alphabet;

public class PmiCorrect {
  
  /**
   * Think of this as storing a tensor with indices [label, feature, instance].
   * The structure is special though: we store it as:
   *    [label, instance] \otimes T([feature, instance])
   * Both of these matrices are sparse and can be stored efficiently using
   * well-known tricks:
   * https://en.wikipedia.org/wiki/Sparse_matrix#Storing_a_sparse_matrix
   *
   * For mapping strings (labels, features) to ints (general method for indexing
   * matrix/tensor data structures), the typical tricks apply:
   * a) feature/label alphabet
   * b) feature/label hashing
   * c) implicit, or AOT alphabet
   *    In code, dictate y=0 means "foo", y=1 means "bar", y=2 means "baz", etc.
   *    This is a variation on a) alphabet
   *
   * How to track instances?
   * 1) hash set                    => too much memory
   * 2) hash a location string      => have to sort inverted indices
   * 3) keep an int counter         => not compatible with instances appearing in multiple files (e.g. one textual location corresponding to multiple relations)
   *
   * "Neg Counts"
   * There are problems where the number of negative instances far outweighs the number of positives.
   * In this representation, we don't want to have to store these in the [feature, instance] matrix.
   * For only neg instances, we move to storing count(featureWithNegInstance) and count(negInstance),
   * rather than the sparse matrix approach outlined above.
   */
  static class PmiCounts {
    Shard shard;
    Alphabet<String> alphY;
    Alphabet<String> alphX;
//    Alphabet<String> alphPosI;
    IntObjectHashMap<IntArrayList> y2posI;
    IntObjectHashMap<IntArrayList> x2posI;
//    IntHashSet posI;
    int numInstances;
    IntIntHashVector x2negICounts;          // Counts of features on negative instances
    int x2negICountSum;
    
    public PmiCounts(Shard s) {
      shard = s;
      alphY = new Alphabet<>();
      alphX = new Alphabet<>();
//      alphPosI = new Alphabet<>();
      numInstances = 0;
      y2posI = new IntObjectHashMap<>();
      x2posI = new IntObjectHashMap<>();
      x2negICounts = new IntIntHashVector();
      x2negICountSum = 0;
//      posI = new IntHashSet();
    }
    
    public List<Pmi> topPmiForLabel(int y) {
      List<Pmi> ps = new ArrayList<>();
      IntArrayList iy = y2posI.get(y);
      double logN = Math.log(numInstances);
      double logCy = Math.log(iy.size());
      assert iy.size() <= numInstances;

      IntObjectHashMap<IntArrayList>.Iterator iter = x2posI.iterator();
      while (iter.hasNext()) {
        iter.advance();
        IntArrayList ix = iter.value();
        assert ix.size() <= numInstances;
        IntArrayList iyx = PmiFeatureSelection.intersectSortedLists(iy, ix);
        int nCooc = iyx.size();
        if (nCooc > 0) {
          int x = iter.key();
          double logCyx = Math.log(nCooc);
          double logCx = Math.log(ix.size());
          double pmi = logCyx + logN - (logCx + logCy);
          ps.add(new Pmi(y, x, pmi, nCooc));
        }
      }
      
      Collections.sort(ps, Pmi.BY_PMI_DESC);
      return ps;
    }
  }

  static class HasEntityDir {
    File entdir;
    
    public HasEntityDir(File entdir) {
      this.entdir = entdir;
    }
    
    File getNegFile() {
      return new File(entdir, "infobox-binary/neg.vw");
    }
    
    List<File> getPosFiles() {
      List<File> a = new ArrayList<>();
      for (File f : new File(entdir, "infobox-binary").listFiles())
        if (f.getName().matches("pos-.*\\.vw"))
          a.add(f);
      return a;
    }
  }

  /** dedup over all vw lines in an entire entity */
  static class ByEntity extends HasEntityDir {
    
    public ByEntity(File entdir) {
      super(entdir);
    }

    public void update(PmiCounts c, boolean addPos, boolean addNeg) throws IOException {
      Log.info("entdir=" + entdir.getPath()
          + "\taddPos=" + addPos + " addNeg=" + addNeg
          + " nInstance=" + c.numInstances + " alphX.size=" + c.alphX.size()
          + "\t" + Describe.memoryUsage());
      
      // Populate this, deduping
      IntHashSet yUniq = new IntHashSet();
      IntHashSet xUniqPos = new IntHashSet();
      IntHashSet xUniqNeg = new IntHashSet();

      // Loop over files, add to two structures above
      List<String> fs = new ArrayList<>();
      if (addNeg) {
        File negF = getNegFile();
        if (negF.isFile()) {
          try (BufferedReader r = FileUtil.getReader(negF)) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
              VwLine vw = new VwLine(line);
              vw.remove('w');
              fs.clear();
              vw.extractAllFeatures(fs);
              for (String f : fs) {
                if (c.shard.matches(f.hashCode())) {
                  int i = c.alphX.lookupIndex(f, false);
                  if (i >= 0) {
                    xUniqNeg.add(i);
                  }
                }
              }
            }
          }
        }
      }
      if (addPos) {
        for (File posF : getPosFiles()) {
          String ys = PmiUpgrades.getRelation(posF);
          int y = c.alphY.lookupIndex(ys);
          yUniq.add(y);
          try (BufferedReader r = FileUtil.getReader(posF)) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
              VwLine vw = new VwLine(line);
              vw.remove('w');
              fs.clear();
              vw.extractAllFeatures(fs);
              for (String f : fs) {
                if (c.shard.matches(f.hashCode())) {
                  int x = c.alphX.lookupIndex(f);
                  xUniqPos.add(x);
                }
              }
            }
          }
        }
      }
      
      /*
       * How do I handle negative instances for ByEntity?
       * In the [label, feature, instance] view of the problem, I clearly should use the same instance for pos/neg features for an entity.
       * At the same time, I do want to keep the ix inverted indices as short as possible.
       * I think the correct answer is to have one instance counter.
       */
      // Add to global counts
      IntIter iterNeg = xUniqNeg.iterator();
      while (iterNeg.hasNext()) {
        int x = iterNeg.next();
        c.x2negICounts.add(x, 1);
        c.x2negICountSum++;
      }
      if (addPos) {
        int instance = c.numInstances++;
        IntIter iterPos = xUniqPos.iterator();
        while (iterPos.hasNext()) {
          int x = iterPos.next();
          PmiUpgrades.getOrNewL(x, c.x2posI).add(instance);
        }
        IntIter iterY = yUniq.iterator();
        while (iterY.hasNext()) {
          int y = iterY.next();
          PmiUpgrades.getOrNewL(y, c.y2posI).add(instance);
        }
      }
    }
    
    public static void main(ExperimentProperties config) throws Exception {
      Shard shard = config.getShard();
      Log.info("feature shard=" + shard);
      File entityDirParent = config.getExistingDir("entityDirParent");
      List<File> ibs = FileUtil.execFind(entityDirParent,
          "-path", "*/train/*",
          "-not", "-path", "*entsum-data*",
          "-type", "d",
          "-name", "infobox-binary");
      Log.info("found " + ibs.size() + " entity directories to read in entityDirParent=" + entityDirParent.getPath());
      
      File output = config.getFile("output");

      double pmiFreqDiscount = config.getDouble("pmiFreqDiscount", 1d);
      Log.info("pmiFreqDiscount=" + pmiFreqDiscount);

      int topFeats = config.getInt("topFeats", 300);
      Log.info("topFeats=" + topFeats);
      
      // DEBUG
//      ibs = ReservoirSample.sample(ibs, 10, new Random(9001));

      PmiCounts c = new PmiCounts(shard);
      List<ByEntity> bes = new ArrayList<>();
      
      // Add pos first, allowing the alphabet to grow
      for (File f : ibs) {
        File entdir = f.getParentFile();
        ByEntity be = new ByEntity(entdir);
        boolean addPos = true;
        boolean addNeg = false;
        be.update(c, addPos, addNeg);
        bes.add(be);
      }
      writeoutPmi(new File(output.getPath() + ".afterPos"), c, topFeats, pmiFreqDiscount);

      // Add neg with only feats from pos
      int nneg = 0;
      for (ByEntity be : bes) {
        boolean addPos = false;
        boolean addNeg = true;
        be.update(c, addPos, addNeg);
        nneg++;
        if (nneg >= 16 && PmiUpgrades.isPosPowerOfTwo(nneg)) {
          String suf = String.format(".afterPosAndNeg%05d", nneg);
          writeoutPmi(new File(output.getPath() + suf), c, topFeats, pmiFreqDiscount);
        }
      }

      // Compute PMI
      writeoutPmi(output, c, topFeats, pmiFreqDiscount);
    }
  }
  
  public static void writeoutPmi(File output, PmiCounts c, int topFeats, double pmiFreqDiscount) throws IOException {
    Log.info("pmiFreqDiscount=" + pmiFreqDiscount + " output=" + output.getPath());
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      int Y = c.alphY.size();
      for (int y = 0; y < Y; y++) {
        String ys = c.alphY.lookupObject(y);
        System.out.print("computing top " + topFeats + " feats for " + ys + "...");
        List<Pmi> ps = c.topPmiForLabel(y);
        System.out.print(" writing to file...");
        int niy = c.y2posI.get(y).size();
        int k = Math.min(topFeats, ps.size());
        for (int i = 0; i < k; i++) {
          Pmi p = ps.get(i);
          String xs = c.alphX.lookupObject(p.featureIdx);
//          System.out.printf("y=%-32s  niy=% 4d  %-48s  %.3f\t%d\n", ys, niy, xs, p.pmi, p.nCooc);
          double pd = p.getFrequencyDiscountedPmi(pmiFreqDiscount);
          w.write(StringUtils.join("\t", Arrays.asList(ys, xs, p.pmi, pd, p.nCooc, niy)));
          w.newLine();
        }
        w.flush();
        System.out.println(" done.");
      }
    }
  }
  
  public static List<Pair<String, File>> getBinaryFeatDirs(File entityDir) {
    List<Pair<String, File>> a = new ArrayList<>();
    for (File f : new File(entityDir, "infobox-binary").listFiles()) {
      if (f.getName().endsWith(".vw")) {
        String rel = PmiUpgrades.getRelation(f);
        a.add(new Pair<>(rel, f));
      }
    }
    return a;
  }
  
  /**
   * Add instances using w/locationOfExtraction; at end of update, so sort -u on [xy]2posI
   * 
   * Instance ids:
   * - hashing                => requires sorting inverted indices, bad idea
   * - alphabet (global)      => too much memory, might still need to sort
   * - alphabet (per entity)  => sort per entity + ```i \in instances(entity_a) & j \in instances(entity_b) & entity_a < entity_b => i < j```
   *                             put another way: sort sub-set of inverted index corresponding to a entity
   * - hashing (per entity)   => would need to emit something like concat(entityIdx, hash(instance)) in order to sort...
   */
  static class ByInstance extends HasEntityDir {
//    static final Charset UTF8 = Charset.forName("UTF-8");
//    private HashFunction hf;

    public ByInstance(File entdir) {
      super(entdir);
//      hf = Hashing.murmur3_128(entdir.getPath().hashCode());
    }
    
    /*
     * In ByEntity I only needed to make one pass over each file.
     * - negFs came last, 
     * 
     * If I use the addPos/addNeg pattern from ByEntity, then I need to hold
     * onto alphI:Alphabet<String> between addPos and addNeg, which could get pretty large!
     * 
     * 5 * 3800 ents * 10k sent * 20 chars * 2 bytes/char = 7.2G
     * 
     * Ah... I see,
     * This is finally a good argument for hashing locations!
     * addPos: hash locs, sort -u them for this entity, add to global
     * addNeg: hash locs, uniq them, foreach uniq add to x2negCounts
     * 
     * AHH!
     * Since for negs we aren't adding to inverted index, only counting uniq, there is
     * no requirement that pos and neg use the same alphI!
     */
    
    public void update(PmiCounts c, boolean addPos, boolean addNeg) throws IOException {
      Log.info("entdir=" + entdir.getPath()
          + "\taddPos=" + addPos + " addNeg=" + addNeg
          + " nInstance=" + c.numInstances + " alphX.size=" + c.alphX.size()
          + "\t" + Describe.memoryUsage());

      List<String> fs = new ArrayList<>();
      
      // OLD: Serves a different role for pos/neg counting,
      //      so its OK that this does not persist across pos/neg calls.
      // NEW: Only pos uses this. For neg it is enough to count knowing the fact that all neg file lines are diff instances.
      Alphabet<String> alphI = new Alphabet<>();

//      IntObjectHashMap<IntHashSet> x2locNeg = new IntObjectHashMap<>();
      IntObjectHashMap<IntHashSet> x2locPos = new IntObjectHashMap<>();
      IntObjectHashMap<IntHashSet> y2loc = new IntObjectHashMap<>();
      
      if (addPos) {
      for (File posF : getPosFiles()) {
        String ys = PmiUpgrades.getRelation(posF);
        int y = c.alphY.lookupIndex(ys);
        try (BufferedReader r = FileUtil.getReader(posF)) {
          for (String line = r.readLine(); line != null; line = r.readLine()) {
            VwLine vw = new VwLine(line);
            
            Namespace w = vw.remove('w');
            // e.g. "/m/02v2lh/s=10/m=1-0"
            String loc = w.features.get(w.features.size()-1);
            int instance = alphI.lookupIndex(loc);
//            int instance = hf.hashString(loc, UTF8).asInt();
            
            PmiUpgrades.getOrNewS(y, y2loc).add(instance);
            
            fs.clear();
            vw.extractAllFeatures(fs);
            
            for (String xs : fs) {
              if (c.shard.matches(xs.hashCode())) {
                int x = c.alphX.lookupIndex(xs);
                PmiUpgrades.getOrNewS(x, x2locPos).add(instance);
              }
            }
          }
        }
      }
      
      IntObjectHashMap<IntHashSet>.Iterator xIter = x2locPos.iterator();
      while (xIter.hasNext()) {
        xIter.advance();
        int x = xIter.key();
        IntHashSet instances = xIter.value();
        int[] ix = instances.toNativeArray();
        IntSort.sortAsc(ix, 0, ix.length);
        IntArrayList ixl = PmiUpgrades.getOrNewL(x, c.x2posI);
        for (int i = 0; i < ix.length; i++)
          ixl.add(ix[i] + c.numInstances);
      }
      IntObjectHashMap<IntHashSet>.Iterator yIter = y2loc.iterator();
      while (yIter.hasNext()) {
        yIter.advance();
        int y = yIter.key();
        IntHashSet instances = yIter.value();
        int[] iy = instances.toNativeArray();
        IntSort.sortAsc(iy, 0, iy.length);
        IntArrayList iyl = PmiUpgrades.getOrNewL(y, c.y2posI);
        for (int i = 0; i < iy.length; i++)
          iyl.add(iy[i] + c.numInstances);
      }
      c.numInstances += alphI.size();

      }
      if (addNeg) {
      File negF = getNegFile();
      if (negF.isFile()) {
        try (BufferedReader r = FileUtil.getReader(negF)) {
          for (String line = r.readLine(); line != null; line = r.readLine()) {
            VwLine vw = new VwLine(line);

            // Every line is a new instance!
            vw.remove('w');
//            Namespace w = vw.remove('w');
//            String loc = w.features.get(w.features.size()-1);
//            int instance = alphI.lookupIndex(loc, false);
//            int instance = hf.hashString(loc, UTF8).asInt();

            fs.clear();
            vw.extractAllFeatures(fs);

            for (String xs : fs) {
              if (c.shard.matches(xs.hashCode())) {
                int x = c.alphX.lookupIndex(xs, false);
                if (x >= 0) {
                  c.x2negICounts.add(x, 1);
                  c.x2negICountSum++;
                }
              }
            }
          }
        }
      }
      }
    }
    
    public static void main(ExperimentProperties config) throws Exception {
      Shard shard = config.getShard();
      Log.info("feature shard=" + shard);
      File entityDirParent = config.getExistingDir("entityDirParent");
      List<File> ibs = FileUtil.execFind(entityDirParent,
          "-path", "*/train/*",
          "-not", "-path", "*entsum-data*",
          "-type", "d",
          "-name", "infobox-binary");
      Log.info("found " + ibs.size() + " entity directories to read in entityDirParent=" + entityDirParent.getPath());
      
      File output = config.getFile("output");

      double pmiFreqDiscount = config.getDouble("pmiFreqDiscount", 1d);
      Log.info("pmiFreqDiscount=" + pmiFreqDiscount);

      int topFeats = config.getInt("topFeats", 300);
      Log.info("topFeats=" + topFeats);
      
      // DEBUG
//      ibs = ReservoirSample.sample(ibs, 10, new Random(9001));

      PmiCounts c = new PmiCounts(shard);
      List<ByInstance> bis = new ArrayList<>();
      
      // Add pos first, allowing the alphabet to grow
      for (File f : ibs) {
        File entdir = f.getParentFile();
        ByInstance bi = new ByInstance(entdir);
        boolean addPos = true;
        boolean addNeg = false;
        bi.update(c, addPos, addNeg);
        bis.add(bi);
      }
      writeoutPmi(new File(output.getPath() + ".afterPos"), c, topFeats, pmiFreqDiscount);

      // Add neg with only feats from pos
      int nneg = 0;
      for (ByInstance bi : bis) {
        boolean addPos = false;
        boolean addNeg = true;
        bi.update(c, addPos, addNeg);
        nneg++;
        if (nneg >= 16 && PmiUpgrades.isPosPowerOfTwo(nneg)) {
          String suf = String.format(".afterPosAndNeg%05d", nneg);
          writeoutPmi(new File(output.getPath() + suf), c, topFeats, pmiFreqDiscount);
        }
      }

      // Compute PMI
      writeoutPmi(output, c, topFeats, pmiFreqDiscount);
    }
    
//    /** run this after all ByInstances have had update called on them */
//    public static void sortInvIdxLists(PmiCounts f) {
//      for (IntObjectHashMap<IntArrayList>.Iterator iter : Arrays.asList(f.x2posI.iterator(), f.y2posI.iterator())) {
//        while (iter.hasNext()) {
//          iter.advance();
//          IntArrayList a = iter.value();
//          a.sortAsc();
//        }
//      }
//    }
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);


    boolean extractionsAsInstances = config.getBoolean("extractionsAsInstances");
    Log.info("extractionsAsInstances=" + extractionsAsInstances);

    if (extractionsAsInstances)
      ByInstance.main(config);
    else
      ByEntity.main(config);
    
    Log.info("done");
  }
}
