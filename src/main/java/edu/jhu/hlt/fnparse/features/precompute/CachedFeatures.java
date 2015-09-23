package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.fnparse.data.PropbankReader;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
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
public class CachedFeatures {

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
//  public List<CacheKey> debugKeys;
  public List<FNParse> debugKeys;
  public boolean keepBoth, keepKeys, keepValues;

  // New way of storing things!
  public static final class Item {
    public final FNParse parse;
    private int[][][][] features;   // [t][arg.start][arg.end] = int[] features
    public Item(FNParse parse) {
      if (parse == null)
        throw new IllegalArgumentException();
      this.parse = parse;
      int T = parse.numFrameInstances();
      this.features = new int[T][0][0][];
    }
    public void setFeatures(int t, Span arg, int[] features) {
      if (arg.start < 0 || arg.end < 0)
        throw new IllegalArgumentException("span=" + arg);
      if (arg.start >= this.features[t].length) {
        int newSize = (int) Math.max(arg.start + 1, 1.6 * this.features[t].length + 1);
        this.features[t] = Arrays.copyOf(this.features[t], newSize);
      }
      if (arg.end >= this.features[t][arg.start].length) {
        int newSize = (int) Math.max(arg.end + 1, 1.6 * this.features[t][arg.start].length + 1);
        this.features[t][arg.start] = Arrays.copyOf(this.features[t][arg.start], newSize);
      }
      assert null == this.features[t][arg.start][arg.end] : "duplicates?";
      this.features[t][arg.start][arg.end] = features;
    }
    public int[] getFeatures(int t, Span arg) {
      int[] feats = this.features[t][arg.start][arg.end];
      assert feats != null;
      return feats;
    }
  }

  private ArrayList<Item> loadedItems;
  private Item lastServedByItemProvider;

  /**
   * A runnable/thread whose only job is to read parses from disk and put them
   * into loadedItems.
   */
  public class Inserter implements Runnable {
    private Map<String, FNParse> sentId2parse;
    private ArrayDeque<File> readFrom;
    public boolean readForever;
    /**
     * @param sentId2parse is needed because this module has an ItemProvider
     * implementation, which needs to know about FNParses.
     */
    public Inserter(Iterable<File> files, boolean readForever, Map<String, FNParse> sentId2parse) {
      this.readForever = readForever;
      this.sentId2parse = sentId2parse;
      this.readFrom = new ArrayDeque<>();
      for (File f : files)
        this.readFrom.offerLast(f);
    }
    @Override
    public void run() {
      File f = readFrom.pollFirst();
      try {
        // This adds to loadedItems
        CachedFeatures.this.read(f, sentId2parse);
      } catch (IOException e) {
        e.printStackTrace();
      }
      if (readForever) {
        readFrom.offerLast(f);
      }
    }
  }
  public static Map<String, FNParse> getPropbankSentId2Parse(ExperimentProperties config) {
    // This can be null since the only reason we need the parses is for
    // computing features, which we've already done.
    ParsePropbankData.Redis propbankAutoParses = null;
    PropbankReader pbr = new PropbankReader(config, propbankAutoParses);
    Map<String, FNParse> map = new HashMap<>();
    for (FNParse y : pbr.getTrainData()) {
      FNParse old = map.put(y.getSentence().getId(), y);
      assert old == null;
    }
    for (FNParse y : pbr.getDevData()) {
      FNParse old = map.put(y.getSentence().getId(), y);
      assert old == null;
    }
    for (FNParse y : pbr.getTestData()) {
      FNParse old = map.put(y.getSentence().getId(), y);
      assert old == null;
    }
    return map;
  }

