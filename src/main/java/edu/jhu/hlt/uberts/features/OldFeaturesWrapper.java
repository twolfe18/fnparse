package edu.jhu.hlt.uberts.features;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import edu.jhu.hlt.concrete.Constituent;
import edu.jhu.hlt.concrete.Parse;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.StringLabeledDirectedGraph;
import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.TemplateContext;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.Template;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.TemplateJoin;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.TemplateAlphabet;
import edu.jhu.hlt.fnparse.features.precompute.FeatureSet;
import edu.jhu.hlt.fnparse.inference.heads.DependencyHeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.rl.full2.AveragedPerceptronWeights;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.SpanPair;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Relation.EqualityArray;
import edu.jhu.hlt.uberts.State;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.UbertsLearnPipeline;
import edu.jhu.hlt.uberts.auto.UbertsPipeline;
import edu.jhu.hlt.uberts.factor.LocalFactor;
import edu.jhu.hlt.uberts.srl.EdgeUtils;
import edu.jhu.hlt.uberts.srl.Srl3EdgeWrapper;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;

/**
 * Uses the same features defined in {@link BasicFeatureTemplates}.
 *
 * NOTE: This contains some general purpose state graph => Sentence code.
 *
 * NOTE: This is hard-coded to a particular transition system.
 */
public class OldFeaturesWrapper {
  public static int DEBUG = 1;

  // set TemplateContext.debugMessage to the HypEdge being scored, features downstream can use this for debugging
  public static boolean DEBUG_CONTEXT_MESSAGE = false;

  // Timer intervals
  public static int INTERVAL_CONV_SENT = 1000;
  public static int INTERVAL_COMP_FEAT = 50000;

  /**
   * Doesn't take the product of feature with the {@link Relation} of the
   * {@link HypEdge} being scored!
   */
  public static class Strings extends FeatureExtractionFactor<Pair<TemplateAlphabet, String>> {
    private OldFeaturesWrapper inner;
    public Strings(OldFeaturesWrapper inner, Double pSkipNeg) {
      this.inner = inner;
      super.customRefinements = inner.customRefinements;
      super.pSkipNeg = pSkipNeg;
    }
    @Override
    public List<Pair<TemplateAlphabet, String>> features(HypEdge yhat, Uberts x) {
      if (pSkipNeg != null && pSkipNeg > 0) {
        if (!x.getLabel(yhat) && x.getRandom().nextDouble() < pSkipNeg)
          return SKIP;
      }
      return inner.features(yhat, x);
    }
    public OldFeaturesWrapper getInner() {
      return inner;
    }
  }

  /**
   * @deprecated
   */
  public static class Ints extends FeatureExtractionFactor<Integer> {
    private OldFeaturesWrapper inner;
    private int mask;
    private int numBits;

    private AveragedPerceptronWeights theta;

    // NEW
    public AveragedPerceptronWeights w0;
    public Map<String, AveragedPerceptronWeights> wf;
    public AveragedPerceptronWeights w1;
    public AveragedPerceptronWeights getWf(String f) {
      AveragedPerceptronWeights w = wf.get(f);
      if (w == null) {
        w = new AveragedPerceptronWeights(1 << numBits, 0);
        wf.put(f, w);
      }
      return w;
    }

    public Ints(OldFeaturesWrapper inner, int numBits) {
      this.numBits = numBits;
      mask = (1 << numBits) - 1;
      super.customRefinements = inner.customRefinements;
      this.inner = inner;
      theta = new AveragedPerceptronWeights(1 << numBits, 0);

      w0 = new AveragedPerceptronWeights(1 << numBits, 0);
      w1 = new AveragedPerceptronWeights(1 << numBits, 0);
      wf = new HashMap<>();
    }

    @Override
    public void completedObservation() {
      theta.completedObservation();
    }

    @Override
    public Adjoints score(HypEdge yhat, Uberts x) {
      int[] y = null;
      if (customRefinements != null)
        y = customRefinements.apply(yhat);

      List<Pair<TemplateAlphabet, String>> f1 = inner.features(yhat, x);
      int n = f1.size();
      int[] f3;

      if (customRefinements != null)
        f3 = new int[n * y.length];
      else
        f3 = new int[n];

      for (int i = 0; i < n; i++) {
        Pair<TemplateAlphabet, String> p = f1.get(i);
        int t = p.get1().index;
        int f = Hash.hash(p.get2());
        if (customRefinements == null) {
          f3[i] = Hash.mix(t, f) & mask;
        } else {
          for (int j = 0; j < y.length; j++)
            f3[i * y.length + j] = Hash.mix(y[j], t, f) & mask;
        }
      }
      if (useAvg)
        return theta.averageView().score(f3, false);
      return theta.score(f3, false);
    }

    public int[] featuresNoRefine(HypEdge yhat, Uberts x) {
      List<Pair<TemplateAlphabet, String>> f1 = inner.features(yhat, x);
      int n = f1.size();
      int[] f3 = new int[n];
      for (int i = 0; i < n; i++) {
        Pair<TemplateAlphabet, String> p = f1.get(i);
        int t = p.get1().index;
        int f = Hash.hash(p.get2());
        f3[i] = Hash.mix(t, f) & mask;
      }
      return f3;
    }

    @Override
    public List<Integer> features(HypEdge yhat, Uberts x) {
//      List<Pair<TemplateAlphabet, String>> f1 = inner.features(yhat, x);
//      int n = f1.size();
//      int T = inner.features.size();
//      List<Integer> f2 = new ArrayList<>(n);
//      for (int i = 0; i < n; i++) {
//        Pair<TemplateAlphabet, String> p = f1.get(i);
//        int t = p.get1().index;
//        int h = Hash.hash(p.get2()) & mask;
//        f2.add(h * T + t);
//      }
//      return f2;
      throw new RuntimeException("should be going through score or inner.features()");
    }

    public OldFeaturesWrapper getInner() {
      return inner;
    }
  }

