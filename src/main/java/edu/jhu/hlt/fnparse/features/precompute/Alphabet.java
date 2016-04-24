package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.AlphabetLine;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.TemplateAlphabet;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.FindReplace;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

/**
 * Stores an Alphabet for many templates.
 *
 * Used to be called Templates.
 *
 * @author travis
 */
public class Alphabet extends ArrayList<TemplateAlphabet> {

  private static final long serialVersionUID = -5960052683453747671L;

  private HeadFinder hf;
  private Map<String, Integer> templateName2index;

  /** Reads all templates out of {@link BasicFeatureTemplates} */
  public Alphabet() {
    this(new BasicFeatureTemplates());
  }

  public Alphabet(BasicFeatureTemplates templateIndex) {
    super();
    this.hf = new SemaforicHeadFinder();
    this.templateName2index = new HashMap<>();
    for (String tn : templateIndex.getBasicTemplateNames()) {
      Template t = templateIndex.getBasicTemplate(tn);
      this.add(new TemplateAlphabet(t, tn, size()));
    }
  }

  @Override
  public boolean add(TemplateAlphabet t) {
    assert t.name != null;
    assert t.index == size();
    Integer old = templateName2index.put(t.name, t.index);
    assert old == null;
    return super.add(t);
  }

  public TemplateAlphabet get(String templateName) {
    return get(templateName2index.get(templateName));
  }

