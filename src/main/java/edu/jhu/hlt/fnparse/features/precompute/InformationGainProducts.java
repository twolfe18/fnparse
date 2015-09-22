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

import edu.jhu.hlt.fnparse.features.precompute.InformationGain.TemplateIG;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
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
 * @author travis
 */
public class InformationGainProducts {
  public static boolean DEBUG = false;
  public static boolean FLATTEN_DEBUG = false;

  /** Extracts just the features needed (given a subset of templates of interet) */
  public static class BaseTemplates {
    private String line;                      // debug, be careful with memory usage
    private int role;                         // in line, useful for y, cyx, etc.
//    private List<IntPair> templateFeatures;   // No guarantee on order!
//    private int[] tf;
    private int[] templates;
    private int[] features;

    public BaseTemplates(int[] templates, int[] features) {
      assert templates.length == features.length;
      this.role = -2;
      this.templates = templates;
      this.features = features;
    }

    public BaseTemplates(BitSet templates, String line, boolean storeTemplates) {
      this.line = line;
      this.role = FeaturePrecomputation.getRole(line);
//      this.templateFeatures = new ArrayList<>();
      List<IntPair> templateFeatures = new ArrayList<>();
      Iterator<IntPair> tmplFeatLocs = BiAlphMerger.findTemplateFeatureMentions(line);
      while (tmplFeatLocs.hasNext()) {
        IntPair se = tmplFeatLocs.next();
        int colon = line.indexOf(':', se.first);
        String ts = line.substring(se.first, colon);
        int t = Integer.parseInt(ts);
        assert t >= 0;
        if (templates.get(t)) {
          if (DEBUG)
            System.out.println("keeping: " + line.substring(se.first, se.second));
          String fs = line.substring(colon + 1, se.second);
          // +1 is because IntIntUnsortedVector doesn't support 0 values
          int f = Integer.parseInt(fs); // + 1;
//          assert f > 0;
          assert f >= 0;
          templateFeatures.add(new IntPair(t, f));
        }
      }
      int n = templateFeatures.size();
      if (storeTemplates)
        this.templates = new int[n];
      this.features = new int[n];
//      this.tf = new int[2 * n];
      for (int i = 0; i < n; i++) {
        IntPair tfi = templateFeatures.get(i);
//        tf[2 * i + 0] = tfi.first;
//        tf[2 * i + 1] = tfi.second;
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
//      return templateFeatures.get(i).first;
//      return tf[2 * i + 0];
      return templates[i];
    }
    public int getValue(int i) {
//      return templateFeatures.get(i).second;
//      return tf[2 * i + 1];
      return features[i];
    }
    public int size() {
//      return templateFeatures.size();
//      return tf.length;
      return features.length;
    }
    public int getRole() {
      return role;
    }
    @Override
    public String toString() {
      StringBuilder tf = new StringBuilder();
      for (int i = 0; i < size(); i++) {
        if (i > 0)
          tf.append(" ");
        tf.append(getTemplate(i) + ":" + getValue(i));
      }
      return "(BaseTemplates k=" + role
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

  private Set<String> ignoreSentenceIds;

  // If this is >0, then the hashing tick is used and every template is allocated
  // a vector of this size to represent cx and cyx.
  private int hashingTrickDim;

  /**
   * @param hashingTrickDim 0 means no hashing trick and >0 is how many dimensions
   * to use for representing features.
   */
  public InformationGainProducts(List<String[]> features, int hashingTrickDim) {
    this.baseFeatureNames = features;
    this.hashingTrickDim = hashingTrickDim;
    if (this.hashingTrickDim <= 0)
      throw new RuntimeException("not implemented");

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
  public void init(BiAlph bialph) {
    assert baseFeatures == null;
    assert products == null;
    assert relevantTemplates == null;
    assert template2cardinality == null;

    // Map everything over
    products = new HashMap<>();
    baseFeatures = new ArrayList<>();
    int featIdx = 0;
    for (String[] strTemplates : baseFeatureNames) {
      int[] intTemplates = new int[strTemplates.length];
      for (int i = 0; i < intTemplates.length; i++)
        intTemplates[i] = bialph.mapTemplate(strTemplates[i]);
      Arrays.sort(intTemplates);
      baseFeatures.add(intTemplates);
      products.put(intTemplates, new TemplateIG(featIdx));
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

/*
  public BiAlph run(File features, File mapping, boolean mappingIsBialph) throws IOException {
    Log.info("features=" + features.getPath()
      + " mapping=" + mapping.getPath()
      + " mappingIsBialph=" + mappingIsBialph);

    // Load the bialph
    BiAlph bialph = new BiAlph(mapping, mappingIsBialph);

    // Possibly initialize some DS if you this is the first time
    if (!isInitialized())
      init(bialph);
*/
  public void update(File features) throws IOException {

    // Scan the features
    try (BufferedReader r = FileUtil.getReader(features)) {
      for (String line = r.readLine(); line != null; line = r.readLine())
        observeLine(line);
    }

    // You can use this later for mapping int template -> string template
//    return bialph;
  }

//  @Override
  public void observeLine(String line) {
    String[] toks = line.split("\t", 3);
    String sentenceId = toks[1];
    if (ignoreSentenceIds.contains(sentenceId))
      return;
    List<Long> fv = new ArrayList<>();
    BaseTemplates bv = new BaseTemplates(relevantTemplates, line, true);
    int k = bv.getRole();
    for (Entry<int[], TemplateIG> x : products.entrySet()) {
      // Get the products
      int[] templates = x.getKey();
      flatten(bv, 0, templates, 0, 1, 1, template2cardinality, fv);

      // Measure IG
      int y = k + 1;  // k=-1 means no role, shift everything up by one
      final TemplateIG t = x.getValue();
      for (long index : fv)
        t.update(y, (int) (Math.floorMod(index, this.hashingTrickDim)));
      fv.clear();
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
      long cardinality, long value,
      int[] template2cardinality,
      List<Long> buffer) {
    if (FLATTEN_DEBUG) {
      System.out.println();
      System.out.println(data.toString());
      System.out.println("dIndex=" + dIndex);
      System.out.println("templates=" + Arrays.toString(templates));
      System.out.println("tIndex=" + tIndex);
      System.out.println("cardinality=" + cardinality);
      System.out.println("value=" + value);

      // Verify templates are sorted
      for (int i = 0; i < templates.length - 1; i++)
        assert templates[i] < templates[i + 1];
      // Verify data is sorted
      for (int i = 0; i < data.size() - 1; i++)
        assert data.getTemplate(i) <= data.getTemplate(i + 1);
    }

    if (tIndex == templates.length) {
      buffer.add(value);
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
    long newCard = cardinality * curTemplateCard;
    if (FLATTEN_DEBUG) {
      System.out.println("cardinality=" + cardinality);
      System.out.println("curTemplateCard=" + curTemplateCard);
      System.out.println("newCard=" + newCard);
    }
    assert newCard > 0;
    for (int i = startDataIndex; i < endDataIndex; i++) {
      if (FLATTEN_DEBUG)
        System.out.println("data.getValue(" + i + ")=" + data.getValue(i));
      assert data.getValue(i) < curTemplateCard;
      long newValue = value * curTemplateCard + data.getValue(i);
      flatten(data, endDataIndex,
          templates, tIndex + 1,
          newCard, newValue,
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

  @SafeVarargs
  public static Pair<String[], Double> prod(Pair<String, Double>... templateIGs) {
    String[] prod = new String[templateIGs.length];
    double igProd = 1;
    for (int i = 0; i < templateIGs.length; i++) {
      prod[i] = templateIGs[i].get1();
      igProd *= templateIGs[i].get2();
    }
    return new Pair<>(prod, igProd);
  }

  public static List<String[]> getProductsSorted(
      ExperimentProperties config,
      BiAlph bialph) throws IOException {
    // Read in the IG of the unigrams (templates)
    List<Pair<String, Double>> templateIGs = new ArrayList<>();
    File templateIGsFile = config.getExistingFile("templateIGs");
    Log.info("reading template IGs from " + templateIGsFile.getPath());
    try (BufferedReader r = FileUtil.getReader(templateIGsFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] toks = line.split("\t");
        double ig = Double.parseDouble(toks[0]);
        int template = Integer.parseInt(toks[1]);
        String templateString = bialph.lookupTemplate(template);
//        String templateString = toks[2];
        templateIGs.add(new Pair<>(templateString, ig));
      }
    }

    int order = config.getInt("order", 3);
    int thresh = (int) Math.pow(templateIGs.size() * 15000, 1d / order);
    if (templateIGs.size() > thresh) {
      Log.info("pruning from " + templateIGs.size() + " => " + thresh);
      templateIGs = templateIGs.subList(0, thresh);
    }

    // Produce a list of template n-grams
    List<Pair<String[], Double>> prodIGs = new ArrayList<>();
    int n = templateIGs.size();
    Log.info("producing templates up to order=" + order + " from " + n + " templates");
    for (int i = 0; i < n - 1; i++) {
      prodIGs.add(prod(templateIGs.get(i)));
      for (int j = i + 1; j < n; j++) {
        if (order >= 2)
          prodIGs.add(prod(templateIGs.get(i), templateIGs.get(j)));
        if (order >= 3) {
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

    // Sort and project
    Log.info("sorting template products by information gain...");
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

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    FLATTEN_DEBUG = config.getBoolean("FLATTEN_DEBUG", false);

    // Load the features and compute the IG for the chosen products
    File featuresParent = config.getExistingDir("featuresParent");
    String featuresGlob = config.getString("featuresGlob");
    File templateAlph = config.getExistingFile("templateAlph");
    boolean templateAlphIsBialph = config.getBoolean("templateAlphIsBialph");

    // Read in the bialph (for things like template cardinality)
    Log.info("reading templateAlph=" + templateAlph.getPath()
      + " templateAlphIsBialph=" + templateAlphIsBialph);
    BiAlph bialph = new BiAlph(templateAlph, templateAlphIsBialph);

    // Find the top K unigrams
    List<String[]> products = filterByShard(getProductsSorted(config, bialph), config);
    int maxProducts = config.getInt("numProducts", 500);
    if (maxProducts > 0 && products.size() > maxProducts) {
      Log.info("taking the top " + maxProducts + " products from the "
          + products.size() + " products that fell in this shard");
      products = products.subList(0, maxProducts);
    }
    Log.info("computing IG for the top " + products.size() + " product features");
    for (int i = 0; i < 10 && i < products.size(); i++)
      Log.info("product[" + i + "]=" + Arrays.toString(products.get(i)));

    int hashingTrickDim = config.getInt("hashingTrickDim", 2 * 1024 * 1024);
    InformationGainProducts igp = new InformationGainProducts(products, hashingTrickDim);
    igp.init(bialph);

    // Scan each of the input files
    PathMatcher pm = FileSystems.getDefault().getPathMatcher(featuresGlob);
    Files.walkFileTree(featuresParent.toPath(), new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
        if (pm.matches(path)) {
          Log.info("reading features: " + path.toFile().getPath() + "\t" + Describe.memoryUsage());
          igp.update(path.toFile());
        }
        return FileVisitResult.CONTINUE;
      }
      @Override
      public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
      }
    });

    // Write out the results in format:
    //  line = IG <tab> featureInts <tab> featureStrings
    // where featureInts and featureStrings are delimited by "-"
    int topK = config.getInt("topK", 10);
    File output = config.getFile("output");
    Log.info("writing output to " + output.getPath());
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      List<TemplateIG> byIG = igp.getTemplatesSortedByIGDecreasing();
      for (int j = 0; j < byIG.size(); j++) {
        TemplateIG t = byIG.get(j);
        StringBuilder sb = new StringBuilder();
        sb.append(String.valueOf(t.ig()));
        sb.append('\t');
        int[] pieces = igp.getTemplatesForFeature(t.getIndex());
        for (int i = 0; i < pieces.length; i++) {
          if (i > 0) sb.append('*');
          sb.append(String.valueOf(pieces[i]));
        }
        sb.append('\t');
        for (int i = 0; i < pieces.length; i++) {
          if (i > 0) sb.append('*');
          sb.append(bialph.lookupTemplate(pieces[i]));
        }
        w.write(sb.toString());
        w.newLine();
        if (j < topK)
          System.out.println(sb.toString());
      }
    }
  }
}

