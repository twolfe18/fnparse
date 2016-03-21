package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.data.propbank.PropbankReader;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.FeatureIGComputation;
import edu.jhu.hlt.fnparse.features.TemplateContext;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.TemplateDescriptionParsingException;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.AlphabetLine;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Target;
import edu.jhu.hlt.fnparse.features.precompute.InformationGainProducts.BaseTemplates;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning.Mode;
import edu.jhu.hlt.fnparse.pruning.FNParseSpanPruning;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.PruneAdjoints;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.full.FModel;
import edu.jhu.hlt.fnparse.rl.full.FModel.CFLike;
import edu.jhu.hlt.fnparse.rl.full.State.CachedFeatureParamsShim;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.params.Adjoints.LazyL2UpdateVector;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.ModelIO;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.OrderStatistics;
import edu.jhu.hlt.tutils.RedisMap;
import edu.jhu.hlt.tutils.SerializationUtils;
import edu.jhu.hlt.tutils.ShardUtils;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.tuple.Pair;
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
public class CachedFeatures implements Serializable {
  private static final long serialVersionUID = 1063789546828258765L;

  public static boolean DEBUG_FLATTEN_CACHE = false;

  // If true sort features and remove any which have the same feature product
  // (does not check against cardinality, though I guess they should probably
  // be equal for all features).
  // With dedup, performance tends to be a little higher.
  public static final boolean DEDUP_FEATS = true;

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
  private java.util.Vector<Item> loadedDevItems;
  private java.util.Vector<Item> loadedTestItems;
  private Map<String, Item> loadedSentId2Item;

  // This is used by the ItemProvider part of this module. Nice to have as a
  // field here so that there is less book-keeping in RerankerTrainer.
  public PropbankFNParses sentIdsAndFNParses;

  // You probably only want to instantiate one of these per module instance.
  // This lets RerankerTrainer turn off dropout.
  public Params params;

  /** A parse and its features */
  public static final class Item implements Serializable {
    private static final long serialVersionUID = 5176130085247467601L;

    public final FNParse parse;
//    private Map<IntPair, BaseTemplates>[] features;   // t -> (i,j) -> BaseTemplates
//    private Map<SpanPair, BaseTemplates> features2;
    private Map<Span, Map<Span, BaseTemplates>> features3;

    // These are for caching InformationGain.flatten
//    private Map<Span, Map<Span, List<ProductIndex>>> features4;
    private Map<SpanPair, Object> features4b;   // values either int[] (if they can fit) or long[]. not taken any modulo, hoping PI from disk/flatten fit in 64 bits
    private Map<Span, List<Span>> argsForTarget;

    public void convertToFlattenedRepresentation(int[][] featureSet, int[] template2cardinality) {
      if (DEBUG_FLATTEN_CACHE)
        Log.info("converting to flattened representation: " + parse.getId());
      if (features4b != null) {
        assert features3 == null;
        return;
      }
      features4b = new HashMap<>();
      argsForTarget = new HashMap<>();
      for (Entry<Span, Map<Span, BaseTemplates>> x1 : features3.entrySet()) {
        Span t = x1.getKey();
        List<Span> args = new ArrayList<>(x1.getValue().size());
        for (Entry<Span, BaseTemplates> x2 : x1.getValue().entrySet()) {
          Span s = x2.getKey();
          List<ProductIndex> features = statelessGetFeaturesNoModulo(t, s, this, featureSet, template2cardinality);
          Object old = features4b.put(new SpanPair(t, s), lpi2la2(features));
          assert old == null;
          args.add(s);
        }
        Object old = argsForTarget.put(t, args);
        assert old == null;
      }
      features3 = null;
    }

    public List<ProductIndex> getFlattenedCachedFeatures(Span t, Span s) {
      if (features4b == null)
        return null;
      Object x = features4b.get(new SpanPair(t, s));
      if (x instanceof int[]) {
        int[] xx = (int[]) x;
        List<ProductIndex> l = new ArrayList<>(xx.length);
        for (int i = 0; i < xx.length; i++)
          l.add(new ProductIndex(xx[i]));
        return l;
      } else {
        long[] xx = (long[]) x;
        List<ProductIndex> l = new ArrayList<>(xx.length);
        for (int i = 0; i < xx.length; i++)
          l.add(new ProductIndex(xx[i]));
        return l;
      }
    }

    public static long[] lpi2la(List<ProductIndex> x) {
      long[] a = new long[x.size()];
      for (int i = 0; i < a.length; i++)
        a[i] = x.get(i).getProdFeature();
      return a;
    }

