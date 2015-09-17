package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Templates;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Tmpl;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.FindReplace;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;

/**
 * Given two alphabets with rows:
 *  (templateIndex, featureIndex, templateName, featureName)
 * Merge them into two int->int mappings.
 *
 * NOTE: Previously I had done
 *  ((features, alphabet), (features, alphabet)) -> (features, alphabet)
 * But this does un-necessary work because
 * 1) it re-writes the features at every step
 * 2) it requires this re-writing be done with a HashMap lookup in the tightest loop.
 * The approach above is lazy in features and comes up with many alphabet
 * re-writes. These amounts to a sort of the two alphabets (by strings not ints)
 * followed by a very simple merge step to produce the two int->int mappings.
 * This has the additional benefit of basically being out of memory (sort),
 * so you don't have to worry about ever loading the string->int map into memory.
 *
 * TODO Rename to BiAlphMerger
 *
 * @author travis
 */
public class AlphabetMerger {

  /**
   * Knows how to find feature keys in the file outputted by
   * {@link FeaturePrecomputation}. Highlights strings like "10:44" using
   * [start,end).
   */
  public static Iterator<IntPair> findFeatureKeys(String input) {
    int offset = 0;
    for (int i = 0; i < 5; i++) {
      int tab = input.indexOf('\t', offset);
      offset = tab + 1;
    }
    List<IntPair> pairs = new ArrayList<>();
    while (true) {
      int tab = input.indexOf('\t', offset);
      if (tab < 0) {
        // Last feature doesn't have a tab after it
        pairs.add(new IntPair(offset, input.length()));
        break;
      }
      pairs.add(new IntPair(offset, tab));
      offset = tab + 1;
    }
    return pairs.iterator();
  }

  /**
   * Knows how to replace strings given an alphabet/hashmap file generated by
   * {@link FeaturePrecomputation}.
   *
   * Only uses name, index, and alphabet from {@link Template}, meaning you
   * don't need instances of {@link Tmpl}.
   *
   * @deprecated Old way of doing things. Don't re-write feature files, just
   * merge bialphs.
   */
  public static class AlphabetReplacer {
    // i1 -> string
    // i2 -> string
    private Templates a1, a2;

    // string:string -> i3
    private Templates merged;

    public AlphabetReplacer(File alphabet1File, File alphabet2File) {
      a1 = new Templates(alphabet1File);
      a2 = new Templates(alphabet2File);
      // Verify that a1 and a2 have the same list of templates
      assert a1.size() == a2.size();
      for (int i = 0; i < a1.size(); i++) {
        Tmpl t1 = a1.get(i);
        Tmpl t2 = a2.get(i);
        assert t1.index == t2.index && t2.index == i;
        assert t1.name.equals(t2.name);
      }
      merged = new Templates(a1);
    }

    // int -> string -> int
    public String replace(String input, boolean firstAlphabet) {
      int colon = input.indexOf(':');
      int template = Integer.parseInt(input.substring(0, colon));
      int feature = Integer.parseInt(input.substring(colon + 1));
      Tmpl t = (firstAlphabet ? a1 : a2).get(template);
      String f = t.alph.lookupObject(feature);
      int x = merged.get(t.index).alph.lookupIndex(f, true);
      return t.index + ":" + x;
    }
  }

  /**
   * Merges by re-writing the feature files and catting them into a single
   * output file. Requires the union of the two given alphabets to fit in memory.
   *
   * @deprecated Should use the alphabet merge instead (don't re-write features).
   */
  public static void findReplace(
      File alph1, File inputFeatures1,
      File alph2, File inputFeatures2,
      File outputAlphabet, File outputFeatures) throws IOException {

    Log.info(Describe.memoryUsage());
    AlphabetReplacer ar = new AlphabetReplacer(alph1, alph2);

    // Convert the first file
    Log.info(Describe.memoryUsage());
    Log.info("converting " + inputFeatures1.getPath() + " => " + outputFeatures.getPath());
    new FindReplace(AlphabetMerger::findFeatureKeys, s -> ar.replace(s, true))
      .findReplace(inputFeatures1, outputFeatures, false);

    // Convert the second file (append to output)
    Log.info(Describe.memoryUsage());
    Log.info("converting " + inputFeatures2.getPath() + " => " + outputFeatures.getPath());
    new FindReplace(AlphabetMerger::findFeatureKeys, s -> ar.replace(s, false))
      .findReplace(inputFeatures2, outputFeatures, true);

    // Save the alphabet
    Log.info(Describe.memoryUsage());
    Log.info("saving merged alphabet to " + outputAlphabet.getPath());
    ar.merged.toFile(outputAlphabet);

    Log.info("done!");
  }

