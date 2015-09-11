package edu.jhu.hlt.fnparse.features;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.google.common.collect.Iterables;

import edu.jhu.gm.feat.FeatureVector;
import edu.jhu.hlt.fnparse.data.PropbankReader;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.inference.frameid.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.inference.role.span.DeterministicRolePruning.Mode;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntTrip;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;
import edu.stanford.nlp.ling.CoreAnnotations.DocIDAnnotation;

/**
 * Compute features ahead of time. Only outputs basic templates, not products,
 * which can be computed on the fly from the basic/kernel features.
 * 
 * Output (columns):
 *  i = dataset id + sentence id
 *  t = (i,j) of target
 *  s = (i,j) of argument                     # forall span that is kept by DeterministicRolePruning
 *  k = index of role or -1 if y_{t,k,s} = 0  # assumes 0 or 1 roles per span w.r.t. a predicate
 *  f = feature*                              # where feat is an int, alphabet should be serialized in a sister file
 *
 * feature = template:feature:value
 *
 * @author travis
 */
public class FeaturePrecomputation {

  public static class Target {
    public static final String NO_DOC_ID = "noDocId".intern();
    public final String docId;      // must contain corpus id if theres more than one corpus
    public final String sentId;
    public final int target;
    private final int hash;
    public Target(String sentId, int target) {
      this(NO_DOC_ID, sentId, target);
    }
    public Target(String docId, String sentId, int target) {
      this.docId = docId;
      this.sentId = sentId;
      this.target = target;
      this.hash = sentId.hashCode()
          ^ Integer.rotateLeft(docId.hashCode(), 16)
          ^ Integer.rotateLeft(target, 24);
    }

    @Override
    public int hashCode() { return hash; }

    @Override
    public boolean equals(Object other) {
      if (other instanceof Target) {
        Target t = (Target) other;
        return hash == t.hash
            && target == t.target
            && sentId.equals(t.sentId)
            && docId.equals(t.docId);
      }
      return false;
    }
    public static String toLine(Target t) {
      if (t.docId == NO_DOC_ID)
        return t.sentId + "\t" + t.target;
      return t.docId + "\t" + t.sentId + "\t" + t.target;
    }
    public static Target fromLine(String line) {
      String[] ar = line.split("\t");
      if (ar.length == 2)
        return new Target(ar[0], Integer.parseInt(ar[1]));
      if (ar.length == 3)
        return new Target(ar[0], ar[1], Integer.parseInt(ar[2]));
      throw new RuntimeException(Arrays.toString(ar));
    }
  }

  public static class Feature {
    public final String templateName;
    public final int template;
    public final String featureName;
    public final int feature;
    public final double value;
    public Feature(String templateName, int template, String featureName, int feature, double value) {
      this.templateName = templateName;
      this.template = template;
      this.featureName = featureName;
      this.feature = feature;
      this.value = value;
    }
    public static Comparator<Feature> BY_TEMPLATE_IDX = new Comparator<Feature>() {
      @Override
      public int compare(Feature o1, Feature o2) {
        return o1.template - o2.template;
      }
    };
  }

  public static class Tmpl {
    public final Template template;
    public final String name;
    public final int index;
    public final Alphabet<String> alph;
    public Tmpl(Template template, String name, int index) {
      this.template = template;
      this.name = name;
      this.index = index;
      this.alph = new Alphabet<>();
    }
  }

  /** TODO This will just product cached (t,s) features with the role */
  public static class Params {
    private Map<Pair<Target, Span>, FeatureVector> tsFeatures;
    private double[][] weights;
  }

  /** TODO This will read the text file produced by this class and compute information gain for each template */
  public static class InformationGainComputation {
    // See FeatureIGComputation for the data structures needed
    public void updateCounts(Target t, Span s) {
      throw new RuntimeException("implement me");
    }
  }

