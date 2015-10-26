package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.LineByLine;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.prim.map.IntIntHashMap;

/**
 * Accumulates counts of feature occurrences. Note that this is done separate
 * from {@link BiAlphMerger} on account of the set-merge semantics of
 * {@link BiAlphMerger}: it unions observed features, but the files merged may
 * overlap and thus lead to double-counting.
 *
 * @author travis
 */
public class FeatureCounts implements LineByLine {

  public static boolean DEBUG = false;

  /** Reads count files produced by {@link FeatureCounts#main} */
  public static class FromFile {
    public static final int MISSING_VALUE = -1;
    private Counts<Integer>[] t2f2count;

    @SuppressWarnings("unchecked")
    public FromFile(File previousOutputOfThisClassesMain) throws IOException {
      TimeMarker tm = new TimeMarker();
      Log.info("loading counts from " + previousOutputOfThisClassesMain.getPath());
      t2f2count = new Counts[9000];   // TODO dynamic resizing
      int i = 0;
      try (BufferedReader r = FileUtil.getReader(previousOutputOfThisClassesMain)) {
        String header = r.readLine();
        assert header.startsWith("#");
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          i++;
          String[] toks = line.split("\t");
          assert toks.length == 4;
          int t = Integer.parseInt(toks[1]);
          int f = Integer.parseInt(toks[2]);
          int count = Integer.parseInt(toks[3]);

          Counts<Integer> cnt = t2f2count[t];
          if (cnt == null) {
            t2f2count[t] = cnt = new Counts<>();
          }
          cnt.update(f, count);

          if (tm.enoughTimePassed(15)) {
            Log.info("read " + i + " lines in "
                + tm.secondsSinceFirstMark() + " seconds, "
                + Describe.memoryUsage());
          }
        }
      }
      Log.info("done");
    }

    public void free(int template) {
      t2f2count[template] = null;
    }

    public int numFeatures(int template) {
      return t2f2count[template].numNonZero();
    }

    /** Can return -1 */
    public IntIntHashMap mapFeaturesByFrequency(int template) {
      Counts<Integer> cnt = t2f2count[template];
      int newSize = Math.max(10, (int) (4 * Math.log(cnt.numNonZero())));
      IntIntHashMap oldF2newF = new IntIntHashMap(newSize, MISSING_VALUE);
      for (int i : cnt.getKeysSortedByCount(true))
        oldF2newF.put(i, oldF2newF.size());
      return oldF2newF;
    }

    /** Can return -1 */
    public int maxNewFeatureWithCountAtLeast(int c, int template) {
      if (c < 2)
        throw new IllegalArgumentException();
      Counts<Integer> cnt = t2f2count[template];
      int newF = 0;
      for (int i : cnt.getKeysSortedByCount(true)) {
        if (cnt.getCount(i) < c)
          break;
        newF++;
      }
      if (DEBUG)
        Log.info("c=" + c + " template=" + template + " newF=" + newF + " cnt.size=" + cnt.numNonZero());
      return newF - 1;
    }

    public int maxNewFeatureIndexInTop(int k, int template) {
      if (k < 0)
        throw new IllegalArgumentException();
      Counts<Integer> cnt = t2f2count[template];
      int newF = 0;
      int prevCount = -1;
      for (int i : cnt.getKeysSortedByCount(true)) {
        int count = cnt.getCount(i);
        if (newF > k && prevCount != count)
          break;
        prevCount = count;
        newF++;
      }
      assert newF > 0 : "k=" + k + " cnt.size=" + cnt.numNonZero();
      if (DEBUG)
        Log.info("k=" + k + " template=" + template + " newF=" + newF + " cnt.size=" + cnt.numNonZero());
      return newF - 1;
    }

  }

  private int[][] tf2count;
  private FeatureFile.Line ffline;
  public boolean expectsSortedFeatureFiles = true;

  public FeatureCounts(BiAlph bialph) {
    int allocated = 0;
    tf2count = new int[bialph.getUpperBoundOnNumTemplates()][];
    for (int t = 0; t < tf2count.length; t++) {
      int F = bialph.getUpperBoundOnNumFeatures(t);
      tf2count[t] = new int[F];
      allocated += F + 1;
    }
    ffline = new FeatureFile.Line("", expectsSortedFeatureFiles);
    Log.info("allocated around " + (allocated*4)/(1024 * 1024.0) + " MB "
        + " for " + tf2count.length + " (up to) templates, "
        + Describe.memoryUsage());
  }

  @Override
  public void observeLine(String line) {
    ffline.init(line, expectsSortedFeatureFiles);
    for (Feature f : ffline.getFeatures()) {
      int[] r = tf2count[f.template];
      r[f.feature]++;
    }
  }

  public int getCount(int template, int feature) {
    return tf2count[template][feature];
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    File bialphFile = config.getExistingFile("bialph");
    File output = config.getFile("output");
    String featuresGlob = config.getString("featuresGlob", "glob:**/*");
    File featuresParent = config.getExistingDir("featuresParent");

    BiAlph bialph = new BiAlph(bialphFile, LineMode.ALPH);
    FeatureCounts fc = new FeatureCounts(bialph);

    fc.runManyFiles(featuresGlob, featuresParent);

    Log.info("writing counts to " + output.getPath());
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      w.write("# templateString templateInt featureInt count");
      w.newLine();
      int T = bialph.getUpperBoundOnNumTemplates();
      for (int t = 0; t < T; t++) {
        String templateName = bialph.lookupTemplate(t);
        if (templateName == null)
          continue;
        int F = bialph.getUpperBoundOnNumFeatures(t);
        for (int f = 0; f < F; f++) {
          w.write(templateName);
          w.write("\t" + t);
          w.write("\t" + f);
          w.write("\t" + fc.getCount(t, f));
          w.newLine();
        }
      }
    }

  }
}
