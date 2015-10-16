package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.InformationGain.TemplateIG;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.ShardUtils;
import edu.jhu.hlt.tutils.StringUtils;
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
 * TODO Rethink what y is when we call update() on counts. I believe it should
 * depend on the dataset:
 *   FrameNet: y1 = roleName (note that k in FI is frame-relative), y2 = (frame,role)
 *   Propbank: same thing actually
 * How does this compare with the fact of y = the COMMIT action was good/no?
 *
 * @author travis
 */
public class InformationGainProducts {
  public static boolean DEBUG = false;
  public static boolean FLATTEN_DEBUG = false;

  public static String DEBUG_TEMPLATE = null;//"head1ParentBc1000/99";

  /** Extracts just the features needed (given a subset of templates of interet) */
  public static class BaseTemplates {
    private String line;                      // debug, be careful with memory usage
    private int[] roles;
    private int[] templates;
    private int[] features;

    public BaseTemplates(int[] templates, int[] features) {
      assert templates.length == features.length;
      this.roles = new int[] {-2};
      this.templates = templates;
      this.features = features;
    }

    /**
     * @param templates the relevent set templates that should be read in a
     * stored, or null if all templates should be taken in.
     * @param line
     * @param storeTemplates
     */
    public BaseTemplates(BitSet templates, String line, boolean storeTemplates) {
      this.line = line;
      this.roles = FeaturePrecomputation.getRoles(line);
      List<IntPair> templateFeatures = new ArrayList<>();
      Iterator<IntPair> tmplFeatLocs = BiAlphMerger.findTemplateFeatureMentions(line);
      while (tmplFeatLocs.hasNext()) {
        IntPair se = tmplFeatLocs.next();
        int colon = line.indexOf(':', se.first);
        String ts = line.substring(se.first, colon);
        int t = Integer.parseInt(ts);
        assert t >= 0;
        if (templates == null || templates.get(t)) {
          if (DEBUG)
            System.out.println("keeping: " + line.substring(se.first, se.second));
          String fs = line.substring(colon + 1, se.second);
          int f = Integer.parseInt(fs);
          assert f >= 0;
          templateFeatures.add(new IntPair(t, f));
        }
      }
      int n = templateFeatures.size();
      if (storeTemplates)
        this.templates = new int[n];
      this.features = new int[n];
      for (int i = 0; i < n; i++) {
        IntPair tfi = templateFeatures.get(i);
        if (storeTemplates)
          this.templates[i] = tfi.first;
        this.features[i] = tfi.second;
      }
    }
    /** Frees memory! */
    public void purgeLine() {
      line = null;
    }
    public int[] setTemplates(int[] templates) {
      int[] old = this.templates;
      this.templates = templates;
      return old;
    }
    public int[] getTemplates() { return templates; }
    public int[] getFeatures() { return features; }
    public int getTemplate(int i) {
      return templates[i];
    }
    public int getValue(int i) {
      return features[i];
    }
    public int size() {
      return features.length;
    }
    public int[] getRoles() {
      return roles;
    }
    @Override
    public String toString() {
      StringBuilder tf = new StringBuilder();
      for (int i = 0; i < size(); i++) {
        if (i > 0)
          tf.append(" ");
        tf.append(getTemplate(i) + ":" + getValue(i));
      }
      return "(BaseTemplates k=" + (roles.length == 1 ? roles[0] : Arrays.toString(roles))
          + " features=" + StringUtils.trunc(tf, 80)
          + " line=" + StringUtils.trunc(line, 80)
          + ")";
    }
  }

  private Map<int[], TemplateIG> products;    // keys (int[]s) are sorted
  private List<int[]> baseFeatures;           // items (int[]s) are sorted, populated after first observed (features,bialph)
  private List<String[]> baseFeatureNames;    // all you know at construction time
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

