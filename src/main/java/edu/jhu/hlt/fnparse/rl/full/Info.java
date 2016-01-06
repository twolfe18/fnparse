package edu.jhu.hlt.fnparse.rl.full;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.LabelIndex;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.inference.stages.StageDatumExampleList;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning;
import edu.jhu.hlt.fnparse.pruning.FNParseSpanPruning;
import edu.jhu.hlt.fnparse.rl.full.GeneralizedCoef.Loss.Mode;
import edu.jhu.hlt.fnparse.rl.full.State.FI;
import edu.jhu.hlt.fnparse.rl.full.weights.ProductIndexAdjoints;
import edu.jhu.hlt.fnparse.rl.full2.AbstractTransitionScheme;
import edu.jhu.hlt.fnparse.rl.full2.HasCounts;
import edu.jhu.hlt.fnparse.rl.full2.SortedEggCache;
import edu.jhu.hlt.fnparse.rl.full2.TFKS;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer.OracleMode;
import edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer.RTConfig;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.FrameRolePacking;
import edu.jhu.hlt.fnparse.util.HasRandom;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.HashableIntArray;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints.Caching2;

/** Everything that is annoying to copy in State */
public class Info implements Serializable, HasCounts, HasRandom {
  private static final long serialVersionUID = -4529781834599237479L;

  public static int COUNTER_CONSTRUCT = 0;
  public static void zeroCounters() {
    COUNTER_CONSTRUCT = 0;
  }
  public static void logCounters() {
    Log.info("nConstruct=" + COUNTER_CONSTRUCT);
  }

  // Put SortedEggCache here or in a sub-class of Node2?
  // newNode with type==K could create a new SortedEggCache...
  // I call newNode many times per (t,f) value... still need to cache them somewhere outside Node2
  private Map<IntPair, SortedEggCache> tf2SortedEggs = new HashMap<>();
  public void putSortedEggs(int t, int f, SortedEggCache eggs) {
    SortedEggCache old = tf2SortedEggs.put(new IntPair(t, f), eggs);
    assert old == null : "should only be one egg cache per (t,f)";
  }
  /** May return null if this value hasn't been computed yet */
  public SortedEggCache getSortedEggs(int t, int f) {
    return tf2SortedEggs.get(new IntPair(t, f));
  }




  Sentence sentence;
  LabelIndex label;     // may be null

  // State space pruning
  FNParseSpanPruning prunedSpans;     // or Map<Span, List<Span>> prunedSpans
  Map<Span, List<Frame>> prunedFIs;

  // Parameters of transition system
  Config config;
  private RTConfig likeConf;  // legacy support :(

//  /* Parameters of search.
//   * objective(s,a) = b0 * modelScore(s,a) + b1 * deltaLoss(s,a) + b2 * rand()
//   *   oracle: {b0: 0.1, b1: -10, b2: 0}
//   *   mv:     {b0: 1.0, b1: 1.0, b2: 0}
//   *   dec:    {b0: 1.0, b1: 0.0, b2: 0}
//   */
//  private GeneralizedCoef coefLoss;
//  private GeneralizedCoef coefModel;
//  private GeneralizedCoef coefRand;
//  // TODO These should be replaced with SearchCoefficients search, constraints;
//
//  private int beamSize = 1;          // How many states to keep at every step
//  private int numConstraints = 1;    // How many states to keep for forming margin constraints (a la k-best MIRA)

  public HowToSearchImpl htsBeam;
  public HowToSearchImpl htsConstraints;


  /* Static feature cache *****************************************************/

  // NOTE: We are now hard-coded to the method of caching Adjoints.Caching
  // instances and constructing a new Adjoints.Scale(-1,wHatchAdjoints) for
  // every call of scoreSquash.
  // ASSUMPTION NEEDED: weights don't change during the life of an Info (e.g.
  // getUpdate, perceptronUpdate, predictNew).

//  public Map<TFKS, ProductIndexAdjoints> staticHatchFeatCache = new HashMap<>();
//  public Map<TFKS, ProductIndexAdjoints> staticSquashFeatCache = new HashMap<>();
  public Map<TFKS, Caching2<ProductIndexAdjoints>> staticHatchFeatCache = new HashMap<>();
  // Just construct a scaled version on the fly...
//  public Map<TFKS, Scale2<Caching2<ProductIndexAdjoints>>> staticSquashFeatCache = new HashMap<>();

  /** Probably don't need to do this because Info's should only last for one search, get GC'd quickly */
  public void clearStaticFeatureCache() {
    staticHatchFeatCache.clear();
//    staticSquashFeatCache.clear();
  }

  // copy between oracle and MV Info instances?
  // easy 2x...
  /** Sets this instances cache to the same cache as other */
  public void shareStaticFeatureCacheWith(Info other) {
    staticHatchFeatCache = other.staticHatchFeatCache;
//    staticSquashFeatCache = other.staticSquashFeatCache;
  }