    private static int SAVED = 0, TOTAL = 0;
    /** Returns either an int[] if possible or a long[] */
    public static Object lpi2la2(List<ProductIndex> x) {
      int n = x.size();
      long[] a = null;
      int[] b = new int[n];
      for (int i = 0; i < n; i++) {
        long f = x.get(i).getProdFeature();
        if (a != null) {
          // Someone earlier couldn't fit, so might as well make this one a long
          a[i] = f;
        } else if (f > Integer.MAX_VALUE) {
          // Too big, give up, copy b -> a
          a = new long[n];
          for (int j = 0; j < i; j++)
            a[j] = b[j];
          b = null;
          a[i] = f;
        } else {
          // Can fit in an int and so can all of the previous ones
          b[i] = (int) f;
        }
      }
      if (TOTAL % 100 == 0 && DEBUG_FLATTEN_CACHE)
        Log.info("saved=" + SAVED + " total=" + TOTAL);
      TOTAL++;
      if (b != null) {
        SAVED++;
        return b;
      }
      return a;
    }

    public Item(FNParse parse) {
      this.parse = parse;
      this.features3 = new HashMap<>();
    }

    public FNParse getParse() {
      return parse;
    }

    public List<Span> getArgSpansForTarget(Span t) {
      if (argsForTarget != null)
        return argsForTarget.get(t);
      Map<Span, BaseTemplates> f = features3.get(t);
      if (f == null)
        throw new RuntimeException("don't know about this target: " + t.shortString());
      List<Span> args = new ArrayList<>();
      args.addAll(f.keySet());
      return args;
    }

    public Iterator<Pair<SpanPair, BaseTemplates>> getFeatures() {
      assert false : "re-implement based on features4";
      List<Pair<SpanPair, BaseTemplates>> l = new ArrayList<>();
      for (Map.Entry<Span, Map<Span, BaseTemplates>> x1 : features3.entrySet()) {
        Span t = x1.getKey();
        for (Map.Entry<Span, BaseTemplates> x2 : x1.getValue().entrySet()) {
          Span s = x2.getKey();
          l.add(new Pair<>(new SpanPair(t, s), x2.getValue()));
        }
      }
      return l.iterator();
    }

    // Comes before conversion to features4
    public void setFeatures(Span t, Span arg, BaseTemplates features) {
      if (arg.start < 0 || arg.end < 0)
        throw new IllegalArgumentException("span=" + arg);
      if (features.getTemplates() == null)
        throw new IllegalArgumentException();
      Map<Span, BaseTemplates> m = features3.get(t);
      if (m == null) {
        m = new HashMap<>();
        features3.put(t, m);
      }
      BaseTemplates old = m.put(arg, features);
      assert old == null;
    }

    /** @deprecated use getFlattenedCachedFeatures */
    public BaseTemplates getFeatures(Span t, Span arg) {
      BaseTemplates feats = this.features3.get(t).get(arg);
      assert feats != null : "t=" + t.shortString() + " arg=" + arg.shortString() + " y=" + parse.getId();
      return feats;
    }

