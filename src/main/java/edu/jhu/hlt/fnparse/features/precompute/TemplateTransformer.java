package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.LineByLine;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ShardUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.prim.map.IntIntHashMap;

/**
 * Takes templates and makes sparser versions of them based on feature frequency.
 * Comes in two flavors:
 * X-topK -- strong: filter out features that don't appear in the top K list by frequency
 * X-cntK -- weak:   filter out features that appear <K times
 *
 * Note: This does increase the number of templates by a factor of ~6 if you
 * run every template transformer (the data will go up by less than 6x because
 * these are more sparse than the original). Also an attempt is made to only
 * apply these filters to templates which instantiate enough features.
 *
 * @author travis
 */
public class TemplateTransformer {

  public static List<Integer> TOP_K_VALUES = Arrays.asList(10, 100, 1000);
  public static List<Integer> AT_LEAST_K_VALUES = Arrays.asList(8, 16);

  // Name of template being filtered
  int baseTemplateInt;
  String baseTemplateString;

  // A mapping of old to new feature indices. New indices are given out in order
  // of frequency and are shared across all transforms of this template.
  IntIntHashMap featuresToKeep;    // unfiltered -> filtered feature indices

  // All the different transforms of this template
  List<Instance> instances;

  public TemplateTransformer(
      int baseTemplateInt,
      String baseTemplateString,
      IntIntHashMap featuresToKeep) {
    this.baseTemplateInt = baseTemplateInt;
    this.baseTemplateString = baseTemplateString;
    this.featuresToKeep = featuresToKeep;
    this.instances = new ArrayList<>();
  }

  public void addIntance(int newTemplateInt, String partialName, int maxAllowableNewFeatureIndex) {
    instances.add(new Instance(newTemplateInt, partialName, maxAllowableNewFeatureIndex));
  }

  /** A particular transform */
  public class Instance {
    String newTemplateString; // full name e.g. "Foo-Cnt5"
    int newTemplateInt;       // >baseTemplateInt
    int maxAllowableNewFeatureIndex;    // this template only fires if newFeature < this
    public Instance(int newTemplateInt, String partialName, int maxAllowableNewFeatureIndex) {
      this.newTemplateInt = newTemplateInt;
      this.newTemplateString = baseTemplateString + "-" + partialName;
      this.maxAllowableNewFeatureIndex = maxAllowableNewFeatureIndex;
    }
  }

  // old: 80M features * 1 bit * 10 filters = 80MB  => no need to shard
  // old: 80M features * ~8 bytes * 8 filters = ~5GB => no need to shard
  // current: 80M features * ~8 bytes = 640MB => no need to shard
  // This overhead is good, it means I can absorb a hit due to more templates

  // write out input line then filtered values
  // input sorted => filtered(input) is sorted (as long as templates are assigned higher numbers)
  public static class Manager implements LineByLine {

    private TemplateTransformer[] t2trans;  // old template -> list of transforms
    private FeatureFile.Line ffline;
    public boolean expectSortedFeatureFiles = true;
    private File outputDir;
    private BufferedWriter outputFeatureFile;
    private int shard;    // says which files to map over
    private int numShards;