  /**
   * Reads from loadedItems. Not proper in this sense: size() and 
   */
  public class ItemProvider implements edu.jhu.hlt.fnparse.rl.rerank.ItemProvider {
    private int intendentSize;
    public ItemProvider(int intendedSize) {
      this.intendentSize = intendedSize;
    }
    @Override
    public Iterator<FNParse> iterator() {
      throw new RuntimeException("implement me");
    }
    @Override
    public int size() {
      return intendentSize;
    }
    @Override
    public FNParse label(int i) {
      assert i >= 0 && i < intendentSize;
      int n;
      while (true) {
        n = CachedFeatures.this.loadedItems.size();
        if (n < 1) {
          try { Thread.sleep(1 * 1000); }
          catch (Exception e) { throw new RuntimeException(e); }
        } else {
          break;
        }
      }
      lastServedByItemProvider = loadedItems.get(i % n);
      return lastServedByItemProvider.parse;
    }
    @Override
    public List<edu.jhu.hlt.fnparse.rl.rerank.Item> items(int i) {
      throw new RuntimeException("not allowed to call this because it is not "
          + "synchronized with label(i). I though I didn't use this anymore "
          + "anyway, don't I just use DeterministicRoleePruning?");
    }
  }

  /**
   * Actually provides the params!
   *
   * Throws a RuntimeException if you ask for features for some FNParse other
   * than the one last served up by this module's ItemProvider.
   */
  public class Params implements edu.jhu.hlt.fnparse.rl.params.Params.Stateless {
    private static final long serialVersionUID = -5359275348868455837L;

    // k -> feature -> weight
    private int dimension = 1 * 1024 * 1024;    // hashing trick
    private double[][] weightsCommit;
    private double[] weightPrune;

    private double l2Penalty = 1e-8;

    public Params(int dimension, int numRoles) {
      this.dimension = dimension;
      this.weightsCommit = new double[numRoles][dimension];
      this.weightPrune = new double[dimension];
      long weightBytes = (numRoles + 1) * dimension * 8;
      Log.info("dimension=" + dimension
          + " numRoles=" + numRoles
          + " numTemplates=" + templateSet.cardinality()
          + " and sizeOfWeights=" + (weightBytes / (1024d * 1024d)) + " MB");
    }

