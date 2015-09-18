package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.google.common.collect.Iterables;

import edu.jhu.hlt.fnparse.data.PropbankReader;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.FeatureIGComputation;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.inference.role.span.DeterministicRolePruning.Mode;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntTrip;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;

/**
 * Compute features ahead of time. Only outputs basic templates, not products,
 * which can be computed on the fly from the basic/kernel features.
 *
 * Produces two types of files:
 *  "features" which is the data below
 *  "alphabet" which is an int<->string bijection.
 *
 * (Note: this doesn't produce "bialphs", but they can be trivially created from
 *  alphabets, see {@link BiAlphMerger}).
 *
 * Columns of "feature" files:
 *  i = dataset id + sentence id
 *  t = (i,j) of target
 *  s = (i,j) of argument                     # forall span that is kept by DeterministicRolePruning
 *  k = index of role or -1 if y_{t,k,s} = 0  # assumes 0 or 1 roles per span w.r.t. a predicate
 *  f = feat*
 * where feat = template:feature:value
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
      return t.docId + "\t" + t.sentId + "\t" + t.target;
    }
    public static void toDos(Target t, DataOutputStream dos) throws IOException {
      dos.writeUTF(t.docId);
      dos.writeUTF(t.sentId);
      dos.writeInt(t.target);
    }
    public static Target fromLine(String line) {
      String[] ar = line.split("\t");
      if (ar.length == 3)
        return new Target(ar[0], ar[1], Integer.parseInt(ar[2]));
      throw new RuntimeException(Arrays.toString(ar));
    }
    public static Target fromDis(DataInputStream dis) throws IOException {
      String docId = dis.readUTF();
      String sentId = dis.readUTF();
      int target = dis.readInt();
      return new Target(docId, sentId, target);
    }
  }

  /** Feature extraction value */
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

  /** Holds a {@link Template} and often serves as an Alphabet */
  public static class TemplateAlphabet {
    public final Template template;
    public final String name;
    public final int index;
    public final edu.jhu.util.Alphabet<String> alph;
    public TemplateAlphabet(Template template, String name, int index) {
      this.template = template;
      this.name = name;
      this.index = index;
      this.alph = new edu.jhu.util.Alphabet<>();
    }
  }

  public static class AlphabetLine {
    public String line;
    public String templateName;
    public String featureName;
    public int template;
    public int feature;
    public AlphabetLine(String line) {
      set(line);
    }
    @Override
    public String toString() {
      return "(AlphabetLine " + line + ")";
    }
    public boolean isNull() {
      return line == null;
    }
    public void set(String line) {
      this.line = line;
      if (line != null) {
        String[] toks = line.split("\t");
        assert toks.length == 4;
        template = Integer.parseInt(toks[0]);
        feature = Integer.parseInt(toks[1]);
        templateName = toks[2];
        featureName = toks[3];
      }
    }
    public static Comparator<AlphabetLine> BY_TEMPLATE_STR_FEATURE_STR = new Comparator<AlphabetLine>() {
      @Override
      public int compare(AlphabetLine o1, AlphabetLine o2) {
        int c1 = o1.templateName.compareTo(o2.templateName);
        if (c1 != 0)
          return c1;
        int c2 = o1.featureName.compareTo(o2.featureName);
        return c2;
      }
    };
  }

  /**
   * Compute all of the features and dump them to a file.
   */
  public static void run(
      Iterator<FNParse> data,
      File outputData,
      File outputAlphabet) {
    Log.info("writing features to " + outputData.getPath());
    Log.info("writing alphabet to " + outputAlphabet.getPath());

    // Setup features
    Alphabet templates = new Alphabet();

    // This is how we prune spans
    Reranker r = new Reranker(null, null, null, Mode.XUE_PALMER_HERMANN, 1, 1, new Random(9001));
 
    // For debugging
    ExperimentProperties config = ExperimentProperties.getInstance();
    int max = config.getInt("limit", 0);
    Log.info("limit=" + max);

    // Scan the data
    Counts<String> parseStats = new Counts<>();
    try (BufferedWriter w = FileUtil.getWriter(outputData)) {
      TimeMarker tm = new TimeMarker();
      while (data.hasNext() && (max <= 0 || tm.numMarks() < max)) {
        FNParse y = data.next();

        emitAll(w, y, templates, r);
        if (tm.enoughTimePassed(15)) {
          Log.info("processed " + tm.numMarks()
              + " sentences in " + tm.secondsSinceFirstMark() + " seconds");
          w.flush();
        }

        // Tally up some stats for debugging
        parseStats.increment("num-parses");
        if (y.getSentence().getBasicDeps(false) == null)
          parseStats.increment("no-basic-deps");
        if (y.getSentence().getCollapsedDeps(false) == null)
          parseStats.increment("no-collapsed-deps");
        if (y.getSentence().getStanfordParse(false) == null)
          parseStats.increment("no-stanford-deps");
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    Log.info("done computing features");
    System.out.println("parseStats=" + parseStats);

    // Save the alphabet
    // template -> feature -> index
    Log.info("saving alphabet");
    try {
      templates.toFile(outputAlphabet);
    } catch (IOException e) {
      e.printStackTrace();
    }

    Log.info("done");
  }

  private static void emitAll(
      Writer w,
      FNParse y,
      edu.jhu.hlt.fnparse.features.precompute.Alphabet templates,
      Reranker r) throws IOException {

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
      for (TemplateAlphabet tmpl : templates) {
        FeatureIGComputation.setContext(y, commit, ctx, templates.getHeadFinder());
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
  public static void emit(Writer w, Target t, Span s, int k, List<Feature> features) throws IOException {
    w.write(Target.toLine(t));
    w.write("\t" + s.start + "," + s.end);
    w.write("\t" + k);
    for (Feature f : features)
      w.write("\t" + f.template + ":" + f.feature);
    w.write('\n');
  }

  public static int getRole(String line) {
    int field = 4;
    return Integer.parseInt(line.split("\t", field + 2)[field]);
  }

  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);
    File wd = config.getExistingDir("workingDir", new File("/tmp"));
    ParsePropbankData.Redis propbankAutoParses = new ParsePropbankData.Redis(config);
    PropbankReader pbr = new PropbankReader(config, propbankAutoParses);

    final int shard = config.getInt("shard");
    final int nShard = config.getInt("numShards");
    pbr.setKeep(s -> Math.floorMod(s.getId().hashCode(), nShard) == shard);

    Iterable<FNParse> data = config.getBoolean("debug", false)
        ? pbr.getDevData()
        : Iterables.concat(pbr.getTrainData(), pbr.getDevData(), pbr.getTestData());

    run(data.iterator(), new File(wd, "features.txt.gz"), new File(wd, "template-feat-indices.txt.gz"));
  }
}
