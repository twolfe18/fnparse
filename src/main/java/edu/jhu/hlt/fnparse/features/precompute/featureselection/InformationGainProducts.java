package edu.jhu.hlt.fnparse.features.precompute.featureselection;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile.TemplateExtraction;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.hlt.tutils.ShardUtils;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;
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

  /** How to accumulate many template IGs into a single score for a feature */
  public static FeatureScoring FEATURE_SCORE_HEURISTIC = FeatureScoring.ARITHMETIC_MEAN;
  public static enum FeatureScoring {
    // Arithmetic is less greedy than harmonic, so if you're exploring a lot of
    // features, arithmetic may lead to finding better features by searching
    // over a more diverse/larger set.
    ARITHMETIC_MEAN,    // score(feature) = 1/N sum_{template \in feature} ig(template)
    HARMONIC_MEAN,
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

    double cHx = config.getDouble("hxScoreCoef", 0.1);
    double cRand = config.getDouble("randScoreCoef", 0.5);
    Random rand = new Random(9001);

    boolean strict = config.getBoolean("strict", true);

    // Read in the IG of the unigrams (templates)
    List<Pair<String, Double>> templateIGs = new ArrayList<>();
    File templateIGsFile = config.getExistingFile("templateIGs");
    Log.info("reading template IGs from " + templateIGsFile.getPath());
    try (BufferedReader r = FileUtil.getReader(templateIGsFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {

        /** @see InformationGain#main for format */
        String[] toks = line.split("\t");
        int template = Integer.parseInt(toks[6]);
        String templateNameCheck = toks[7];

        String templateString = bialph.lookupTemplate(template);
        if (!templateNameCheck.equals(templateString)) {
          Log.warn("tempates in " + templateIGsFile
            + " are not indexed with alphabet in " + bialph.getSource()
            + " because the feature file contained " + templateNameCheck
            + " and the alphabet said it would be " + templateString);
          assert !strict;
        }
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
        double fom = ig
            -cHx*hx
            -cRand*rand.nextGaussian();

        templateIGs.add(new Pair<>(templateString, fom));
      }
    }

    // Generating all products can blow up in time/space, specially when order>2
    // This prunes the set of products being generated to only take the topK.
    int maxProducts = config.getInt("numProducts");
    int thresh = (int) Math.pow(maxProducts * Math.sqrt(templateIGs.size()) * 2000, 1d / order);
    Log.info("maxProducts=" + maxProducts + " templateIG.size=" + templateIGs.size() + " order=" + order + " thresh=" + thresh);
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
   * Read in the scores of templates (unigram features), heuristically come up
   * with some potential product features to try out, and then take a shard of
   * the total features to compute actual IG/MI for.
   */
  public static void igp(TemplateIG.Refined<FeatureName> posProdIG, TemplateIG.Refined<FeatureName> negProdIG) throws IOException {
    assert posProdIG != null || negProdIG != null;
    ExperimentProperties config = ExperimentProperties.getInstance();
    Log.info("computing IG/MI for some product features");
    boolean debug = false;

    // Load the features and compute the IG for the chosen products
    File templateAlph = config.getExistingFile("bialph");
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
    double gain = config.getDouble("gain", 1.5);
    int maxProducts = config.getInt("numProducts", 200);
    List<String[]> prod1 = getProductsHeuristicallySorted(config, bialph, 1, showSkipCard);
    List<String[]> prod2 = getProductsHeuristicallySorted(config, bialph, 2, showSkipCard);
    List<String[]> prod3 = getProductsHeuristicallySorted(config, bialph, 3, showSkipCard);
    assert maxProducts > 0;
    int n1 = count(1, gain, 3, maxProducts);
    int n2 = count(2, gain, 3, maxProducts);
    int n3 = count(3, gain, 3, maxProducts);

    if (debug) {
      Log.info("n1=" + n1 + " n2=" + n2 + " n3=" + n3 + " maxProducts=" + maxProducts);
      Log.info("prod1.size=" + prod1.size() + " prod2.size=" + prod2.size() + " prod3.size=" + prod3.size());
    }

    int t1 = 0;
    for (int i = 0; i < prod1.size() && t1 < n1; i++) {
      FeatureName fn = new FeatureName(prod1.get(i));
      if (debug) Log.info("prod1(" + i + ")=" + Arrays.toString(prod1.get(i)) + " fn.hash=" + fn.hashCode());
      boolean p = posProdIG != null && posProdIG.register(fn);
      boolean n = negProdIG != null && negProdIG.register(fn);
      if (p || n) {
        if (debug) Log.info("added something, t1=" + t1 + " fn=" + fn);
        fn.computeTemplateInts(bialph);
        t1++;
      }
    }
    int t2 = 0;
    for (int i = 0; i < prod2.size() && t2 < n2; i++) {
      FeatureName fn = new FeatureName(prod2.get(i));
      if (debug) Log.info("prod2(" + i + ")=" + Arrays.toString(prod2.get(i)) + " fn.hash=" + fn.hashCode());
      boolean p = posProdIG != null && posProdIG.register(fn);
      boolean n = negProdIG != null && negProdIG.register(fn);
      if (p || n) {
        if (debug) Log.info("added something, t2=" + t2 + " fn=" + fn);
        fn.computeTemplateInts(bialph);
        t2++;
      }
    }
    int t3 = 0;
    for (int i = 0; i < prod3.size() && t3 < n3; i++) {
      FeatureName fn = new FeatureName(prod3.get(i));
      if (debug) Log.info("prod3(" + i + ")=" + Arrays.toString(prod3.get(i)) + " fn.hash=" + fn.hashCode());
      boolean p = posProdIG != null && posProdIG.register(fn);
      boolean n = negProdIG != null && negProdIG.register(fn);
      if (p || n) {
        if (debug) Log.info("added something, t3=" + t3 + " fn=" + fn);
        fn.computeTemplateInts(bialph);
        t3++;
      }
    }
    if (debug) 
      Log.info("t1=" + t1 + " t2=" + t2 + " t3=" + t3);
  }

  /**
   * Take some chosen products/features and compute IG for them. Other options
   * for where to route output, etc are read in through {@link ExperimentProperties}.
   */
  public static void computeIG(
      BiAlph bialph,
      ExperimentProperties config) throws IOException {

    final File output = config.getFile("output");
    Log.info("output=" + output.getPath());

    final EntropyMethod em = EntropyMethod.valueOf(config.getString("entropyMethod"));
    Log.info("using " + em + " to compute entropy");

    int[] template2cardinality = bialph.makeTemplate2Cardinality();

    // If 0, don't write out any intermediate results
    final int writeTopProductsEveryK = config.getInt("writeTopProductsEveryK", 32);
    Log.info("writeTopProductsEveryK=" + writeTopProductsEveryK);

    int numY = config.getInt("numRoles", 30);
    Log.info("numY=" + numY);

    Shard refinementShard = config.getShard("refinement");
    Log.info("refinementShard=" + refinementShard);

    // Scan each of the input files
    List<File> featureFiles = config.getFileGlob("features");
    // You can use dataShard=0/10 to take 1/10th of the data
    Shard dataShard = config.getShard("data");
    if (dataShard.getNumShards() > 1) {
      Log.info("filtering " + featureFiles.size() + " files according to dataShard=" + dataShard);
      featureFiles = ShardUtils.shardByIndex(featureFiles, dataShard);
    }
    Log.info("iterating over " + featureFiles.size() + " feature files");

    File ignoreSentenceIdsFile = config.getExistingFile("ignoreSentenceIds");
    Log.info("ignoring the sentence ids in " + ignoreSentenceIdsFile.getPath());
    Set<String> ignoreSentenceIds = new HashSet<>();
    try (BufferedReader r = FileUtil.getReader(ignoreSentenceIdsFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        ignoreSentenceIds.add(line);
      }
    }

    /* These can be constructed lazily with just a shard **********************/
    // POS: role ~ feature @template@frame
    // key = (frame,template)
    TemplateIG.Refined<IntPair> posIG = null;
    if (config.getBoolean("posUnigram", false)) {
      Log.info("computing IG/MI for POS unigram templates, refinementShard=" + refinementShard);
      posIG = new TemplateIG.Refined<>(numY, em, refinementShard, true);
    }

    // NEG: binary ~ feature @template
    TemplateIG.Refined<Integer> negIG = null;
    if (config.getBoolean("negUnigram", false)) {
      Log.info("computing IG/MI for NEG unigram templates, refinementShard=" + refinementShard);
      negIG = new TemplateIG.Refined<>(numY, em, refinementShard, true);
    }
    /* ************************************************************************/

    // When we go to products, IGP.flatten will take FeatureFile.Line => List<ProductIndex>
    // @template -> @templateProd
    // Once we go to products, we will not every refine on @frame, since it will be too much.
    // For PB we can omit @frame in the first one since their roles are universal.
    TemplateIG.Refined<FeatureName> posProdIG = null;
    if (config.getBoolean("posNgram", false))
      posProdIG = new TemplateIG.Refined<>(numY, em, refinementShard, false);
    TemplateIG.Refined<FeatureName> negProdIG = null;
    if (config.getBoolean("negNgram", false))
      negProdIG = new TemplateIG.Refined<>(numY, em, refinementShard, false);
    if (posProdIG != null || negProdIG != null) {
      igp(posProdIG, negProdIG);
      if (posProdIG != null) {
        Log.info("computing IG/MI for POS n-gram template features,"
            + " refinementShard=" + refinementShard
            + " numRefinements=" + posProdIG.getNumRefinements());
      }
      if (negProdIG != null) {
        Log.info("computing IG/MI for NEG n-gram template features,"
            + " refinementShard=" + refinementShard
            + " numRefinements=" + negProdIG.getNumRefinements());
      }
    }
    /* ************************************************************************/

    // Maybe set to true when posNgram=true and mode=framenet since we cannot
    // afford to refine on frame, but do not want to conflate roles.
    boolean useFrameRoleInPlaceOfRole =
        config.getBoolean("useFrameRoleInPlaceOfRole", false);

    BubEntropyEstimatorAdapter bubEst = null;
    if (em == EntropyMethod.BUB) {
      final File bubFuncParentDir = config.getExistingDir("bubFuncParentDir");
      Log.info("using BUB code in " + bubFuncParentDir.getPath());
      bubEst = new BubEntropyEstimatorAdapter(bubFuncParentDir);
      bubEst.debug = config.getBoolean("bubDebug", false);
    }

    TimeMarker tm = new TimeMarker();
    long nlines = 0, nlinesIgnored = 0;
    int filesSeen = 0;
    for (File f : featureFiles) {
      for (FeatureFile.Line line : FeatureFile.getLines(f, true)) {
        if (tm.enoughTimePassed(15))
          Log.info("processed " + nlines + " and ignored " + nlinesIgnored
              + " lines in " + tm.secondsSinceFirstMark() + " sec, "
              + Describe.memoryUsage());
        nlines++;

        if (ignoreSentenceIds.contains(line.getSentenceId())) {
          nlinesIgnored++;
          continue;
        }

        if (posProdIG != null) {
          if (line.getPosLabelB()) {
            // TODO Could use frameRole here instead.
            // For FN, this is feasible and desirable.
            // For PB, this is infeasible and not desirable.
            int role = line.getRoles(false)[0];
            if (useFrameRoleInPlaceOfRole)
              role = line.getFrameRoles(false)[0];
            List<ProductIndex> buffer = new ArrayList<>();
            for (FeatureName fname : posProdIG.getRefinements()) {
              buffer.clear();
              flatten(line, 0, fname.templateInt, 0, ProductIndex.NIL, template2cardinality, buffer);
              long[] x = new long[buffer.size()];
              for (int i = 0; i < x.length; i++)
                x[i] = buffer.get(i).getProdFeature();
              posProdIG.update(fname, role, x);
            }
          }
        }

        if (negProdIG != null) {
          int y = line.getPosLabelB() ? 1 : 0;
          List<ProductIndex> buffer = new ArrayList<>();
          for (FeatureName fname : posProdIG.getRefinements()) {
            buffer.clear();
            flatten(line, 0, fname.templateInt, 0, ProductIndex.NIL, template2cardinality, buffer);
            long[] x = new long[buffer.size()];
            for (int i = 0; i < x.length; i++)
              x[i] = buffer.get(i).getProdFeature();
            negProdIG.update(fname, y, x);
          }
        }

        if (posIG != null) {
          if (line.getPosLabelB()) {
            int frame = line.getFrames(false)[0];
            int role = line.getRoles(false)[0];
            for (TemplateExtraction te : line.groupByTemplate()) {
              IntPair refinement = new IntPair(frame, te.template);
              posIG.update(refinement, role, te.features);
            }
          }
        }

        if (negIG != null) {
          int y = line.getPosLabelB() ? 1 : 0;
          for (TemplateExtraction te : line.groupByTemplate()) {
            Integer refinement = te.template;
            negIG.update(refinement, y, te.features);
          }
        }
      }

      // Write out results to disk
      if (writeTopProductsEveryK > 0 && filesSeen % writeTopProductsEveryK == 0)
        writeout(output, posIG, negIG, posProdIG, negProdIG, bialph);
      filesSeen++;
    }
    Log.info("done reading, saw " + filesSeen + " files");

    // Write out final results
    writeout(output, posIG, negIG, posProdIG, negProdIG, bialph);

    if (bubEst != null) {
      Log.info("closing matlab/bub connection");
      bubEst.close();
    }

    Log.info("done");
  }

  private static void writeout(File output,
      TemplateIG.Refined<IntPair> posIG,
      TemplateIG.Refined<Integer> negIG,
      TemplateIG.Refined<FeatureName> posProdIG,
      TemplateIG.Refined<FeatureName> negProdIG,
      BiAlph templateNames) throws IOException {
    Log.info("writing to " + output.getPath());
    try (BufferedWriter w = FileUtil.getWriter(output)) {

      if (posIG != null) {
        Log.info("num POS unigram template refinements " + posIG.getNumRefinements());
        posIG.writeout(w, frameTemplate -> {
          // order <tab> templateInt <tab> templateStr (<tab> restrictions)?
          return "1\t" + frameTemplate.second
              + "\t" + templateNames.lookupTemplate(frameTemplate.second)
              + "\ty=1,frame=" + frameTemplate.first;
        });
      }

      if (negIG != null) {
        Log.info("num NEG unigram template refinements " + negIG.getNumRefinements());
        negIG.writeout(w, template -> {
          // order <tab> templateInt <tab> templateStr (<tab> restrictions)?
          return "1\t" + template
              + "\t" + templateNames.lookupTemplate(template)
              + "\ty=0";
        });
      }

      if (posProdIG != null) {
        Log.info("num POS n-gram template feature refinements " + posProdIG.getNumRefinements());
        posProdIG.writeout(w, fname -> {
          // These don't get refined, so its fine to break the "k=v" format.
          StringBuilder sb = new StringBuilder();
          sb.append(fname.templateInt.length);  // order
          sb.append("\t");
          sb.append(StringUtils.join("*", fname.templateInt));
          sb.append("\t");
          sb.append(StringUtils.join("*", fname.templateStr));
          sb.append("\ty=1");
          return sb.toString();
        });
      }

      if (negProdIG != null) {
        Log.info("num NEG n-gram template feature refinements " + negProdIG.getNumRefinements());
        negProdIG.writeout(w, fname -> {
          // These don't get refined, so its fine to break the "k=v" format.
          StringBuilder sb = new StringBuilder();
          sb.append(fname.templateInt.length);  // order
          sb.append("\t");
          sb.append(StringUtils.join("*", fname.templateInt));
          sb.append("\t");
          sb.append(StringUtils.join("*", fname.templateStr));
          sb.append("\ty=0");
          return sb.toString();
        });
      }
    }
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    // BiAlph gives int<->string for templates
    File bf = config.getExistingFile("bialph");
    BiAlph bialph;
    if (bf.getName().contains(".jser")) {
      bialph = (BiAlph) FileUtil.deserialize(bf);
    } else {
      bialph = new BiAlph(bf, LineMode.ALPH);
    }

    computeIG(bialph, config);
  }
}