  public static class Intercept {
    private double weight;
    private double sumOfWeights;
    private int numUpdates;
    private double magnitudeOfFeature;  // The bigger this is, the faster weight will be updated

    public Intercept(double magnitude) {
      this.magnitudeOfFeature = magnitude;
    }

    public Adjoints score() {
      return new Adjoints() {
        private double w = weight;
        @Override
        public double forwards() {
          return w;
        }
        @Override
        public void backwards(double dErr_dForwards) {
          weight -= dErr_dForwards * magnitudeOfFeature;
        }
        @Override
        public String toString() {
          return String.format("(Intercept forwards=%.2f mag=%.2f theta=%.2f)",
              w, magnitudeOfFeature, weight);
        }
      };
    }

    public Adjoints avgScore() {
      return new Adjoints() {
        private double w = sumOfWeights / numUpdates;
        @Override
        public double forwards() {
          return w;
        }
        @Override
        public void backwards(double dErr_dForwards) {
          Log.info("WARNING: shouldn't be updating this, dErr_dForwards=" + dErr_dForwards);
        }
        @Override
        public String toString() {
          return String.format("(AvgIntercept forwards=%.2f numUpdates=%d)", w, numUpdates);
        }
      };
    }

    public void completedObservation() {
      sumOfWeights += weight;
      numUpdates++;
    }
  }

  /**
   * WARNING: Do not give this features which make use of anything other than
   * (t,s) if you are using refinements (argument4). Features are cached on that
   * key but computed without prohibiting reading (f,k). If features read (f,k)
   * then the correctness depends on the order of argument4(t,f,s,k) seen at
   * training and test time.
   */
  public static class Ints3 implements LocalFactor {

    // Have learnDebug=true for the first N calls to score
    public static boolean AUTO_LEARN_DEBUG = true;

    public static final boolean USE_SHA256 = false; // ExperimentProperties.getInstance().getBoolean("Int3.SHA256", false);

    public static Ints3 build(String name, BasicFeatureTemplates bft, Relation r, boolean fixed, boolean learnDebug, ExperimentProperties config) {
      if (name == null)
        throw new IllegalArgumentException("name is null");
      // Old way: take a map of <relationName>:<featureFile>
//      String key = "rel2feat";
//      Map<String, String> rel2featFile = config.getMapping(key);
//      String ffn = rel2featFile.get(r.getName());
//      if (ffn == null)
//        throw new RuntimeException("no features for " + r.getName() + ", update " + key);
//      int dim = config.getInt("hashDimension", 1 << 19);
//      return new Ints3(bft, r, new File(ffn), dim);

      // New way: take a directory and then look for <dir>/<relationName>.fs
      File dir = config.getExistingDir("featureSetDir");
      File ff = new File(dir, r.getName() + ".fs");
      if (!ff.isFile())
        throw new RuntimeException("not a file: " + ff.getPath());
      int dim = 1 << config.getInt(r.getName() + ".hashBits", 22);
      Log.info("[main] relation=" + r.getName() + " featureSetDir=" + dir.getPath() + " dim=" + dim);
      boolean cacheArg4 = !learnDebug && r.getName().equals("argument4");  // && ff.getPath().contains("by-hand");
      Ints3 i3 = new Ints3(name, bft, r, ff, dim, fixed, cacheArg4, learnDebug);

      if ("argument4".equals(r.getName())) {
        Log.info("[main] refining with (f,k) and (k,)");
        i3.inner.onlyUseTS = cacheArg4;
        i3.useBaseFeatures(false);
        i3.refine((Function<HypEdge, String> & Serializable) e -> {
          assert e.getRelation().getName().equals("argument4");
          String frame = EdgeUtils.frame(e);
          String role = EdgeUtils.role(e);
          return frame + "-" + role;
        }, "fk");
//        i3.refine((Function<HypEdge, String> & Serializable) e -> {
//          assert e.getRelation().getName().equals("argument4");
//          String frame = EdgeUtils.frame(e);
//          return frame;
//        }, "f");
        i3.refine((Function<HypEdge, String> & Serializable) e -> {
          assert e.getRelation().getName().equals("argument4");
          String role = EdgeUtils.role(e);
          return role;
        }, "k");
      } else if ("predicate2".equals(r.getName())) {

        // We don't need any refinements because the semafor frameid features include them (not good?)
        
//        i3.useBaseFeatures(false);
//        i3.refine((Function<HypEdge, String> & Serializable) e -> {
//          String frame = EdgeUtils.frame(e);
//          return frame;
//        }, "f");
        
      } else if ("event1".equals(r.getName())) {
        
        // No refinements

      } else {
        throw new RuntimeException("how to handle: " + r);
      }

      return i3;
    }