  /**
   * Compute all of the features and dump them to a file.
   */
  public static void run(
      Iterator<FNParse> data,
      File outputData,
      File outputAlphabet) {

    // Setup features
    HeadFinder hf = new SemaforicHeadFinder();
    BasicFeatureTemplates.Indexed templateIndex = BasicFeatureTemplates.getInstance();
    List<Tmpl> templates = new ArrayList<>();
    for (String tn : templateIndex.getBasicTemplateNames()) {
      Template t = templateIndex.getBasicTemplate(tn);
      int tIdx = templates.size();
      templates.add(new Tmpl(t, tn, tIdx));
    }

    // This is how we prune spans
    Reranker r = new Reranker(null, null, null, Mode.XUE_PALMER_HERMANN, 1, 1, new Random(9001));
 
    // For debugging
    ExperimentProperties config = ExperimentProperties.getInstance();
    int max = config.getInt("limit", 0);
    int shard = config.getInt("shard");
    int nShard = config.getInt("numShards");

    // Scan the data
    try (BufferedWriter w = FileUtil.getWriter(outputData)) {
      TimeMarker tm = new TimeMarker();
      while (data.hasNext() && (max <= 0 || tm.numMarks() < max)) {
        FNParse y = data.next();

        // Only take data from this shard
        if (Math.floorMod(y.getId().hashCode(), nShard) != shard)
          continue;

        emitAll(w, y, templates, r, hf);
        if (tm.enoughTimePassed(15)) {
          Log.info("processed " + tm.numMarks()
              + " sentences in " + tm.secondsSinceFirstMark() + " seconds");
          w.flush();
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    // Save the alphabet
    // template -> feature -> index
    try (BufferedWriter w = FileUtil.getWriter(outputAlphabet)) {
      w.write("# templateIndex featureIndex templateName featureName\n");
      for (Tmpl t : templates) {
        int n = t.alph.size();
        for (int i = 0; i < n; i++) {
          w.write(t.index
              + "\t" + i
              + "\t" + t.name
              + "\t" + t.alph.lookupObject(i)
              + "\n");
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static void emitAll(
      Writer w,
      FNParse y,
      List<Tmpl> templates,
      Reranker r,
      HeadFinder hf) throws IOException {

    // Keep track of what (t,s) I have already emitted and not emit duplicates
    Set<IntTrip> emittedTS = new HashSet<>();

    // Use the COMMIT actions to loop over (t,k,s)
    State st = r.getInitialStateWithPruning(y, y);
    TemplateContext ctx = new TemplateContext();

    // Loop over all COMMIT actions, and thus (t,k,s)
    for (Action commit : ActionType.COMMIT.next(st)) {
      Span s = commit.getSpan();
      if (!emittedTS.add(new IntTrip(commit.t, s.start, s.end)))
        continue;

      // Find if this (t,s) corresponds to a role
      int k = -1;
      FrameInstance fi = y.getFrameInstance(commit.t);
      int K = fi.getFrame().numRoles();
      for (int ki = 0; ki < K; ki++) {
        Span arg = fi.getArgument(ki);
        if (arg == s) {
          assert k < 0;
          k = ki;
        }
      }

      String docId = "na";  // Not currently needed
      Target t = new Target(docId, y.getId(), commit.t);

      // Extract features
      List<Feature> features = new ArrayList<>();
      for (Tmpl tmpl : templates) {
        FeatureIGComputation.setContext(y, commit, ctx, hf);
        Iterable<String> feats = tmpl.template.extract(ctx);
        if (feats != null) {
          for (String feat : feats) {
            int featIdx = tmpl.alph.lookupIndex(feat, true);
            features.add(new Feature(tmpl.name, tmpl.index, feat, featIdx, 1.0));
          }
        }
      }

      emit(w, t, s, k, features);
    }
  }

  /** Emits one line */
  private static void emit(Writer w, Target t, Span s, int k, List<Feature> features) throws IOException {
    w.write(Target.toLine(t));
    w.write("\t" + s.start + "," + s.end);
    w.write("\t" + k);
    for (Feature f : features)
      w.write("\t" + f.template + ":" + f.feature);
    w.write('\n');
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    File wd = config.getExistingDir("workingDir", new File("/tmp"));
    ParsePropbankData.Redis propbankAutoParses = new ParsePropbankData.Redis(config);
    PropbankReader pbr = new PropbankReader(config, propbankAutoParses);

    Iterable<FNParse> data = Iterables.concat(
        pbr.getTrainData(),
        pbr.getDevData(),
        pbr.getTestData());
//    Iterable<FNParse> data = pbr.getDevData();
//    pbr.setKeep(s -> Math.floorMod(s.getId().hashCode(), 100) == 0);
//    config.put("limit", "10");

    run(data.iterator(), new File(wd, "features.txt.gz"), new File(wd, "template-feat-indices.txt.gz"));
  }
}
