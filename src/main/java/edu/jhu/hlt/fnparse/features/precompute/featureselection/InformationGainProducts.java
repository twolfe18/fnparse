package edu.jhu.hlt.fnparse.features.precompute.featureselection;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.Average;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.HashableIntArray;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.ShardUtils;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.tuple.Pair;

/**
 * Computes information gain for products of templates.
 *
 * Takes a template IG estimate produced by {@link InformationGain}, sorts the
 * products of templates by the product of IG of the templates, then computes
 * the IG for the product of templates.
 *
 * See the main method for the options to run this thing.
 *
 * @author travis
 */
public class InformationGainProducts {
  public static final boolean DEBUG = false;
  public static final boolean FLATTEN_DEBUG = false;

  // Leave off: see note in flattenMaybeMemoize
  public static final boolean FLATTEN_MEMOIZE = false;

  public static String DEBUG_TEMPLATE = null;//"head1ParentBc1000/99";

  // Features being scored and their stats, some are indexed.
  // These are disjoint collections of TemplateIGs.
  private IntObjectHashMap<List<TemplateIG>> featuresFrameRestricted;
  private Map<IntPair, List<TemplateIG>> featuresFrameRoleRestricted;
  private List<TemplateIG> featuresUnrestricted;

  // Input, list of relevant features to evaluate, before instantiating TemplateIGs
  private List<FeatureName> baseFeatures;

  private BitSet relevantTemplates;   // Union of values in keys of products
  private int[] template2cardinality; // Indexed by base template.index, will contain gaps in places of un-necessary templates

  /** How to accumulate many template IGs into a single score for a feature */
  public static FeatureScoring FEATURE_SCORE_HEURISTIC = FeatureScoring.ARITHMETIC_MEAN;
  public static enum FeatureScoring {
    // Arithmetic is less greedy than harmonic, so if you're exploring a lot of
    // features, arithmetic may lead to finding better features by searching
    // over a more diverse/larger set.
    ARITHMETIC_MEAN,    // score(feature) = 1/N sum_{template \in feature} ig(template)
    HARMONIC_MEAN,
  }

  private Set<String> ignoreSentenceIds;

  // If this is >0, then the hashing tick is used and every template is allocated
  // a vector of this size to represent cx and cyx.
  private int hashingTrickDim;

  private BiAlph lastBialph;

  // Actually computes the entropy/MI
  private BubEntropyEstimatorAdapter bubEst;

  // How to commpute entropy/MI
  private EntropyMethod entropyMethod;

