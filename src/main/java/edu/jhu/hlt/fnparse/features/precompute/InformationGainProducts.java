package edu.jhu.hlt.fnparse.features.precompute;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Templates;
import edu.jhu.hlt.fnparse.features.precompute.InformationGain.TemplateIG;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.prim.vector.IntFloatUnsortedVector;

/**
 * Computes information gain for products of templates.
 *
 * Uses template alphabets from super class.
 *
 * @author travis
 */
public class InformationGainProducts implements LineByLine {
  public static boolean DEBUG = false;

  /** Extracts just the features needed */
  public static class BaseTemplates {
    private String line;  // debug
    private int size;
    private int role; // something that is in the line
//    private IntIntUnsortedVector templateFeatures;
    private List<IntPair> templateFeatures;
    public BaseTemplates(BitSet templates, String line) {
      this.line = line;
      this.size = 0;
      this.role = FeaturePrecomputation.getRole(line);
//      this.templateFeatures = new IntIntUnsortedVector();
      this.templateFeatures = new ArrayList<>();
      Iterator<IntPair> tmplFeatLocs = AlphabetMerger.findFeatureKeys(line);
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
          int f = Integer.parseInt(fs) + 1;
          assert f > 0;
          size++;
//          templateFeatures.add(t, f);
          templateFeatures.add(new IntPair(t, f));
        }
      }
    }
    public int getTemplate(int i) {
//      return templateFeatures.getInternalIndices()[i];
      return templateFeatures.get(i).first;
    }
    public int getValue(int i) {
//      return templateFeatures.getInternalValues()[i];
      return templateFeatures.get(i).second;
    }
    public int size() {
      return size;
    }
    public int getRole() {
      return role;
    }
    @Override
    public String toString() {
      List<Integer> t = new ArrayList<>();
      List<Integer> f = new ArrayList<>();
      for (int i = 0; i < templateFeatures.size(); i++) {
        t.add(templateFeatures.get(i).first);
        f.add(templateFeatures.get(i).second);
      }
      return "(BaseTemplates k=" + role
//          + " templates=" + Arrays.toString(templateFeatures.getInternalIndices())
//          + " feature=" + Arrays.toString(templateFeatures.getInternalValues())
          + " templates=" + t
          + " feature=" + f
          + " line=" + line
          + ")";
    }
  }

  private Map<int[], TemplateIG> products;
  private List<int[]> baseFeatures;   // Feature index -> int[] of templates
  private Templates alphs;
  private BitSet relevantTemplates;   // Union of values in keys of products
  private int[] template2cardinality; // Indexed by base template.index, will contain gaps in places of un-necessary templates

  public InformationGainProducts(List<int[]> features, File templateAlph) {
    // Allocate counters for IG
    baseFeatures = features;
    products = new HashMap<>();
    for (int i = 0; i < features.size(); i++) {
      products.put(features.get(i), new TemplateIG(i));
    }

    // Construct the union of base templates
    relevantTemplates = new BitSet();
    for (int[] prod : features)
      for (int i : prod)
        relevantTemplates.set(i);

    // Setup template cardinalities
    alphs = new Templates(templateAlph);
    template2cardinality = new int[3000];   // TODO resizing code
    Arrays.fill(template2cardinality, -1);
    for (int t = relevantTemplates.nextSetBit(0); t >= 0; t = relevantTemplates.nextSetBit(t + 1)) {
      // Lookup cardinality for template[relevantTemplates[i]] (comes from a file)
      template2cardinality[t] = alphs.get(t).alph.size() + 1;
    }
  }

  @Override
  public void observeLine(String line) {
    IntFloatUnsortedVector fv = new IntFloatUnsortedVector();
    BaseTemplates bv = new BaseTemplates(relevantTemplates, line);
    int k = bv.getRole();
    for (Entry<int[], TemplateIG> x : products.entrySet()) {
      // Get the products
      int[] templates = x.getKey();
      flatten(bv, 0, templates, 0, 1, 1, fv);

      // Measure IG
      int y = k + 1;  // k=-1 means no role, shift everything up by one
      final TemplateIG t = x.getValue();
      fv.forEach(ide -> {
        t.update(y, ide.index());
      });
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
  private void flatten(
      BaseTemplates data, int dIndex,   // data has templates needed for *all* products/features
      int[] templates, int tIndex,      // these are the templates for *this* product/feature
      int cardinality, int value,
      IntFloatUnsortedVector buffer) {
    if (DEBUG) {
      System.out.println();
      System.out.println(data.toString());
      System.out.println("dIndex=" + dIndex);
      System.out.println("templates=" + Arrays.toString(templates));
      System.out.println("tIndex=" + tIndex);
      System.out.println("cardinality=" + cardinality);
      System.out.println("value=" + value);
    }

    if (tIndex == templates.length) {
      buffer.add(value, 1);
      return;
    }

    // Find the first data index matching the template we're looking for
    int curTemplate = templates[tIndex];
    int curTemplateCard = template2cardinality[curTemplate];
    int startDataIndex = dIndex;
    boolean found = false;
    while (startDataIndex < data.size && !found) {
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
      if (DEBUG)
        System.out.println("not found!");
      return;
    }

    // Find the last data index that matches the current template
    int endDataIndex = startDataIndex + 1;
    while (endDataIndex < data.size && data.getTemplate(endDataIndex) == curTemplate)
      endDataIndex++;

    // Recurse
    int newCard = cardinality * curTemplateCard;
    if (DEBUG) {
      System.out.println("cardinality=" + cardinality);
      System.out.println("curTemplateCard=" + curTemplateCard);
      System.out.println("newCard=" + newCard);
    }
    assert newCard > 0;
    for (int i = startDataIndex; i < endDataIndex; i++) {
      if (DEBUG)
        System.out.println("data.getValue(" + i + ")=" + data.getValue(i));
      assert data.getValue(i) < curTemplateCard;
      int newValue = value * curTemplateCard + data.getValue(i);
      flatten(data, endDataIndex,
          templates, tIndex + 1,
          newCard, newValue,
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

  public Templates getAlphabets() {
    return alphs;
  }

  public static void test() throws IOException {
    List<int[]> products = Arrays.asList(
        new int[] {1, 2, 3},
        new int[] {4, 5},
        new int[] {6});
    File templateAlph = new File("/tmp/first/merged-0/template-feat-indices.txt.gz");
    InformationGainProducts igp = new InformationGainProducts(products, templateAlph);
    igp.run(new File("/tmp/first/merged-0/features.txt.gz"));
    int k = 10;
    List<TemplateIG> byIG = igp.getTemplatesSortedByIGDecreasing();
    Templates alphs = igp.getAlphabets();
    for (int i = 0; i < Math.min(k, byIG.size()); i++) {
      TemplateIG t = byIG.get(i);
      int[] pieces = igp.getTemplatesForFeature(t.getIndex());
      String[] spieces = new String[pieces.length];
      for (int j = 0; j < pieces.length; j++)
        spieces[j] = alphs.get(pieces[j]).name;
      System.out.println(t.ig()
        + "\t" + t.getIndex()
        + "\t" + Arrays.asList(pieces)
        + "\t" + Arrays.asList(spieces));
    }
  }

  @SafeVarargs
  public static Pair<int[], Double> prod(Pair<Integer, Double>... templateIGs) {
    int[] prod = new int[templateIGs.length];
    double igProd = 1;
    for (int i = 0; i < templateIGs.length; i++) {
      prod[i] = templateIGs[i].get1();
      igProd *= templateIGs[i].get2();
    }
    return new Pair<>(prod, igProd);
  }

  public static List<int[]> getProductsSorted(ExperimentProperties config) throws IOException {
    // Read in the IG of the unigrams (templates)
    List<Pair<Integer, Double>> templateIGs = new ArrayList<>();
    File templateIGsFile = config.getExistingFile("templateIGs");
    try (BufferedReader r = FileUtil.getReader(templateIGsFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] toks = line.split("\t");
        double ig = Double.parseDouble(toks[0]);
        int template = Integer.parseInt(toks[1]);
        templateIGs.add(new Pair<>(template, ig));
      }
    }

    // Produce a list of template n-grams
    int order = config.getInt("order", 3);
    List<Pair<int[], Double>> prodIGs = new ArrayList<>();
    int n = templateIGs.size();
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
    Collections.sort(prodIGs, new Comparator<Pair<int[], Double>>() {
      @Override
      public int compare(Pair<int[], Double> o1, Pair<int[], Double> o2) {
        if (o1.get2() < o2.get2()) return 1;
        if (o1.get2() > o2.get2()) return -1;
        return 0;
      }
    });
    List<int[]> prods = new ArrayList<>();
    for (Pair<int[], Double> p : prodIGs)
      prods.add(p.get1());
    return prods;
  }

  public static List<int[]> filterByShard(List<int[]> all, ExperimentProperties config) {
    int shard = config.getInt("shard", 0);
    int numShards = config.getInt("numShards", 1);
    List<int[]> keep = new ArrayList<>();
    for (int[] feat : all) {
      int h = 0;
      for (int i : feat)
        h = i + 31 * h;
      if (Math.floorMod(h, numShards) == shard)
        keep.add(feat);
    }
    return keep;
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    //test();

    // Find the top K unigrams
    List<int[]> products = filterByShard(getProductsSorted(config), config);
    int maxProducts = config.getInt("numProducts", 1000);
    if (maxProducts > 0 && products.size() > maxProducts)
      products = products.subList(0, maxProducts);

    // Load the features and compute the IG for the chosen products
    File features = config.getExistingFile("features");
    File templateAlph = config.getExistingFile("templateAlph");
    InformationGainProducts igp = new InformationGainProducts(products, templateAlph);
    igp.run(features);

    // Write out the results in format:
    //  line = IG <tab> featureInts <tab> featureStrings
    // where featureInts and featureStrings are delimited by "-"
    File output = config.getFile("output");
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      List<TemplateIG> byIG = igp.getTemplatesSortedByIGDecreasing();
      Templates alphs = igp.getAlphabets();
      for (TemplateIG t : byIG) {
        w.write(String.valueOf(t.ig()));
        w.write("\t");
        int[] pieces = igp.getTemplatesForFeature(t.getIndex());
        for (int i = 0; i < pieces.length; i++) {
          if (i > 0) w.write("-");
          w.write(String.valueOf(pieces[i]));
        }
        w.write("\t");
        for (int i = 0; i < pieces.length; i++) {
          if (i > 0) w.write("-");
          w.write(alphs.get(pieces[i]).name);
        }
        w.newLine();
      }
    }
  }
}