  public static class TIdx {
    public int template;
    public int feature;
    public TIdx(int template, int feature) {
      this.template = template;
      this.feature = feature;
    }
    /** Reads from newInt and sets template and feature */
    public void set(BiAlph.Line al) {
      this.template = al.newIntTemplate;
      this.feature = al.newIntFeature;
    }
    public void incrementTemplate() {
      template++;
      feature = 0;
    }
    public void incrementFeature() {
      feature++;
    }
    @Override
    public String toString() {
      return "(TIdx t=" + template + " f=" + feature + ")";
    }
  }

  /**
   * Merges two bialphs.
   *
   * Reads and writes the format:
   *  (newIntTemplate, newIntFeature, stringTemplate, stringFeature, oldIntTemplate, oldIntFeature)
   * So the first four columns are a valid (template,feature) alphabet, but the
   * last two provide the (int,int) -> (int,int) mapping needed to take the
   * original feature extractions into a coherent alphabet. Some rows will not
   * have oldInt fields. newInt and strings are unioned during merges and are
   * present in every row. For rows with no oldInt values, -1 is used.
   *
   * a1 and a2 should be sorted by stringTemplate, then stringFeature.
   *
   * NOTE: Make sure you set the locale: `LC_ALL=C sort -t '\t' -k 3,4 ...`,
   * and make sure that the `sort` command immediately follows `LC_ALL=C`.
   */
  public static void mergeAlphabets(File in1, File in2, File out1, File out2, boolean header) throws IOException {
    Log.info("header=" + header);
    Log.info("merging " + in1.getPath() + "  =>  " + out1.getPath());
    Log.info("merging " + in2.getPath() + "  =>  " + out2.getPath());
    try (BufferedReader r1 = FileUtil.getReader(in1);
        BufferedReader r2 = FileUtil.getReader(in2);
        BufferedWriter w1 = FileUtil.getWriter(out1);
        BufferedWriter w2 = FileUtil.getWriter(out2)) {
      if (header) {
        // Strip off the header
        String h1 = r1.readLine();
        String h2 = r2.readLine();
        assert h1.charAt(0) == '#' && h1.equals(h2);
        // Write the header
        w1.write(h1); w1.newLine();
        w2.write(h2); w2.newLine();
      }
      // Merge the remaining lines
      String prevTemplate = "";
      BiAlph.Line l1 = new BiAlph.Line(r1.readLine());
      BiAlph.Line l2 = new BiAlph.Line(r2.readLine());
      TIdx i = new TIdx(0, 0);                      // sets values for newInt
      while (!l1.isNull() && !l2.isNull()) {
        int c;
        if (l1.isNull()) {
          c = 1;
        } else if (l2.isNull()) {
          c = -1;
        } else {
          c = BiAlph.Line.BY_TEMPLATE_STR_FEATURE_STR.compare(l1, l2);
        }
        if (c == 0) {
          // both s1 and s2 get i, update both
          if (!prevTemplate.equals(l1.stringTemplate)) {
            i.incrementTemplate();
            prevTemplate = l1.stringTemplate;
          }
          write(w1, i, l1, l1);
          write(w2, i, l2, l2);
          l1.set(r1.readLine());
          l2.set(r2.readLine());
        } else if (c < 0) {
          // s1 gets i, update s1
          if (!prevTemplate.equals(l1.stringTemplate)) {
            i.incrementTemplate();
            prevTemplate = l1.stringTemplate;
          }
          write(w1, i, l1, l1);
          write(w2, i, l1, null);
          l1.set(r1.readLine());
        } else {
          // s2 gets i, update s2
          if (!prevTemplate.equals(l2.stringTemplate)) {
            i.incrementTemplate();
            prevTemplate = l2.stringTemplate;
          }
          write(w1, i, l2, null);
          write(w2, i, l2, l2);
          l2.set(r2.readLine());
        }
        i.incrementFeature();
      }
      Log.info("done, i=" + i);
    }
  }
  /**
   * @param o may be null if there is no corresponding oldInt* for this row
   */
  private static void write(BufferedWriter w, TIdx i, BiAlph.Line s, BiAlph.Line o) throws IOException {
    int oldIntTemplate = -1;
    int oldIntFeature = -1;
    if (o != null) {
      oldIntTemplate = o.oldIntTemplate;
      oldIntFeature = o.oldIntFeature;
    }
    w.write(i.template
        + "\t" + i.feature
        + "\t" + s.stringTemplate
        + "\t" + s.stringFeature
        + "\t" + oldIntTemplate
        + "\t" + oldIntFeature
        + "\n");
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    mergeAlphabets(
        config.getExistingFile("inAlph1"),
        config.getExistingFile("inAlph2"),
        config.getFile("outAlph1"),
        config.getFile("outAlph2"),
        config.getBoolean("header", false));
  }