  /**
   * @param hashingTrickDim 0 means no hashing trick and >0 is how many dimensions
   * to use for representing features.
   */
  public InformationGainProducts(List<String[]> features, int hashingTrickDim, BubEntropyEstimatorAdapter bubEstimator) {
    this.baseFeatureNames = features;
    this.hashingTrickDim = hashingTrickDim;
    if (this.hashingTrickDim <= 0)
      throw new RuntimeException("not implemented");
    this.bubEst = bubEstimator;
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

  public boolean isInitialized() {
    return baseFeatures != null;
  }

  /**
   * Converts strings given at construction into ints for efficient processing
   * (only needs to be called once).
   */
  public void init(BiAlph bialph, int numRoles) {
    assert baseFeatures == null;
    assert products == null;
    assert relevantTemplates == null;
    assert template2cardinality == null;

    lastBialph = bialph;

    // Map everything over
    products = new HashMap<>();
    baseFeatures = new ArrayList<>();
    int featIdx = 0;
    for (String[] strTemplates : baseFeatureNames) {
      int[] intTemplates = new int[strTemplates.length];
      for (int i = 0; i < intTemplates.length; i++) {
        intTemplates[i] = bialph.mapTemplate(strTemplates[i]);
      }
      Arrays.sort(intTemplates);
      baseFeatures.add(intTemplates);
      TemplateIG tig = new TemplateIG(featIdx, StringUtils.join("*", strTemplates));
      tig.useBubEntropyEstimation(bubEst);
      products.put(intTemplates, tig);
      featIdx++;
    }

    // Construct the union of base templates
    relevantTemplates = new BitSet();
    for (int[] prod : baseFeatures)
      for (int i : prod)
        relevantTemplates.set(i);

    // Setup template cardinalities
    ExperimentProperties config = ExperimentProperties.getInstance();
    template2cardinality = new int[config.getInt("numTemplates", 3000)];   // TODO resizing code
    Arrays.fill(template2cardinality, -1);
    for (int t = relevantTemplates.nextSetBit(0); t >= 0; t = relevantTemplates.nextSetBit(t + 1)) {
      // Lookup cardinality for template[relevantTemplates[i]] (comes from a file)
      template2cardinality[t] = bialph.cardinalityOfNewTemplate(t) + 1;
    }
  }

  private int numUpdates = 0;
  public void update(File features) throws IOException {
    try (BufferedReader r = FileUtil.getReader(features)) {
      for (String line = r.readLine(); line != null; line = r.readLine())
        observeLine(line);
      numUpdates++;
    }
  }
  public int getNumUpdates() {
    return numUpdates;
  }

  public void observeLine(String line) {
    String[] toks = line.split("\t", 3);
    String sentenceId = toks[1];
    BaseTemplates bv = new BaseTemplates(relevantTemplates, line, true);

    // y = BaseTemplates.getRoles()
    // x = feature vector produced by flatten()
    int[] y;
    if (ignoreSentenceIds.contains(sentenceId)) {
      y = null;
    } else {
      y = bv.getRoles();
      for (int i = 0; i < y.length; i++)
        y[i]++;   // k=-1 means no role, shift everything up by one
    }

    for (Entry<int[], TemplateIG> entry : products.entrySet()) {
      int[] templates = entry.getKey();

      // Get the products
      List<ProductIndex> prods = new ArrayList<>();
      flatten(bv, 0, templates, 0, ProductIndex.NIL, template2cardinality, prods);

      // Measure IG
      final TemplateIG t = entry.getValue();
      for (ProductIndex pi : prods)
        t.update(y, new ProductIndex[] {pi});
    }
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
      BaseTemplates data, int dIndex,   // data has templates needed for *all* products/features
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
      for (int i = 0; i < data.size() - 1; i++)
        assert data.getTemplate(i) <= data.getTemplate(i + 1);
    }

    if (tIndex == templates.length) {
      buffer.add(cardValue);
      return;
    }

    // Find the first data index matching the template we're looking for
    int curTemplate = templates[tIndex];
    int curTemplateCard = template2cardinality[curTemplate];
    int startDataIndex = dIndex;
    boolean found = false;
    while (startDataIndex < data.size() && !found) {
      int t = data.getTemplate(startDataIndex);
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
    while (endDataIndex < data.size() && data.getTemplate(endDataIndex) == curTemplate)
      endDataIndex++;

    // Recurse
    if (FLATTEN_DEBUG)
      System.out.println("curTemplateCard=" + curTemplateCard);
    for (int i = startDataIndex; i < endDataIndex; i++) {
      assert data.getValue(i) < curTemplateCard;
      int card = template2cardinality[data.getTemplate(i)];
      ProductIndex newValue2 = cardValue.prod(data.getValue(i), card);
      if (FLATTEN_DEBUG) {
        System.out.println("data.getValue(" + i + ")=" + data.getValue(i)
          + " card=" + card + " newValue2=" + newValue2);
      }
      flatten(data, endDataIndex,
          templates, tIndex + 1,
          newValue2,
          template2cardinality,
          buffer);
    }
  }

  public List<TemplateIG> getTemplatesSortedByIGDecreasing() {
    List<TemplateIG> l = new ArrayList<>();
    l.addAll(products.values());
    Collections.sort(l, TemplateIG.BY_IG_DECREASING);
    return l;
  }

  public int[] getTemplatesForFeature(int i) {
    return baseFeatures.get(i);
  }

  /**
   * Write out the results in format:
   *   line = IG <tab> order <tab> featureInts
   * where featureInts is delimited by "*"
   */
  public void writeOutProducts(File output) {
    Log.info("writing output to " + output.getPath());
    List<TemplateIG> byIG = getTemplatesSortedByIGDecreasing();
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      if (byIG.size() == 0) {
        w.write("<no templates>\n");
        Log.warn("no templates!");
      }
      for (int j = 0; j < byIG.size(); j++) {
        TemplateIG t = byIG.get(j);
        StringBuilder sb = new StringBuilder();
        sb.append(String.valueOf(t.ig()));
        int[] pieces = getTemplatesForFeature(t.getIndex());
        sb.append("\t" + pieces.length + "\t");
        for (int i = 0; i < pieces.length; i++) {
          if (i > 0) sb.append('*');
          sb.append(String.valueOf(pieces[i]));
        }
        sb.append('\t');
        for (int i = 0; i < pieces.length; i++) {
          if (i > 0) sb.append('*');
          sb.append(lastBialph.lookupTemplate(pieces[i]));
        }
        w.write(sb.toString());
        w.newLine();
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
      int order) throws IOException {

    // Read in the IG of the unigrams (templates)
    List<Pair<String, Double>> templateIGs = new ArrayList<>();
    File templateIGsFile = config.getExistingFile("templateIGs");
    Log.info("reading template IGs from " + templateIGsFile.getPath());
    try (BufferedReader r = FileUtil.getReader(templateIGsFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {

        /** @see InformationGain#main for format */
        String[] toks = line.split("\t");
        int template = Integer.parseInt(toks[0]);

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
        double fom = ig / (1 + hx);

        if (DEBUG_TEMPLATE != null && !DEBUG_TEMPLATE.equals(templateString))
          Log.info("skipping " + templateString + " because DEBUG_TEMPLATE is set");
        else
          templateIGs.add(new Pair<>(templateString, fom));
      }
    }

    // Generating all products can blow up in time/space, specially when order>2
    // This prunes the set of products being generated to only take the topK.
    int thresh = (int) Math.pow(templateIGs.size() * 25000, 1d / order);
    if (templateIGs.size() > thresh) {
      Log.info("pruning from " + templateIGs.size() + " => " + thresh);
      templateIGs = templateIGs.subList(0, thresh);
    }

    // Produce a list of template n-grams
    List<Pair<String[], Double>> prodIGs = new ArrayList<>();
    int n = templateIGs.size();
    Log.info("producing templates up to order=" + order + " from " + n + " templates");
    for (int i = 0; i < n - 1; i++) {
      if (order == 1)
        prodIGs.add(prod(templateIGs.get(i)));
      for (int j = i + 1; j < n; j++) {
        if (order == 2)
          prodIGs.add(prod(templateIGs.get(i), templateIGs.get(j)));
        if (order == 3) {
          for (int k = j + 1; k < n; k++) {
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

  public static List<String[]> filterByShard(List<String[]> all, ExperimentProperties config) {
    int shard = config.getInt("shard", 0);
    int numShards = config.getInt("numShards", 1);
    assert shard >= 0 && shard < numShards;
    Log.info("starting filtering products by shard=" + shard + " numShards=" + numShards);
    List<String[]> keep = new ArrayList<>();
    for (String[] feat : all) {
      int h = 0;
      for (String i : feat)
        h = i.hashCode() + 31 * h;
      if (Math.floorMod(h, numShards) == shard)
        keep.add(feat);
    }
    Log.info("all.size=" + all.size() + " keep.size=" + keep.size());
    return keep;
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

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    FLATTEN_DEBUG = config.getBoolean("FLATTEN_DEBUG", false);

    // Load the features and compute the IG for the chosen products
    File featuresParent = config.getExistingDir("featuresParent");
    String featuresGlob = config.getString("featuresGlob");
    File templateAlph = config.getExistingFile("templateAlph");
    boolean templateAlphIsBialph = config.getBoolean("templateAlphIsBialph");

    final File output = config.getFile("output");
    Log.info("output=" + output.getPath());

    final int writeTopProductsEveryK = config.getInt("writeTopProductsEveryK", 4);
    Log.info("writeTopProductsEveryK=" + writeTopProductsEveryK);


    // Read in the bialph (for things like template cardinality)
    Log.info("reading templateAlph=" + templateAlph.getPath()
    + " templateAlphIsBialph=" + templateAlphIsBialph);
    BiAlph bialph = new BiAlph(templateAlph, templateAlphIsBialph ? LineMode.BIALPH : LineMode.ALPH);

    // Find the top K unigrams.
    // Splitting the features by order and then assigning resources according to
    // "gain" (high gain searches more higher order features, gain=1 searches
    // over the same number of features from all orders) is a hedge against the
    // feature scoring heuristic being bad.
    IntPair shard = ShardUtils.getShard(config);
    List<String[]> prod1 = ShardUtils.shard(getProductsHeuristicallySorted(config, bialph, 1), InformationGainProducts::stringArrayHash, shard);
    List<String[]> prod2 = ShardUtils.shard(getProductsHeuristicallySorted(config, bialph, 2), InformationGainProducts::stringArrayHash, shard);
    List<String[]> prod3 = ShardUtils.shard(getProductsHeuristicallySorted(config, bialph, 3), InformationGainProducts::stringArrayHash, shard);
    double gain = config.getDouble("gain", 1.5);
    int maxProducts = config.getInt("numProducts", 500);
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

    final File bubFuncParentDir = config.getExistingDir("bubFuncParentDir");
    Log.info("using BUB code in " + bubFuncParentDir.getPath());
    try (BubEntropyEstimatorAdapter bubEst = new BubEntropyEstimatorAdapter(bubFuncParentDir)) {
      int numRoles = config.getInt("numRoles", 30);
      int hashingTrickDim = config.getInt("hashingTrickDim", 512 * 1024);
      InformationGainProducts igp = new InformationGainProducts(products, hashingTrickDim, bubEst);
      igp.init(bialph, numRoles);

      // Scan each of the input files
      PathMatcher pm = FileSystems.getDefault().getPathMatcher(featuresGlob);
      Files.walkFileTree(featuresParent.toPath(), new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
          if (pm.matches(path)) {
            Log.info("reading features: " + path.toFile().getPath() + "\t" + Describe.memoryUsage());
            igp.update(path.toFile());

            if (igp.getNumUpdates() % writeTopProductsEveryK == 0)
              igp.writeOutProducts(output);
          }
          return FileVisitResult.CONTINUE;
        }
        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
          return FileVisitResult.CONTINUE;
        }
      });

      // Write out final results
      igp.writeOutProducts(output);

      // Show top products in log/command line
      int topK = config.getInt("topK", 10);
      List<TemplateIG> byIG = igp.getTemplatesSortedByIGDecreasing();
      for (int j = 0; j < byIG.size(); j++) {
        TemplateIG t = byIG.get(j);
        StringBuilder sb = new StringBuilder();
        sb.append(String.valueOf(t.ig()));
        int[] pieces = igp.getTemplatesForFeature(t.getIndex());
        sb.append("\t" + pieces.length + "\t");
        for (int i = 0; i < pieces.length; i++) {
          if (i > 0) sb.append('*');
          sb.append(String.valueOf(pieces[i]));
        }
        sb.append('\t');
        for (int i = 0; i < pieces.length; i++) {
          if (i > 0) sb.append('*');
          sb.append(bialph.lookupTemplate(pieces[i]));
        }
        if (j < topK)
          System.out.println(sb.toString());
      }
      Log.info("closing matlab/bub connection");
    }
    Log.info("done");
  }
}