  /* ************************************************************************ */




  public static class HowToSearchImpl implements HowToSearch {
    GeneralizedCoef model, loss, rand;
    int beam;
    public HowToSearchImpl(GeneralizedCoef model, GeneralizedCoef loss, GeneralizedCoef rand) {
      this(model, loss, rand, ExperimentProperties.getInstance().getInt("beamSize", 1));
    }
    public HowToSearchImpl(GeneralizedCoef model, GeneralizedCoef loss, GeneralizedCoef rand, int beam) {
      this.model = model;
      this.loss = loss;
      this.rand = rand;
      this.beam = beam;
    }
    @Override public GeneralizedCoef coefLoss() { return loss; }
    @Override public GeneralizedCoef coefModel() { return model; }
    @Override public GeneralizedCoef coefRand() { return rand; }
    @Override public int beamSize() { return beam; }
    @Override
    public String toString() {
      return "(HTS"
          + " model=" + model
          + " loss=" + loss
          + " rand=" + rand
          + " beam=" + beam
          + ")";
    }
  }

  public Info(Config config) {
    COUNTER_CONSTRUCT++;
    this.config = config;
    this.setDecodeCoefs();

//    RuntimeException e = new RuntimeException();
//    for (StackTraceElement ste : e.getStackTrace()) {
//      System.out.println(ste);
//    }
//    System.out.println();

//    Log.info("beam.beam=" + this.htsBeam.beam
//        + " cons.beam=" + this.htsConstraints.beam);
  }

  public Config getConfig() {
    return config;
  }

  @Override
  public Random getRandom() {
    assert false : "don't use this!";
    return config.rand;
  }

  public FrameIndex getFrameIndex() {
    return config.frPacking.getFrameIndex();
  }

  public FrameRolePacking getFRPacking() {
    return config.frPacking;
  }

  public boolean roleDependsOnFrame() {
    ExperimentProperties config = ExperimentProperties.getInstance();
    if (config.getBoolean("overrideRoleDependsOnFrame", true))
      return true;
    boolean b = getConfig().roleDependsOnFrame;
    assert b == !config.getBoolean("propbank");
    return b;
  }

  public LabelIndex getLabel() {
    return label;
  }

  public FNParse getLabelParse() {
    return label.getParse();
  }

  public Sentence getSentence() {
    return sentence;
  }

  /** Mutates! returns this */
  public Info copyLabel(Info from) {
    sentence = from.sentence;
    label = from.label;
    prunedFIs = null;
    prunedSpans = null;
    return this;
  }

  /** Mutates! returns this */
  public Info setLabel(FNParse y) {
    return setLabel(y, null);
  }
  public Info setLabel(FNParse y, AbstractTransitionScheme<FNParse, ?> ts) {
    sentence = y.getSentence();
    if (ts == null)
      label = new LabelIndex(y);
    else
      label = new LabelIndex(y, ts);
    prunedFIs = null;
    prunedSpans = null;
    return this;
  }

  /** Mutates! returns this */
  public Info setSentence(Sentence s) {
    sentence = s;
    label = null;
    prunedFIs = null;
    prunedSpans = null;
    return this;
  }

  public boolean sentenceAndLabelMatch() {
    if (sentence == null)
      throw new RuntimeException("this should never happen!");
    if (label == null)
      return true;
    boolean m = label.getParse().getSentence() == sentence;
    if (!m) {
      System.err.println("label: " + label.getParse().getSentence().getId());
      System.err.println("sentence: " + sentence.getId());
    }
    return m;
  }

  public Info setLike(RTConfig config) {
    this.likeConf = config;
    if (config == null) {
      Log.warn("null config! no-op!");
    } else {
      assert config.trainBeamSize == config.testBeamSize;
      htsBeam.beam = config.trainBeamSize;
      this.config.rand = config.rand;
    }
    return this;
  }

  @Override
  public String toString() {
    return "(Info for " + sentence.getId()
      + " htsBeam=" + htsBeam
      + " htsConstraint=" + htsConstraints
      + ")";
  }