  /**
   * @param hashingTrickDim 0 means no hashing trick and >0 is how many dimensions
   * to use for representing features.
   */
  public InformationGainProducts(
      List<FeatureName> features,
      int hashingTrickDim,
      BubEntropyEstimatorAdapter bubEstimator,
      EntropyMethod em) {
    this.baseFeatures = features;
    this.hashingTrickDim = hashingTrickDim;
    if (this.hashingTrickDim > 0)
      throw new RuntimeException("don't this/not implemented: hashingTrickDim=" + hashingTrickDim);
    this.bubEst = bubEstimator;
    this.entropyMethod = em;
    ExperimentProperties config = ExperimentProperties.getInstance();
    File ignoreSentenceIdsFile = config.getExistingFile("ignoreSentenceIds");
    Log.info("ignoring the sentence ids in " + ignoreSentenceIdsFile.getPath());
    ignoreSentenceIds = new HashSet<>();
    try (BufferedReader r = FileUtil.getReader(ignoreSentenceIdsFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        ignoreSentenceIds.add(line);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private boolean initialized = false;
  public boolean isInitialized() {
    return initialized;
  }

  /**
   * Converts strings given at construction into ints for efficient processing
   * (only needs to be called once).
   */
  public void init(BiAlph bialph, int numRoles) {
    Log.info("initializing... numRoles=" + numRoles);
    assert !initialized;
    assert relevantTemplates == null;
    assert template2cardinality == null;
    initialized = true;
    lastBialph = bialph;

    // Map everything over
    featuresFrameRestricted = new IntObjectHashMap<>();
    featuresFrameRoleRestricted = new HashMap<>();
    featuresUnrestricted = new ArrayList<>();
    int featIdx = 0;
    for (FeatureName fn : baseFeatures) {
      fn.computeTemplateInts(bialph);
      TemplateIG tig = new TemplateIG(
          featIdx,
          StringUtils.join("*", fn.templateStr),
          numRoles,
          entropyMethod,
          fn.getY);
      tig.featureName = fn;
      if (bubEst != null)
        tig.useBubEntropyEstimation(bubEst);
      addFeature(tig);
      featIdx++;
    }

    // Construct the union of base templates
    relevantTemplates = new BitSet();
    for (FeatureName fn : baseFeatures)
      for (int i : fn.templateInt)
        relevantTemplates.set(i);

    // Setup template cardinalities
    template2cardinality = new int[bialph.getUpperBoundOnNumTemplates()];
    Arrays.fill(template2cardinality, -1);
    for (int t = relevantTemplates.nextSetBit(0); t >= 0; t = relevantTemplates.nextSetBit(t + 1)) {
      // Lookup cardinality for template[relevantTemplates[i]] (comes from a file)
      template2cardinality[t] = bialph.cardinalityOfNewTemplate(t) + 1;
    }
    Log.info("done");
  }

  private void addFeature(TemplateIG tig) {
//    Log.info(tig.featureName.toString());
    FeatureName fn = tig.featureName;
    List<TemplateIG> addTo;
    if (fn.getY instanceof FrameRoleFilter) {
      FrameRoleFilter frf = (FrameRoleFilter) fn.getY;
      if (frf.hasRoleRestriction()) {
        IntPair key = new IntPair(frf.getFrame(), frf.getRole());
        addTo = featuresFrameRoleRestricted.get(key);
        if (addTo == null) {
          addTo = new ArrayList<>();
          featuresFrameRoleRestricted.put(key, addTo);
        }
      } else {
        int key = frf.getFrame();
        addTo = featuresFrameRestricted.get(key);
        if (addTo == null) {
          addTo = new ArrayList<>();
          featuresFrameRestricted.put(key, addTo);
        }
      }
    } else {
      addTo = featuresUnrestricted;
    }
    addTo.add(tig);
  }

  private int numUpdates = 0;
  public void update(File features) throws IOException {
    TimeMarker tm = new TimeMarker();
    Log.info("reading features=" + features.getPath()
        + " with numFeatures=" + getAllFeatures().size()
        + " numUpdates=" + numUpdates
        + " featuresUnrestricted.size=" + featuresUnrestricted.size()
        + " featuresFrameRestricted.size=" + featuresFrameRestricted.size()
        + " featuresFrameRoleRestricted.size=" + featuresFrameRoleRestricted.size()
        + " numUpdates=" + numUpdates);
    int lines = 0;
    try (BufferedReader r = FileUtil.getReader(features)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        observeLine(line);
        if (tm.enoughTimePassed(15)) {
          Log.info("read " + lines + " lines in "
              + tm.secondsSinceFirstMark() + " seconds, "
              + Describe.memoryUsage());
        }
        lines++;
      }
      numUpdates++;
    }
  }
  public int getNumUpdates() {
    return numUpdates;
  }

  /*
   * TODO(optimization): if we have foo@frame1, foo@frame2, foo@frame3, etc,
   * we are calling flatten with foo.int_templates many times!
   */
  private void updateMany(List<TemplateIG> updates, FeatureFile.Line ffl, List<ProductIndex> prodBuf) {
    if (updates == null)
      return;
    for (TemplateIG u : updates) {
      int[] templates = u.featureName.templateInt;
      assert templates != null;

      prodBuf.clear();
      flatten(ffl, 0, templates, 0, ProductIndex.NIL, template2cardinality, prodBuf);
//      prodBuf = flattenMaybeMemoize(ffl, templates, template2cardinality);

      for (ProductIndex pi : prodBuf)
        u.update(ffl, new ProductIndex[] {pi});
    }
  }

//  // For debugging
//  private static String[] inverseRole2Name;
//  private static void buildInverseRole2Name(Map<String, Integer> role2name) {
//    int m = 0;
//    for (int i : role2name.values())
//      if (i > m)
//        m = i;
//    inverseRole2Name = new String[m + 1];
//    for (Entry<String, Integer> x : role2name.entrySet()) {
//      int i = x.getValue();
//      if (inverseRole2Name[i] != null)
//        inverseRole2Name[i] += ", " + x.getKey();
//      else
//        inverseRole2Name[i] = x.getKey();
//    }
//  }

  public void observeLine(String line) {
    boolean sorted = true;
    FeatureFile.Line ffl = new FeatureFile.Line(line, sorted);
    String sentenceId = ffl.getSentenceId();

    // y = is determined by getY:Function<FeatureFile.Line, int[]> in TemplateIG
    // x = feature vector produced by flatten()
    if (ignoreSentenceIds.contains(sentenceId)) {
      return;
    }

    List<ProductIndex> prods = new ArrayList<>();

    int[] frames = ffl.getFrames(InformationGain.ADD_ONE);
    int[] roles = ffl.getRoles(InformationGain.ADD_ONE);

    assert frames.length > 0;
    assert roles.length > 0;

    for (int frame : frames) {
      updateMany(featuresFrameRestricted.get(frame), ffl, prods);
      if (!featuresFrameRoleRestricted.isEmpty()) {
        for (int role : roles) {
          IntPair key = new IntPair(frame, role);
          updateMany(featuresFrameRoleRestricted.get(key), ffl, prods);
        }
      }
    }
    updateMany(featuresUnrestricted, ffl, prods);
  }

  /**
   * @deprecated
   * Use this as a non-helper version of flatten, but the memoization should be
   * turned off. In IGP, though I do create many (t,f1), (t,f2), (t,f3)...
   * where t is a templates:int[] and fX is a FrameRoleFilter, we don't have to
   * worry about calling flatten with t many times due to the fact that for any
   * given line at most one frame appears (and thus at most one fX fires), so
   * we will not end up re-extracting.
   *
   * Do not modify the returned value, as it may be in a cache!
   */
  public static List<ProductIndex> flattenMaybeMemoize(
      FeatureFile.Line data,
      int[] templates,
      int[] template2cardinality) {
    if (!FLATTEN_MEMOIZE) {
      List<ProductIndex> buffer = new ArrayList<>();
      flatten(data, 0, templates, 0, ProductIndex.NIL, template2cardinality, buffer);
      return buffer;
    }
    if (data.flattenCache == null)
      data.flattenCache = new HashMap<>();
    HashableIntArray key = new HashableIntArray(templates);
    List<ProductIndex> m = data.flattenCache.get(key);
//    Log.info("extracting " + Arrays.toString(templates));
    if (m == null) {
//      Log.info("MISS!");
      m = new ArrayList<>();
      flatten(data, 0, templates, 0, ProductIndex.NIL, template2cardinality, m);
      data.flattenCache.put(key, m);
    } else {
//      Log.info("HIT!");
    }
    return m;
  }

  /**
   * data = (template, feature)*  # sorted by template
   * templates = template*        # sorted by template, unique items
   *
   * This method takes the intersection, producing a:
   *  (template, feature)*  # sorted by template
   * Then takes all paths through the trellis of:
   *  ((template, feature)*)* = groupByTemplate((template, feature)*)
   * While going through this trellis, it constructs a valid index/domain for
   * every path through the trellis.
   *
   * One invariant of this method: it always processes a single template at each
   * call (or fails or completes).
   */
  public static void flatten(
      FeatureFile.Line data,
      int dIndex,   // data has templates needed for *all* products/features
      int[] templates, int tIndex,      // these are the templates for *this* product/feature
      ProductIndex cardValue,
      int[] template2cardinality,
      List<ProductIndex> buffer) {
    if (FLATTEN_DEBUG) {
      System.out.println();
      System.out.println(data.toString());
      System.out.println("dIndex=" + dIndex);
      System.out.println("templates=" + Arrays.toString(templates));
      System.out.println("tIndex=" + tIndex);

      // Verify templates are sorted
      for (int i = 0; i < templates.length - 1; i++)
        assert templates[i] < templates[i + 1];
      // Verify data is sorted
      assert data.checkFeaturesAreSortedByTemplate();
    }

    if (tIndex == templates.length) {
      if (FLATTEN_DEBUG)
        System.out.println("done taking product, buffer.size=" + buffer.size() + "");
      buffer.add(cardValue);
      return;
    }

    // Find the first data index matching the template we're looking for
    List<Feature> features = data.getFeatures();
    int curTemplate = templates[tIndex];
    int curTemplateCard = template2cardinality[curTemplate];
    int startDataIndex = dIndex;
    boolean found = false;
    int n = features.size();
    while (startDataIndex < n && !found) {
      int t = features.get(startDataIndex).template;
      if (t == curTemplate) {
        found = true;
      } else if (t < curTemplate) {
        startDataIndex++;
      } else {
        break;
      }
    }

    if (!found) {
      // One of the templates in our product didn't fire => feature doesn't fire
      if (FLATTEN_DEBUG)
        System.out.println("not found!");
      return;
    }

    // Find the last data index that matches the current template
    int endDataIndex = startDataIndex + 1;
    while (endDataIndex < n && features.get(endDataIndex).template == curTemplate)
      endDataIndex++;

    // Recurse
    if (FLATTEN_DEBUG)
      System.out.println("curTemplateCard=" + curTemplateCard);
    for (int i = startDataIndex; i < endDataIndex; i++) {
      Feature f = features.get(i);
      assert f.feature < curTemplateCard
        && f.template == curTemplate
        : "data.getValue(" + i + ")=" + f.feature
        + " data.getTemplate(" + i + ")=" + f.template
        + " curTemplate=" + curTemplate
        + " curTemplateCard=" + curTemplateCard
        + " dIndex=" + dIndex
        + " tIndex=" + tIndex
        + " templates=" + Arrays.toString(templates)
        + " baseTemplates=" + data;
      int card = template2cardinality[f.template];
      ProductIndex newValue2 = cardValue.prod(f.feature, card);
      if (FLATTEN_DEBUG) {
        System.out.println("data.getValue(" + i + ")=" + f.feature
          + " card=" + card + " newValue2=" + newValue2);
      }
      flatten(data, endDataIndex,
          templates, tIndex + 1,
          newValue2,
          template2cardinality,
          buffer);
    }
  }

  public List<TemplateIG> getAllFeatures() {
    List<TemplateIG> all = new ArrayList<>();
    IntObjectHashMap<List<TemplateIG>>.Iterator itr = featuresFrameRestricted.iterator();
    while (itr.hasNext()) {
      itr.advance();
      all.addAll(itr.value());
    }
    for (List<TemplateIG> l : featuresFrameRoleRestricted.values())
      all.addAll(l);
    all.addAll(featuresUnrestricted);
    Log.info("all.size=" + all.size()
      + " featuresUnrestricted.size=" + featuresUnrestricted.size()
      + " featuresFrameRestricted.size=" + featuresFrameRestricted.size()
      + " featuresFrameRoleRestricted.size=" + featuresFrameRoleRestricted.size()
      + " numUpdates=" + numUpdates);
    return all;
  }

  public List<TemplateIG> getTemplatesSorted(Comparator<TemplateIG> cmp) {
    TimeMarker tm = new TimeMarker();
    List<TemplateIG> all = getAllFeatures();
    List<TemplateIG> out = new ArrayList<>();
    for (TemplateIG t : all) {
      if (t.totalCount() > 0)
        out.add(t);
    }
    Log.info("all.size=" + all.size() + " out.size=" + out.size());
    int done = 0;
    for (TemplateIG t : out) {
      t.ig();
      done++;
      if (tm.enoughTimePassed(15)) {
        Log.info("took " + tm.secondsSinceFirstMark()
            + " seconds to compute MI for "
            + done + " of " + out.size() + " templates");
      }
    }
    Collections.sort(out, cmp);
    Log.info("done");
    return out;
  }

  public int[] getTemplatesForFeature(int i) {
    assert initialized;
    return baseFeatures.get(i).templateInt;
  }

  /**
   * Write out the results in format:
   *   line = FOM <tab> mi <tab> hx <tab> order <tab> featureInts <tab> featureStrings
   * where featureInts is delimited by "*"
   *
   * @param explode says if you should call {@link TemplateIG#explode()} on
   * on every output. This is useful if you want to output PMIs instead of MI
   * for the entire {@link TemplateIG}.
   * @see scripts/precompute-features/combine-mutual-information.py
   */
  public void writeOutProducts(File output, Refinement mode) {
    boolean explode = mode == Refinement.FRAME || mode == Refinement.FRAME_ROLE;
    Log.info("writing output to " + output.getPath()
        + " refinementMode=" + mode +  " explode=" + explode);
    List<TemplateIG> templates = getTemplatesSorted(TemplateIG.BY_NMI_DECREASING);
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      if (templates.size() == 0) {
        w.write("<no templates>\n");
        Log.warn("no templates!");
      }
      for (int j = 0; j < templates.size(); j++) {
        TemplateIG template = templates.get(j);
        List<TemplateIG> ts = explode ? template.explode() : Arrays.asList(template);
        for (TemplateIG t : ts) {
          StringBuilder sb = new StringBuilder();

          // FOM
          sb.append(String.valueOf(t.heuristicScore()));

          // mi
          //        sb.append("\t" + t.ig().miSmoothed.mi());
          sb.append("\t" + t.ig().mi());

          // hx
          sb.append("\t" + t.hx());

          // selectivity
          sb.append("\t" + t.ig().selectivity);

          // order
          int[] pieces = getTemplatesForFeature(t.getIndex());
          sb.append("\t" + pieces.length + "\t");

          // featureInts
          for (int i = 0; i < pieces.length; i++) {
            if (i > 0) sb.append('*');
            sb.append(String.valueOf(pieces[i]));
          }

          // featureStrings
          sb.append('\t');
          for (int i = 0; i < pieces.length; i++) {
            if (i > 0) sb.append('*');
            sb.append(lastBialph.lookupTemplate(pieces[i]));
          }

          // How many updates we've seen. If no filtering is done, this will just
          // be the number of lines in the feature files. If however, we create
          // TemplateIGs with filters, then it will reflect the relative frequency
          // of the filter passing.
          sb.append("\t" + t.numObservations());

          // frame,framerole restrictions
          sb.append('\t');
          if (t.featureName.getY instanceof FrameRoleFilter) {
            FrameRoleFilter frf = (FrameRoleFilter) t.featureName.getY;
            sb.append("f=" + frf.getFrame());
            if (frf.hasRoleRestriction())
              sb.append(",r=" + frf.getRole());
          } else if (t.featureName.getY instanceof NullLabelGetY) {
            NullLabelGetY n = (NullLabelGetY) t.featureName.getY;
            sb.append(n.name);
          } else {
            sb.append("NA");
          }

          w.write(sb.toString());
          w.newLine();
        }
      }
      w.flush();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @SafeVarargs
  public static Pair<String[], Double> prod(Pair<String, Double>... templateIGs) {
    String[] prod = new String[templateIGs.length];
    double igProd = 1;
    for (int i = 0; i < templateIGs.length; i++) {
      prod[i] = templateIGs[i].get1();
      assert prod[i] != null;
      switch (FEATURE_SCORE_HEURISTIC) {
      case ARITHMETIC_MEAN:
        igProd += templateIGs[i].get2();
        break;
      case HARMONIC_MEAN:
        igProd *= (1 + templateIGs[i].get2());
        break;
      default:
        throw new RuntimeException("implement me: " + FEATURE_SCORE_HEURISTIC);
      }
    }
    switch (FEATURE_SCORE_HEURISTIC) {
    case ARITHMETIC_MEAN:
      igProd /= templateIGs.length;
      break;
    case HARMONIC_MEAN:
      igProd = Math.pow(igProd, 1 / templateIGs.length);
      break;
    default:
      throw new RuntimeException("implement me: " + FEATURE_SCORE_HEURISTIC);
    }
    return new Pair<>(prod, igProd);
  }

  /** Only return products of the given order */
  public static List<String[]> getProductsHeuristicallySorted(
      ExperimentProperties config,
      BiAlph bialph,
      int order,
      boolean showSkippedCard) throws IOException {

    // Read in the IG of the unigrams (templates)
    List<Pair<String, Double>> templateIGs = new ArrayList<>();
    File templateIGsFile = config.getExistingFile("templateIGs");
    Log.info("reading template IGs from " + templateIGsFile.getPath());
    try (BufferedReader r = FileUtil.getReader(templateIGsFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {

        /** @see InformationGain#main for format */
        String[] toks = line.split("\t");
        //int template = Integer.parseInt(toks[0]);
        int template = Integer.parseInt(toks[5]);

        String templateString = bialph.lookupTemplate(template);
        if (templateString == null) {
          Log.warn("skipping template " + template + " because it is not in"
              + " the provided BiAlph: " + bialph.getSource().getPath());
          continue;
        }

        double ig = Double.parseDouble(toks[1]);
        double hx = Double.parseDouble(toks[2]);
//        double hyx = Double.parseDouble(toks[3]);
//        double hy = Double.parseDouble(toks[4]);

        // What we sort on
        //double fom = ig / (1 + hx);
        double fom = ig - 0.1 * hx;

        if (DEBUG_TEMPLATE != null && !DEBUG_TEMPLATE.equals(templateString))
          Log.info("skipping " + templateString + " because DEBUG_TEMPLATE is set");
        else
          templateIGs.add(new Pair<>(templateString, fom));
      }
    }

    // Generating all products can blow up in time/space, specially when order>2
    // This prunes the set of products being generated to only take the topK.
    int thresh = (int) Math.pow(templateIGs.size() * 50000, 1d / order);
    if (templateIGs.size() > thresh) {
      Log.info("pruning from " + templateIGs.size() + " => " + thresh);
      templateIGs = templateIGs.subList(0, thresh);
    }

    // Don't allow any features (products of templates) which have a cardinality
    // of more than this. The BUB octave/matlab code will crash if this is above
    // 100M. It allocates a dense array this long (i.e. its costly in memory).
    // This is a heuristic, as templates may be correlated and have much lower
    // actual cardinalities. But, if they are correlated it is unclear how much
    // information they are adding.
    // Remember, this is D_x not D_yx. So if you were storing this many features
    // in a discriminative model, you still need to multiply in D_y to get an
    // estimate of how many weights you're learning.
    long cardinalityLimit = 10 * 1024 * 1024;

    // Produce a list of template n-grams
    List<Pair<String[], Double>> prodIGs = new ArrayList<>();
    int n = templateIGs.size();
    Log.info("producing templates up to order=" + order + " from " + n + " templates");
    for (int i = 0; i < n - 1; i++) {

      String t_i_name = templateIGs.get(i).get1();
      int t_i = bialph.mapTemplate(t_i_name);
      long Dx_i = bialph.cardinalityOfNewTemplate(t_i);
      if (Dx_i > cardinalityLimit) {
        if (showSkippedCard)
          Log.info("skipping1 " + t_i_name + " because of cardinality limit: " + Dx_i);
        continue;
      }

      if (order == 1)
        prodIGs.add(prod(templateIGs.get(i)));
      for (int j = i + 1; j < n; j++) {

        String t_j_name = templateIGs.get(j).get1();
        int t_j = bialph.mapTemplate(t_j_name);
        long Dx_ij = Dx_i * bialph.cardinalityOfNewTemplate(t_j);
        if (Dx_ij > cardinalityLimit) {
          if (showSkippedCard)
            Log.info("skipping2 " + t_i_name + "*" + t_j_name + " because of cardinality limit: " + Dx_ij);
          continue;
        }

        if (order == 2)
          prodIGs.add(prod(templateIGs.get(i), templateIGs.get(j)));

        if (order == 3) {
          for (int k = j + 1; k < n; k++) {

            String t_k_name = templateIGs.get(k).get1();
            int t_k = bialph.mapTemplate(t_k_name);
            long Dx_ijk = Dx_ij * bialph.cardinalityOfNewTemplate(t_k);
            if (Dx_ijk > cardinalityLimit) {
              if (showSkippedCard)
                Log.info("skipping3 " + t_i_name + "*" + t_j_name + "*" + t_k_name + " because of cardinality limit: " + Dx_ijk);
              continue;
            }

            prodIGs.add(prod(
                templateIGs.get(i),
                templateIGs.get(j),
                templateIGs.get(k)));
          }
        }
        if (order > 3)
          throw new RuntimeException("not implemented! order=" + order);
      }
    }

    // Sort templates and project back to just template names (discarding score)
    Log.info("sorting " + prodIGs.size() + " template products by information gain...");
    Collections.sort(prodIGs, new Comparator<Pair<String[], Double>>() {
      @Override
      public int compare(Pair<String[], Double> o1, Pair<String[], Double> o2) {
        if (o1.get2() < o2.get2()) return 1;
        if (o1.get2() > o2.get2()) return -1;
        return 0;
      }
    });
    List<String[]> prods = new ArrayList<>();
    for (Pair<String[], Double> p : prodIGs)
      prods.add(p.get1());
    return prods;
  }

  public static int stringArrayHash(String[] ar) {
    int h = 0;
    for (String i : ar)
      h = i.hashCode() + 31 * h;
    return h;
  }

  public static <T> List<T> take(List<T> all, int n) {
    if (all.size() <= n)
      return all;
    return all.subList(0, n);
  }
  @SafeVarargs
  public static <T> List<T> concat(List<T>... lists) {
    List<T> all = new ArrayList<>();
    for (List<T> l : lists)
      all.addAll(l);
    return all;
  }

  /**
   * How to split the template budget between various product orders.
   * Try gain=1.5
   */
  public static double weight(int order, double gain) {
    if (gain < 1 || order < 1)
      throw new IllegalArgumentException();
//    Log.info("order=" + order + " gain=" + gain + " result=" + Math.pow(gain, order));
    return Math.pow(gain, order);
  }
  public static int count(int order, double gain, int maxOrder, int n) {
    if (order > n || n < 1)
      throw new IllegalArgumentException();
    double z = 0;
    for (int ord = 1; ord <= maxOrder; ord++)
      z += weight(ord, gain);
    double p = weight(order, gain) / z;
    Log.info("order=" + order + " gain=" + gain + " p=" + p
        + " maxOrder=" +maxOrder + " n=" + n + " final=" + ((int) (p * n + 0.5)));
    return (int) (p * n + 0.5);
  }

  /**
   * {@link InformationGain#main(String[])} does not shard, it just tries to
   * compute MI for every template. Now that I want to split into the granularity
   * of template@frame@role, we cannot store all {@link TemplateIG}s in
   * memory at once, and we must shard even the first step of computing IG for
   * unigram features (templates).
   *
   * This main method uses almost all of the machinery of {@link InformationGainProducts}s,
   * specifically sharding, as to accomplish the same goal as {@link InformationGain},
   * but with more machines and less memory per machine.
   */
  public static void igReplacement() throws IOException {
    ExperimentProperties config = ExperimentProperties.getInstance();
    Log.info("computing IG/MI for every template (unigram feature)");

    // Read in mapping between frame/role ints and their names (e.g. "f=framenet/Commerce_buy"
    Map<String, Integer> role2name = readRole2Name(config);

    // BiAlph gives int<->string for templates
    File bf = config.getExistingFile("bialph");
    BiAlph bialph;
    if (bf.getName().contains(".jser")) {
      bialph = (BiAlph) FileUtil.deserialize(bf);
    } else {
      bialph = new BiAlph(bf, LineMode.ALPH);
    }

    // What shard of the template@frame@role to take?
    Shard shard = config.getShard();

    // Build a list of unigram features (templates),
    // Each with a @frame@role refinement
    List<String[]> features = new ArrayList<>();
    int[] t2c = bialph.makeTemplate2Cardinality();
    for (int t = 0; t < t2c.length; t++) {
      if (t2c[t] == 0)
        continue;
      String templateName = bialph.lookupTemplate(t);
      features.add(new String[] {templateName});
    }
    //Refinement refinementMode = Refinement.FRAME_ROLE;
    Refinement refinementMode = Refinement.valueOf(config.getString("refinementMode"));
    List<FeatureName> templates = productFeaturesWithFrameRole(features, role2name, shard, refinementMode);

    Log.info("after taking the " + shard + " shard,"
        + " numTemplates=" + templates.size());

    config.putIfAbsent("explode", "false");
//    computeIG(templates, bialph, config);
    computeIG(templates, refinementMode, bialph, config);
  }

  /** Reads the frame/role/label dictionary written out by {@link FeaturePrecomputation} */
  public static Map<String, Integer> readRole2Name(ExperimentProperties config) throws IOException {
    // Read in mapping between frame/role ints and their names (e.g. "f=framenet/Commerce_buy"
    boolean pb = config.getBoolean("propbank");
    Map<String, Integer> role2name = new HashMap<>();
    File f = config.getFile("roleNames");
    Log.info("reading role names from " + f.getPath());
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] tok = line.split("\t");
        int c = Integer.parseInt(tok[0]);

        if (InformationGain.ADD_ONE)
          c++;

        assert tok.length == 2;
        Object old = role2name.put(tok[1], c);
        assert old == null;

        // Earlier extractions don't have the frame index included in the name.
        // Add it now for backwards compatibility.
        int eq = tok[1].indexOf('=');
        if (eq >= 0) {
          String fin = pb ? "propbank" : "framenet";
          String newName = tok[1].substring(0, eq) + "=" + fin + "/" + tok[1].substring(eq + 1);
          Object old2 = role2name.put(newName, c);
          assert old2 == null || old2.equals(c);
        }
      }
    }
//    buildInverseRole2Name(role2name);
    return role2name;
  }

  /** Specifies how to turn a single features (String[]) into many refinements */
  public enum Refinement {
    NONE,         // no refinements, one TemplateIG per feature:String[]
    FRAME,        // one TemplateIG for every frame in the FrameIndex chosen
    FRAME_ROLE,   // one TemplateIG for every (f,k)
    NULL_LABEL,   // Y is redefined from labelMode to be label==valid, see NullLabelGetY
  }

  /**
   * @param features
   * @param bialph
   * @param role2name
   * @param frameShard is what fraction of all @frame@role FeatureNames to take,
   * if null then take all of them.
   * @return
   */
  public static List<FeatureName> productFeaturesWithFrameRole(
      List<String[]> features,
      Map<String, Integer> role2name,
      Shard frameShard,
      Refinement mode) {
    Log.info("taking product with " + features.size() + " features,"
        + " mode=" + mode + " frameShard=" + frameShard);
    ExperimentProperties config = ExperimentProperties.getInstance();
    boolean pb = config.getBoolean("propbank");
    FrameIndex fi = pb ? FrameIndex.getPropbank() : FrameIndex.getFrameNet();

    Function<FeatureFile.Line, int[]> getY = InformationGain.getGetY(config);

    TimeMarker tm = new TimeMarker();
    Average.Uniform kPerF = new Average.Uniform();
    kPerF.add(1); // avoid div by zero, slightly biases average
    List<FeatureName> refinements = new ArrayList<>();
    for (int j = 0; j < features.size(); j++) {
      String[] feature = features.get(j);

      if (mode == Refinement.NULL_LABEL) {
        refinements.add(new FeatureName(feature, new NullLabelGetY("nullLabel", getY)));
        continue;
      } else if (mode == Refinement.NONE) {
        refinements.add(new FeatureName(feature, getY));
        continue;
      }

      for (Frame ff : fi.allFrames()) {
        String frame = "f=" + ff.getName();
        int frameIdx = role2name.get(frame);

        // A shard will get all roles for a given template@frame
        if (frameShard != null) {
          long[] ha = new long[feature.length+1];
          ha[0] = frameIdx;
          for (int i = 0; i < feature.length; i++)
            ha[i+1] = feature[i].length();
          long h = Hash.mix64(ha);
          if (Math.floorMod(h, frameShard.getNumShards()) != frameShard.getShard())
            continue;
        }

        if (tm.enoughTimePassed(15)) {
          Log.info("added "
              + refinements.size() + " refinements, "
              + j + " of " + features.size() + " features so far, in "
              + tm.secondsSinceFirstMark() + " seconds, "
              + Describe.memoryUsage());
        }

        if (mode == Refinement.FRAME) {
          FrameRoleFilter filteredGetY = new FrameRoleFilter(getY, InformationGain.ADD_ONE, frameIdx);
          FeatureName fn = new FeatureName(feature, filteredGetY);
//          Log.info("creating " + fn);
          refinements.add(fn);
        } else {
          assert mode == Refinement.FRAME_ROLE;
          int K = ff.numRoles();
          kPerF.add(K);
          for (int k = 0; k < K; k++) {
            String role = "r=" + ff.getRole(k);
            int roleIdx = role2name.get(role);
            FrameRoleFilter filteredGetY = new FrameRoleFilter(getY, InformationGain.ADD_ONE, frameIdx, roleIdx);
            refinements.add(new FeatureName(feature, filteredGetY));
          }
        }
      }
    }
    int F = fi.allFrames().size();
    Log.info("features.size=" + features.size()
        + " refinements.size=" + refinements.size()
        + " numFrames=" + F
        + " kPerF=" + kPerF.getAverage() + " (n=" + kPerF.getNumObservations() + ")"
        + " shard=" + frameShard
        + " " + Describe.memoryUsage());
    return refinements;
  }

  /**
   * Read in the scores of templates (unigram features), heuristically come up
   * with some potential product features to try out, and then take a shard of
   * the total features to compute actual IG/MI for.
   */
  public static void igp() throws IOException {
    ExperimentProperties config = ExperimentProperties.getInstance();
    Log.info("computing IG/MI for some product features");

    // Load the features and compute the IG for the chosen products
    File templateAlph = config.getExistingFile("templateAlph");
    BiAlph bialph;
    if (templateAlph.getName().contains(".jser")) {
      bialph = (BiAlph) FileUtil.deserialize(templateAlph);
    } else {
      LineMode lm = LineMode.valueOf(config.getString("templateAlphLineMode", LineMode.ALPH.name()));
      bialph = new BiAlph(templateAlph, lm);
    }

    // Find the top K unigrams.
    // Splitting the features by order and then assigning resources according to
    // "gain" (high gain searches more higher order features, gain=1 searches
    // over the same number of features from all orders) is a hedge against the
    // feature scoring heuristic being bad.
    boolean showSkipCard = config.getBoolean("showSkipCard", false);
    Shard shard = config.getShard();
    List<String[]> prod1 = ShardUtils.shard(getProductsHeuristicallySorted(config, bialph, 1, showSkipCard), InformationGainProducts::stringArrayHash, shard);
    List<String[]> prod2 = ShardUtils.shard(getProductsHeuristicallySorted(config, bialph, 2, showSkipCard), InformationGainProducts::stringArrayHash, shard);
    List<String[]> prod3 = ShardUtils.shard(getProductsHeuristicallySorted(config, bialph, 3, showSkipCard), InformationGainProducts::stringArrayHash, shard);
    double gain = config.getDouble("gain", 1.5);
    int maxProducts = config.getInt("numProducts", 200);
    assert maxProducts > 0;
    int n1 = count(1, gain, 3, maxProducts);
    int n2 = count(2, gain, 3, maxProducts);
    int n3 = count(3, gain, 3, maxProducts);
    List<String[]> products = concat(
        take(prod1, n1),
        take(prod2, n2),
        take(prod3, n3));

    Log.info("computing IG for the top " + products.size() + " product features,"
        + " gain=" + gain + " n1=" + n1 + " n2=" + n2 + " n3=" + n3);
    for (int i = 0; i < 10 && i < prod1.size(); i++)
      Log.info("product[1," + i + "]=" + Arrays.toString(prod1.get(i)));
    for (int i = 0; i < 10 && i < prod2.size(); i++)
      Log.info("product[2," + i + "]=" + Arrays.toString(prod2.get(i)));
    for (int i = 0; i < 10 && i < prod3.size(); i++)
      Log.info("product[3," + i + "]=" + Arrays.toString(prod3.get(i)));

    // List of features => list of feature@frame@role
    Map<String, Integer> role2name = readRole2Name(config);
    Shard frameShard = null;  // take all, sharding has already occurred on the features
    Refinement refinementMode = Refinement.valueOf(config.getString("refinementMode"));//, Refinement.FRAME.name()));
    List<FeatureName> feats = productFeaturesWithFrameRole(products, role2name, frameShard, refinementMode);

    computeIG(feats, refinementMode, bialph, config);
  }

  /**
   * Take some chosen products/features and compute IG for them. Other options
   * for where to route output, etc are read in through {@link ExperimentProperties}.
   */
  public static void computeIG(
      List<FeatureName> products,
      Refinement refinementMode,
      BiAlph bialph,
      ExperimentProperties config) throws IOException {

    Log.info("refinementMode=" + refinementMode);

    if (products.isEmpty()) {
      Log.warn("products.size=0, exiting early!");
      return;
    }

    final File output = config.getFile("output");
    Log.info("output=" + output.getPath());

    final EntropyMethod em = EntropyMethod.valueOf(config.getString("entropyMethod"));
    Log.info("using " + em + " to compute entropy");

    // If 0, don't write out any intermediate results
    final int writeTopProductsEveryK = config.getInt("writeTopProductsEveryK", 64);
    Log.info("writeTopProductsEveryK=" + writeTopProductsEveryK);

//    final File featuresParent = config.getExistingDir("featuresParent");
//    final String featuresGlob = config.getString("featuresGlob", "glob:**/*");

    BubEntropyEstimatorAdapter bubEst = null;
    if (em == EntropyMethod.BUB) {
      final File bubFuncParentDir = config.getExistingDir("bubFuncParentDir");
      Log.info("using BUB code in " + bubFuncParentDir.getPath());
      bubEst = new BubEntropyEstimatorAdapter(bubFuncParentDir);
      bubEst.debug = config.getBoolean("bubDebug", false);
    }

    int numRoles = config.getInt("numRoles", 30);
    int hashingTrickDim = config.getInt("hashingTrickDim", 0);
    Log.info("numRoles=" + numRoles + " hashingTrickDim=" + hashingTrickDim);
    InformationGainProducts igp = new InformationGainProducts(
        products, hashingTrickDim, bubEst, em);
    igp.init(bialph, numRoles);

    // Scan each of the input files
    List<File> featureFiles = config.getFileGlob("features");
    // You can use dataShard=0/10 to take 1/10th of the data
    Shard dataShard = config.getShard("data");
    if (dataShard.getNumShards() > 1) {
      Log.info("filtering " + featureFiles.size() + " files according to dataShard=" + dataShard);
      featureFiles = ShardUtils.shardByIndex(featureFiles, dataShard);
    }
    Log.info("iterating over " + featureFiles.size() + " feature files");
    for (File f : featureFiles) {
      igp.update(f);
      if (writeTopProductsEveryK > 0 && igp.getNumUpdates() % writeTopProductsEveryK == 0)
        igp.writeOutProducts(output, refinementMode);
    }

    // Write out final results
    igp.writeOutProducts(output, refinementMode);

    if (bubEst != null) {
      Log.info("closing matlab/bub connection");
      bubEst.close();
    }

    Log.info("done");
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    if (config.getBoolean("unigrams", false))
      igReplacement();
    else
      igp();
  }
}