    public Manager(File counts, BiAlph bialph, File outputDir, int shard, int numShards) {
      if (!outputDir.isDirectory())
        throw new IllegalArgumentException("outputDir must be directory: " + outputDir.getPath());
      assert shard < numShards;
      assert shard >= 0;
      this.shard = shard;
      this.numShards = numShards;
      Log.info("shard=" + shard + " numShards=" + numShards);
      // Read in feature counts
      FeatureCounts.FromFile fc;
      try {
        fc = new FeatureCounts.FromFile(counts);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      ffline = new FeatureFile.Line("", expectSortedFeatureFiles);
      this.outputDir = outputDir;
      // Setup filters
      Log.info("setting up template transformers...");
      int kept = 0, total = 0;
      int T = bialph.getUpperBoundOnNumTemplates();
      int i = 0;
      t2trans = new TemplateTransformer[T];
      for (int oldTemplateInt = 0; oldTemplateInt < T; oldTemplateInt++) {
        String oldTemplateName = bialph.lookupTemplate(oldTemplateInt);
        if (oldTemplateName == null)
          continue;
        IntIntHashMap oldF2newF = fc.mapFeaturesByFrequency(oldTemplateInt);
        TemplateTransformer tt = new TemplateTransformer(oldTemplateInt, oldTemplateName, oldF2newF);
        t2trans[oldTemplateInt] = tt;
        for (int K : TOP_K_VALUES) {
          total++;
          int maxAllowableNewFeatureIndex = fc.maxNewFeatureIndexInTop(K, oldTemplateInt);
          if (maxAllowableNewFeatureIndex < 1)
            continue;
          int nf = fc.numFeatures(oldTemplateInt);
          if (nf < K * 2)
            continue;
          if (maxAllowableNewFeatureIndex * 1.1 + 1 >= nf)
            continue;
          kept++;
          tt.addIntance(T + i++, "Top" + K, maxAllowableNewFeatureIndex);
        }
        for (int K : AT_LEAST_K_VALUES) {
          total++;
          int maxAllowableNewFeatureIndex = fc.maxNewFeatureWithCountAtLeast(K, oldTemplateInt);
          if (maxAllowableNewFeatureIndex < 1)
            continue;
          int nf = fc.numFeatures(oldTemplateInt);
          if (nf < Math.pow(2, K+2))
            continue;   // e.g.  2^(4+2)=64  2^(7+2)=512
          if (maxAllowableNewFeatureIndex * 1.1 + 1 >= nf)
            continue;
          kept++;
          tt.addIntance(T + i++, "Cnt" + K, maxAllowableNewFeatureIndex);
        }
        fc.free(oldTemplateInt);
      }
      Log.info("kept " + kept + " of " + total + " possible template transforms");
    }

    /**
     * Given alphabet (line mode ALPH, 4 column tsv), write out a new alphabet
     * that includes all of the original features plus all of the new (filtered)
     * features.
     *
     * NOTE: while the run method needs to be run once for every input feature
     * file, this only needs to be run once on the alphabet.
     */
    public void mapAlphabet(File inputAlphabet, File outputAlphabet) throws IOException {
      Log.info("adding features from " + inputAlphabet.getPath() + " to the "
          + numTransforms() + " transforms to " + outputAlphabet.getPath());
      try (BufferedWriter w = FileUtil.getWriter(outputAlphabet)) {
        // First: copy all of the old template:features
        try (BufferedReader r = FileUtil.getReader(inputAlphabet)) {
          for (String line = r.readLine(); line != null; line = r.readLine()) {
            w.write(line);
            w.newLine();
          }
        }
        Log.info("done copying old features, now for tranforms...");
        // Second: add all of the new templates
        for (int templateInt = 0; templateInt < t2trans.length; templateInt++) {
          TemplateTransformer tt = t2trans[templateInt];
          if (tt == null)
            continue;

          // Build new->old feature map.
          // This is used to give meaningful feature names to transformed features.
          // E.g. if you have "Foo-Cnt10=45", you can go look up feature 45 in template "Foo"
          IntIntHashMap o2n = tt.featuresToKeep;
          IntIntHashMap n2o = new IntIntHashMap(o2n.size(), FeatureCounts.FromFile.MISSING_VALUE);
          o2n.iterate((k,v) -> {
            int oldK = n2o.put(v, k);
            if (oldK >= 0)
              Log.warn("double mapping? k=" + k + " v=" + v + " oldK=" + oldK);
          });

          for (Instance inst : tt.instances) {    // loop over new templates
            int N = inst.maxAllowableNewFeatureIndex;
            for (int i = 0; i <= N; i++) {         // loop over new features
              w.write(String.valueOf(inst.newTemplateInt)); // template int
              w.write('\t');
              w.write(String.valueOf(i));                   // feature int
              w.write('\t');
              w.write(inst.newTemplateString);              // template string
              w.write('\t');
              int v = n2o.get(i);
              if (v >= 0)
                w.write("i=" + v);                          // feature string
              else
                w.write("i=UNK" + i);
              w.newLine();
            }
          }
        }
      }
      Log.info("done");
    }

    public int numTransforms() {
      int nt = 0;
      for (TemplateTransformer tt : t2trans)
        if (tt != null)
          nt += tt.instances.size();
      return nt;
    }

    /**
     * Takes unfilterd features and outputs another filtered feature file. The
     * output file is given upon construction.
     */
    @Override
    public void run(File features) throws IOException {
      int hc = features.getPath().hashCode();
      int fm = Math.floorMod(hc, numShards);
      Log.info("hc=" + hc + " fm=" + fm + " shard=" + shard + " numShards=" + numShards);
      assert fm >= 0 && fm < numShards;
      if (fm != shard) {
        Log.info("skipping " + features.getPath() + " b/c not in shard " + shard + "/" + numShards);
        return;
      }
      File out = getOutputFor(features);
      Log.info("mapping " + features.getPath() + "  ==>  " + out.getPath());
      this.outputFeatureFile = FileUtil.getWriter(out);
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
      this.outputFeatureFile.close();
    }

    public File getOutputFor(File inputFeatureFile) {
      return new File(outputDir, inputFeatureFile.getName());
    }

    /**
     * Writes out both the input and filtered features to the output file set
     * by {@link TemplateTransformer.Manager#run(File)}.
     */
    @Override
    public void observeLine(String line) {
      try {
        // Pass the original features through:
        outputFeatureFile.write(line);

        // Filter the given features and output them
        ffline.init(line, expectSortedFeatureFiles);
        for (Feature f : ffline.getFeatures()) {
          TemplateTransformer tt = t2trans[f.template];
          if (tt == null)
            continue;
          int newF = tt.featuresToKeep.get(f.feature);
          for (Instance trans : tt.instances)
            if (newF <= trans.maxAllowableNewFeatureIndex)
              outputFeatureFile.write("\t" + trans.newTemplateInt + ":" + newF);
        }

        // Done
        outputFeatureFile.newLine();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    FeatureCounts.DEBUG = config.getBoolean("debug", false);
    File inputAlphabet = config.getExistingFile("bialph");
    File outputFeatureFileDir = config.getExistingDir("outputFeatureFileDir");
    File countFile = config.getExistingFile("countFile");
    BiAlph bialph = new BiAlph(inputAlphabet, LineMode.ALPH);
    IntPair shard = ShardUtils.getShard(config);
    Manager m = new Manager(countFile, bialph, outputFeatureFileDir, shard.first, shard.second);
    String oa = "outputBialph";
    if (config.containsKey(oa)) {
      File outputAlphabet = config.getFile(oa);
      m.mapAlphabet(inputAlphabet, outputAlphabet);
    } else {
      Log.info("not converting bialph because " + oa + " wasn't provided");
    }
    File ffDir = config.getExistingDir("featuresParent");
    Log.info("about to run over files in " + ffDir.getPath());
    m.runManyFiles(
        config.getString("featuresGlob", "glob:**/*"), ffDir);
    Log.info("done");
  }
}
