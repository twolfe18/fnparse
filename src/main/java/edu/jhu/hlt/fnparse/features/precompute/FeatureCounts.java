package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.BitSet;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.LineByLine;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;

/**
 * Accumulates counts of feature occurrences. Note that this is done separate
 * from {@link BiAlphMerger} on account of the set-merge semantics of
 * {@link BiAlphMerger}: it unions observed features, but the files merged may
 * overlap and thus lead to double-counting.
 *
 * @author travis
 */
public class FeatureCounts implements LineByLine {

  /** Reads count files produced by {@link FeatureCounts#main} */
  public static class FromFile {
    private Counts<Integer>[] t2f2count;
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
                + tm.secondsSinceFirstMark() + " seconds");
          }
        }
      }
    }
    public BitSet countAtLeast(int c, int template) {
      if (c < 2)
        throw new IllegalArgumentException();
      Counts<Integer> cnt = t2f2count[template];
      BitSet bs = new BitSet();
      for (int i : cnt.countIsAtLeast(c))
        bs.set(i);
      return bs;
    }
    public BitSet freqInTop(int k, int template) {
      if (k < 0)
        throw new IllegalArgumentException();
      Counts<Integer> cnt = t2f2count[template];
      BitSet bs = new BitSet();
      int size = 0;
      for (int i : cnt.getKeysSortedByCount(true)) {
        bs.set(i);
        if (++size == k)
          break;
      }
      return bs;
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
    for (Feature f : ffline.getFeatures())
      tf2count[f.template][f.feature]++;
  }

  public int getCount(int template, int feature) {
    return tf2count[template][feature];
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    File bialphFile = config.getExistingFile("bialph");
    File output = config.getFile("output");
    String featuresGlob = config.getString("featuresGlob", "");
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