  public Info setOracleCoefs() {
    // Beam vs Constraint objectives do not matter for oracle because we take
    // the final state (enforcing Proj(z) = {y}) rather than the best thing on
    // all beam.
    // TODO Any benefit to using MAX_LOSS instead of taking from the last beam step?

    OracleMode m;
    if (likeConf == null) {
      Log.warn("likeConf is null, defaulting to MIN");
      m = OracleMode.MIN;
    } else {
      m = likeConf.oracleMode;
    }

    GeneralizedCoef cL = new GeneralizedCoef.Loss.Oracle();
    double mScale = 1;
    double rScale = 1;
    switch (m) {
    case RAND_MIN:
      return setSameHTS(new HowToSearchImpl(
          new GeneralizedCoef.Model(+mScale, true),
          cL,
          new GeneralizedCoef.Rand(rScale)));
    case RAND_MAX:
      return setSameHTS(new HowToSearchImpl(
          new GeneralizedCoef.Model(-mScale, true),
          cL,
          new GeneralizedCoef.Rand(rScale)));
    case MIN:
      return setSameHTS(new HowToSearchImpl(
          new GeneralizedCoef.Model(+mScale, true),
          cL,
          GeneralizedCoef.ZERO));
    case MAX:
      return setSameHTS(new HowToSearchImpl(
          new GeneralizedCoef.Model(-mScale, true),
          cL,
          GeneralizedCoef.ZERO));
    default:
      throw new RuntimeException();
    }
  }

  public Info setMostViolatedCoefs() {
    return setSameHTS(new HowToSearchImpl(
        new GeneralizedCoef.Model(1, false),
        new GeneralizedCoef.Loss(1, Mode.H_LOSS, 0.5),
        GeneralizedCoef.ZERO));
  }

  public Info setDecodeCoefs() {
    // updateAway for decoder is used in perceptron updates
    boolean updateTowards = false;
    return setSameHTS(new HowToSearchImpl(
        new GeneralizedCoef.Model(1, updateTowards),
        GeneralizedCoef.ZERO,
        GeneralizedCoef.ZERO));
  }

  public Info setSameHTS(HowToSearchImpl beamAndConstraints) {
    htsBeam = beamAndConstraints;
    htsConstraints = beamAndConstraints;
    return this;
  }

  public int numFrames() {
    return config.frPacking.getNumFrames();
  }

  public Collection<Span> getPossibleTargets() {
    return prunedFIs.keySet();
  }

  public Collection<Frame> getPossibleFrames(Span t) {
    return prunedFIs.get(t);
  }

  /** Does not include nullSpan */
  public List<Span> getPossibleArgs(FI fi) {
    assert fi.t != null;
    assert fi.f != null;
    return getPossibleArgs(fi.f, fi.t);
  }

  public List<Span> getPossibleArgs(Frame f, Span t) {
    FrameInstance key = FrameInstance.frameMention(f, t, sentence);
    List<Span> all = prunedSpans.getPossibleArgs(key);
    if (all == null)
      return Arrays.asList(Span.nullSpan);
    List<Span> nn = new ArrayList<>(all.size() - 1);
    for (Span s : all)
      if (s != Span.nullSpan)
        nn.add(s);
    return nn;
  }

  public Info setTargetPruningToGoldLabels() {
    return setTargetPruningToGoldLabels(null);
  }
  public Info setTargetPruningToGoldLabels(Info alsoSetThisInstance) {
    if (label == null)
      throw new IllegalStateException("need a label for this operation");
    assert sentenceAndLabelMatch();
    prunedSpans = null;
    prunedFIs = new HashMap<>();
    for (FrameInstance fi : label.getParse().getFrameInstances()) {
      Span t = fi.getTarget();
      Frame f = fi.getFrame();
      List<Frame> other = prunedFIs.put(t, Arrays.asList(f));
      assert other == null;
    }
    if (alsoSetThisInstance != null) {
      assert alsoSetThisInstance.sentenceAndLabelMatch();
      assert sentence == alsoSetThisInstance.sentence;
      alsoSetThisInstance.prunedSpans = null;
      alsoSetThisInstance.prunedFIs = prunedFIs;
    }
    return this;
  }

  public Info setArgPruningUsingGoldLabelWithNoise() {
    return setArgPruningUsingGoldLabelWithNoise(3, 3);
  }
  public Info setArgPruningUsingGoldLabelWithNoise(int kPerTF, int sPerTFK) {
    Log.info("kPerTF=" + kPerTF + " sPerTFK" + sPerTFK);
    prunedSpans = new FNParseSpanPruning(getSentence(), Collections.emptyList(), new HashMap<>(), false);
    for (FrameInstance fi : label.getParse().getFrameInstances()) {
      Frame f = fi.getFrame();
      FrameInstance key = FrameInstance.frameMention(f, fi.getTarget(), getSentence());
      int K = f.numRoles();
      int miscK = 0;
      for (int k = 0; k < K; k++) {
        Span a = fi.getArgument(k);
        if (a != Span.nullSpan || miscK < kPerTF) {
          prunedSpans.addSpan(key, a);
          if (a == Span.nullSpan)
            miscK++;
        }
        if (config.useContRoles || config.useRefRoles)
          throw new RuntimeException("implement me");
        assert fi.getContinuationRoleSpans(k).isEmpty();
        assert fi.getReferenceRoleSpans(k).isEmpty();
      }
    }
    return this;
  }

