package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.File;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.fnparse.features.TemplatedFeatures;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;

/**
 * Utilities for getting feature sets from file and parsing.
 *
 * Does not read feature files (i.e. the feautres that were extracted for every
 * (y,x) instance), which are handled by {@link FeatureFile}.
 *
 * @see TemplatedFeatures
 *
 * @author travis
 */
public class FeatureSet {
  public static int DEBUG = 1;

  /** Returns a set of templates (in sorted order) contained in the given feature set */
  public int[] getTemplateSet(int[][] featureSet) {
    List<Integer> l = new ArrayList<>();
    BitSet bs = new BitSet();
    for (int[] f : featureSet) {
      for (int fi : f) {
        if (!bs.get(fi)) {
          bs.set(fi);
          l.add(fi);
        }
      }
    }
    Collections.sort(l);
    int[] uniq = new int[l.size()];
    for (int i = 0; i < l.size(); i++)
      uniq[i] = l.get(i);
    return uniq;
  }

  /**
   * @param f see {@link FeatureSet#getFeatureSetString(File)}
   * @param bialph provides mapping from template names to ints
   * @return a list of features, each represented as an int[] of templates.
   */
  public static List<int[]> getFeatureSet(File f, BiAlph bialph) {
    String fs = getFeatureSetString(f);
    List<int[]> tokenized = parseFeatureSet(fs, bialph);
    return tokenized;
  }
  public static int[][] getFeatureSet2(File f, BiAlph bialph) {
//    return list2array(getFeatureSet(f, bialph));
    List<int[]> fs = getFeatureSet(f, bialph);
    return fs.toArray(new int[0][]);
  }
  public static List<String[]> getFeatureSet3(File f) {
    if (DEBUG > 0)
      Log.info("loading feature set from " + f.getPath());
    List<String[]> features = new ArrayList<>();
    Set<String> templates = new HashSet<>();
    String fs = getFeatureSetString(f);
    for (String featureString : TemplatedFeatures.tokenizeTemplates(fs)) {
      List<String> strTemplates = TemplatedFeatures.tokenizeProducts(featureString);
      templates.addAll(strTemplates);
      features.add(strTemplates.toArray(new String[strTemplates.size()]));
    }
    if (DEBUG > 0)
      Log.info("[main] loaded " + features.size() + " features covering " + templates.size() + " templates");
    return features;
  }


  /**
   * Reads the 7 column tab-separated format:
   * score, ig, hx, selectivity, arity, intTemplates, stringTemplates
   */
  public static String getFeatureSetString(File f) {
    if (DEBUG > 0)
      Log.info("[main] reading from " + f.getPath());
    if (!f.isFile())
      throw new IllegalArgumentException("not a file: " + f.getPath());
    List<String> features = new ArrayList<>();
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] toks = line.split("\t");
        if (toks.length != 8)
          Log.warn("unknown line format: " + line);


//        // TODO TEMPORARY HACK
//        int c;
//        if ((c = toks[7].indexOf("-Top")) > 0)
//          toks[7] = toks[7].substring(0, c);
//        if ((c = toks[7].indexOf("-Cnt")) > 0)
//          toks[7] = toks[7].substring(0, c);


        features.add(toks[7]);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return StringUtils.join(" + ", features);
  }

//  public static <T> T[] list2array(List<T> ts) {
//    @SuppressWarnings("unchecked")
//    T[] t = (T[]) new Object[ts.size()];
//    for (int i = 0; i < t.length; i++)
//      t[i] = ts.get(i);
//    return t;
//  }

  /**
   * @param fs is the names of a feature set like "foo + bar * baz"
   * @param bialph provides mapping from template names to ints
   * @return a list of features, each represented as an int[] of templates.
   */
  public static List<int[]> parseFeatureSet(String fs, BiAlph bialph) {
    ExperimentProperties config = ExperimentProperties.getInstance();
    boolean allowLossyAlphForFS = config.getBoolean("allowLossyAlphForFS", false);
    List<int[]> features = new ArrayList<>();
    BitSet templates = new BitSet();
    for (String featureString : TemplatedFeatures.tokenizeTemplates(fs)) {
      List<String> strTemplates = TemplatedFeatures.tokenizeProducts(featureString);
      int n = strTemplates.size();
      int[] intTemplates = new int[n];
      for (int i = 0; i < n; i++) {
        String tn = strTemplates.get(i);
        Log.info("looking up template: " + tn);
        int t = bialph.mapTemplate(tn);
        if (t < 0 && allowLossyAlphForFS) {
          Log.info("skipping because " + tn + " was not in the alphabet");
          continue;
        }
        assert t >= 0 : "couldn't find \"" + tn + "\" in " + bialph.getSource().getPath();
        intTemplates[i] = t;
        templates.set(t);
      }
      features.add(intTemplates);
    }
    if (DEBUG > 0)
      Log.info("[main] loaded " + features.size() + " features covering " + templates.cardinality() + " templates");
    return features;
  }
}
