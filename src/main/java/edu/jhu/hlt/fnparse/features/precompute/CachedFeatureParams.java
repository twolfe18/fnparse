package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.FeatureIGComputation;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.AlphabetLine;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Target;
import edu.jhu.hlt.fnparse.features.precompute.InformationGainProducts.BaseTemplates;
import edu.jhu.hlt.fnparse.inference.frameid.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.inference.role.span.DeterministicRolePruning.Mode;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.OrderStatistics;
import edu.jhu.hlt.tutils.RedisMap;
import edu.jhu.hlt.tutils.SerializationUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleUnsortedVector;
import edu.jhu.prim.vector.IntDoubleVector;

/**
 * Reads in cached features from disk and serves them up (implements {@link Params}).
 * This class reads features out of a table of
 *  (targetSpan, roleSpan, template:features+)
 * You can specify a set of templates (e.g. "5") and/or template products
 * (e.g. "5-9"), and this class will load only the necessary data.
 *
 * TODO Use serialization to store the sub-set chosen after filtering?
 * (memoize init calls)
 *
 * @author travis
 */
public class CachedFeatureParams implements Params.Stateless {
  private static final long serialVersionUID = 2993313358342467909L;

  private int[][] featureSet;   // each int[] is a feature, each of those values is a template
  private BitSet templateSet;
  private int[] templateSetSorted;
  private BiAlph bialph;  // used for cardinality and names
  private int[] template2cardinality;

  // If true, the features for prune actions are indicators on the frame,
  // the role, and the frame-role. Otherwise, you need to implement features
  // on Span.NULL_SPAN and save them in the feature files.
  public boolean simplePruneFeatures = true;

  public IntArrayList debugFeatures;
  public List<CacheKey> debugKeys;
  public boolean keepBoth, keepKeys, keepValues;

  public static final class CacheKey {
    private String sentId;  // assuming docId is not needed
    private int t;
    private int argStart, argEnd;
    private int hash;
    public CacheKey(Target t, Span arg) {
      this.sentId = t.sentId.intern();
      this.t = t.target;
      this.argStart = arg.start;
      this.argEnd = arg.end;
      this.hash = this.sentId.hashCode()
          ^ ((this.argEnd - this.argStart) << 24)
          ^ (this.argStart << 16)
          ^ (this.t << 8)
          ;
    }
    @Override
    public int hashCode() { return hash; }
    @Override
    public boolean equals(Object other) {
      if (other instanceof CacheKey) {
        CacheKey ck = (CacheKey) other;
        return hash == ck.hash
            && sentId == ck.sentId
            && t == ck.t
            && argStart == ck.argStart
            && argEnd == ck.argEnd;
      }
      return false;
    }
  }

  // t -> s -> features
  // There is no k here, it is computed on the fly.
//  private Map<Target, Map<Span, BaseTemplates>> cache;
//  private Map<Pair<Target, Span>, BaseTemplates> cache;
//  private Map<CacheKey, BaseTemplates> cache;
  private Map<CacheKey, int[]> cache;
  /*
   * TODO This cache should really be
   *    (docId, sentId) -> (t,span) -> features
   * Then we could cache get calls on the first map for locality.
   */

  // k -> feature -> weight
  private int dimension = 1 * 1024 * 1024;    // hashing trick
  private double[][] weightsCommit;
  private double[] weightPrune;

  private double l2Penalty = 1e-8;

  public CachedFeatureParams(BiAlph bialph, int numRoles, List<int[]> features, int dimension) {
    this.dimension = dimension;
    this.bialph = bialph;
    this.template2cardinality = bialph.makeTemplate2Cardinality();
    this.cache = new HashMap<>();
    this.debugFeatures = new IntArrayList();
    this.debugKeys = new ArrayList<>();

    this.featureSet = new int[features.size()][];
    for (int i = 0; i < featureSet.length; i++)
      featureSet[i] = features.get(i);

    templateSet = new BitSet();
    for (int[] feat : features)
      for (int t : feat)
        templateSet.set(t);

    templateSetSorted = new int[templateSet.cardinality()];
    for (int i = 0, t = templateSet.nextSetBit(0); t >= 0; t = templateSet.nextSetBit(t + 1), i++)
      templateSetSorted[i] = t;

    this.weightsCommit = new double[numRoles][dimension];
    this.weightPrune = new double[dimension];
    long weightBytes = (numRoles + 1) * dimension * 8;
    Log.info("dimension=" + dimension
        + " numRoles=" + numRoles
        + " numTemplates=" + templateSet.cardinality()
        + " and sizeOfWeights=" + (weightBytes / (1024d * 1024d)) + " MB");

    // For debugging (look at memory usage with various configurations).
    // For regular operation, make sure these are their defaults.
    ExperimentProperties config = ExperimentProperties.getInstance();
    keepBoth = config.getBoolean("cachedFeatureParams.keepBoth", true);
    keepKeys = config.getBoolean("cachedFeatureParams.keepKeys", false);
    keepValues = config.getBoolean("cachedFeatureParams.keepValues", false);
  }

