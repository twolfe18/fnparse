package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile.TemplateExtraction;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.LineByLine;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.vector.IntIntDenseVector;

/**
 * Uses output of {@link FeaturePrecomputation} to compute IG for feature selection.
 *
 * NOTE: This version was created before bialph merging. This may be good enough
 * for getting a rough top K list of templates, but if not, see
 * {@link InformationGainProducts} for how to read many feature files that don't
 * shard a common indexing scheme (you map with a bialph created by {@link BiAlphMerger}).
 *
 * TODO Rethink how y is chosen, see note in {@link InformationGainProducts}.
 *
 * @author travis
 */
public class InformationGain implements Serializable, LineByLine {
  private static final long serialVersionUID = -5727222496637587197L;

  public static class MI implements Serializable {
    private static final long serialVersionUID = -5839206491069959192L;

    private double mi_zero, mi_nonzero, mi_zero_correction;

    public void updateMiZero(double x) {
      assert Double.isFinite(x) && !Double.isNaN(x);
      mi_zero += x;
    }

    public void updateMiNonzero(double p_yx, double pmi_numerator, double pmi_denominator) {
      if (p_yx > 0)
        mi_nonzero += p_yx * (Math.log(pmi_numerator) - Math.log(pmi_denominator));
      else
        assert p_yx == 0;
    }

    public void updateMiZeroCorrection(double p_yx, double pmi_numerator, double pmi_denominator) {
      if (p_yx > 0)
        mi_zero_correction += p_yx * (Math.log(pmi_numerator) - Math.log(pmi_denominator));
      else
        assert p_yx == 0;
    }

    public double mi() {
      return (mi_zero - mi_zero_correction) + mi_nonzero;
    }

    @Override
    public String toString() {
      return String.format("%.4f [=(%.4f - %.4f) + %.4f]",
          mi(), mi_zero, mi_zero_correction, mi_nonzero);
    }
  }

  /** Values derived from counts in {@link TemplateIG} */
  public static class MISummary implements Serializable {
    private static final long serialVersionUID = 3888066629569424209L;

    // What is being described
    public int templateInt;
    public String templateName;
    // Values of interest

    public MI miEmpirical = new MI();
    public MI miSmoothed = new MI();
//    public double h_yx;   // TODO add this
    public double h_y_p, h_y;
    public double h_x_p, h_x;

    // Providence
    public double alpha_yx_p;
    public double alpha_y_p;
    public double alpha_x_p;
    public double alpha_y;
    public double alpha_x;
    public double C;    // sum of counts
    public int numInstances;
    public int hashinigDim;

    @Override
    public String toString() {
      return "(MISummary"
//          + " templateInt=" + templateInt
//          + " templateName=" + templateName
          + " miEmpirical=" + miEmpirical
          + " miSmoothed=" + miSmoothed
          + " h_y_p=" + h_y_p
          + " h_y=" + h_y
          + " h_x_p=" + h_x_p
          + " h_x=" + h_x
          + " alpha_yx_p=" + alpha_yx_p
          + " alpha_y_p=" + alpha_y_p
          + " alpha_x_p=" + alpha_x_p
          + " alpha_y=" + alpha_y
          + " alpha_x=" + alpha_x
          + " C=" + C
          + " numInstances=" + numInstances
          + " hashinigDim=" + hashinigDim
          + ")";
    }
  }

  public static class TemplateIG implements Serializable {
    private static final long serialVersionUID = 1287953772086645433L;
    public static int HASHING_DIM = 4 * 512 * 1024;
    public static boolean FULL_BAYESIAN_H = false;
    public static boolean ADD_UNOBSERVED = false;
    private int index;
    private String name;

    private IntIntDenseVector cy, cx;
    private Counts<IntPair> cyx;    // |Y| ~= 20  |X| ~= 20 * 10,000
    private int updates;
    private MISummary igCache = null;

    public double alpha_yx_p = 2500;   // should be ~= (alpha_y_p * alpha_x_p)
    public double alpha_y_p = 50;
    public double alpha_x_p = 50;
    public double alpha_y = 5;
    public double alpha_x = 5;