  /** Parses templates and alphabets in same format written by {@link Alphabet#toFile(File)} */
  public Alphabet(File file) {
    this(file, true);
  }
  public Alphabet(File file, boolean header) {
    // Parse file like:
    // # templateIndex featureIndex templateName featureName
    // 0       0       head1head2PathNgram-POS-DIRECTION-len4  head1head2PathNgram-POS-DIRECTION-len4=.<ROOT>
    // 0       1       head1head2PathNgram-POS-DIRECTION-len4  head1head2PathNgram-POS-DIRECTION-len4=<ROOT>VBN
    // ...
    // TODO After the iterate method below is tested, refactor this code to use it.
    this.templateName2index = new HashMap<>();
    this.hf = new SemaforicHeadFinder();
    Log.info("reading template alphabets from " + file.getPath());
    BasicFeatureTemplates templateMap = null;
    try {
      templateMap = new BasicFeatureTemplates();
    } catch (Exception e) {
      Log.warn("can't load Templates due to:");
      e.printStackTrace();
    }
    try (BufferedReader r = FileUtil.getReader(file)) {
      if (header) {
        String h = r.readLine();
        assert h.charAt(0) == '#';
      }
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        AlphabetLine al = new AlphabetLine(line);
        if (al.template == size()) {
          Template t = templateMap == null ? null : templateMap.getBasicTemplate(al.templateName);
          add(new TemplateAlphabet(t, al.templateName, al.template));
        }
        TemplateAlphabet t = get(al.template);
        int featureIndexNew = t.alph.lookupIndex(al.featureName, true);
        assert al.feature == featureIndexNew : "old=" + al.feature + " new=" + featureIndexNew;
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * @deprecated Just use AlphabetLine -- this aggregation by template is
   * probably not what you want.
   *
   * Iterates over every template (i.e. a group of alphabet lines) in a file.
   * Use this when you don't want ot load an entire alphabet into memory but
   * still want someone else to parse the alphabet file format for you.
   *
   * Assumes the file is sorted by template index (first column).
   */
  public static void iterateOverAlphabet(File file, boolean header, Consumer<TemplateAlphabet> callForEveryTemplate) {
    Log.info("reading template alphabets from " + file.getPath());

    // Get the string -> template map
    BasicFeatureTemplates templateMap = null;
    try {
      templateMap = new BasicFeatureTemplates();
    } catch (Exception e) {
      Log.warn("can't load Templates due to:");
      e.printStackTrace();
    }

    // Iterate over the file
    TemplateAlphabet cur = null;
    int curIdx = -1;
    try (BufferedReader r = FileUtil.getReader(file)) {
      if (header) {
        String h = r.readLine();
        assert h.charAt(0) == '#';
      }
//      String prevFeatureName = null;
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        AlphabetLine al = new AlphabetLine(line);
        assert al.template >= 0;
        if (al.template != curIdx) {
          if (cur != null)
            callForEveryTemplate.accept(cur);
          curIdx = al.template;
          Template t = templateMap == null ? null : templateMap.getBasicTemplate(al.templateName);
          cur = new TemplateAlphabet(t, al.templateName, al.template);
//          prevFeatureName = null;
        }
        int featureIndexNew = cur.alph.lookupIndex(al.featureName, true);
//        assert !al.featureName.equals(prevFeatureName) : "prev=" + prevFeatureName + " cur=" + al.featureName;
//        assert prevFeatureName == null || featureIndexNew != cur.alph.lookupIndex(prevFeatureName);
        assert al.feature == featureIndexNew : "old=" + al.feature + " new=" + featureIndexNew + " name=" + al.featureName + " template=" + cur.name + " " + cur.index + " alph.size=" + cur.alph.size();
//        prevFeatureName = al.featureName;
      }
      callForEveryTemplate.accept(cur);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Copies just the template name and index but creates new/empty {@link TemplateAlphabet}s */
  public Alphabet(Alphabet copyTemplateNames) {
    super();
    this.templateName2index = new HashMap<>();
    for (TemplateAlphabet t : copyTemplateNames)
      add(new TemplateAlphabet(t.template, t.name, t.index));
  }

  public HeadFinder getHeadFinder() {
    return hf;
  }

  public void toFile(File f) throws IOException {
    // Save the alphabet
    // template -> feature -> index
    try (BufferedWriter w = FileUtil.getWriter(f)) {
      w.write("# templateIndex featureIndex templateName featureName\n");
      for (TemplateAlphabet t : this) {
        int n = t.alph.size();
        if (n == 0)
          w.write(t.index + "\t0\t" + t.name + "\t<NO-FEATURES>\n");
        for (int i = 0; i < n; i++) {
          w.write(t.index
              + "\t" + i
              + "\t" + t.name
              + "\t" + t.alph.lookupObject(i)
              + "\n");
        }
      }
    }
  }

  /* BELOW THIS is some procedural stuff that isn't really used any more */

  /**
   * Knows how to replace strings given an alphabet/hashmap file generated by
   * {@link FeaturePrecomputation}.
   *
   * Only uses name, index, and alphabet from {@link Template}, meaning you
   * don't need instances of {@link TemplateAlphabet}.
   *
   * @deprecated Old way of doing things. Don't re-write feature files, just
   * merge bialphs.
   */
  public static class AlphabetReplacer {
    // i1 -> string
    // i2 -> string
    private Alphabet a1, a2;

    // string:string -> i3
    Alphabet merged;

    public AlphabetReplacer(File alphabet1File, File alphabet2File) {
      a1 = new Alphabet(alphabet1File);
      a2 = new Alphabet(alphabet2File);
      // Verify that a1 and a2 have the same list of templates
      assert a1.size() == a2.size();
      for (int i = 0; i < a1.size(); i++) {
        TemplateAlphabet t1 = a1.get(i);
        TemplateAlphabet t2 = a2.get(i);
        assert t1.index == t2.index && t2.index == i;
        assert t1.name.equals(t2.name);
      }
      merged = new Alphabet(a1);
    }

    // int -> string -> int
    public String replace(String input, boolean firstAlphabet) {
      int colon = input.indexOf(':');
      int template = Integer.parseInt(input.substring(0, colon));
      int feature = Integer.parseInt(input.substring(colon + 1));
      TemplateAlphabet t = (firstAlphabet ? a1 : a2).get(template);
      String f = t.alph.lookupObject(feature);
      int x = merged.get(t.index).alph.lookupIndex(f, true);
      return t.index + ":" + x;
    }
  }

  public static void test() throws IOException {
      // Merge
      merge(
          new File("/tmp/wd1/template-feat-indices.txt.gz"),
          new File("/tmp/wd1/features.txt.gz"),
          new File("/tmp/wd2/template-feat-indices.txt.gz"),
          new File("/tmp/wd2/features.txt.gz"),
          new File("/tmp/merged/template-feat-indices.txt.gz"),
          new File("/tmp/merged/features.txt.gz"));

      // int features -> string features for wd1
      new FindReplace(BiAlphMerger::findTemplateFeatureMentions,
          new BiAlphMerger.DeIntifier(new File("/tmp/wd1/template-feat-indices.txt.gz"))::replace)
            .findReplace(new File("/tmp/wd1/features.txt.gz"),
                new File("/tmp/wd1/features-strings.txt.gz"));

      // int features -> string features for wd2
      new FindReplace(BiAlphMerger::findTemplateFeatureMentions,
          new BiAlphMerger.DeIntifier(new File("/tmp/wd2/template-feat-indices.txt.gz"))::replace)
            .findReplace(new File("/tmp/wd2/features.txt.gz"),
                new File("/tmp/wd2/features-strings.txt.gz"));

      // int features -> string features for merged
      new FindReplace(BiAlphMerger::findTemplateFeatureMentions,
          new BiAlphMerger.DeIntifier(new File("/tmp/merged/template-feat-indices.txt.gz"))::replace)
            .findReplace(new File("/tmp/merged/features.txt.gz"),
                new File("/tmp/merged/features-strings.txt.gz"));
  //    new FindReplace(AlphabetMerger::findFeatureKeys,
  //        new DeIntifier(new File("/tmp/merged/template-feat-indices.txt.gz"))::replace)
  //          .findReplace(new File("/tmp/merged/features.txt"),
  //              new File("/tmp/merged/features-strings-nogz.txt"));

      Log.info("done");
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
    merge(
        config.getExistingFile("alphIn1"),
        config.getExistingFile("featIn1"),
        config.getExistingFile("alphIn2"),
        config.getExistingFile("featIn2"),
        config.getFile("alphOut"),
        config.getFile("featOut"));
  }

  /**
   * Merges by re-writing the feature files and catting them into a single
   * output file. Requires the union of the two given alphabets to fit in memory.
   *
   * @deprecated Should use the alphabet merge instead (don't re-write features).
   */
  public static void merge(
      File alph1, File inputFeatures1,
      File alph2, File inputFeatures2,
      File outputAlphabet, File outputFeatures) throws IOException {

    Log.info(Describe.memoryUsage());
    AlphabetReplacer ar = new AlphabetReplacer(alph1, alph2);

    // Convert the first file
    Log.info(Describe.memoryUsage());
    Log.info("converting " + inputFeatures1.getPath() + " => " + outputFeatures.getPath());
    new FindReplace(BiAlphMerger::findTemplateFeatureMentions, s -> ar.replace(s, true))
      .findReplace(inputFeatures1, outputFeatures, false);

    // Convert the second file (append to output)
    Log.info(Describe.memoryUsage());
    Log.info("converting " + inputFeatures2.getPath() + " => " + outputFeatures.getPath());
    new FindReplace(BiAlphMerger::findTemplateFeatureMentions, s -> ar.replace(s, false))
      .findReplace(inputFeatures2, outputFeatures, true);

    // Save the alphabet
    Log.info(Describe.memoryUsage());
    Log.info("saving merged alphabet to " + outputAlphabet.getPath());
    ar.merged.toFile(outputAlphabet);

    Log.info("done!");
  }
}