package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Iterables;

import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.TemplateContext;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;

/**
 * Reads in feature counts from disk, and then uses these counts to implement
 * "someTemplate-Top1000" and "someTemplate-Cnt8" style templates.
 *
 * @author travis
 */
public class TemplateTransformerTemplate {

//  private Map<String, Counts<Integer>> t2fcounts;
  private FeatureCounts.FromFile tfCounts;
  private Map<IntPair, String> tf2str;
  private Map<String, Integer> tAlph;

  private Set<String> specialTemplates;

  /**
   * Once the Top and Cnt instances have been created, you can free up memory
   * with this method.
   */
  public void free() {
    tfCounts = null;
    tf2str = null;
    tAlph = null;
  }

  public TemplateTransformerTemplate(File tfCountFile, File bialph, String featuresWithPluses) throws IOException {
    this(tfCountFile, bialph, TemplatedFeatures.tokenizeTemplates(featuresWithPluses));
  }

  /**
   * @param tfCountFile
   * @param features
   * @throws IOException 
   */
  public TemplateTransformerTemplate(File tfCountFile, File bialph, List<String> features) throws IOException {
    if (bialph.getPath().contains("jser"))
      Log.warn("you almost certainly have " + bialph.getPath() + " wrong, should be 4 col TSV");
    // Read in the set of templates we need to keep track of
    specialTemplates = new HashSet<>();
    int seen = 0;
    Set<String> tKeep = new HashSet<>();
    for (String tprod : features) {
      for (final String t : TemplatedFeatures.tokenizeProducts(tprod)) {
        seen++;
        String pre = normalizeIfRelevant(t);
        if (pre != null) {
          tKeep.add(pre);
          specialTemplates.add(t);
        }
      }
    }
    Log.info("saw " + seen + " templates and kept stats for " + tKeep.size() + " of them");

    // (t,f) counts with int keys
    tfCounts = new FeatureCounts.FromFile(tfCountFile, tKeep);

    // load (t,f):IntPair -> featureName:String
    int keptFeatStr = 0;
    TimeMarker tm = new TimeMarker();
    int lines = 0;
    tAlph = new HashMap<>();
    tf2str = new HashMap<>();
    try (BufferedReader r = FileUtil.getReader(bialph)) {
      BiAlph.Line bl = new BiAlph.Line(null, LineMode.ALPH);
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        if (tm.enoughTimePassed(15)) {
          Log.info("read " + lines + " lines in " + tm.secondsSinceFirstMark() + " sec");
        }
        lines++;
        bl.set(line, LineMode.ALPH);
        if (tKeep.contains(bl.stringTemplate)) {
          tAlph.put(bl.stringTemplate, bl.newIntTemplate);
//          if (tfCounts.getCount(bl.newIntTemplate, bl.newIntFeature) >= 8) {
            keptFeatStr++;
            IntPair key = new IntPair(bl.newIntTemplate, bl.newIntFeature);
            Object old = tf2str.put(key, bl.stringFeature);
            assert old == null;
//          }
        }
      }
    }
    Log.info("kept " + keptFeatStr + " feature strings");
  }

  public static String normalizeIfRelevant(String t) {
    int c;
    if ((c = t.indexOf("-Top")) > 0)
      return t.substring(0, c);
    if ((c = t.indexOf("-Cnt")) > 0)
      return t.substring(0, c);
    return null;
  }

  // Contains the name -> Template mapping for the special templates constructed
  // by this class.
  public Map<String, Template> getSpecialTemplates(BasicFeatureTemplates bft) {
    HashMap<String, Template> m = new HashMap<>();
    for (String t : this.specialTemplates) {
      String pre = normalizeIfRelevant(t);
      Template base = bft.getBasicTemplate(pre);
      int c = t.lastIndexOf('-');
      String mod = t.substring(c+1, t.length());
      if (mod.contains("Top")) {
        int k = Integer.parseInt(mod.substring(3));
        m.put(t, this.new Top(k, base, pre, t));
      } else {
        assert mod.contains("Cnt");
        int cc = Integer.parseInt(mod.substring(3));
        m.put(t, this.new Cnt(cc, base, pre, t));
      }
    }
    return m;
  }

  public class Top implements Template {
    private Template wrapped;
    private Set<String> keep;
    private String name;
    public Top(int k, Template wrapped, String pre, String name) {
      this.name = name;
      this.wrapped = wrapped;
      int t = tAlph.get(pre);
      Counts<Integer> fcounts = tfCounts.getCounts(t);
      int idx = tfCounts.maxNewFeatureIndexInTop(k, t);
      List<Integer> l = fcounts.getKeysSortedByCount(true);
      if (l.size() > idx)
        l = l.subList(0, idx);
      keep = new HashSet<>();
      for (int f : l) {
        String fs = tf2str.get(new IntPair(t, f));
        assert fs != null;
        keep.add(fs);
      }
    }
    public String getName() {
      return name;
    }
    @Override
    public Iterable<String> extract(TemplateContext context) {
      Iterable<String> itr = wrapped.extract(context);
      if (itr == null)
        return null;
      return Iterables.filter(itr, keep::contains);
    }
  }

  public class Cnt implements Template {
    private Template wrapped;
    private Set<String> keep;
    private String name;
    public Cnt(int c, Template wrapped, String pre, String name) {
      this.name = name;
      this.wrapped = wrapped;
      int t = tAlph.get(pre);
      Counts<Integer> fcounts = tfCounts.getCounts(t);
      List<Integer> l = fcounts.countIsAtLeast(c);
      keep = new HashSet<>();
      for (int f : l) {
        String fs = tf2str.get(new IntPair(t, f));
        assert fs != null;
        keep.add(fs);
      }
    }
    public String getName() {
      return name;
    }
    @Override
    public Iterable<String> extract(TemplateContext context) {
      Iterable<String> itr = wrapped.extract(context);
      if (itr == null)
        return null;
      return Iterables.filter(itr, keep::contains);
    }
  }
}