    public TemplateIG(int index) {
      this(index, "template-" + index);
    }

    public TemplateIG(int index, String name) {
      this.index = index;
      this.name = name;
      this.cy = new IntIntDenseVector();
      this.cx = new IntIntDenseVector();
      this.cyx = new Counts<>();
      this.updates = 0;
    }

    public int getIndex() {
      return index;
    }

    public String getName() {
      return name;
    }

    /**
     * if NEW_SMOOTHING and y==null, then set y to [[1], [2], ..., [numRoles]]
     * and calls smi.addUnobserved instead of smi.add.
     */
    public void update(int[] y, ProductIndex[] x) {
      if (y != null) {
        for (int yy : y) {
          for (ProductIndex xpi : x) {
            int xx;
            if (xpi.getArity() == 1) {
              // InformationGain only generates single template features which
              // should easily fit in an int.
              assert xpi.getProdFeature() < ((long) Integer.MAX_VALUE);
              xx = (int) xpi.getProdFeature();
              assert xx >= 0;
            } else {
              // InformationGainProducts generates product features which may
              // overflow an int.
              //              xx = xpi.getHashedProdFeatureNonNegative();
              xx = xpi.getHashedProdFeatureModulo(HASHING_DIM);
              assert xx >= 0;
            }
            cyx.increment(new IntPair(yy, xx));
            cy.add(yy, 1);
            cx.add(xx, 1);
            updates++;
          }
        }
      } else if (ADD_UNOBSERVED) {
        throw new RuntimeException("implement me");
      }
      igCache = null;
    }

    public int numUpdates() {
      return updates;
    }

    public MISummary ig() {
      if (igCache == null) {
        igCache = new MISummary();
        igCache.alpha_yx_p = alpha_yx_p;
        igCache.alpha_y_p = alpha_y_p;
        igCache.alpha_x_p = alpha_x_p;
        igCache.alpha_y = alpha_y;
        igCache.alpha_x = alpha_x;
        igCache.C = updates;
        igCache.hashinigDim = HASHING_DIM;
        igCache.numInstances = updates;
        igCache.templateInt = index;
        igCache.templateName = name;
        MI emp = igCache.miEmpirical;
        MI smo = igCache.miSmoothed;

        final double C_yx = updates, C_y = updates, C_x = updates;
        //          int D_y = cy.getNumImplicitEntries();
        //          int D_x = cx.getNumExplicitEntries();
        int D_y = 0;
        int D_x = 0;
        for (int i = 0; i < cy.getNumImplicitEntries(); i++)
          if (cy.get(i) > 0)
            D_y++;
        for (int i = 0; i < cx.getNumImplicitEntries(); i++)
          if (cx.get(i) > 0)
            D_x++;
        final double Z_yx_p = C_yx + alpha_yx_p;
        final double Z_y_p = C_y + alpha_y_p * D_y;
        final double Z_x_p = C_x + alpha_x_p * D_x;
        final double Z_y = C_y + alpha_y * D_y;
        final double Z_x = C_x + alpha_x * D_x;

        // c(y,x)=0
        for (int i = 0; i < D_y; i++) {
          double c_y = cy.get(i);
          double p_y = (c_y + alpha_y) / Z_y;
          igCache.h_y += p_y * -Math.log(p_y);
          double p_y_p = (c_y + alpha_y_p) / Z_y_p;
          igCache.h_y_p += p_y_p * -Math.log(p_y_p);
        }
        for (int i = 0; i < D_x; i++) {
          double c_x = cx.get(i);
          double p_x = (c_x + alpha_x) / Z_x;
          igCache.h_x += p_x * -Math.log(p_x);
          double p_x_p = (c_x + alpha_x_p) / Z_x_p;
          igCache.h_x_p += p_x_p * -Math.log(p_x_p);
        }
        smo.updateMiZero( (alpha_yx_p/Z_yx_p) * (
            Math.log(alpha_yx_p)
            - igCache.h_y_p
            - igCache.h_x_p
            + igCache.h_y
            + igCache.h_x
            -Math.log(Z_yx_p)
            ));

        // c(y,x)>0
        for (Entry<IntPair, Integer> c : cyx.entrySet()) {
          double c_yx = c.getValue();
          double c_y = cy.get(c.getKey().first);
          double c_x = cx.get(c.getKey().second);
          double n_y = c_y + alpha_y;
          double n_x = c_x + alpha_x;

          // Empirical
          double pyx_emp = c_yx / C_yx;
          double num_emp = (c_yx + 0) * (C_y + 0) * (C_x + 0);
          double den_emp = (C_yx + 0) * (c_y + 0) * (c_x + 0);
          emp.updateMiNonzero(pyx_emp, num_emp, den_emp);
          // Note: We don't have to add a correction term for empirical dist.


          // Smoothed
          double py_hat = (c_y + alpha_y_p) / Z_y_p;
          double px_hat = (c_x + alpha_x_p) / Z_x_p;
          double pyx_asym_backoff = py_hat * px_hat;
          double n_yx_p = c_yx + alpha_yx_p * pyx_asym_backoff;
          double p_yx = n_yx_p / Z_yx_p;

          double pmi_num = n_yx_p * Z_y * Z_x;
          double pmi_denom = Z_yx_p * n_y * n_x;
          smo.updateMiNonzero(p_yx, pmi_num, pmi_denom);

          // Correction
          double n_yx_p_zero = 0 + alpha_yx_p * pyx_asym_backoff;
          double pmi_num_cor =   n_yx_p_zero * Z_y * Z_x;
          double p_yx_zero = n_yx_p_zero / Z_yx_p;
          smo.updateMiZeroCorrection(p_yx_zero, pmi_num_cor, pmi_denom);
        }
      }
      return igCache;
    }

