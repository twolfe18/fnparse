package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;

/**
 * Merges bialphs by maintaining oldInt*, unioning string*, and rewriting newInt*,
 * where a bialph is:
 *  (newIntTemplate, newIntFeature, stringTemplate, stringFeature, oldIntTemplate, oldIntFeature)
 *
 * NOTE: Previously I had done
 *  ((features, alphabet), (features, alphabet)) -> (features, alphabet)
 * But this does un-necessary work because
 * 1) it re-writes the features at every step
 * 2) it requires this re-writing be done with a HashMap lookup in the tightest loop.
 * The approach above is lazy in features and comes up with many alphabet
 * re-writes. These amounts to a sort of the two alphabets (by strings not ints)
 * followed by a very simple merge step to produce the two int->int mappings.
 * This has the additional benefit of basically being out of memory (sort), so
 * you don't have to worry about ever loading the string->int map into memory.
 *
 * @author travis
 */
public class BiAlphMerger {

  /**
   * Knows how to find feature keys in the file outputted by
   * {@link FeaturePrecomputation}. Highlights strings like "10:44" using
   * [start,end).
   */
  public static Iterator<IntPair> findTemplateFeatureMentions(String input) {
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

  /** Given line = "foo 1:2 bar baz", highlighted = (4,7), gives (1,2) */
  public static IntPair parseTemplateFeature(String line, IntPair highlighted) {
    int colon = line.indexOf(':', highlighted.first);
    String templateString = line.substring(highlighted.first, colon);
    String featureString = line.substring(colon + 1, highlighted.second);
    int template = Integer.parseInt(templateString);
    int feature = Integer.parseInt(featureString);
    return new IntPair(template, feature);
  }

  /** Accepts strings like "22:42" */
  public static IntPair parseTemplateFeature(String templateFeature) {
    int colon = templateFeature.indexOf(':');
    String templateString = templateFeature.substring(0, colon);
    String featureString = templateFeature.substring(colon + 1);
    int template = Integer.parseInt(templateString);
    int feature = Integer.parseInt(featureString);
    return new IntPair(template, feature);
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
      TIdx i = new TIdx(-1, 0);                      // sets values for newInt
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

  /** For converting int feature files to String feature files for debugging */
  public static class DeIntifier {
    private Alphabet templates;
    public DeIntifier(File templatesAlphabet) {
      this.templates = new Alphabet(templatesAlphabet);
    }
    public String replace(String input) {
      int colon = input.indexOf(':');
      int template = Integer.parseInt(input.substring(0, colon));
      int feature = Integer.parseInt(input.substring(colon + 1));
      return templates.get(template).alph.lookupObject(feature);
    }
  }
}
