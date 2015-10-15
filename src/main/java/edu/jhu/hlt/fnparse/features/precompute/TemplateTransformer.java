package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.LineByLine;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;

/**
 * X-topK -- strong: filter out features that don't appear in the top K list by frequency, for K [16, 64, 256, 1024, 4096]
 * X-cntK -- weak:   filter out features that appear <K times for K in [3, 4, 5, 6]
 *
 * @author travis
 */
public class TemplateTransformer {

  // Name of template being filtered
  int baseTemplateInt;
  String baseTemplateString;

  // Which features to keep, see FeatureCounts.FromFile
  BitSet featuresToKeep;

  // Name of filtered template
  int newTemplateInt;
  String newTemplateName;

  public TemplateTransformer(
      int baseTemplateInt, String baseTemplateString,
      BitSet featuresToKeep,
      int newTemplateInt, String newTemplateName) {
    this.baseTemplateInt = baseTemplateInt;
    this.baseTemplateString = baseTemplateString;
    this.featuresToKeep = featuresToKeep;
    this.newTemplateInt = newTemplateInt;
    this.newTemplateName = newTemplateName;
  }

  // 80M features * 1 bit * 10 filters = 80MB  => no need to shard
  // write out input line then filtered values
  // input sorted => filtered(input) is sorted (as long as templates are assigned higher numbers)
  public static class Manager implements LineByLine {
    private List<TemplateTransformer>[] t2trans;
    private FeatureFile.Line ffline;
    public boolean expectSortedFeatureFiles = true;
    private BufferedWriter output;

    @SuppressWarnings("unchecked")
    public Manager(File counts, BiAlph bialph) {
      FeatureCounts.FromFile fc;
      try {
        fc = new FeatureCounts.FromFile(counts);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      ffline = new FeatureFile.Line("", expectSortedFeatureFiles);
      // Setup filters
      int T = bialph.getUpperBoundOnNumTemplates();
      int i = 0;
      t2trans = new List[T];
      for (int t = 0; t < T; t++) {
        String oldTemplateName = bialph.lookupTemplate(t);
        if (oldTemplateName == null)
          continue;
        t2trans[t] = new ArrayList<>();
        for (int K : Arrays.asList(16, 64, 256, 1024, 4096)) {
          BitSet keep = fc.freqInTop(K, t);
          String name = oldTemplateName + "-Top" + K;
          TemplateTransformer tt = new TemplateTransformer(t, oldTemplateName, keep, T + (i++), name);
          t2trans[t].add(tt);
        }
        for (int K : Arrays.asList(3, 4, 5, 6)) {
          BitSet keep = fc.countAtLeast(K, t);
          String name = oldTemplateName + "-Cnt" + K;
          TemplateTransformer tt = new TemplateTransformer(t, oldTemplateName, keep, T + (i++), name);
          t2trans[t].add(tt);
        }
      }
    }

    @Override
    public void run(File features) throws IOException {
      File out = getOutputForInput(features);
      this.output = FileUtil.getWriter(out);
      TimeMarker tm = new TimeMarker();
      try (BufferedReader r = FileUtil.getReader(features)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          observeLine(line);
          if (tm.enoughTimePassed(15)) {
            Log.info("processed " + tm.numMarks()
            + " lines in " + tm.secondsSinceFirstMark() + " seconds, "
            + Describe.memoryUsage());
          }
        }
      }
      this.output.close();
    }

    private static File getOutputForInput(File inputFeatureFile) {
      String p = inputFeatureFile.getPath();
      String e = ".txt.gz";
      assert p.endsWith(e);
      return new File(p.substring(0, p.length() - e.length()) + ".withFilters" + e);
    }

    @Override
    public void observeLine(String line) {
      try {
        // Pass the original features through:
        output.write(line);

        // Filter the given features and output them
        ffline.init(line, expectSortedFeatureFiles);
        for (Feature f : ffline.getFeatures()) {
          for (TemplateTransformer trans : t2trans[f.template]) {
            if (trans.featuresToKeep.get(f.feature)) {
              assert trans.newTemplateInt > f.template;
              output.write("\t" + trans.newTemplateInt + ":" + f.feature);
            }
          }
        }

        // Done
        output.newLine();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static void main(String[] args) throws IOException {
    // TODO I still need to write out an expanded alphabet
    ExperimentProperties config = ExperimentProperties.init(args);
    BiAlph bialph = new BiAlph(config.getExistingFile("bialph"), LineMode.ALPH);
    Manager m = new Manager(config.getExistingFile("countFile"), bialph);
    m.runManyFiles(
        config.getString("glob", "glob:**/*"),
        config.getExistingDir("featureFiles"));
  }
}