    public static Comparator<TemplateIG> BY_IG_DECREASING = new Comparator<TemplateIG>() {
      @Override
      public int compare(TemplateIG o1, TemplateIG o2) {
        double mi1 = o1.ig().miSmoothed.mi();
        double mi2 = o2.ig().miSmoothed.mi();
        double d = mi2 - mi1;
        if (d > 0) return 1;
        if (d < 0) return -1;
        return 0;
      }
    };
  }

  // |XY| = 4M
  // |T| ~= 1000
  // |XYT| = 4B ints = 16B bytes
  protected TemplateIG[] templates;
  protected Set<String> ignoreSentenceIds;
  protected int numRoles;

  public InformationGain() {
    this(-1);
  }

  /** You need to provide numRoles to get the proper OOV counting */
  public InformationGain(int numRoles) {
    ExperimentProperties config = ExperimentProperties.getInstance();
    templates = new TemplateIG[config.getInt("numTemplates", 3000)];  // TODO resize code
    for (int i = 0; i < templates.length; i++)
      templates[i] = new TemplateIG(i);

    this.numRoles = numRoles;

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

  @Override
  public void observeLine(String line) {
    String[] toks = line.split("\t");
    FeatureFile.Line l = new FeatureFile.Line(line, true);
    String sentenceId = toks[1];
    if (ignoreSentenceIds.contains(sentenceId)) {
      if (numRoles < 1)
        return;
      for (TemplateExtraction te : l.groupByTemplate())
        templates[te.template].update(null, te.featureToProductIndex());
    } else {
      // y = vector of roles (probably just one, but FN may have >1)
      // x_t = vector of extracted values for template t
      String[] kss = toks[4].split(",");
      int[] ksi = new int[kss.length];
      for (int i = 0; i < kss.length; i++)
        ksi[i] = Integer.parseInt(kss[i]) + 1;  // +1 b/c -1 means no role

      for (TemplateExtraction te : l.groupByTemplate())
        templates[te.template].update(ksi, te.featureToProductIndex());
    }
  }

  public List<TemplateIG> getTemplatesSortedByIGDecreasing() {
    List<TemplateIG> l = new ArrayList<>();
    for (TemplateIG t : templates)
      if (t.numUpdates() > 0)
        l.add(t);
    Collections.sort(l, TemplateIG.BY_IG_DECREASING);
    return l;
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    System.out.println("usage:");
    System.out.println("\tinputIG: serialized InformationGain file to read from [optional]");
    System.out.println("\tfeatures: int feature file produced by FeaturePrecomputation [optional]");
    System.out.println("\ttemplateAlph: alphabet file produced by FeaturePrecomputation for looking up template names [optional]");
    System.out.println("\ttopK: how many of the top templates to print [optional]");
    System.out.println("\toutputIG: where to serialize updated InformationGain");
    System.out.println("\toutputFeatures: text file for saving the templates/information gain");
    System.out.println("\tignoreSentenceIds: text file containing one sentence id per line to ignore");

    // stats + features -> stats
    // stats -> topK
    String inputStats = config.getString("inputIG", "none");
    final InformationGain input;
    if (inputStats.equals("none")) {
      Log.info("starting with empty stats");
      input = new InformationGain();
    } else {
      Log.info("reading stats from " + inputStats);
      input = (InformationGain) FileUtil.deserialize(new File(inputStats));
    }

    String features = config.getString("features", "none");
    if (!features.equals("none")) {
      Log.info("updating stats from " + features);
      input.run(new File(features));
    }

    String featuresGlob = config.getString("featuresGlob", "");
    if (!featuresGlob.isEmpty()) {
      File featuresParent = config.getExistingDir("featuresParent");
      PathMatcher pm = FileSystems.getDefault().getPathMatcher(featuresGlob);
      Files.walkFileTree(featuresParent.toPath(), new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
          if (pm.matches(path)) {
            Log.info("reading features: " + path.toFile().getPath() + "\t" + Describe.memoryUsage());
            input.run(path.toFile());
          }
          return FileVisitResult.CONTINUE;
        }
        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
          return FileVisitResult.CONTINUE;
        }
      });
    }

    // You dont want this: Alphabet loads all feature names, too big for memory
    Alphabet tNames = null;
