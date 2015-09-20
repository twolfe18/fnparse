package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;

/**
 * Takes a feature file of ints and sorts the features by template, then feature.
 *
 * Reads the format put out by {@link FeaturePrecomputation#emit(java.io.Writer, edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Target, edu.jhu.hlt.fnparse.datatypes.Span, int, java.util.List)}
 *
 * @author travis
 */
public class FeatureFileSorter {

  public static char IFS = '\t';
  public static char OFS = '\t';

  /**
   * Sorts the template:feature entries without modifying the preceding fields.
   * Does not add a new line at the end of the string.
   */
  public static String rewrite(String input) {
    // docId, sentId, target, span, role, feat+
    StringBuilder output = new StringBuilder();
    String[] toks = input.split(String.valueOf(IFS));
    final int numPrefixFields = 5;
    for (int i = 0; i < numPrefixFields; i++) {
      if (i > 0) output.append(OFS);
      output.append(toks[i]);
    }
    List<IntPair> tfs = new ArrayList<>(toks.length - numPrefixFields);
    for (int i = numPrefixFields; i < toks.length; i++)
     tfs.add(BiAlphMerger.parseTemplateFeature(toks[i]));
    Collections.sort(tfs, IntPair.ASCENDING);
    for (int i = 0; i < tfs.size(); i++) {
      output.append(OFS);
      output.append(BiAlphMerger.generateTemplateFeature(tfs.get(i)));
    }
    return output.toString();
  }

  public static void rewrite(File input, File output, boolean append) throws IOException {
    Log.info("sorting features in every line: " + input.getPath() + "  ==>  " + output.getPath());
    TimeMarker tm = new TimeMarker();
    try (BufferedReader r = FileUtil.getReader(input);
        BufferedWriter w = FileUtil.getWriter(output, append)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String outline = rewrite(line);
        w.write(outline);
        w.newLine();

        if (tm.enoughTimePassed(15)) {
          Log.info("processed " + tm.numMarks() + " line in "
              + tm.secondsSinceFirstMark() + " seconds, " + Describe.memoryUsage());
        }
      }
    }
    Log.info("done");
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    File input = config.getExistingFile("inputFeatures");
    File output = config.getFile("outputFeatures");
    boolean append = config.getBoolean("append", false);
    rewrite(input, output, append);
  }
}
