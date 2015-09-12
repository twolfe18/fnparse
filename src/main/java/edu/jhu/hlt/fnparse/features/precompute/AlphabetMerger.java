package edu.jhu.hlt.fnparse.features.precompute;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Templates;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Tmpl;
import edu.jhu.hlt.fnparse.util.FindReplace;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;

public class AlphabetMerger {

  /** Knows how to find feature keys in the file outputted by {@link FeaturePrecomputation} */
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

  /** Knows how to replace strings given an alphabet/hashmap file generated by {@link FeaturePrecomputation} */
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

  public static void findReplace(
      File alph1, File inputFeatures1,
      File alph2, File inputFeatures2,
      File outputAlphabet, File outputFeatures) throws IOException {

    AlphabetReplacer ar = new AlphabetReplacer(alph1, alph2);

    // Convert the first file
    Log.info("converting " + inputFeatures1.getPath() + " => " + outputFeatures.getPath());
    new FindReplace(AlphabetMerger::findFeatureKeys, s -> ar.replace(s, true))
      .findReplace(inputFeatures1, outputFeatures, false);

    // Convert the second file (append to output)
    Log.info("converting " + inputFeatures2.getPath() + " => " + outputFeatures.getPath());
    new FindReplace(AlphabetMerger::findFeatureKeys, s -> ar.replace(s, false))
      .findReplace(inputFeatures2, outputFeatures, true);

    // Save the alphabet
    Log.info("saving merged alphabet to " + outputAlphabet.getPath());
    ar.merged.toFile(outputAlphabet);

    Log.info("done!");
  }

  public static void main(String[] args) throws IOException {
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
        config.getExistingFile("featIn1"),
        config.getExistingFile("alphIn1"),
        config.getExistingFile("featIn2"),
        config.getExistingFile("alphIn2"),
        config.getExistingFile("featOut"),
        config.getExistingFile("alphOut"));
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