//    String tNamesFile = config.getString("templateAlph", "none");
//    if (!tNamesFile.equals("none")) {
//      Log.info("loading template names from " + tNamesFile);
//      boolean header = false;
//      tNames = new Alphabet(new File(tNamesFile), header);
//    }

    // This should fit much easiser in memory
    BiAlph tNames2 = null;
    String tNames2File = config.getString("bialph", "");
    if (!tNames2File.isEmpty())
      tNames2 = new BiAlph(config.getExistingFile("bialph"), LineMode.ALPH);

    List<TemplateIG> templates = input.getTemplatesSortedByIGDecreasing();

    int topK = config.getInt("topK", 0);
    if (topK > 0)
      Log.info("top " + topK + " templates:");

    String outf = config.getString("outputFeatures", "none");
    BufferedWriter w = null;
    if (!outf.equals("none"))
      w = FileUtil.getWriter(new File(outf));

    // Loop over results
    Exception last = null;
    for (int i = 0; i < templates.size(); i++) {
      TemplateIG t = templates.get(i);
      String line;
      if (tNames != null) {
        line = t.ig() + "\t" + t.getIndex() + "\t" + tNames.get(t.getIndex()).name;
      } else if (tNames2 != null) {
        line = t.ig() + "\t" + t.getIndex() + "\t" + tNames2.lookupTemplate(t.getIndex());
      } else {
        line = t.ig() + "\t" + t.getIndex();
      }
      if (i < topK)
        System.out.println(line);
      if (w != null) {
        try {
          w.write(line);
          w.newLine();
        } catch (Exception e) {
          last = e;
        };
      }
    }
    if (last != null)
      last.printStackTrace();
    if (w != null)
      w.close();

    String output = config.getString("outputIG", "none");
    if (!output.equals("none")) {
      Log.info("serializing InformationGain object to " + output);
      try {
        FileUtil.serialize(input, new File(output));
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    Log.info("done");
  }

  public static long pack(int i1, int i2) {
    assert i1 >= 0 && i2 >= 0;
    return ((long) i1) << 32 | ((long) i2);
  }
  public static int unpack1(long i1i2) {
    return (int) (i1i2 >>> 32);
  }
  public static int unpack2(long i1i2) {
    long mask = 0xffffffff;
    return (int) (i1i2 & mask);
  }
}