    /**
     * Returns a set of spans based on what we have features for. Adds nullSpan
     * even if there are no features for it.
     */
    public Map<FrameInstance, List<Span>> spansWithFeatures() {
      boolean pedantic = !ExperimentProperties.getInstance().getBoolean("ignoreNoNullSpanFeatures", false);
      Map<FrameInstance, List<Span>> m = new HashMap<>();
      for (FrameInstance yt : parse.getFrameInstances()) {
        FrameInstance key = FrameInstance.frameMention(yt.getFrame(), yt.getTarget(), parse.getSentence());
        List<Span> values = new ArrayList<>();
        // Gaurantee that nullSpan is in there by putting it first
        values.add(Span.nullSpan);
        boolean sawNullSpan = false;
        for (Span s : this.features3.get(yt.getTarget()).keySet()) {
          if (s != Span.nullSpan)
            values.add(s);
          else
            sawNullSpan = true;
        }
        if (!sawNullSpan && pedantic)
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
              sentIdsAndFNParses.devSetSentIds,
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

  public static class PropbankFNParses implements Serializable {
    private static final long serialVersionUID = -8859010506985261169L;

    public Map<String, FNParse> sentId2parse;
    public Set<String> testSetSentIds;
    public Set<String> devSetSentIds;
    private int maxRole;

    public PropbankFNParses(ExperimentProperties config) {
      Log.info("loading sentId->FNparse mapping...");
      // This can be null since the only reason we need the parses is for
      // computing features, which we've already done.
      // NOT TRUE! These parses were used for DetermnisticRolePruning, but I have
      // added a mode to just use CachedFeautres instead of parses.
      // => This has been mitigated by adding some cooperation between DetermnisticRolePruning
      //    and CachedFeatures.
      //    ParsePropbankData.Redis propbankAutoParses = new ParsePropbankData.Redis(config);
      sentId2parse = new HashMap<>();
      testSetSentIds = new HashSet<>();
      devSetSentIds = new HashSet<>();
      if (config.getBoolean("propbank")) {
        Log.info("loading PB data");
        ParsePropbankData.Redis propbankAutoParses = null;
        PropbankReader pbr = new PropbankReader(config, propbankAutoParses);
        for (FNParse y : pbr.getTrainData()) {
          FNParse old = sentId2parse.put(y.getSentence().getId(), y);
          assert old == null;
        }
        for (FNParse y : pbr.getDevData()) {
          boolean added = devSetSentIds.add(y.getSentence().getId());
          assert added;
          FNParse old = sentId2parse.put(y.getSentence().getId(), y);
          assert old == null;
        }
        for (FNParse y : pbr.getTestData()) {
          boolean added = testSetSentIds.add(y.getSentence().getId());
          assert added;
          FNParse old = sentId2parse.put(y.getSentence().getId(), y);
          assert old == null;
        }
      } else {
        Log.info("loading FN data");
        Iterator<FNParse> itr;
        itr = FileFrameInstanceProvider.dipanjantrainFIP.getParsedSentences();
        while (itr.hasNext()) {
          FNParse y = itr.next();
          if (Math.floorMod(y.getSentence().getId().hashCode(), 10) == 0) {
            boolean added = devSetSentIds.add(y.getSentence().getId());
            assert added;
          }
          FNParse old = sentId2parse.put(y.getSentence().getId(), y);
          assert old == null;
        }
        itr = FileFrameInstanceProvider.dipanjantestFIP.getParsedSentences();
        while (itr.hasNext()) {
          FNParse y = itr.next();
          boolean added = testSetSentIds.add(y.getSentence().getId());
          assert added;
          FNParse old = sentId2parse.put(y.getSentence().getId(), y);
          assert old == null;
        }
      }
      maxRole = 0;
      for (FNParse y : sentId2parse.values()) {
        int T = y.numFrameInstances();
        for (int t = 0; t < T; t++) {
          int K = y.getFrameInstance(t).getFrame().numRoles();
          if (K > maxRole)
            maxRole = K;
        }
      }
      Log.info("done");
    }
    public int getMaxRole() {
      return maxRole;
    }
    public int trainSize() {
      return sentId2parse.size() - (devSetSentIds.size() + testSetSentIds.size());
    }
    public int devSize() {
      return devSetSentIds.size();
    }
    public int testSize() {
      return testSetSentIds.size();
    }
    public int totalSize() {
      return sentId2parse.size();
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
    private boolean test, dev;  // if both are false, then train
    public ItemProvider(int eventualSize, boolean dev, boolean test) {
      Log.info("eventualSize=" + eventualSize + " dev=" + dev + " test=" + test);
      this.eventualSize = eventualSize;
      this.dev = dev;
      this.test = test;
    }
    public int getNumActuallyLoaded() {
      if (test)
        return loadedTestItems.size();
      else if (dev)
        return loadedDevItems.size();
      else
        return loadedTrainItems.size();
    }
    @Override
    public Iterator<FNParse> iterator() {
      final int n = size();
      return new Iterator<FNParse>() {
        private int i = 0;
        @Override
        public boolean hasNext() {
          return i < n;
        }
        @Override
        public FNParse next() {
          return label(i++);
        }
      };
    }
    @Override
    public int size() {
      return eventualSize;
    }
    @Override
    public FNParse label(int i) {
      assert i >= 0 && i < eventualSize;
      java.util.Vector<Item> li;
      if (test)
        li = loadedTestItems;
      else if (dev)
        li = loadedDevItems;
      else
        li = loadedTrainItems;
      int n;
      while (true) {
        n = li.size();
        if (n < 1) {
          Log.info("sleeping because there are no labels to give out yet, test=" + test + " dev=" + dev + " eventualSize=" + eventualSize);
          try {
            Thread.sleep(2 * 1000);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        } else {
          break;
        }
      }
      return li.get(i % n).parse;
    }
    @Override
    public List<edu.jhu.hlt.fnparse.rl.rerank.Item> items(int i) {
      throw new RuntimeException("not allowed to call this because it is not "
          + "synchronized with label(i). I though I didn't use this anymore "
          + "anyway, don't I just use DeterministicRoleePruning?");
    }
  }

  public static enum DropoutMode {
    OFF, TRAIN, TEST
  };

  /**
   * Actually provides the params!
   *
   * Throws a RuntimeException if you ask for features for some FNParse other
   * than the one last served up by this module's ItemProvider.
   */
  public class Params implements Serializable,
      edu.jhu.hlt.fnparse.rl.params.Params.Stateless,
      edu.jhu.hlt.fnparse.rl.params.Params.PruneThreshold,
      CachedFeatureParamsShim,
      CFLike {
    private static final long serialVersionUID = -5359275348868455837L;

    /*
     * TODO Store pruning features.
     * You can just look them up by (frame.index, role) in an array.
     * TODO Compute better pruning features.
     * This conflicts with the caching scheme above.
     */

    // k -> feature -> weight
    // Intercept is stored at position 0, followed by this.dimension non-intercept features
    private int dimension;

    // These must all have the same dimension for IO
    private LazyL2UpdateVector[] weightsCommit;
    private LazyL2UpdateVector weightsPrune;

    private double l2Penalty;

    private DropoutMode dropoutMode = DropoutMode.OFF;
    private double dropoutProbability = 0;
    private Random rand;

    /** {@link Random} is needed for dropout (can be null if no dropout) */
    public Params(double l2Penalty, int dimension, int numRoles, Random rand, int updateL2Every) {
      this.l2Penalty = l2Penalty;
      this.rand = rand;
      this.dimension = dimension;
      this.weightsCommit = new LazyL2UpdateVector[numRoles];
      for (int i = 0; i < weightsCommit.length; i++)
        this.weightsCommit[i] = new LazyL2UpdateVector(new IntDoubleDenseVector(dimension + 1), updateL2Every);
      this.weightsPrune = new LazyL2UpdateVector(new IntDoubleDenseVector(dimension + 1), updateL2Every);
      long weightBytes = (numRoles + 1) * (dimension + 1) * 8;
      Log.info("globalL2Penalty=" + l2Penalty
          + " dimension=" + dimension
          + " numRoles=" + numRoles
          + " numTemplates=" + templateSet.cardinality()
          + " and sizeOfWeights=" + (weightBytes / (1024d * 1024d)) + " MB");
    }

    public int getLazyL2UpdateInterval() {
      return weightsPrune.getUpdateInterval();
    }

    public int getDimension() {
      return dimension;
    }

    // NOTE: If you make another constructor, LazyL2UpdateVector must be
    // initialized because it stores updateInterval (cannot be determined later,
    // needs to be known at construction).

    public DropoutMode getDropoutMode() {
      return this.dropoutMode;
    }
    public void setDropoutMode(DropoutMode mode) {
      Log.info("toggling dropout mode from " + this.dropoutMode + " to " + mode);
      this.dropoutMode = mode;
    }
    public double getDropoutProbability() {
      return this.dropoutProbability;
    }
    public void setDropoutProbability(double pDropout) {
      if (pDropout < 0 || pDropout >= 1)
        throw new IllegalArgumentException("must be in [0,1): " + pDropout);
      assert pDropout == 0 || rand != null;
      this.dropoutProbability = pDropout;
    }

    /**
     * Ignores the feature set, just returns all of the templates we have in memory.
     */
    public BaseTemplates getDebugRawTemplates(FNTagging f, Action a) {
      if (a.getActionType() != ActionType.COMMIT)
        throw new RuntimeException();
      Item cur = loadedSentId2Item.get(f.getSentence().getId());
      Span t = f.getFrameInstance(a.t).getTarget();
      return cur.getFeatures(t, a.getSpan());
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
      fv.add(0, 2);
      fv.add(Math.floorMod(f1, dimension) + 1, 1);
      fv.add(Math.floorMod(f2, dimension) + 1, 1);
      fv.add(Math.floorMod(f3, dimension) + 1, 1);
      return new Adjoints.Vector(this, a, weightsPrune, fv, l2Penalty);
    }

    public IntDoubleUnsortedVector getFeatures(FNTagging f, Span t, Span s) {
      return getFeatures(f.getSentence(), t, s);
    }

    @Override
    public List<ProductIndex> getFeaturesNoModulo(Sentence sent, Span t, Span s) {
      if (dropoutMode != DropoutMode.OFF)
        throw new RuntimeException("fixme");
      Item cur = loadedSentId2Item.get(sent.getId());
      List<ProductIndex> feats = cur.getFlattenedCachedFeatures(t, s);
      if (feats != null)
        return feats;
      Log.info("didn't find any cached flattened features! t=" + t.shortString()
        + " s=" + s.shortString() + " sent=" + sent.getId());
      return statelessGetFeaturesNoModulo(t, s, cur, featureSet, template2cardinality);
//      final int fsLen = featureSet.length;
//
//      // pre-computed features don't include nullSpan
//      ProductIndex intercept = new ProductIndex(0, fsLen + 1);
//      if (s == Span.nullSpan) {
//        return Arrays.asList(intercept.prod(false));
//      }
//      List<ProductIndex> features = new ArrayList<>();
//      features.add(intercept.prod(true));
//
//      // Get the templates needed for all the features.
//      Item cur = loadedSentId2Item.get(sent.getId());
//      BaseTemplates data = cur.getFeatures(t, s);
//
//      List<ProductIndex> buf = new ArrayList<>();
//      for (int i = 0; i < fsLen; i++) {
//        int[] feat = featureSet[i];
//        ProductIndex featPI = new ProductIndex(i + 1, fsLen + 1);
//
//        // Note that these features don't need to be producted with k due to the
//        // fact that we have separate weights for those.
//        InformationGainProducts.flatten(data, 0, feat, 0, ProductIndex.NIL, template2cardinality, buf);
//
//        // If I get a long here, I can zip them all together by multiplying by
//        // featureSet.length and then adding in an offset.
//        for (ProductIndex pi : buf)
//          features.add(featPI.flatProd(pi));
//
//        buf.clear();
//      }
//      return features;
    }

    @Override
    public IntDoubleUnsortedVector getFeatures(Sentence sent, Span t, Span s) {
      if (dropoutMode != DropoutMode.OFF)
        throw new RuntimeException("fixme");
      List<ProductIndex> feats = getFeaturesNoModulo(sent, t, s);
      IntDoubleUnsortedVector fv = new IntDoubleUnsortedVector(feats.size());
      for (ProductIndex f : feats)
        fv.add(f.getProdFeatureModulo(dimension), 1);
      return fv;
    }

//    public IntDoubleUnsortedVector getFeatures(Sentence sent, Span t, Span s) {
//      if (dropoutMode != DropoutMode.OFF && dropoutProbability <= 0)
//        throw new RuntimeException("mode=" + dropoutMode + " prob=" + dropoutProbability);
//
//      // I should be able to use the same code as in InformationGainProducts.
//      IntDoubleUnsortedVector features = new IntDoubleUnsortedVector();
//      features.add(0, 2);   // intercept
//
//      // pre-computed features don't include nullSpan
//      if (s == Span.nullSpan)
//        return features;
//
//      // Get the templates needed for all the features.
//      Item cur = loadedSentId2Item.get(sent.getId());
//      BaseTemplates data = cur.getFeatures(t, s);
//
//      List<ProductIndex> buf = new ArrayList<>();
//      final double v = dropoutMode == DropoutMode.TRAIN ? (1 / (1-dropoutProbability)) : 1;
//      final int fsLen = featureSet.length;
//      for (int i = 0; i < fsLen; i++) {
//        int[] feat = featureSet[i];
//        // Note that these features don't need to be producted with k due to the
//        // fact that we have separate weights for those.
//        InformationGainProducts.flatten(data, 0, feat, 0, ProductIndex.NIL, template2cardinality, buf);
//
//        // If I get a long here, I can zip them all together by multiplying by
//        // featureSet.length and then adding in an offset.
//        for (ProductIndex pi : buf) {
//
//          if (dropoutMode == DropoutMode.TRAIN && rand.nextDouble() < dropoutProbability)
//            continue;
//
////          int h = pi.getHashedProdFeatureModulo(dimension);
//          int h = pi.prod(i, fsLen).getProdFeatureModulo(dimension);
//          Log.info("i=" + i + " dimension=" + dimension + " fsLen=" + fsLen + " feat=" + Arrays.toString(feat));
//          features.add(h + 1, v);   // +1 to make space for intercept
//        }
//        buf.clear();
//      }
//      return features;
//    }

    /**
     * @param a must be a COMMIT action.
     */
    private Adjoints scoreCommit(FNTagging f, Action a) {
      Span t = f.getFrameInstance(a.t).getTarget();
      IntDoubleVector features = getFeatures(f, t, a.getSpan());
      return new Adjoints.Vector(this, a, weightsCommit[a.k], features, l2Penalty);
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
      double[][] w = new double[weightsCommit.length + 1][];
      w[0] = weightsPrune.makeCopyOfWeights(dimension + 1);
      for (int i = 0; i < weightsCommit.length; i++)
        w[i+1] = weightsCommit[i].makeCopyOfWeights(dimension + 1);
      ModelIO.writeTensor2(w, out);
    }

    @Override
    public void deserialize(DataInputStream in) throws IOException {
      double[][] w = ModelIO.readTensor2(in);
      assert w.length == weightsCommit.length + 1;
      if (w[0].length != dimension + 1)
        Log.info("[main] oldDimension=" + dimension + " newDimension=" + w[0].length);
      dimension = w[0].length;
      weightsPrune.set(w[0]);
      for (int i = 1; i < w.length; i++)
        weightsCommit[i-1].set(w[i]);
    }

    @Override
    public void scaleWeights(double scale) {
      for (int i = 0; i < weightsCommit.length; i++)
        weightsCommit[i].scale(scale);
      weightsPrune.scale(scale);
    }

    @Override
    public void addWeights(
        edu.jhu.hlt.fnparse.rl.params.Params other,
        boolean checkAlphabetEquality) {
      if (other instanceof CachedFeatures.Params) {
        CachedFeatures.Params x = (CachedFeatures.Params) other;
        assert weightsCommit.length == x.weightsCommit.length;
        for (int i = 0; i < weightsCommit.length; i++) {
          weightsCommit[i].weights.add(x.weightsCommit[i].weights);
        }
        weightsPrune.weights.add(x.weightsPrune.weights);
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
      return new FNParseSpanPruning(y.getSentence(), y.getFrameInstances(), possibleSpans, true);
    }
  }

  public CachedFeatures(BiAlph bialph, List<int[]> features) {
    this.bialph = bialph;
    this.template2cardinality = bialph.makeTemplate2Cardinality();
    this.loadedTrainItems = new java.util.Vector<>();
    this.loadedDevItems = new java.util.Vector<>();
    this.loadedTestItems = new java.util.Vector<>();
    this.loadedSentId2Item = new ConcurrentHashMap<>();
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

    if (config.getBoolean("templateCardinalityBug", false)) {
      Log.info("templateCardinalityBug: adding 1 to the cardinality of every "
          + "template because of an earlier bug -- regenerating all of the "
          + "data will fix this (code has been fixed), but this is for "
          + "short-term results");
      for (int i = 0; i < template2cardinality.length; i++)
        if (template2cardinality[i] > 0)
          template2cardinality[i]++;
    }
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
      Set<String> devSentIds,
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

        Span ta = Span.inverseShortString(toks[2]);
        Target t = new Target(toks[0], toks[1], ta);

        String[] spanToks = toks[3].split(",");
        Span span = Span.getSpan(Integer.parseInt(spanToks[0]), Integer.parseInt(spanToks[1]));

        BaseTemplates data = new BaseTemplates(templateSet, line, true);
        data.purgeLine();
        nnz.add(data.getFeatures().length);

        // Group by sentence/parse id
        if (cur == null || !t.sentId.equals(cur.parse.getSentence().getId())) {
          if (cur != null)
            addItem(cur, devSentIds, testSentIds);
          FNParse parse = sentId2parse.get(t.sentId);
          if (parse == null) {
            if (skipEntriesNotInSentId2ParseMap) {
              skipped++;
              continue;
            } else {
              throw new RuntimeException("no parse for " + t.sentId
                  + " in featureFile=" + featureFile.getPath()
                  + " in map of size " + sentId2parse.size());
            }
          }
          cur = new Item(parse);
          parse.featuresAndSpans = cur;
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
    addItem(cur, devSentIds, testSentIds);
    Log.info("nnz: " + nnz.getOrdersStr() + " mean=" + nnz.getMean()
        + " nnz/template=" + (nnz.getMean() / templateSetSorted.length));
    Log.info("templateSetSorted.length=" + templateSetSorted.length
        + " templateSet.cardinality=" + templateSet.cardinality());
    if (keepValues)
      Log.info("debugFeatures.size=" + debugFeatures.size());
    if (keepKeys)
      Log.info("debugKeys.size=" + debugKeys.size());
    Log.info("done reading " + numLines + " lines,"
        + " train.size=" + loadedTrainItems.size()
        + " dev.size=" + loadedDevItems.size()
        + " test.size=" + loadedTestItems.size());
  }

  private void addItem(Item cur, Set<String> devSentIds, Set<String> testSentIds) {
    if (FModel.CACHE_FLATTEN) {
      Log.info("converting to flattened representation for storage: " + cur.getParse().getId());
      assert featureSet != null && featureSet.length > 0;
      assert template2cardinality != null && template2cardinality.length > 0;
      cur.convertToFlattenedRepresentation(featureSet, template2cardinality);
    }
    // I think this might still be wrong...
    synchronized (loadedSentId2Item) {
      Item old = loadedSentId2Item.put(cur.parse.getSentence().getId(), cur);
      if (old != null) {
        Log.warn("duplicate item: key=" + cur.parse.getSentence().getId()
            + " old=" + old.parse.getId() + " new=" + cur.parse.getId()
            + ", skipping");
      } else {
        String id = cur.parse.getSentence().getId();
        if (testSentIds.contains(id))
          loadedTestItems.add(cur);
        else if (devSentIds.contains(id))
          loadedDevItems.add(cur);
        else
          loadedTrainItems.add(cur);
      }
    }
  }


  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    // start redis in data/debugging/redis-for-feature-names
    RedisMap<String> tf2name = getFeatureNameRedisMap(config);

    Log.info("loading bialph in serial...");
    BiAlph bialph = new BiAlph(config.getExistingFile("alphabet"), LineMode.ALPH);
    Random rand = new Random();
//    int numTemplates = config.getInt("numTemplates", 993);
    int numTemplates = bialph.getUpperBoundOnNumTemplates();
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
    Random rand = new Random(9001);
    Reranker r = new Reranker(null, null, null, Mode.CACHED_FEATURES, this, 1, 1, rand);
    BasicFeatureTemplates ti = new BasicFeatureTemplates();

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
    int updateL2Every = 32;
    double l2Penalty = 1e-8;
    Params params = this.new Params(l2Penalty, dimension, numRoles, rand, updateL2Every);

    ItemProvider ip = this.new ItemProvider(sentIdsAndFNParses.trainSize(), false, false);
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
          FeatureIGComputation.setRoleIdContext(y, a, ctx, hf);
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

  public static List<ProductIndex> statelessGetFeaturesNoModulo(
      Span t, Span s,
      Item cur, int[][] featureSet, int[] template2cardinality) {
    //      if (dropoutMode != DropoutMode.OFF)
    //        throw new RuntimeException("fixme");
    final int fsLen = featureSet.length;

    // pre-computed features don't include nullSpan
    ProductIndex intercept = new ProductIndex(0, fsLen + 1);
    if (s == Span.nullSpan) {
      return Arrays.asList(intercept.prod(false));
    }
    List<ProductIndex> features = new ArrayList<>();
    features.add(intercept.prod(true));

    // Get the templates needed for all the features.
    //      Item cur = loadedSentId2Item.get(sent.getId());
    BaseTemplates data = cur.getFeatures(t, s);

    List<ProductIndex> buf = new ArrayList<>();
    for (int i = 0; i < fsLen; i++) {
      int[] feat = featureSet[i];
      ProductIndex featPI = new ProductIndex(i + 1, fsLen + 1);

      // Note that these features don't need to be producted with k due to the
      // fact that we have separate weights for those.
      InformationGainProducts.flatten(data, 0, feat, 0, ProductIndex.NIL, template2cardinality, buf);

      if (DEDUP_FEATS && buf.size() > 1) {
        Collections.sort(buf, ProductIndex.BY_PROD_FEAT_ASC);
        List<ProductIndex> tmp = new ArrayList<>();
        for (ProductIndex pi : buf) {
          if (tmp.size() > 0 && tmp.get(tmp.size() - 1).getProdFeature() == pi.getProdFeature())
            continue;
          tmp.add(pi);
        }
//        Log.info("[dedup] " + buf.size() + " => " + tmp.size());
        buf = tmp;
      }

      // If I get a long here, I can zip them all together by multiplying by
      // featureSet.length and then adding in an offset.
      for (ProductIndex pi : buf)
        features.add(featPI.flatProd(pi));

      buf.clear();
    }
    return features;
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


  public static CachedFeatures buildCachedFeaturesForTesting(ExperimentProperties config) throws TemplateDescriptionParsingException {
//    String fs = "Word3-3-grams-between-Head1-and-Head2-Top25*head1head2Path-Basic-NONE-DEP-t-Top25"
//        + " + Dist-Direction-<S>-Span1.Last"
//        + " + Lemma-2-grams-between-<S>-and-Span1.Last";
    String fs = "Bc256/99-1-grams-between-Head2-and-Span1.Last"
        + " + WordWnSynset-2-grams-between-<S>-and-Span1.First"
        + " + Dist-discLen2-Span1.Last-Span2.First";
    return buildCachedFeaturesForTesting(config, fs);
  }
  /**
   * TODO Need to do coordination betweeen what the CachedFeatures module has
   * loaded and what you ask for features for!
   */
  public static CachedFeatures buildCachedFeaturesForTesting(ExperimentProperties config, String fs) throws TemplateDescriptionParsingException {
    MultiTimer mt = new MultiTimer();
    Random rand = new Random(9001);

    // stringTemplate <-> intTemplate and other stuff like template cardinality
    mt.start("alph");
    BiAlph bialph = new BiAlph(
        config.getExistingFile("cachedFeatures.bialph"),
        LineMode.valueOf(config.getString("cachedFeatures.bialph.lineMode")));
    mt.stop("alph");

    // Convert string feature set to ints for CachedFeatures (using BiAlph)
    mt.start("features");
    boolean allowLossyAlphForFS = config.getBoolean("allowLossyAlphForFS", false);
    List<int[]> features = new ArrayList<>();
    for (String featureString : TemplatedFeatures.tokenizeTemplates(fs)) {
      List<String> strTemplates = TemplatedFeatures.tokenizeProducts(featureString);
      int n = strTemplates.size();
      int[] intTemplates = new int[n];
      for (int i = 0; i < n; i++) {
        String tn = strTemplates.get(i);
        Log.info("looking up template: " + tn);
        int t = bialph.mapTemplate(tn);
        if (t < 0 && allowLossyAlphForFS) {
          Log.info("skipping because " + tn + " was not in the alphabet");
          continue;
        }
        assert t >= 0;
        intTemplates[i] = t;
      }
      features.add(intTemplates);
    }
    mt.stop("features");

    // Instantiate the module (holder of the data)
    mt.start("cache");
    CachedFeatures cachedFeatures = new CachedFeatures(bialph, features);
    mt.stop("cache");

    // Load the sentId -> FNParse mapping (cached features only gives you sentId and features)
    mt.start("parses");
    cachedFeatures.sentIdsAndFNParses = new PropbankFNParses(config);
    Log.info("[main] train.size=" + cachedFeatures.sentIdsAndFNParses.trainSize()
    + " dev.size=" + cachedFeatures.sentIdsAndFNParses.devSize()
    + " test.size=" + cachedFeatures.sentIdsAndFNParses.testSize());
    mt.stop("parses");

    // Start loading the data in the background
    mt.start("load");
    int numDataLoadThreads = config.getInt("cachedFeatures.numDataLoadThreads", 1);
    Log.info("[main] loading data in the background with "
        + numDataLoadThreads + " extra threads");
    List<File> featureFiles = FileUtil.find(
        config.getExistingDir("cachedFeatures.featuresParent"),
        config.getString("cachedFeatures.featuresGlob", "glob:**/*"));
    assert numDataLoadThreads > 0;
    boolean readForever = false;  // only useful with reservoir sampling, not doing that
    boolean skipEntriesNotInSentId2ParseMap = false;
    for (int i = 0; i < numDataLoadThreads; i++) {
      List<File> rel = ShardUtils.shard(featureFiles, f -> f.getPath().hashCode(), i, numDataLoadThreads);
      Thread t = new Thread(cachedFeatures.new Inserter(rel, readForever, skipEntriesNotInSentId2ParseMap));
      t.start();
    }
    mt.stop("load");

    // Setup the params
    mt.start("params");
    int dimension = config.getInt("cachedFeatures.hashingTrickDim", 1 * 1024 * 1024);
    int numRoles = config.getInt("cachedFeatures.numRoles",
        cachedFeatures.sentIdsAndFNParses.getMaxRole());
    int updateL2Every = config.getInt("cachedFeatures.updateL2Every", 32);
    double globalL2Penalty = config.getDouble("globalL2Penalty", 1e-7);
    CachedFeatures.Params params = cachedFeatures.new Params(globalL2Penalty, dimension, numRoles, rand, updateL2Every);
    cachedFeatures.params = params;
    mt.stop("params");

    Log.info("times: " + mt);
    return cachedFeatures;
  }
}
