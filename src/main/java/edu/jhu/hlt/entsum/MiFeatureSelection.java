package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.entsum.VwLine.Namespace;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.TopDownClustering;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.util.Alphabet;

public class MiFeatureSelection {
  
  private IntObjectHashMap<IntArrayList> label2instance;      // values are sorted, uniq, ascending lists/sets
  private IntObjectHashMap<IntArrayList> feature2instance;    // values are sorted, uniq, ascending lists/sets
  private int numInstances;
  
  public MiFeatureSelection() {
    label2instance = new IntObjectHashMap<>();
    feature2instance = new IntObjectHashMap<>();
    numInstances = 0;
  }
  
  public void addInstance(int y, int... x) {
    int instance = numInstances++;
    getOrNew(y, label2instance).add(instance);
    Set<Integer> seen = new HashSet<>();
    for (int i = 0; i < x.length; i++)
      if (seen.add(x[i]))
        getOrNew(x[i], feature2instance).add(instance);
  }
  
  private static IntArrayList getOrNew(int key, IntObjectHashMap<IntArrayList> map) {
    IntArrayList val = map.get(key);
    if (val == null) {
      val = new IntArrayList();
      map.put(key, val);
    }
    return val;
  }
  
  private static IntArrayList intersectSortedLists(IntArrayList a, IntArrayList b) {
    IntArrayList dest = new IntArrayList();
    TopDownClustering.intersectSortedLists(a, b, dest);
    return dest;
  }

  public double pmi(int label, int feature) {
    IntArrayList instancesWithLabel = label2instance.get(label);
    IntArrayList instancesWithFeature = feature2instance.get(feature);
    double logN = Math.log(numInstances);
    return pmi(instancesWithLabel, instancesWithFeature, logN);
  }
  
  private double pmi(IntArrayList instancesWithLabel, IntArrayList instancesWithFeature, double logN) {
    IntArrayList instancesWithBoth = intersectSortedLists(instancesWithLabel, instancesWithFeature);
    double logPxy = Math.log(instancesWithBoth.size()) - logN;
    double logPy = Math.log(instancesWithLabel.size()) - logN;
    double logPx = Math.log(instancesWithFeature.size()) - logN;
    return logPxy - (logPy + logPx);
  }
  
  public static class Pmi {
    public final int label;
    public final int feature;
    public final double pmi;
    
    public Pmi(int label, int feature, double pmi) {
      this.label = label;
      this.feature = feature;
      this.pmi = pmi;
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
  }
  
  public List<Pmi> argTopByPmi(int label, int k) {
    List<Pmi> p = new ArrayList<>();
    double logN = Math.log(numInstances);
    IntArrayList instancesWithLabel = label2instance.get(label);
    IntObjectHashMap<IntArrayList>.Iterator iter = feature2instance.iterator();
    while (iter.hasNext()) {
      iter.advance();
      int feature = iter.key();
      IntArrayList instancesWithFeature = iter.value();
      double pmi = pmi(instancesWithLabel, instancesWithFeature, logN);
      p.add(new Pmi(label, feature, pmi));
    }
    Collections.sort(p, Pmi.BY_PMI_DESC);
    if (k > 0 && p.size() > k) {
      List<Pmi> p2 = new ArrayList<>();
      for (int i = 0; i < k; i++)
        p2.add(p.get(i));
      p = p2;
    }
    return p;
  }
  
  static class Adapater {
    private Alphabet<String> alphY;
    private Alphabet<String> alphX;
    private MiFeatureSelection mifs;
    
    public Adapater() {
      this.alphY = new Alphabet<>();
      this.alphX = new Alphabet<>();
      this.mifs = new MiFeatureSelection();
    }
    
    public void add(String y, File yx) throws IOException {
      Log.info("y=" + y + " yx=" + yx.getPath());
      if (!yx.isFile()) {
        Log.info("warning: not a file");
        return;
      }
      try (BufferedReader r = FileUtil.getReader(yx)) {
        for (String line = r.readLine(); line != null; line = r.readLine())
          add(y, new VwLine(line));
      }
    }
    public void add(String y, VwLine yx) {
      int yi = alphY.lookupIndex(y);
      IntArrayList x = new IntArrayList();
      for (Namespace ns : yx.x) {
        for (String xs : ns.features) {
          int xi = alphX.lookupIndex(xs);
          xi = xi * 256 + ns.name;
          x.add(xi);
        }
      }
      mifs.addInstance(yi, x.toNativeArray());
    }

    public List<Feat> argTopPmi(String label, int k) {
      int y = alphY.lookupIndex(label, false);
      List<Pmi> pmi = mifs.argTopByPmi(y, k);
      List<Feat> out = new ArrayList<>();
      for (Pmi p : pmi)
        out.add(new Feat(alphX.lookupObject(p.feature), p.pmi));
      return out;
    }
    
    public Map<String, List<Feat>> argTopPmiAllLabels(int k) {
      Map<String, List<Feat>> out = new HashMap<>();
      for (int i = 0; i < alphY.size(); i++) {
        String label = alphY.lookupObject(i);
        Object old = out.put(label, argTopPmi(label, k));
        assert old == null;
      }
      return out;
    }
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    File entityDirParent = config.getExistingDir("entityDirParent");
//    List<File> ibs = FileUtil.find(entityDirParent, "glob:**/infobox-binary");
    List<File> ibs = FileUtil.findDirs(entityDirParent, "glob:**/infobox-binary");
    Log.info("found " + ibs.size() + " entity directories");
    Adapater a = new Adapater();
    int n = 0;
    for (File ib : ibs) {
      a.add("neg", new File(ib, "neg.vw"));
      for (File f : ib.listFiles(f -> f.getName().matches("pos-\\S+.vw")))
        a.add(f.getName(), f);
      
      System.out.println("after " + (++n) + " entities:");
      Map<String, List<Feat>> pmi = a.argTopPmiAllLabels(10);
      for (String label : pmi.keySet())
        System.out.printf("%-30s %s\n", label, pmi.get(label));
      System.out.println();
    }
  }
}