  public void read(File featureFile) throws IOException {
    Log.info("reading features from " + featureFile.getPath());
    OrderStatistics<Integer> nnz = new OrderStatistics<>();
    TimeMarker tm = new TimeMarker();
    int numLines = 0;
    try (BufferedReader r = FileUtil.getReader(featureFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        numLines++;
        String[] toks = line.split("\t");

        Target t = new Target(toks[0], toks[1], Integer.parseInt(toks[2]));

        String[] spanToks = toks[3].split(",");
        Span span = Span.getSpan(Integer.parseInt(spanToks[0]), Integer.parseInt(spanToks[1]));

        BaseTemplates data = new BaseTemplates(templateSet, line, false);
        data.purgeLine();
        nnz.add(data.getFeatures().length);

//        cache.get(t).put(span, data);
//        Pair<Target, Span> key = new Pair<>(t, span);
        CacheKey key = new CacheKey(t, span);
        if (keepBoth) {
//        BaseTemplates old = cache.put(key, data);
        int[] old = cache.put(key, data.getFeatures());
        assert old == null : key + " maps to two values, one if which is in " + featureFile.getPath();
        }

        if (keepKeys) {
          debugKeys.add(key);
        }

        if (keepValues) {
          for (int f : data.getFeatures())
            debugFeatures.add(f);
        }

        if (tm.enoughTimePassed(15)) {
          Log.info("processed " + tm.numMarks()
            + " lines in " + tm.secondsSinceFirstMark() + " seconds, "
            + Describe.memoryUsage());
        }
      }
    }
    Log.info("numLines=" + numLines);
    Log.info("nnz: " + nnz.getOrdersStr() + " mean=" + nnz.getMean());
    Log.info("nnz/template=" + (nnz.getMean() / templateSetSorted.length));
    Log.info("templateSetSorted.length=" + templateSetSorted.length);
    Log.info("templateSet.cardinality=" + templateSet.cardinality());
    Log.info("debugFeatures.size=" + debugFeatures.size());
    Log.info("debugKeys.size=" + debugKeys.size());
    Log.info("done");
  }

  @Override
  public Adjoints score(FNTagging f, Action a) {
    if (a.getActionType() == ActionType.COMMIT)
      return scoreCommit(f, a);
    assert a.getActionType() == ActionType.PRUNE;
    if (!simplePruneFeatures)
      throw new RuntimeException("implement me");
    return scorePrune(f, a);
  }

  /**
   * @param a must be a PRUNE action.
   */
  private Adjoints scorePrune(FNTagging f, Action a) {
    Frame frame = f.getFrameInstance(a.t).getFrame();
    int f1 = frame.getId() * 3 + 0;
    int f2 = ((frame.getId() << 12) ^ a.k) * 3 + 1;
    int f3 = a.k * 3 + 2;
    IntDoubleUnsortedVector fv = new IntDoubleUnsortedVector(3);
    fv.add(Math.floorMod(f1, dimension), 1);
    fv.add(Math.floorMod(f2, dimension), 1);
    fv.add(Math.floorMod(f3, dimension), 1);
    IntDoubleVector theta = new IntDoubleDenseVector(weightPrune);
    return new Adjoints.Vector(this, a, theta, fv, l2Penalty);
  }


