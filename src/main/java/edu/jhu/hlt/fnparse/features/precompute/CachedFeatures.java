package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.fnparse.data.PropbankReader;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.FeatureIGComputation;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.AlphabetLine;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Target;
import edu.jhu.hlt.fnparse.features.precompute.InformationGainProducts.BaseTemplates;
import edu.jhu.hlt.fnparse.inference.frameid.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.inference.frameid.TemplateContext;
import edu.jhu.hlt.fnparse.inference.frameid.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.inference.role.span.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.inference.role.span.DeterministicRolePruning.Mode;
import edu.jhu.hlt.fnparse.inference.role.span.FNParseSpanPruning;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.PruneAdjoints;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.OrderStatistics;
import edu.jhu.hlt.tutils.RedisMap;
import edu.jhu.hlt.tutils.SerializationUtils;
import edu.jhu.hlt.tutils.ShardUtils;
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
  public List<FNParse> debugKeys;
  public boolean keepBoth, keepKeys, keepValues;

  private java.util.Vector<Item> loadedTrainItems;
  private java.util.Vector<Item> loadedTestItems;
  private Map<String, Item> loadedSentId2Item;

  // This is used by the ItemProvider part of this module. Nice to have as a
  // field here so that there is less book-keeping in RerankerTrainer.
  public PropbankFNParses sentIdsAndFNParses;

  /** A parse and its features */
  public static final class Item {
    public final FNParse parse;
    private Map<IntPair, BaseTemplates>[] features;

    @SuppressWarnings("unchecked")
    public Item(FNParse parse) {
      if (parse == null)
        throw new IllegalArgumentException();
      this.parse = parse;
      int T = parse.numFrameInstances();
      this.features = new Map[T];
      for (int t = 0; t < T; t++)
        this.features[t] = new HashMap<>();
    }

    public void setFeatures(int t, Span arg, BaseTemplates features) {
      if (arg.start < 0 || arg.end < 0)
        throw new IllegalArgumentException("span=" + arg);
      if (features.getTemplates() == null)
        throw new IllegalArgumentException();
      BaseTemplates old = this.features[t].put(new IntPair(arg.start, arg.end), features);
      assert old == null;
    }

    public BaseTemplates getFeatures(int t, Span arg) {
      BaseTemplates feats = this.features[t].get(new IntPair(arg.start, arg.end));
      assert feats != null;
      return feats;
    }

    /**
     * Returns a set of spans based on what we have features for. Adds nullSpan
     * even if there are no features for it.
     */
    public Map<FrameInstance, List<Span>> spansWithFeatures() {
      Map<FrameInstance, List<Span>> m = new HashMap<>();
      for (int t = 0; t < this.features.length; t++) {
        FrameInstance yt = parse.getFrameInstance(t);
        FrameInstance key = FrameInstance.frameMention(yt.getFrame(), yt.getTarget(), parse.getSentence());
        List<Span> values = new ArrayList<>();
        // Gaurantee that nullSpan is in there by putting it first
        values.add(Span.nullSpan);
        boolean sawNullSpan = false;
        for (IntPair s : this.features[t].keySet()) {
          if (s.first == Span.nullSpan.start && s.second == Span.nullSpan.end) {
            sawNullSpan = true;
          } else {
            values.add(Span.getSpan(s.first, s.second));
          }
        }
        if (!sawNullSpan)
          Log.warn("no features for nullSpan!");
        List<Span> old = m.put(key, values);
        assert old == null;
      }
      return m;
    }
  }
  public Map<FrameInstance, List<Span>> spansWithFeatures(FNTagging y) {
    Item cur = loadedSentId2Item.get(y.getSentence().getId());
    return cur.spansWithFeatures();
  }

  /**
   * A runnable/thread whose only job is to read parses from disk and put them
   * into CachedFeature's data structures in memory.
   */
  public class Inserter implements Runnable {
    private ArrayDeque<File> readFrom;
    public boolean readForever;
    public boolean skipEntriesNotInSentId2ParseMap;
    public Inserter(Iterable<File> files, boolean readForever, boolean skipEntriesNotInSentId2ParseMap) {
      Log.info("files=" + files + " readForever=" + readForever
          + " sentId2parse.size=" + sentIdsAndFNParses.sentId2parse.size()
          + " testSetSentIds.size=" + sentIdsAndFNParses.testSetSentIds.size());
      this.readForever = readForever;
      this.skipEntriesNotInSentId2ParseMap = skipEntriesNotInSentId2ParseMap;
      this.readFrom = new ArrayDeque<>();
      for (File f : files)
        this.readFrom.offerLast(f);
    }
    @Override
    public void run() {
      while (!readFrom.isEmpty()) {
        File f = readFrom.pollFirst();
        Log.info("about to insert items from " + f.getPath() + " numLeft=" + readFrom.size());
        try {
          // This adds to loaded(Train|Test)Items
          CachedFeatures.this.read(f,
              sentIdsAndFNParses.testSetSentIds,
              sentIdsAndFNParses.sentId2parse,
              skipEntriesNotInSentId2ParseMap);
        } catch (IOException e) {
          e.printStackTrace();
        }
        if (readForever) {
          Log.info("readForever=true, so adding it back");
          readFrom.offerLast(f);
        }
      }
      Log.info("done reading all files");
    }
  }

  public static class PropbankFNParses {
    public Map<String, FNParse> sentId2parse;
    public Set<String> testSetSentIds;
    public PropbankFNParses(ExperimentProperties config) {
      Log.info("loading sentId->FNparse mapping...");
      // This can be null since the only reason we need the parses is for
      // computing features, which we've already done.
      // NOT TRUE! These parses were used for DetermnisticRolePruning, but I have
      // added a mode to just use CachedFeautres instead of parses.
      // => This has been mitigated by adding some cooperation between DetermnisticRolePruning
      //    and CachedFeatures.
      //    ParsePropbankData.Redis propbankAutoParses = new ParsePropbankData.Redis(config);
      ParsePropbankData.Redis propbankAutoParses = null;
      PropbankReader pbr = new PropbankReader(config, propbankAutoParses);

      sentId2parse = new HashMap<>();
      for (FNParse y : pbr.getTrainData()) {
        FNParse old = sentId2parse.put(y.getSentence().getId(), y);
        assert old == null;
      }
      for (FNParse y : pbr.getDevData()) {
        FNParse old = sentId2parse.put(y.getSentence().getId(), y);
        assert old == null;
      }
      testSetSentIds = new HashSet<>();
      for (FNParse y : pbr.getTestData()) {
        boolean added = testSetSentIds.add(y.getSentence().getId());
        assert added;
        FNParse old = sentId2parse.put(y.getSentence().getId(), y);
        assert old == null;
      }
      Log.info("done");
    }
    public int trainSize() {
      return sentId2parse.size() - testSetSentIds.size();
    }
    public int testSize() {
      return testSetSentIds.size();
    }
  }

  /**
   * Reads from either loadedTrainItems or loadedTestItems. Not proper in this
   * sense: repeated calls to label(i) may not return the same value, even if i
   * doesn't change. Does not support calls to items(i) because 1) this would
   * introduce further synchronization problems with label(i) and 2) it is not
   * needed, use {@link DeterministicRolePruning}.
   */
  public class ItemProvider implements edu.jhu.hlt.fnparse.rl.rerank.ItemProvider {
    private int eventualSize;
    private boolean train;
    public ItemProvider(int eventualSize, boolean train) {
      Log.info("eventualSize=" + eventualSize + " train=" + train);
      this.eventualSize = eventualSize;
      this.train = train;
    }
    public int getNumActuallyLoaded() {
      synchronized (loadedSentId2Item) {
        return train ? loadedTrainItems.size() : loadedTestItems.size();
      }
    }
    @Override
    public Iterator<FNParse> iterator() {
      throw new RuntimeException("implement me");
    }
    @Override
    public int size() {
      return eventualSize;
    }
    @Override
    public FNParse label(int i) {
      assert i >= 0 && i < eventualSize;
      synchronized (loadedSentId2Item) {
        java.util.Vector<Item> li = train ? loadedTrainItems : loadedTestItems;
        int n;
        while (true) {
          n = li.size();
          if (n < 1) {
            Log.info("sleeping because there are no labels to give out yet");
            try {
              Thread.sleep(1 * 1000);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          } else {
            break;
          }
        }
        return li.get(i % n).parse;
      }
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
  public class Params implements edu.jhu.hlt.fnparse.rl.params.Params.Stateless,
      edu.jhu.hlt.fnparse.rl.params.Params.PruneThreshold {
    private static final long serialVersionUID = -5359275348868455837L;

    /*
     * TODO Store pruning features.
     * You can just look them up by (frame.index, role) in an array.
     * TODO Compute better pruning features.
     * This conflicts with the caching scheme above.
     */

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
    public BaseTemplates getDebugRawTemplates(FNTagging f, Action a) {
      if (a.getActionType() != ActionType.COMMIT)
        throw new RuntimeException();
      Item cur = loadedSentId2Item.get(f.getSentence().getId());
      return cur.getFeatures(a.t, a.getSpan());
    }

    @Override
    public Adjoints score(FNTagging frames, PruneAdjoints pruneAction, String... providenceInfo) {
      // TODO This ignores providence info?
      return scorePrune(frames, pruneAction);
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
      Item cur = loadedSentId2Item.get(f.getSentence().getId());
      BaseTemplates data = cur.getFeatures(a.t, a.getSpan());

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
      Log.info("TODO: implement me!");
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
      for (int i = 0; i < weightsCommit.length; i++)
        for (int j = 0; j < weightsCommit[i].length; j++)
          weightsCommit[i][j] *= scale;
      for (int i = 0; i < weightPrune.length; i++)
        weightPrune[i] *= scale;
    }

    @Override
    public void addWeights(
        edu.jhu.hlt.fnparse.rl.params.Params other,
        boolean checkAlphabetEquality) {
      if (other instanceof CachedFeatures.Params) {
        CachedFeatures.Params x = (CachedFeatures.Params) other;
        assert weightsCommit.length == x.weightsCommit.length;
        for (int i = 0; i < weightsCommit.length; i++) {
          assert weightsCommit[i].length == x.weightsCommit[i].length;
          for (int j = 0; j < weightsCommit[i].length; j++) {
            weightsCommit[i][j] *= x.weightsCommit[i][j];
          }
        }
        assert weightPrune.length == x.weightPrune.length;
        for (int i = 0; i < weightPrune.length; i++)
          weightPrune[i] *= x.weightPrune[i];
      } else {
        throw new RuntimeException("other=" + other.getClass().getName());
      }
    }
  }

  /**
   * For interoperatiblity with {@link DeterministicRolePruning}
   */
  public class ArgPruning {
    /**
     * Assume that the spans that we have features for were the ones not pruned.
     */
    public FNParseSpanPruning getPruningMask(FNTagging y) {
      Item cur = loadedSentId2Item.get(y.getSentence().getId());
      Map<FrameInstance, List<Span>> possibleSpans = cur.spansWithFeatures();
      return new FNParseSpanPruning(y.getSentence(), y.getFrameInstances(), possibleSpans);
    }
  }

  public CachedFeatures(BiAlph bialph, List<int[]> features) {
    this.bialph = bialph;
    this.template2cardinality = bialph.makeTemplate2Cardinality();
    this.loadedTrainItems = new java.util.Vector<>();
    this.loadedTestItems = new java.util.Vector<>();
    this.loadedSentId2Item = new HashMap<>();
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

  /**
   * Adds the features from the given file to this modules in-memory store.
   * NOTE: You should probably not call this directly, but rather through a
   * thread running an {@link Inserter}.
   * @param testSentIds is the set of ids that should go in the test set. The
   * complement of this set is interpretted as the set of training sentence ids.
   * @param sentId2parse provides the actual {@link FNParse}s, as we only read
   * the sentence ids out of the given file, and there are a few areas in the
   * program where we need the {@link FNParse} in addition to the features.
   * @param skipEntriesNotInSentId2ParseMap says whether we should skip items
   * in the file which don't appear in sentId2parse, which can save memory if
   * you don't need all of the parses/features. If this is false and an item is
   * found in the feature file for which there is no sentId -> {@link FNParse}
   * mapping, an exception is thrown.
   */
  private void read(File featureFile,
      Set<String> testSentIds,
      Map<String, FNParse> sentId2parse,
      boolean skipEntriesNotInSentId2ParseMap) throws IOException {
    Log.info("reading features from " + featureFile.getPath());
    OrderStatistics<Integer> nnz = new OrderStatistics<>();
    TimeMarker tm = new TimeMarker();
    int numLines = 0;

    // How do I get the FNParse for the Item that I'm building?
    // Could take sentId -> FNParse map ahead of time?
    // DONE: Take it in inserter which then passes it into this method

    int skipped = 0, kept = 0;
    Item cur = null;
    try (BufferedReader r = FileUtil.getReader(featureFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        numLines++;
        String[] toks = line.split("\t");

        Target t = new Target(toks[0], toks[1], Integer.parseInt(toks[2]));

        String[] spanToks = toks[3].split(",");
        Span span = Span.getSpan(Integer.parseInt(spanToks[0]), Integer.parseInt(spanToks[1]));

        BaseTemplates data = new BaseTemplates(templateSet, line, true);
        data.purgeLine();
        nnz.add(data.getFeatures().length);

        if (cur == null || !t.sentId.equals(cur.parse.getSentence().getId())) {
          if (cur != null)
            addItem(cur, testSentIds);
          FNParse parse = sentId2parse.get(t.sentId);
          if (parse == null) {
            if (skipEntriesNotInSentId2ParseMap) {
              skipped++;
              continue;
            } else {
              throw new RuntimeException("no parse for " + t.sentId
                  + " in map of size " + sentId2parse.size());
            }
          }
          cur = new Item(parse);
        }

        kept++;

        if (keepBoth)
          cur.setFeatures(t.target, span, data);

        if (keepKeys)
          debugKeys.add(sentId2parse.get(t.sentId));

        if (keepValues) {
          for (int f : data.getFeatures())
            debugFeatures.add(f);
        }

        if (tm.enoughTimePassed(15)) {
          Log.info("processed " + tm.numMarks()
            + " lines in " + tm.secondsSinceFirstMark() + " seconds, "
            + " skipped=" + skipped + " kept=" + kept + ", "
            + Describe.memoryUsage());
        }
      }
    }
    addItem(cur, testSentIds);
    Log.info("nnz: " + nnz.getOrdersStr() + " mean=" + nnz.getMean()
        + " nnz/template=" + (nnz.getMean() / templateSetSorted.length));
    Log.info("templateSetSorted.length=" + templateSetSorted.length
        + " templateSet.cardinality=" + templateSet.cardinality());
    if (keepValues)
      Log.info("debugFeatures.size=" + debugFeatures.size());
    if (keepKeys)
      Log.info("debugKeys.size=" + debugKeys.size());
    Log.info("done reading " + numLines + " lines, train.size="
        + loadedTrainItems.size() + " test.size=" + loadedTestItems.size());
  }

  private void addItem(Item cur, Set<String> testSentIds) {
    synchronized (loadedSentId2Item) {
      Item old = loadedSentId2Item.put(cur.parse.getSentence().getId(), cur);
      assert old == null;
      if (testSentIds.contains(cur.parse.getSentence().getId()))
        loadedTestItems.add(cur);
      else
        loadedTrainItems.add(cur);
    }
  }


  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    // start redis in data/debugging/redis-for-feature-names
    RedisMap<String> tf2name = getFeatureNameRedisMap(config);

    Log.info("loading bialph in serial...");
    BiAlph bialph = new BiAlph(config.getExistingFile("alphabet"), LineMode.ALPH);
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

    int numInsertThreads = 2;
    cfp.testIntStringEquality(config, tf2name, numInsertThreads);
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
  public void testIntStringEquality(ExperimentProperties config, RedisMap<String> tf2name, int numInsertThreads) {
    boolean verbose = config.getBoolean("showFeatureMatches", true);

    TemplateContext ctx = new TemplateContext();
    HeadFinder hf = new SemaforicHeadFinder();
    Reranker r = new Reranker(null, null, null, Mode.CACHED_FEATURES, this, 1, 1, new Random(9001));
    BasicFeatureTemplates.Indexed ti = BasicFeatureTemplates.getInstance();

//    Map<String, FNParse> sentId2parse = getPropbankSentId2Parse(config);
    sentIdsAndFNParses = new PropbankFNParses(config);

    Log.info("starting " + numInsertThreads + " insert threads");
    boolean skipEntriesNotInSentId2ParseMap = true;
    File featuresParent = config.getExistingDir("featuresParent");
    String featuresGlob = config.getString("featuresGlob", "glob:**/*");
    List<File> featureFiles = FileUtil.find(featuresParent, featuresGlob);
    for (int i = 0; i < numInsertThreads; i++) {
      List<File> featureFilesI = ShardUtils.shard(featureFiles, File::hashCode, i, numInsertThreads);
      Log.info("starting thread to ingest: " + featureFilesI);
      Inserter ins = this.new Inserter(featureFilesI, false, skipEntriesNotInSentId2ParseMap);
      Thread insThread = new Thread(ins);
      insThread.start();
    }

    int dimension = 256 * 1024;
    int numRoles = 20;
    Params params = this.new Params(dimension, numRoles);

    ItemProvider ip = this.new ItemProvider(sentIdsAndFNParses.trainSize(), true);
    for (int index = 0; index < ip.size(); index++) {
      FNParse y = ip.label(index);
      Log.info("checking " + y.getSentence().getId() + ", there are "
          + ip.getNumActuallyLoaded() + " parses loaded in memory.");
      State st = r.getInitialStateWithPruning(y, y);
      for (Action a : ActionType.COMMIT.next(st)) {
        // For each template int featureSetFlat, lookup the string (template,feature) via:
        // 1) re-extract using the Template
        // 2) lookup via redis (which reflects the contents of the files -- round trip)

        // Check 1: extracted template-values match stored template-values
        BaseTemplates data = params.getDebugRawTemplates(y, a);

        // Right now I don't have parses populated, so the features for those
        // are different. Rather than fix this, I'd rather just ensure that it
        // works for other templates.
        boolean blowUpOnProblem = false;

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
          if (featureStringValues == null) {
            if (blowUpOnProblem)
              throw new RuntimeException(templateName + " didn't extract any features when we thought it said: " + featureName);
            Log.warn(templateName + " didn't extract any features when we thought it said: " + featureName);
            continue;
          }

          // Check that featureName is at least in featureStringValues.
          int matches = 0;
          for (String fsv : featureStringValues)
            if (fsv.equals(featureName))
              matches++;
          if (matches == 0) {
            if (blowUpOnProblem)
              throw new RuntimeException("no match for template=" + templateName + " featureName=" + featureName + " featureStringValues=" + featureStringValues);
            if (verbose)
              Log.warn("no match for template=" + templateName + " featureName=" + featureName + " featureStringValues=" + featureStringValues);
          } else {
            if (verbose)
              Log.info("success on " + featureName);
          }

          // Check 2: flattened product (template-values) make sense.
          // flatten : (int -> int[]) -> int
          // but it uses hashing.
          // TODO flatten should be tested separtely!
        }
      }
    }
  }

  // For redis (intTemplate,intFeature) -> stringFeature
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
    RedisMap<String> tf2feat = new RedisMap<>(config, SerializationUtils::t2bytes, SerializationUtils::bytes2t);

    String k = "redisInsert";
    if (!config.getBoolean(k, false)) {
      Log.info("skpping the actual redis insert because flag not used: " + k);
      return tf2feat;
    }

    TimeMarker tm = new TimeMarker();
    File alphFile = config.getExistingFile("alphabet");
    boolean header = config.getBoolean("alphabet.header", false);
    try (BufferedReader r = FileUtil.getReader(alphFile)) {
      if (header) {
        String line = r.readLine();
        assert line.startsWith("#");
      }
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        AlphabetLine al = new AlphabetLine(line);
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

}