    public void readWeightsFrom(File f, boolean fixed) {
      Log.info("rel=" + rel.getName() + " fixed=" + fixed + " f=" + f.getPath());
      try (InputStream is = FileUtil.getInputStream(f);
          ObjectInputStream ois = new ObjectInputStream(is);
          DataInputStream dis = new DataInputStream(ois)) {
        String relName = dis.readUTF();
        if (!relName.equals(rel.getName())) {
          throw new RuntimeException("this.rel=" + rel.getName()
              + " but serialized weights were for " + relName);
        }
//        System.out.println("before: dim=" + dimension + " useAvg=" + useAvg);
        dimension = dis.readInt();
        useAvg = dis.readBoolean();
        useBaseFeatures = dis.readBoolean();
//        System.out.println("after:  dim=" + dimension + " useAvg=" + useAvg);
        theta = (AveragedPerceptronWeights) ois.readObject();
//        theta2 = (List<Pair<ToIntFunction<HypEdge>, AveragedPerceptronWeights>>) ois.readObject();
        theta2 = (List<Pair<Function<HypEdge, String>, AveragedPerceptronWeights>>) ois.readObject();
        this.fixed = fixed;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    public void writeWeightsTo(File f) {
      Log.info("[main] rel=" + rel.getName() + " f=" + f.getPath() + " dim=" + dimension + " useAvg=" + useAvg);
      assert intercept == null : "implement this";
      try (OutputStream os = FileUtil.getOutputStream(f);
          ObjectOutputStream oos = new ObjectOutputStream(os);
          DataOutputStream dos = new DataOutputStream(oos)) {
        dos.writeUTF(rel.getName());
        dos.writeInt(dimension);
        dos.writeBoolean(useAvg);
        dos.writeBoolean(useBaseFeatures);
        oos.writeObject(theta);
        oos.writeObject(theta2);
      } catch (Exception e) {
//        throw new RuntimeException(e);
        Log.info("WARNING: failed to serialize, " + e.getMessage());
        e.printStackTrace();
      }

      // Diagnostic, how many nonzero features are there?
      int nnz = 0;
      for (int i = 0; i < dimension; i++)
        if (Math.abs(theta.getWeight(i)) > 1e-10)
          nnz++;
      Log.info("nnz=" + nnz + " dim=" + dimension + " percentNonzero=" + (100d*nnz)/dimension);
    }

    public void dbgShowWeights(String prefix) {
      int nnz = 0;
      for (int i = 0; i < dimension; i++) {
        double w = theta.getWeight(i);
        if (w != 0) {

          String feat;
          if (UbertsLearnPipeline.FEATURE_DEBUG == null)
            feat = "" + i;
          else
            feat = UbertsLearnPipeline.FEATURE_DEBUG.lookupObject(i);

          nnz++;
          System.out.printf("%s theta[%s][noRef][%s]=%+.4f\n", prefix, name, feat, w);
        }
      }
      System.out.printf("%s %s %s nnz=%d fixed=%s useAvg=%s\n", prefix, name, "noRef", nnz, fixed, useAvg);
      for (int j = 0; theta2 != null && j < theta2.size(); j++) {
        String ref = theta2RefName.get(j);
        nnz = 0;
        for (int i = 0; i < dimension; i++) {
          double w = theta2.get(j).get2().getWeight(i);
          if (w != 0) {

            String feat;
            if (UbertsLearnPipeline.FEATURE_DEBUG == null)
              feat = "" + i;
            else
              feat = UbertsLearnPipeline.FEATURE_DEBUG.lookupObject(i);

            nnz++;
            System.out.printf("%s theta[%s][%s][%s]=%+.4f\n", prefix, name, ref, feat, w);
          }
        }
        System.out.printf("%s %s %s nnz=%d fixed=%s useAvg=%s\n", prefix, name, ref, nnz, fixed, useAvg);
      }
    }

    public final String name;
    public OldFeaturesWrapper inner;
    private Relation rel;
    private AveragedPerceptronWeights theta;
    private int dimension;
    private Intercept intercept;
    private boolean useAvg = false;
    private Counts<String> cnt = new Counts<>();

    // Set to true when you are using pre-trained weights. Does three things:
    // 1) disables the effects of calling useAverageWeights
    // 2) disables the effects of calling completedObservation
    // 3) returns Adjoints which do not do anything when backwards is called.
    private boolean fixed;

    // For refinements
//    private List<Pair<ToIntFunction<HypEdge>, AveragedPerceptronWeights>> theta2;
    private List<Pair<Function<HypEdge, String>, AveragedPerceptronWeights>> theta2;
    private List<String> theta2RefName;

    // For writing out features to a file
    private BufferedWriter featStrDebug;

    // For caching refined features based on (t,s)
    // Only for use with refinements.
    private Map<SpanPair, int[]> arg4FeatureCache;
    private Counts<String> cacheCounts = new Counts<>();
    private Sentence cacheTag = null;

    // You can add refinements (label features) to make the features more specific,
    // but this boolean says whether just f(x) should be included in every feature
    // vector, as a sort of data-relative intercept.
    public boolean useBaseFeatures = true;

    public boolean learnDebug;

    private Double pOracleFeatureFlip = null;
    private Random rand = null;
    public void includeOracleFeature(double pOracleFeatureFlip, Random rand) {
      Log.info("pOracleFeatureFlip=" + pOracleFeatureFlip + " relation=" + rel.getName() + " name=" + name);
      assert pOracleFeatureFlip >= 0 && pOracleFeatureFlip < 1 : "pOracleFeature=" + pOracleFeatureFlip;
      assert pOracleFeatureFlip == 0 || rand != null;
      this.pOracleFeatureFlip = pOracleFeatureFlip;
      this.rand = rand;
    }
    public void dontIncludeOracleFeature() {
      Log.info("");
      this.pOracleFeatureFlip = null;
      this.rand = null;
    }

    @Override
    public String toString() {
      return "(Int3 " + rel.getName()
          + " refs=" + theta2RefName
          + " pOracleFeatFlip=" + pOracleFeatureFlip
          + " dim=" + dimension
          + " intercept=" + intercept
          + " fixed=" + fixed + ")";
    }

    public void refine(Function<HypEdge, String> f, String name) {
      // By default lambdas aren't Serializable...
      f = (Function<HypEdge, String> & Serializable) f;
      if (theta2 == null) {
        theta2 = new ArrayList<>();
        theta2RefName = new ArrayList<>();
      }
      theta2.add(new Pair<>(f, new AveragedPerceptronWeights(dimension, 0)));
      theta2RefName.add(name);
    }

    /**
     * For _feature_ IO (e.g. computing mutual information), not _weight_ IO (e.g. saving model parameters).
     */
    public void writeFeaturesToDisk(File writeFeatsTo) {
      Log.info("[main] writing features to " + writeFeatsTo.getPath());
      try {
        featStrDebug = FileUtil.getWriter(writeFeatsTo);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    /**
     * For _feature_ IO (e.g. computing mutual information), not _weight_ IO (e.g. saving model parameters).
     */
    public void closeWriter() {
      if (featStrDebug != null) {
        try {
          featStrDebug.close();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }

    public Ints3(String name, BasicFeatureTemplates bft, Relation r, File featureSet, int dimension, boolean fixed, boolean cacheArg4, boolean learnDebug) {
      Log.info("name=" + name
          + "r=" + r.getName()
          + " featureSet=" + featureSet.getPath()
          + " dimension=" + dimension
          + " fixed=" + fixed
          + " cacheArg4=" + cacheArg4
          + " learnDebug=" + learnDebug);
      if (cacheArg4) assert !learnDebug : "if you want learnDebug=true, you should set cacheArg4=false";
      this.name = name;
      this.inner = new OldFeaturesWrapper(bft, featureSet);
      this.rel = r;
      int numIntercept = 0;
      this.dimension = dimension;
      this.theta = new AveragedPerceptronWeights(dimension, numIntercept);
      this.fixed = fixed;
      this.learnDebug = learnDebug;

      if (cacheArg4 && r.getName().equals("argument4")) {
        arg4FeatureCache = new HashMap<>();
      }

      ExperimentProperties config = ExperimentProperties.getInstance();
      String k = r.getName() + ".intercept.mag";
      double mag = config.getDouble(k, -1);
      if (mag > 0) {
        Log.info("[main] using " + k + "=" + mag);
        this.intercept = new Intercept(mag);
      }
    }

    public void useBaseFeatures(boolean useBase) {
      Log.info(this + "\t" + useBaseFeatures + " => " + useBase);
      this.useBaseFeatures = useBase;
    }

    public void useAverageWeights(boolean useAvg) {
      if (fixed) {
        Log.info("this.useAvg=" + this.useAvg + ", ignoring useAvg=" + useAvg);
      } else {
        Log.info("useAvg " + this.useAvg + " => " + useAvg);
        this.useAvg = useAvg;
      }
    }

    public void completedObservation() {
      if (fixed)
        return;
      theta.completedObservation();
      if (intercept != null)
        intercept.completedObservation();
      if (theta2 != null) {
        for (Pair<?, AveragedPerceptronWeights> x : theta2)
          x.get2().completedObservation();
      }
    }

    int scoreCalls = 0;
    @Override
    public Adjoints score(HypEdge y, Uberts x) {
      scoreCalls++;
      assert y.getRelation() == rel;
      cnt.increment("score/" + y.getRelation().getName());

//      if (rel.getName().equals("predicate2")) {
//        Log.info("check me out");
//      }

      if (AUTO_LEARN_DEBUG && scoreCalls == 5000) {
        learnDebug = false;
        UbertsLearnPipeline.turnOffDebug();
      }

      // Check whether the cache is valid
      assert x.dbgSentenceCache != null;
      if (arg4FeatureCache != null && x.dbgSentenceCache != cacheTag) {
        cacheTag = x.dbgSentenceCache;
        arg4FeatureCache.clear();
        cacheCounts.increment("arg4CacheInvalidation");
      }

      // Create the key used for caching features
      SpanPair key = null;
      int[] features = null;
      boolean arg4 = y.getRelation().getName().equals("argument4");
      if (arg4FeatureCache != null && arg4) {
        Span t = Span.inverseShortString((String) y.getTail(0).getValue());
        Span s = Span.inverseShortString((String) y.getTail(2).getValue());
        key = new SpanPair(t, s);
        features = arg4FeatureCache.get(key);
      }

      List<String> fx;
      if (features != null) {
        fx = null;
        if (arg4)
          cacheCounts.increment("arg4CacheHit");
      } else {
        if (arg4)
          cacheCounts.increment("arg4CacheMiss");
        if (key != null && cacheCounts.getTotalCount() % 10000 == 0)
          Log.info(cacheCounts);

        // Call the wrapped features
        List<Pair<TemplateAlphabet, String>> fyx = inner.features(y, x);
        if (fyx.isEmpty()) {
          cnt.increment("score/noFeat/" + y.getRelation().getName());
          if (intercept == null)
            return Adjoints.Constant.ZERO;
          return useAvg ? intercept.avgScore() : intercept.score();
        }
        if (cnt.getTotalCount() % 750000 == 0)
          System.out.println("Int3 events: " + cnt.toString());

        // Convert to int[]
        fx = new ArrayList<>();
        features = new int[fyx.size()];
        int T = inner.getNumTemplates();
        for (int i = 0; i < features.length; i++) {
          Pair<TemplateAlphabet, String> fyxi = fyx.get(i);
          int t = fyxi.get1().index;
          fx.add(fyxi.get2());
          assert t >= 0 && t < T;
          if (UbertsLearnPipeline.FEATURE_DEBUG != null) {
            features[i] = UbertsLearnPipeline.FEATURE_DEBUG.lookupIndex(fyxi.get1().name + "/" + fyxi.get2());
          } else if (USE_SHA256) {
            long f = Hash.sha256(fyxi.get2());
            features[i] = (int) (f * T + t);
          } else {
            int f = Hash.hash(fyxi.get2());
            features[i] = f * T + t;
          }
        }

        if (UbertsLearnPipeline.EXACTLY_ONE_CONSUME) {
          Log.info("something uniq " + key + "\t" + Arrays.toString(features));
        }

        // Save to cache
        if (key != null) {
          arg4FeatureCache.put(key, features);
        }

        // Maybe output features to disk
        if (featStrDebug != null) {
          try {
            featStrDebug.write(y + "\t" + x.getLabel(y));
            for (int i = 0; i < features.length; i++) {
              featStrDebug.write("\t" + features[i]);
            }
            featStrDebug.newLine();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }

      // Maybe add on one oracle feature in fx
      if (pOracleFeatureFlip != null) {
        // re-allocate as not to include this feature in the cache
        boolean flip = rand.nextDouble() < pOracleFeatureFlip;
        int n = 20;
        features = Arrays.copyOf(features, features.length + n);
        boolean show = flip ^ x.getLabel(y);
        // Also show whether this is a nil span, tin foil measure.
        boolean nil = UbertsLearnPipeline.isNilFact(y);
        // In the rare event of a hash collision, have two
        for (int i = 1; i <= n; i++) {
          features[features.length - i] = Hash.hash("y=" + show + ",nil=" + nil + ",seed=" + i);
        }
      }

      boolean reindex = true;
      List<String> fy = new ArrayList<>();
      Adjoints a = Adjoints.Constant.ZERO;
      if (useBaseFeatures) {
        fy.add("1");
        if (useAvg) {
          a = theta.averageView().score(features, reindex);
          if (intercept != null)
            a = Adjoints.sum(a, intercept.avgScore());
        } else {
          a = theta.score(features, reindex);
          if (intercept != null)
            a = Adjoints.sum(a, intercept.score());
        }
      }

      // If there are refinements, take the product of those with appropriate weights
      if (theta2 != null) {
        for (int i = 0; i < theta2.size(); i++) {

//          Pair<ToIntFunction<HypEdge>, AveragedPerceptronWeights> ref = theta2.get(i);
//          int r = ref.get1().applyAsInt(y);
          Pair<Function<HypEdge, String>, AveragedPerceptronWeights> ref = theta2.get(i);
          String rr = ref.get1().apply(y);

          fy.add(theta2RefName.get(i) + "=" + rr);
          int[] fr = new int[features.length];

          // Mix in the VALUE of the refinement, not the refinement NAME
          if (UbertsLearnPipeline.FEATURE_DEBUG != null) {
            for (int j = 0; j < fr.length; j++) {
              fr[j] = UbertsLearnPipeline.FEATURE_DEBUG.lookupIndex(
                  "ref/" + theta2RefName.get(i) + "=" + rr + "/"
                  + UbertsLearnPipeline.FEATURE_DEBUG.lookupObject(features[j]));
            }
          } else {
            int r = Hash.hash(rr);
            for (int j = 0; j < fr.length; j++)
              fr[j] = Hash.mix(r, features[j]);
          }

          Adjoints a2;
          if (useAvg) {
            a2 = ref.get2().averageView().score(fr, reindex);
//            a2 = ref.get2().averageView().score(features, reindex);
          } else {
            a2 = ref.get2().score(fr, reindex);
//            a2 = ref.get2().score(features, reindex);
          }
          a = Adjoints.sum(a2, a);
        }
      }

      assert a != Adjoints.Constant.ZERO : "useBaseFeatures=" + useBaseFeatures + " and refinements=" + theta2RefName;

      // If fixed, make sure the parameters are not updated via backwards
      if (fixed)
        a = new Adjoints.WithLearningRate(0, a);

      // Create a wrapper which knows how to show features
      if (learnDebug)
        a = new DebugFeatureAdj(a, fy, fx, y);

      if (learnDebug && "predicate2".equals(rel.getName())) {
        Log.info("edge=" + y + " score=" + a.forwards());
      }

      return a;
    }
  }

  /**
   * Tracks weights for every relation.
   */
  public static class Ints2 implements LocalFactor {
    private OldFeaturesWrapper inner;
    private Alphabet<Relation> rels;
    private AveragedPerceptronWeights[] rel2theta;
    private int dimension;
//    private long nScore = 0, nScoreNoFeat = 0;
    private Counts<String> cnt = new Counts<>();
    private Timer timer;

    /**
     * @param inner
     * @param dimension is how many weights per {@link Relation}
     */
    public Ints2(OldFeaturesWrapper inner, int dimension) {
      this.inner = inner;
      this.rels = new Alphabet<>();
      // TODO Dynamic resizing of rel2theta
      this.rel2theta = new AveragedPerceptronWeights[16];
      this.dimension = dimension;
      this.timer = new Timer("Int2/score", 250_000, true);
    }

    @Override
    public Adjoints score(HypEdge y, Uberts x) {
      timer.start();

      cnt.increment("score");
      cnt.increment("score/" + y.getRelation().getName());
      List<Pair<TemplateAlphabet, String>> fyx = inner.features(y, x);
      if (fyx.isEmpty()) {
        cnt.increment("score/noFeat");
        cnt.increment("score/noFeat/" + y.getRelation().getName());
        return Adjoints.Constant.ZERO;
      }
      if (cnt.getTotalCount() % 75000 == 0)
        System.out.println("Int2 events: " + cnt.toString());

      int ri = rels.lookupIndex(y.getRelation());
      AveragedPerceptronWeights theta = rel2theta[ri];
      if (theta == null) {
        int numIntercept = 0;
        theta = new AveragedPerceptronWeights(dimension, numIntercept);
        rel2theta[ri] = theta;
      }

      int[] features = new int[fyx.size()];
      int T = inner.getNumTemplates();
      for (int i = 0; i < features.length; i++) {
        Pair<TemplateAlphabet, String> fyxi = fyx.get(i);
        int t = fyxi.get1().index;
        assert t >= 0 && t < T;
        int f = Hash.hash(fyxi.get2());
        features[i] = f * T + t;
      }

      boolean reindex = true;
      Adjoints a = theta.score(features, reindex);
      timer.stop();
      return a;
    }
  }

  // See TemplatedFeatures.parseTemplate(String), etc
  private edu.jhu.hlt.fnparse.features.precompute.Alphabet features;
  private TemplateContext ctx;
  private HeadFinder hf;
  private Counts<String> skipped;

  // If you can setup a TemplateContext given a HypEdge, then you can use this class.
  // Sentence setup and conversion is handled for you (so you can access ctx.getSentence()).
  // If you leave this as null, only srl1,srl2,srl3 will work (maybe more later).
  public BiConsumer<Pair<HypEdge, Uberts>, TemplateContext> customEdgeCtxSetup = null;

  public Function<HypEdge, int[]> customRefinements = null;

  public boolean onlyUseTS = false;


  public int getNumTemplates() {
    return features.size();
  }

  public OldFeaturesWrapper(BasicFeatureTemplates bft, File featureSet) {
    if (!featureSet.isFile())
      throw new IllegalArgumentException("feature set file doesn't exist: " + featureSet);

    Random rightRandomPrune = null; //new Random(9001);
    this.features = new edu.jhu.hlt.fnparse.features.precompute.Alphabet(bft, false);
    for (String[] feat : FeatureSet.getFeatureSet3(featureSet)) {
      String n = StringUtils.join("*", feat);
      Template[] fts = bft.getBasicTemplates(feat);
      Template ft = TemplateJoin.prod(fts, rightRandomPrune);
      features.add(new TemplateAlphabet(ft, n, features.size()));
    }

    ctx = new TemplateContext();
    hf = new DependencyHeadFinder();
    skipped = new Counts<>();
  }

  /** Starts up with some dummy features, for debugging */
  public OldFeaturesWrapper(BasicFeatureTemplates bft) {

    // This should be enough to over-fit
//    String w = "Bc256/8";
    String w = "Word";
    String[] tempNames = new String[] {
//        "Bc256/8-2-grams-between-Head1-and-Span2.Last",
//        "Head1Head2-PathNgram-Basic-LEMMA-DIRECTION-len2",
//        "Head1Head2-PathNgram-Basic-POS-DEP-len2",
        "Head1Head2-Path-Basic-LEMMA-DEP-t",
        "Span2-PosPat-FULL_POS-3-1",
        "Span2-First-" + w,
        "Span2-Last-" + w,
        "Span1-PosPat-FULL_POS-3-1",
        "Span1-First-" + w,
        "Span1-Last-" + w,
        "Span1-Width-Div2",
        "Head1-Child-Basic-" + w,
        "Head1-Parent-Basic-" + w,
        "Head1-Grandparent-Basic-" + w,
        "Head1-RootPath-Basic-POS-DEP-t",
//        "Head1-RootPathNgram-Basic-LEMMA-DIRECTION-len3",
        "Head2-RootPath-Basic-POS-DEP-t",
//        "Head2-RootPathNgram-Basic-LEMMA-DIRECTION-len3",
        "Head2-Child-Basic-" + w,
        "Head2-Parent-Basic-" + w,
        "Head2-Grandparent-Basic-" + w,
//        "lexPredArg", "lexArgMod", "lexPredMod",
    };

    if (DEBUG > 0)
      Log.info("using DEBUG feature set of " + tempNames.length + " templates (1,2,3 grams)");

    Template[] temps = new Template[tempNames.length];
    for (int i = 0; i < temps.length; i++) {
      temps[i] = bft.getBasicTemplate(tempNames[i]);
      if (temps[i] == null)
        throw new RuntimeException("couldn't look up: " + tempNames[i]);
    }

    this.features = new edu.jhu.hlt.fnparse.features.precompute.Alphabet(bft, false);
    // UNIGRAMS
    for (int i = 0; i < temps.length; i++) {
      features.add(new TemplateAlphabet(temps[i], tempNames[i], features.size()));
    }
    // BIGRAMS
    for (int i = 0; i < temps.length-1; i++) {
      for (int j = i+1; j < temps.length; j++) {
        Template prod = new TemplatedFeatures.TemplateJoin(temps[i], temps[j]);
        String name = tempNames[i] + "*" + tempNames[j];
        features.add(new TemplateAlphabet(prod, name, features.size()));
      }
    }

    ctx = new TemplateContext();
    hf = new DependencyHeadFinder();
    skipped = new Counts<>();
  }

  public edu.jhu.hlt.fnparse.features.precompute.Alphabet getFeatures() {
    return features;
  }

  private static String getSentenceId(Uberts u) {
    State s = u.getState();
    Relation rel = u.getEdgeType("startDoc");
    HypEdge e = s.match2(rel).item;
    return (String) e.getTail(0).getValue();
  }

  public static boolean NEW_CSYN_MODE = true;  // true means csyn5-*, false means csyn6-*
  private static ConstituencyParse buildCP(Uberts u, String sentenceId) {
    // def csyn6-stanford id parentId head start end label
    // def csyn5-stanford id parentId head span phrase
    Relation consRel = NEW_CSYN_MODE
        ? u.getEdgeType("csyn5-stanford")
        : u.getEdgeType("csyn6-stanford");
    State st = u.getState();
    List<Pair<Integer, edu.jhu.hlt.concrete.Constituent>> cons = new ArrayList<>();
    Map<Integer, edu.jhu.hlt.concrete.Constituent> id2con = new HashMap<>();
    int foundCons = 0;
    for (LL<HypEdge> cur = st.match2(consRel); cur != null; cur = cur.next) {
      foundCons++;
      HypEdge e = cur.item;
      assert e.getNumTails() == (NEW_CSYN_MODE ? 5 : 6);
      int cid = Integer.parseInt((String) e.getTail(0).getValue());
      int parent = Integer.parseInt((String) e.getTail(1).getValue());
      int headToken = Integer.parseInt((String) e.getTail(2).getValue());
      Span s;
      String lhs;
      if (NEW_CSYN_MODE) {
        String ss = (String) e.getTail(3).getValue();
        s = Span.inverseShortString(ss);
        lhs = (String) e.getTail(4).getValue();
      } else {
        int startToken = Integer.parseInt((String) e.getTail(3).getValue());
        int endToken = Integer.parseInt((String) e.getTail(4).getValue());
        s = Span.getSpan(startToken, endToken);
        lhs = (String) e.getTail(5).getValue();
      }

      edu.jhu.hlt.concrete.Constituent c = new Constituent();
      c.setId(cid);
      c.setStart(s.start);
      c.setEnding(s.end);
      c.setTag(lhs);
      c.setHeadChildIndex(headToken);  // Need to convert token -> child index

      if (DEBUG > 1)
        Log.info(cid + " -> " + c);
      id2con.put(cid, c);
      cons.add(new Pair<>(parent, c));
    }

    if (foundCons == 0) {
//      Log.info("WARNING: " + sentenceId + " has no " + consRel.getName() + " facts?");
      return null;
    }

    // Add children
    for (Pair<Integer, edu.jhu.hlt.concrete.Constituent> pc : cons) {
      int parent = pc.get1();
      if (parent < 0)
        continue; // ROOT
      edu.jhu.hlt.concrete.Constituent c = pc.get2();
      edu.jhu.hlt.concrete.Constituent p = id2con.get(parent);
      p.addToChildList(c.getId());
    }

    // Set heads
    for (Pair<Integer, edu.jhu.hlt.concrete.Constituent> pc : cons) {
      edu.jhu.hlt.concrete.Constituent c = pc.get2();
      c.setHeadChildIndex(-1);
      if (!c.isSetChildList() || c.getChildListSize() == 0) {
//        assert c.getStart()+1 == c.getEnding() : "TODO attach all POS tags in this span as children.";
        if (c.getStart()+1 != c.getEnding())
          Log.warn("TODO attach all POS tags in this span as children.");
        continue;
      }
      int headToken = c.getHeadChildIndex();
      int headChildIdx = -1;
      int i = 0;
      for (int childId : c.getChildList()) {
        edu.jhu.hlt.concrete.Constituent child = id2con.get(childId);
        if (child.getStart() <= headToken && headToken < child.getEnding()) {
          assert headChildIdx < 0;
          headChildIdx = i;
        }
        i++;
      }
      c.setHeadChildIndex(headChildIdx);
    }

    Parse p = new Parse();
    for (Pair<Integer, edu.jhu.hlt.concrete.Constituent> pc : cons)
      p.addToConstituentList(pc.get2());
    return new ConstituencyParse(sentenceId, p);
  }

  private static DependencyParse getDepTree(String depsRelName, Uberts u, List<HypNode> tokens) {
    Relation depsRel = u.getEdgeType(depsRelName);
    State st = u.getState();
    int n = tokens.size();
    String[] lab = new String[n];
    int[] gov = new int[n];
    for (int i = 0; i < n; i++) {
      // Find the 0 or 1 tokens which govern this token
      HypNode dep = tokens.get(i);
      LL<HypEdge> gov2dep = st.match(1, depsRel, dep);
      if (gov2dep == null) {
        gov[i] = -1;
        lab[i] = "UKN";
      } else {
        if (gov2dep.next != null) {
          Log.warn("two gov (not tree) for " + depsRelName + " ? proof: " + gov2dep
            + "\nin sentence: " + tokens);
        }
        HypEdge e = gov2dep.item;
        gov[i] = Integer.parseInt((String) e.getTail(0).getValue());
        lab[i] = (String) e.getTail(2).getValue();
      }
    }
    return new DependencyParse(gov, lab);
  }

  /**
   * @param root should probably be the length of the sentence. It must be >=0.
   * @param depRel should have columns gov, dep, label.
   */
  private static StringLabeledDirectedGraph getDepGraph(Uberts u, int root, Relation depRel, Alphabet<String> depGraphEdges) {
    if (depRel == null)
      return null;
    if (root < 0)
      throw new IllegalArgumentException("root=" + root + " must be >= 0");
    StringLabeledDirectedGraph g = new StringLabeledDirectedGraph(depGraphEdges);
    State st = u.getState();
    for (LL<HypEdge> cur = st.match2(depRel); cur != null; cur = cur.next) {
      HypEdge e = cur.item;
      assert e.getNumTails() == 3;
      int h = Integer.parseInt((String) e.getTail(0).getValue());
      int m = Integer.parseInt((String) e.getTail(1).getValue());
      assert m >= 0;
      if (h < 0)
        h = root;
      String l = (String) e.getTail(2).getValue();
      g.add(h, m, l);
    }
    return g;
  }

  // NEW
  public static Sentence readSentenceFromState(Uberts u) {
    return readSentenceFromState(u, null);
  }
  public static Sentence readSentenceFromState(Uberts u, String id) {
    if (id == null)
      id = getSentenceId(u);

    // See FNParseToRelations for these definitions
    NodeType tokenIndex = u.lookupNodeType("tokenIndex", false);
    Relation wordRel = u.getEdgeType("word2");
    Relation posRel = u.getEdgeType("pos2");
    Relation lemmaRel = u.getEdgeType("lemma2");

    State st = u.getState();
    List<HypNode> tokens = new ArrayList<>();
    List<String> wordL = new ArrayList<>();
    List<String> posL = new ArrayList<>();
    List<String> lemmaL = new ArrayList<>();
    for (int i = 0; true; i++) {
      HypNode tok = u.lookupNode(tokenIndex, String.valueOf(i));
      if (tok == null)
        break;
      tokens.add(tok);
      LL<HypEdge> maybeWord = st.match(0, wordRel, tok);
      if (maybeWord == null)
        break;
      assert maybeWord.next == null : "more than one word at " + tok + ": " + maybeWord;
      HypEdge wordE = maybeWord.item;
      HypNode word = wordE.getTail(1);
      HypNode pos = st.match1(0, posRel, tok).getTail(1);

      LL<HypEdge> lemma = st.match(0, lemmaRel, tok);

      wordL.add((String) word.getValue());
      posL.add((String) pos.getValue());
      if (lemma != null) {
        assert lemma.next == null : "two lemmas?";
        lemmaL.add((String) lemma.item.getTail(1).getValue());
      } else {
        lemmaL.add("na");
      }
    }
    int n = wordL.size();
    String[] wordA = wordL.toArray(new String[n]);
    String[] posA = posL.toArray(new String[n]);
    String[] lemmaA = lemmaL.toArray(new String[n]);

    String dataset = "na";
    Sentence sentCache = new Sentence(dataset, id, wordA, posA, lemmaA);

    // Shapes and WN are computed on the fly
    // deps (basic, col, colcc) and constituents need to be added
    sentCache.setBasicDeps(getDepTree("dsyn3-basic", u, tokens));
    sentCache.setParseyDeps(getDepTree("dsyn3-parsey", u, tokens));
    boolean allowNull = true;
    Alphabet<String> depGraphEdges = u.dbgSentenceCacheDepsAlph;
    sentCache.setCollapsedDeps2(getDepGraph(u, n, u.getEdgeType("dsyn3-col", allowNull), depGraphEdges));
    sentCache.setCollapsedCCDeps2(getDepGraph(u, n, u.getEdgeType("dsyn3-colcc", allowNull), depGraphEdges));
    sentCache.setStanfordParse(buildCP(u, id));
    sentCache.computeShapes();
    sentCache.getWnWord(0);

    return sentCache;
  }

  public List<Pair<TemplateAlphabet, String>> features(HypEdge yhat, Uberts x) {
    Span t = null, s = null;
    String f = null, k = null;
    ctx.clear();
    if (DEBUG_CONTEXT_MESSAGE) {
      ctx.debugMessage = "setup for " + yhat.toString();
      ctx.debugEdge = yhat;
    }
    assert x.dbgSentenceCache != null;
    ctx.setSentence(x.dbgSentenceCache);
    if (customEdgeCtxSetup == null) {
      switch (yhat.getRelation().getName()) {
      case "srl1":
        s = extractSpan(yhat, 0, 1);
        break;
      case "srl2":
        Object arg0 = yhat.getTail(0).getValue();
        Object arg1 = yhat.getTail(1).getValue();
        if (arg0 instanceof EqualityArray && arg1 instanceof EqualityArray) {
          // Old form:
          EqualityArray s1 = (EqualityArray) arg0;
          EqualityArray e1 = (EqualityArray) arg1;
          t = extractSpan(e1, 0, 1);
          s = extractSpan(s1, 0, 1);
        } else if (arg0 instanceof String && arg1 instanceof String) {
          // new form, use Span
          t = Span.inverseShortString((String) arg0);
          s = Span.inverseShortString((String) arg1);
        } else {
          throw new RuntimeException("don't know how to handle: " + yhat);
        }
        break;
      case "srl3":
        Srl3EdgeWrapper s3 = new Srl3EdgeWrapper(yhat);
        t = s3.t;
        s = s3.s; // may be null if srl3(t,f,k)
//        ctx.setFrame(FrameIndex.getFrameWithSchemaPrefix(s3.f));
//        ctx.setRoleS(s3.k);
        f = s3.f;
        k = s3.k;
        break;
      case "srl4":
        t = UbertsPipeline.getTargetFromSrl4(yhat);
        s = UbertsPipeline.getArgFromSrl4(yhat);
        f = (String) yhat.getTail(2).getValue();
        k = (String) yhat.getTail(5).getValue();
        break;
      case "event1":
        t = Span.inverseShortString((String) yhat.getTail(0).getValue());
        break;
      case "predicate2": // (t,f)
        t = Span.inverseShortString((String) yhat.getTail(0).getValue());
        f = (String) yhat.getTail(1).getValue();
        break;
      case "argument4": // (t,f,s,k)
        assert yhat.getNumTails() == 4;
        t = Span.inverseShortString((String) yhat.getTail(0).getValue());
        f = (String) yhat.getTail(1).getValue();
        s = Span.inverseShortString((String) yhat.getTail(2).getValue());
        k = (String) yhat.getTail(3).getValue();
        break;
      default:
        skipped.increment(yhat.getRelation().getName());
//        if (skipped.getTotalCount() % 1000 == 0)
//          Log.info("skipped: " + skipped.toString());
        Log.warn("skipped: " + skipped.toString());
        throw new RuntimeException("don't know how to handle: " + yhat);
//        break;
      }
      if (s != null) {
        ctx.setArg(s);
        ctx.setSpan1(s);
        if (s != Span.nullSpan) {
          int sh = hf.headSafe(s, ctx.getSentence());
          if (sh >= 0) {
            ctx.setArgHead(sh);
            ctx.setHead1(ctx.getArgHead());
          }
        }
      }
      if (t != null) {
        ctx.setTarget(t);
        ctx.setSpan2(t);
        if (t != Span.nullSpan) {
          int th = hf.headSafe(t, ctx.getSentence());
          if (th >= 0) {
            ctx.setTargetHead(th);
            ctx.setHead2(ctx.getTargetHead());
          }
        }
      }
    } else {
      customEdgeCtxSetup.accept(new Pair<>(yhat, x), ctx);
    }
    if (!onlyUseTS) {
      if (f != null) {
        ctx.setFrameStr(f);
      }
      if (k != null)
        ctx.setRoleS(k);
    }

    // Actually compute the features
    if (DEBUG > 1) {
      Log.info("computing features for " + yhat);
      TemplateContext.showContext(ctx);
    }
    List<Pair<TemplateAlphabet, String>> feats = new ArrayList<>();
    for (TemplateAlphabet ftemp : features) {
      Iterable<String> fts = ftemp.template.extract(ctx);
      if (fts != null) {
        for (String ft : fts) {
          Pair<TemplateAlphabet, String> p = new Pair<>(ftemp, ft);
          if (DEBUG > 1)
            System.out.println("\t" + ftemp.name + "\t" + ft);
          feats.add(p);
        }
      }
    }
    if (DEBUG > 1)
      System.out.println(feats.size() + " features for " + yhat);

    return feats;
  }

  public static Span extractSpan(HypEdge e, int startTailIdx, int endTailIdx) {
    int start = Integer.parseInt((String) e.getTail(startTailIdx).getValue());
    int end = Integer.parseInt((String) e.getTail(endTailIdx).getValue());
    Span s = Span.getSpan(start, end);
    return s;
  }

  public static Span extractSpan(EqualityArray ea, int startTailIdx, int endTailIdx) {
    int start = Integer.parseInt((String) ea.get(startTailIdx));
    int end = Integer.parseInt((String) ea.get(endTailIdx));
    Span s = Span.getSpan(start, end);
    return s;
  }
}