  public Info setArgPruning(DeterministicRolePruning drp, boolean includeGoldSpansIfMissing) {
    return setArgPruning(drp, includeGoldSpansIfMissing, null);
  }

  /**
   * @param drp if null, will look for {@link FNParse#featuresAndSpans} in the label for how to do the arg pruning
   * @param includeGoldSpansIfMissing
   * @param alsoSet
   * @return
   */
  public Info setArgPruning(DeterministicRolePruning drp, boolean includeGoldSpansIfMissing, Info alsoSet) {
    assert sentenceAndLabelMatch();
    if (drp == null) {
//      Log.info("using CachedFeatures.Item to get possible arg spans");
      FNParse y = label.getParse();
      CachedFeatures.Item i = y.featuresAndSpans;
      assert i != null;
      // span -> [frame]
      // fi -> [span]
      Map<FrameInstance, List<Span>> spans = new HashMap<>();
      for (FrameInstance fi : y.getFrameInstances()) {
        FrameInstance key = FrameInstance.frameMention(fi.getFrame(), fi.getTarget(), sentence);
        List<Span> possArgs = i.getArgSpansForTarget(fi.getTarget());
        spans.put(key, possArgs);
      }
      boolean nullSpanInPossArgs = false;
      prunedSpans = new FNParseSpanPruning(sentence, y.getFrameInstances(), spans, nullSpanInPossArgs);
    } else {
      Log.warn("decoding via DeterministicRolePruning");
      StageDatumExampleList<FNTagging, FNParseSpanPruning> inf = drp.setupInference(Arrays.asList(label.getParse()), null);
      prunedSpans = inf.decodeAll().get(0);
    }

    // Add any spans that appear in the gold label to the pruning mask if they do not appear already
    if (includeGoldSpansIfMissing) {
      if (label == null)
        throw new RuntimeException("you need a label to perform this operation");
      int adds = 0;
      int realized = 0;
      int present = 0;

      // Is it possible to not emit any possible Span args for a given frame/target?

      List<FrameInstance> fis = label.getParse().getFrameInstances();
      for (FrameInstance fi : fis) {
        FrameInstance key = FrameInstance.frameMention(fi.getFrame(), fi.getTarget(), fi.getSentence());
        List<Span> possible = prunedSpans.getPossibleArgs(key);
        if (possible == null) {
          System.out.println(label.getParse().getId());
          System.out.println(label.getParse().getSentence().getId());
          for (FrameInstance fi2 : fis)
            System.out.println("label: " + Describe.frameInstance(fi2));
          for (FrameInstance fi2 : prunedSpans.getFrameInstances())
            System.out.println("prune: " + Describe.frameInstance(fi2));
          throw new RuntimeException();
        }
        present += possible.size();
        int K = fi.getFrame().numRoles();
        for (int k = 0; k < K; k++) {

          Span s = fi.getArgument(k);
          if (s != Span.nullSpan) {
            realized++;
            if (!prunedSpans.getPossibleArgs(key).contains(s)) {
              adds++;
              prunedSpans.addSpan(key, s);
            }
          }

          if (config.useContRoles) {
            for (Span ss : fi.getContinuationRoleSpans(k)) {
              if (ss != Span.nullSpan) {
                realized++;
                if (!prunedSpans.getPossibleArgs(key).contains(ss)) {
                  adds++;
                  prunedSpans.addSpan(key, ss);
                }
              }
            }
          }

          if (config.useRefRoles) {
            for (Span ss : fi.getReferenceRoleSpans(k)) {
              if (ss != Span.nullSpan) {
                realized++;
                if (!prunedSpans.getPossibleArgs(key).contains(ss)) {
                  adds++;
                  prunedSpans.addSpan(key, ss);
                }
              }
            }
          }

        }
      }
      if (State.DEBUG) {
        Log.debug("includeGoldSpansIfMissing: adds=" + adds
            + " realized=" + realized + " presentBefore=" + present
            + " nFI=" + label.getParse().numFrameInstances()
            + " nTokens=" + sentence.size());
      }
    }
    if (alsoSet != null) {
      assert alsoSet.sentenceAndLabelMatch();
      assert alsoSet.sentence == sentence;
      alsoSet.prunedSpans = prunedSpans;
    }
    return this;
  }

//  @Override
//  public GeneralizedCoef coefLoss() {
//    return coefLoss;
//  }
//
//  @Override
//  public GeneralizedCoef coefModel() {
//    return coefModel;
//  }
//
//  @Override
//  public GeneralizedCoef coefRand() {
//    return coefRand;
//  }

//  @Override
//  public int beamSize() {
//    return beamSize;
//  }
//
//  @Override
//  public int numConstraints() {
//    return numConstraints;
//  }

  @Override
  public Counts<HashableIntArray> getCounts() {
    if (label == null)
      throw new IllegalStateException("can't call this on unlabelled Infos");
    return label.getCounts2();
  }

}
