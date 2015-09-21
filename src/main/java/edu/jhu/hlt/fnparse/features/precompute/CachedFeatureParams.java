package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Target;
import edu.jhu.hlt.fnparse.features.precompute.InformationGainProducts.BaseTemplates;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.params.Adjoints;
import edu.jhu.hlt.fnparse.rl.params.Params;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
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
 *
 * @author travis
 */
public class CachedFeatureParams implements Params.Stateless {
  private static final long serialVersionUID = 2993313358342467909L;

  private int[][] featureSet;   // each int[] is a feature, each of those values is a template
  private BitSet templateSet;
  private BiAlph bialph;  // used for cardinality and names
  private int[] template2cardinality;

  // If true, the features for prune actions are indicators on the frame,
  // the role, and the frame-role. Otherwise, you need to implement features
  // on Span.NULL_SPAN and save them in the feature files.
  public boolean simplePruneFeatures = true;

  // Could replace this with a method for (sentId, t, s) -> byte[]
  // byte[] keys can be used in a trie!
  public static final class ByteIntTrie {
    // Ho snap, if "pt/foo/bar/quux/baz/d43" is too long, you can write
    // specialized code that segments the input (throwing away separators like
    // '/') and even resort to an alphabet ("pt/foo/bar" -> 24 -> "24" -> 0x00
    // 0x00 0x02 0x04 == 2 dererences). Actually I goofed a little on that last
    // part because "0" != 0x00, but you could do this properly.
    private ByteIntTrie[] children;
    private int payload;  // index of features
  }
  // Even if I do this, I still need to implement the int[] => flatten pathway.

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
  private Map<CacheKey, BaseTemplates> cache;
  /*
   * TODO This cache should really be
   *    (docId, sentId) -> (t,span) -> features
   * Then we could cache get calls on the first map for locality.
   */

  // k -> feature -> weight
  private int dimension = 2 * 1024 * 1024;    // hashing trick
  private double[][] weightsCommit;
  private double[] weightPrune;

  private double l2Penalty = 1e-8;

  public CachedFeatureParams(BiAlph bialph, int numRoles, List<int[]> features) {
    this.bialph = bialph;
    this.template2cardinality = bialph.makeTemplate2Cardinality();
    this.cache = new HashMap<>();

    this.featureSet = new int[features.size()][];
    for (int i = 0; i < featureSet.length; i++)
      featureSet[i] = features.get(i);

    templateSet = new BitSet();
    for (int[] feat : features)
      for (int t : feat)
        templateSet.set(t);

    this.weightsCommit = new double[numRoles][dimension];
    this.weightPrune = new double[dimension];
  }

  public void read(File featureFile) throws IOException {
    Log.info("reading features from " + featureFile.getPath());
    TimeMarker tm = new TimeMarker();
    try (BufferedReader r = FileUtil.getReader(featureFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] toks = line.split("\t");

        Target t = new Target(toks[0], toks[1], Integer.parseInt(toks[2]));

        String[] spanToks = toks[3].split(",");
        Span span = Span.getSpan(Integer.parseInt(spanToks[0]), Integer.parseInt(spanToks[1]));

        BaseTemplates data = new BaseTemplates(templateSet, line);
        data.purgeLine();

//        cache.get(t).put(span, data);
//        Pair<Target, Span> key = new Pair<>(t, span);
        CacheKey key = new CacheKey(t, span);
        BaseTemplates old = cache.put(key, data);
        assert old == null : key + " maps to two values, one if which is in " + featureFile.getPath();

        if (tm.enoughTimePassed(15)) {
          Log.info("processed " + tm.numMarks()
            + " lines in " + tm.secondsSinceFirstMark() + " seconds, "
            + Describe.memoryUsage());
        }
      }
    }
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

    // Get this from this.templates.

    // Get the templates needed for all the features.
    Target t = new Target(f.getSentence().getId(), a.t);
//    Map<Span, BaseTemplates> cacheT = cache.get(t);
    Span s = a.getSpan();
//    BaseTemplates data = cacheT.get(s);
//    BaseTemplates data = cache.get(new Pair<>(t, s));
    BaseTemplates data = cache.get(new CacheKey(t, s));
    if (data == null) {
      throw new RuntimeException("couldn't come up with features for "
          + f.getId() + " action=" + a);
    }

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
    // TODO do some type of testing here
    File parent = new File("data/debugging/coherent-shards/");
    BiAlph bialph = new BiAlph(new File(parent, "alphabet.txt.gz"), false);
    int numRoles = 32;
    List<int[]> features = Arrays.asList(
        new int[] {1,2,3},
        new int[] {3, 5},
        new int[] {4});
    CachedFeatureParams params = new CachedFeatureParams(bialph, numRoles, features);
    for (File f : FileUtil.find(new File(parent, "features/"), "glob:**/*"))
      params.read(f);
  }
}