    /**
     * Ignores the feature set, just returns all of the templates we have in memory.
     */
    public int[] getDebugRawTemplates(FNTagging f, Action a) {
      if (!lastItemMatches(f) || a.getActionType() != ActionType.COMMIT)
        throw new RuntimeException();
      return lastServedByItemProvider.getFeatures(a.t, a.getSpan());
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

    private boolean lastItemMatches(FNTagging f) {
      if (lastServedByItemProvider == null
          || !f.getSentence().getId().equals(
              lastServedByItemProvider.parse.getSentence().getId())) {
        return false;
      }
      return true;
    }

    /**
     * @param a must be a COMMIT action.
     */
    private Adjoints scoreCommit(FNTagging f, Action a) {

      if (!lastItemMatches(f))
        throw new RuntimeException("who gave out this FNParse?");

      // Get the templates needed for all the features.
//      Target t = new Target(f.getSentence().getId(), a.t);
//      Span s = a.getSpan();
//      int[] feats = cache.get(new CacheKey(t, s));
      int[] feats = lastServedByItemProvider.getFeatures(a.t, a.getSpan());
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
    public void scaleWeights(double scale) {
      throw new RuntimeException("implement me");
    }

    @Override
    public void addWeights(edu.jhu.hlt.fnparse.rl.params.Params other,
        boolean checkAlphabetEquality) {
      throw new RuntimeException("implement me");
    }
  }

  /**
   * @deprecated This should be removed when loadedItems starts being used.
   */
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
//  /** @deprecated */
//  private Map<CacheKey, int[]> cache;

  public CachedFeatures(BiAlph bialph, List<int[]> features) {
    this.bialph = bialph;
    this.template2cardinality = bialph.makeTemplate2Cardinality();
//    this.cache = new HashMap<>();
    this.loadedItems = new ArrayList<>();
    this.lastServedByItemProvider = null;
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

    // For debugging (look at memory usage with various configurations).
    // For regular operation, make sure these are their defaults.
    ExperimentProperties config = ExperimentProperties.getInstance();
    keepBoth = config.getBoolean("cachedFeatureParams.keepBoth", true);
    keepKeys = config.getBoolean("cachedFeatureParams.keepKeys", false);
    keepValues = config.getBoolean("cachedFeatureParams.keepValues", false);
  }

  // TODO make it clear that you should use new Thread(new Inserter(...)) instead of this
  // TODO make this insert into loadedItems
  private void read(File featureFile, Map<String, FNParse> sentId2parse) throws IOException {
    Log.info("reading features from " + featureFile.getPath());
    OrderStatistics<Integer> nnz = new OrderStatistics<>();
    TimeMarker tm = new TimeMarker();
    int numLines = 0;

    // TODO lines should form contiguous blocks of FNParses.
    // 1) Check this and 2) populate loadedItems

    // How do I get the FNParse for the Item that I'm building?
    // Could take sentId -> FNParse map ahead of time?
    // DONE: Take it in inserter which then passes it into this method

    Item cur = null;
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

        if (cur == null) {
          FNParse parse = sentId2parse.get(t.sentId);
          cur = new Item(parse);
        } else if (!t.sentId.equals(cur.parse.getSentence().getId())) {
          loadedItems.add(cur);
          FNParse parse = sentId2parse.get(t.sentId);
          cur = new Item(parse);
        }

//        cache.get(t).put(span, data);
//        Pair<Target, Span> key = new Pair<>(t, span);
//        CacheKey key = new CacheKey(t, span);
        if (keepBoth) {
//        BaseTemplates old = cache.put(key, data);
//        int[] old = cache.put(key, data.getFeatures());
//        assert old == null : key + " maps to two values, one if which is in " + featureFile.getPath();
          cur.setFeatures(t.target, span, data.getFeatures());
        }

        if (keepKeys) {
//          debugKeys.add(key);
          debugKeys.add(sentId2parse.get(t.sentId));
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


  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    // start redis in data/debugging/redis-for-feature-names
    RedisMap<String> tf2name = getFeatureNameRedisMap(config);

    BiAlph bialph = new BiAlph(config.getExistingFile("alphabet"), false);
    Random rand = new Random();
    int numTemplates = config.getInt("numTemplates", 993);
    int numFeats = config.getInt("numFeats", 50);
    List<int[]> features = new ArrayList<>();
    for (int i = 0; i < numFeats; i++) {
      int a = rand.nextInt(numTemplates);
      int b = rand.nextInt(numTemplates);
      features.add(new int[] {a, b});
    }
    CachedFeatures cfp = new CachedFeatures(bialph, features);

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

    Map<String, FNParse> sentId2parse = getPropbankSentId2Parse(config);

    List<File> featureFiles = FileUtil.find(new File("data/debugging/coherent-shards/features"), "glob:**/*");
    Inserter ins = this.new Inserter(featureFiles, false, sentId2parse);
    Thread insThread = new Thread(ins);
    insThread.start();

    int dimension = 256 * 1024;
    int numRoles = 20;
    Params params = this.new Params(dimension, numRoles);

    ItemProvider ip = this.new ItemProvider(sentId2parse.size());
    for (int index = 0; index < ip.size(); index++) {
      FNParse y = ip.label(index);
      State st = r.getInitialStateWithPruning(y, y);
      for (Action a : ActionType.COMMIT.next(st)) {
        // For each template int featureSetFlat, lookup the string (template,feature) via:
        // 1) re-extract using the Template
        // 2) lookup via redis (which reflects the contents of the files -- round trip)

        // Check 1: extracted template-values match stored template-values
        int[] feats = params.getDebugRawTemplates(y, a);
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

  /*
   * @deprecated see new Thread(new {@link Inserter})
  public static CachedFeatures loadFeaturesIntoMemory(ExperimentProperties config) throws IOException {
    Log.info("going to try to load all of the data to see if it fits in memory");
    File parent = config.getExistingDir("featuresParent");
    String glob = config.getString("featuresGlob", "glob:**\/*");
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
//    CachedFeatures params = new CachedFeatures(bialph, numRoles, features, dimension);
    CachedFeatures params = new CachedFeatures(bialph, features);
    for (File f : FileUtil.find(parent, glob))
      params.read(f);
    Log.info("done");
    return params;
  }
   */
}