  public static void oldMain(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    if (config.contains("help")) {
      System.out.println("please provide:");
      System.out.println("featIn1: an input feature file (made by FeaturePrecomputation)");
      System.out.println("alphIn1: an input alphabet (made by FeaturePrecomputation)");
      System.out.println("featIn2: an input feature file (made by FeaturePrecomputation)");
      System.out.println("alphIn2: an input alphabet (made by FeaturePrecomputation)");
      System.out.println("featOut: an output feature file");
      System.out.println("alphOut: an output alphabet");
      return;
    }
    findReplace(
        config.getExistingFile("alphIn1"),
        config.getExistingFile("featIn1"),
        config.getExistingFile("alphIn2"),
        config.getExistingFile("featIn2"),
        config.getFile("alphOut"),
        config.getFile("featOut"));
  }

  public static void test() throws IOException {
    // Merge
    findReplace(
        new File("/tmp/wd1/template-feat-indices.txt.gz"),
        new File("/tmp/wd1/features.txt.gz"),
        new File("/tmp/wd2/template-feat-indices.txt.gz"),
        new File("/tmp/wd2/features.txt.gz"),
        new File("/tmp/merged/template-feat-indices.txt.gz"),
        new File("/tmp/merged/features.txt.gz"));

    // int features -> string features for wd1
    new FindReplace(AlphabetMerger::findFeatureKeys,
        new DeIntifier(new File("/tmp/wd1/template-feat-indices.txt.gz"))::replace)
          .findReplace(new File("/tmp/wd1/features.txt.gz"),
              new File("/tmp/wd1/features-strings.txt.gz"));

    // int features -> string features for wd2
    new FindReplace(AlphabetMerger::findFeatureKeys,
        new DeIntifier(new File("/tmp/wd2/template-feat-indices.txt.gz"))::replace)
          .findReplace(new File("/tmp/wd2/features.txt.gz"),
              new File("/tmp/wd2/features-strings.txt.gz"));

    // int features -> string features for merged
    new FindReplace(AlphabetMerger::findFeatureKeys,
        new DeIntifier(new File("/tmp/merged/template-feat-indices.txt.gz"))::replace)
          .findReplace(new File("/tmp/merged/features.txt.gz"),
              new File("/tmp/merged/features-strings.txt.gz"));
//    new FindReplace(AlphabetMerger::findFeatureKeys,
//        new DeIntifier(new File("/tmp/merged/template-feat-indices.txt.gz"))::replace)
//          .findReplace(new File("/tmp/merged/features.txt"),
//              new File("/tmp/merged/features-strings-nogz.txt"));

    Log.info("done");
  }

  /** For converting int feature files to String feature files for debugging */
  public static class DeIntifier {
    private Templates templates;
    public DeIntifier(File templatesAlphabet) {
      this.templates = new Templates(templatesAlphabet);
    }
    public String replace(String input) {
      int colon = input.indexOf(':');
      int template = Integer.parseInt(input.substring(0, colon));
      int feature = Integer.parseInt(input.substring(colon + 1));
      return templates.get(template).alph.lookupObject(feature);
    }
  }
}