  /**
   * @param a must be a COMMIT action.
   */
  private Adjoints scoreCommit(FNTagging f, Action a) {

    // Get the templates needed for all the features.
    Target t = new Target(f.getSentence().getId(), a.t);
//    Map<Span, BaseTemplates> cacheT = cache.get(t);
    Span s = a.getSpan();
//    BaseTemplates data = cacheT.get(s);
//    BaseTemplates data = cache.get(new Pair<>(t, s));
//    BaseTemplates data = cache.get(new CacheKey(t, s));
    int[] feats = cache.get(new CacheKey(t, s));
    if (feats == null) {
      throw new RuntimeException("couldn't come up with features for "
          + f.getId() + " action=" + a);
    }
    BaseTemplates data = new BaseTemplates(templateSetSorted, feats);

    // I should be able to use the same code as in InformationGainProducts.
    IntDoubleVector features = new IntDoubleUnsortedVector(featureSet.length + 1);
    List<Long> buf = new ArrayList<>(4);
    for (int i = 0; i < featureSet.length; i++) {
      int[] feat = featureSet[i];
      // Note that these features don't need to be producted with k due to the
      // fact that we have separate weights for those.
      InformationGainProducts.flatten(data, 0, feat, 0, 1, 1, template2cardinality, buf);

      // If I get a long here, I can zip them all together by multiplying by
      // featureSet.length and then adding in an offset.
      for (long l : buf) {
        long ll = l * featureSet.length + i;
        int h = ((int) ll) ^ ((int) (ll >>> 32));
        h = Math.floorMod(h, dimension);
        features.add(h, 1);
      }
      buf.clear();
    }

    IntDoubleVector theta = new IntDoubleDenseVector(weightsCommit[a.k]);
    return new Adjoints.Vector(this, a, theta, features, l2Penalty);
  }

  @Override
  public void doneTraining() {
    Log.info("no op");
  }

  @Override
  public void showWeights() {
    throw new RuntimeException("implement me");
  }

  @Override
  public void serialize(DataOutputStream out) throws IOException {
    throw new RuntimeException("implement me");
  }

  @Override
  public void deserialize(DataInputStream in) throws IOException {
    throw new RuntimeException("implement me");
  }

  @Override
  public void addWeights(Params other, boolean checkAlphabetEquality) {
    throw new RuntimeException("implement me");
  }

  @Override
  public void scaleWeights(double scale) {
    throw new RuntimeException("implement me");
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    // start redis in data/debugging/redis-for-feature-names
    RedisMap<String> tf2name = getFeatureNameRedisMap(config);
    CachedFeatureParams cfp = loadFeaturesIntoMemory(config);
    cfp.testIntStringEquality(config, tf2name);
  }

  /**
   * How to test:
   * 1) setup a redis server hosting (intTemplate,intFeature) -> (stringTemplate,stringFeature)
   * 2) every time a feature is computed, foreach template
   *    a) stringTemplate = redisGet(intTemplate)
   *    b) Template = BasicFeatureTemplates.getBasicTemplate(templateString)
   *    c) stringFeature' = Template.extract(state, action)
   *    d) stringFeature = redisGet(intTemplate, intFeature)
   * 3) sort the stringFeature and stringFeature' and make sure they are equal
   */
  public void testIntStringEquality(ExperimentProperties config, RedisMap<String> tf2name) {
    boolean verbose = config.getBoolean("showFeatureMatches", true);
    TemplateContext ctx = new TemplateContext();
    HeadFinder hf = new SemaforicHeadFinder();
    Reranker r = new Reranker(null, null, null, Mode.XUE_PALMER_HERMANN, 1, 1, new Random(9001));
    BasicFeatureTemplates.Indexed ti = BasicFeatureTemplates.getInstance();
    Iterator<FNParse> itr = DumpSentenceIds.getData(config);
    while (itr.hasNext()) {
      FNParse y = itr.next();
      State st = r.getInitialStateWithPruning(y, y);
      for (Action a : ActionType.COMMIT.next(st)) {
        // For each template int featureSetFlat, lookup the string (template,feature) via:
        // 1) re-extract using the Template
        // 2) lookup via redis (which reflects the contents of the files -- round trip)

        // Check 1: extracted template-values match stored template-values
        Target t = new Target(y.getSentence().getId(), a.t);
        Span s = a.getSpan();
        int[] feats = cache.get(new CacheKey(t, s));
        if (feats == null) {
          throw new RuntimeException("couldn't come up with features for "
              + y.getId() + " action=" + a);
        }
        BaseTemplates data = new BaseTemplates(templateSetSorted, feats);
        for (int i = 0; i < data.size(); i++) {
          // What we have from disk.
          int templateIndex = data.getTemplate(i);
          int feature = data.getValue(i);
          String templateName = bialph.lookupTemplate(templateIndex);
          String featureName = tf2name.get(makeKey(templateIndex, feature));

          // What we would have gotten had we extracted on the fly.
          Template template = ti.getBasicTemplate(templateName);
          FeatureIGComputation.setContext(y, a, ctx, hf);
          Iterable<String> featureStringValues = template.extract(ctx);

          // Check that featureName is at least in featureStringValues.
          int matches = 0;
          for (String fsv : featureStringValues)
            if (fsv.equals(featureName))
              matches++;
          if (matches == 0)
            throw new RuntimeException("feautreName=" + featureName + " featureStringValues=" + featureStringValues);
          if (verbose)
            System.out.println("found match for " + featureName);

          // Check 2: flattened product (template-values) make sense.
          // flatten : (int -> int[]) -> int
          // but it uses hashing.
          // TODO flatten should be tested separtely!
        }
      }
    }
  }

  public static String makeKey(int template, int feature) {
    return "t" + template + "f" + feature;
  }

  /**
   * Adds (template,feature) int -> string mapping:
   *  template-feature: "t1f9" -> "CfgFeat-AllChildrenBag-Category=Category:!!?"
   * Scans an alphabet file, so it takes very little memory (<100M) but a good
   * bit of time (~10 mins).
   *
   * Returns a map with keys make by makeKey and values which are the feature
   * string values producted by {@link Template}s.
   */
  public static RedisMap<String> getFeatureNameRedisMap(ExperimentProperties config) throws IOException {
//    RedisMap<String> t2name = new RedisMap<>(config, SerializationUtils::t2bytes, SerializationUtils::bytes2t);
    RedisMap<String> tf2feat = new RedisMap<>(config, SerializationUtils::t2bytes, SerializationUtils::bytes2t);

    String k = "redisInsert";
    if (!config.getBoolean(k, false)) {
      Log.info("skpping the actual redis insert because flag not used: " + k);
      return tf2feat;
    }

//    Consumer<TemplateAlphabet> f = ta -> {
//      Log.info("reading index=" + ta.index + " name=" + ta.name
//          + " size=" + ta.alph.size() + " " + Describe.memoryUsage());
//      t2name.put("t" + ta.index, ta.name);
//      int n = ta.alph.size();
//      for (int i = 0; i < n; i++)
//        tf2feat.put("t" + ta.index + "f" + i, ta.alph.lookupObject(i));
//    };
//    Alphabet.iterateOverAlphabet(
//        config.getExistingFile("alphabet"),
//        config.getBoolean("alphabet.header", false),
//        f);

    TimeMarker tm = new TimeMarker();
//    BitSet templates = new BitSet();    // set of templates we've put into redis
    File alphFile = config.getExistingFile("alphabet");
    boolean header = config.getBoolean("alphabet.header", false);
    try (BufferedReader r = FileUtil.getReader(alphFile)) {
      if (header) {
        String line = r.readLine();
        assert line.startsWith("#");
      }
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        AlphabetLine al = new AlphabetLine(line);
//        if (!templates.get(al.template)) {
//          templates.set(al.template);
//          t2name.put("t" + al.template, al.templateName);
//        }
        tf2feat.put(makeKey(al.template, al.feature), al.featureName);

        if (tm.enoughTimePassed(15)) {
          Log.info("processed " + tm.numMarks()
            + " lines in " + tm.secondsSinceFirstMark() + " seconds, "
            + Describe.memoryUsage());
        }
      }
    }
    return tf2feat;
  }

  public static CachedFeatureParams loadFeaturesIntoMemory(ExperimentProperties config) throws IOException {
    Log.info("going to try to load all of the data to see if it fits in memory");
    File parent = config.getExistingDir("featuresParent");
    String glob = config.getString("featuresGlob", "glob:**/*");
    BiAlph bialph = new BiAlph(config.getExistingFile("alphabet"), false);
    int numRoles = config.getInt("numRoles", 20);
    Random rand = new Random();
    int numTemplates = config.getInt("numTemplates", 993);
    int numFeats = config.getInt("numFeats", 50);
    List<int[]> features = new ArrayList<>();
    for (int i = 0; i < numFeats; i++) {
      int a = rand.nextInt(numTemplates);
      int b = rand.nextInt(numTemplates);
      features.add(new int[] {a, b});
    }
    int dimension = config.getInt("dimension", 256 * 1024);
    CachedFeatureParams params = new CachedFeatureParams(bialph, numRoles, features, dimension);
    for (File f : FileUtil.find(parent, glob))
      params.read(f);
    Log.info("done");
    return params;
  }
}
