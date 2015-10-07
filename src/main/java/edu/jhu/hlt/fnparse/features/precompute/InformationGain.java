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

import edu.jhu.hlt.fnparse.features.precompute.FeatureFile.TemplateExtraction;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.LineByLine;
import edu.jhu.hlt.ml.regularization.dirmult.SmoothedMutualInformation;
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

  public static class TemplateIG implements Serializable {
    private static final long serialVersionUID = 1287953772086645433L;
    public static double ADD_LAMBDA_SMOOTHING = 1;
    public static boolean FULL_BAYESIAN_H = false;
    public static boolean ADD_UNOBSERVED = false;
    public static boolean NMI = false;
    public static boolean NEW_SMOOTHING = false;
    public static boolean NEW_SMOOTHING_ONLY_APPROX = false;
    public static boolean BACKOFF_TO_MARGINS = true;
    private int index;
    private String name;

    private IntIntDenseVector cy, cx;
    private Counts<IntPair> cyx;    // |Y| ~= 20  |X| ~= 20 * 10,000
    private int updates;
    private SmoothedMutualInformation<Integer, ProductIndex> smi;
    private Double igCache = null;

    public TemplateIG(int index) {
      this(index, "template-" + index);
    }

    public TemplateIG(int index, String name) {
      this.index = index;
      this.name = name;
      this.cy = new IntIntDenseVector();
      this.cx = new IntIntDenseVector();
      this.cyx = new Counts<>();
      this.smi = new SmoothedMutualInformation<>();
      this.updates = 0;
    }

    public int getIndex() {
      return index;
    }

//    /** @deprecated */
//    public void update(int y, int x) {
//      cyx.increment(new IntPair(y, x));
//      cy.add(y, 1);
//      cx.add(x, 1);
//      updates++;
//      igCache = null;
//    }

    /**
     * if NEW_SMOOTHING and y==null, then set y to [[1], [2], ..., [numRoles]]
     * and calls smi.addUnobserved instead of smi.add.
     */
    public void update(int[] y, ProductIndex[] x) {
      if (NEW_SMOOTHING || NMI) {
        if (y == null) {
          if (ADD_UNOBSERVED) {
            int numRoles = cy.getNumImplicitEntries();
            Integer[][] yy = new Integer[numRoles][1];
            for (int i = 0; i < numRoles; i++)
              yy[i] = new Integer[] {i};
            smi.addUnobserved(yy, x);
          }
        } else {
          // int -> Integer
          Integer[] yy = new Integer[y.length];
          for (int i = 0; i < y.length; i++)
            yy[i] = y[i];
          smi.add(yy, x);
        }
      } else {
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
                xx = xpi.getHashedProdFeatureModulo(16 * 512 * 1024);
                assert xx >= 0;
              }
              cyx.increment(new IntPair(yy, xx));
              cy.add(yy, 1);
              cx.add(xx, 1);
              updates++;
            }
          }
        }
      }
      igCache = null;
    }

    public int numUpdates() {
      return updates;
    }

    public static double sum(double[] terms) {
      double d = 0;
      for (double dd : terms)
        d += dd;
      return d;
    }

    public double ig() {
      if (igCache == null) {
        double ig = 0;
        if (NMI) {
          smi.smoothManual(ADD_LAMBDA_SMOOTHING);
          Log.info("computing MI for " + this.name);
          ig = smi.computeMutualInformation(this.name);
          Log.info("computing H for " + this.name);
          double hx = smi.getX().computeEntropy(smi.getAlphaX());
          Log.info("MI=" + ig + " Hx=" + hx + " NMI=" + (ig/hx) + " name=" + this.name);
          ig /= hx;
        } else if (NEW_SMOOTHING) {
          Log.info("smoothing counts for " + this.name);
          Log.info("\t" + smi);
//          if (NEW_SMOOTHING_ONLY_APPROX)
//            smi.smoothApprox();
//          else
//            smi.smooth();
          smi.smoothManual(ADD_LAMBDA_SMOOTHING);
          Log.info("computing MI for " + this.name);
          ig = smi.computeMutualInformation(this.name);
        } else if (FULL_BAYESIAN_H) {
          /*
           * Unfortuneatly, I think this is too slow to be practical.
           * Proving once again: you never go full Bayesian.
           * 
           * @deprecated This has not received updates in a while
           */
          int Tx = cx.getNumImplicitEntries();
          int Ty = cy.getNumImplicitEntries();
          int Tyx = Ty * Tx;
          for (int x = 0; x < Tx; x++) {
            double cx = this.cx.get(x) + ADD_LAMBDA_SMOOTHING;
            double nx = updates + Tx * ADD_LAMBDA_SMOOTHING;
            for (int y = 0; y < Ty; y++) {
              double cyx = this.cyx.getCount(new IntPair(y, x)) + ADD_LAMBDA_SMOOTHING;
              double cy = this.cy.get(y) + ADD_LAMBDA_SMOOTHING;
              double nyx = updates + Tyx * ADD_LAMBDA_SMOOTHING;
              double ny = updates + Ty * ADD_LAMBDA_SMOOTHING;
              // pmi = log(p(xy)) - [log(p(x)) + log(p(y))]
//              double pmi =
//                  (log(cyx) - log(nyx))
//                  - (log(cy) - log(ny))
//                  - (log(cx) - log(nx));
//              double pmi = log( (cyx/nyx) / ((cy*cx)/(ny*nx)) );
//              double pmi = log( (cyx/nyx) ) - log( ((cy*cx)/(ny*nx)) );
//              double pmi = log( (cyx * nx * ny) / (nyx * cy * cx) );
              double pmi = log( (cyx * nx * ny) ) - log( (nyx * cy * cx) );
              double pyx = cyx / nyx;
              ig += pyx * pmi;
            }
          }
        } else {
          /*
           * NOTE: IG = KL(p(XY), p(X)*p(Y)) = E_{p(XY)} PMI(X,Y)
           * NOTE: I have not gone full Bayesian!
           * If I had, I would have to use IG = KL(posterior(XY), margin(posterior(X))*margin(posterior(Y)))
           * If I did this, I would have to loop over all XY.
           * As it is, I'm using a frequentist estimate of PMI, meaning that c(YX)=0 => pmi=0
           * If I went full Bayesian, c(yx)=0 + prior => posterior(c(yx)) > 0
           */
          //        int Nt = cyx.numNonZero();    // this is less than the number of types!

          boolean verbose = true;

          double alpha_x = Math.exp(1); //ADD_LAMBDA_SMOOTHING;
          double alpha_y = Math.exp(1); //ADD_LAMBDA_SMOOTHING;
          double alpha_yx = alpha_y * alpha_x; //ADD_LAMBDA_SMOOTHING;

          double C_yx = updates, C_y = updates, C_x = updates;

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
          double D_yx = D_y * D_x;

          /*
           * I'm missing the area where c(yx)=0
           * Lets do the "subtract off" solution:
           * 1) assume c(yx)=0 everywhere
           * 2) subtract off bits you just added where c(yx)>0
           */
          // sum_yx pyx=constant * log(pyx=constant / (py * px))
          // pyx=constant * sum_yx { log(pyx=constant) - log(py) - log(px) }
          // pyx=constant * [D_yx*log(pyx=constant) - sum_yx log(py) - sum_yx log(px)]
          // pyx=constant * [D_yx*log(pyx=constant) - D_x*sum_y log(py) - D_y*sum_x log(px)]
          double pyx_zero = alpha_yx / (C_yx + alpha_yx * D_yx);
          double lpyx_zero = Math.log(pyx_zero);
          double sum_lpy_zero = 0;
          double sum_py_lpy_zero = 0;
          double h_y_smooth = 0;
          double h_y_emp = 0;
          for (int i = 0; i < D_y; i++) {
            double c_y = cy.get(i);
            if (c_y == 0)
              continue;
            double py = (c_y + alpha_y) / (C_y + alpha_y * D_y);
            double lpy = Math.log(py);
            sum_lpy_zero += lpy;
            sum_py_lpy_zero += py * lpy;
            h_y_smooth += py * -lpy;
            double py_emp = c_y / C_y;
            double lpy_emp = Math.log(py_emp);
            h_y_emp += py_emp * -lpy_emp;
          }
          double sum_lpx_zero = 0;
          double sum_px_lpx_zero = 0;
          double h_x_smooth = 0;
          double h_x_emp = 0;
          for (int i = 0; i < D_x; i++) {
            double c_x = cx.get(i);
            if (c_x == 0)
              continue;
            double px = (c_x + alpha_x) / (C_x + alpha_x * D_x);
            double lpx = Math.log(px);
            sum_lpx_zero += lpx;
            sum_px_lpx_zero += px * lpx;
            h_x_smooth += px * -lpx;
            double px_emp = c_x / C_x;
            double lpx_emp = Math.log(px_emp);
            h_x_emp += px_emp * -lpx_emp;
          }
          final double mi_zero = pyx_zero * (D_yx * lpyx_zero + (-D_x * sum_lpy_zero) + (-D_y * sum_lpx_zero));
          assert Double.isFinite(mi_zero) && !Double.isNaN(mi_zero);


          // This is estimating the partial MI on the c(y,x)=0 part of the distribution
          // where we assume that the backoff distribution for p(y,x) is p(y)p(x)
          // (note: I mean that we will backoff to our smoothed estimates, not
          //  the empirical ones -- though this shouldn't make too much of a
          //  difference on account of the fact that p(y) shouldn't be too high
          //  dimensional (easy to estimate) and p(x) can be estimated arbitrarily
          //  well by looking at unlabeled data).
          // See my math in 2015-10-06.txt
          double Z_yx = C_yx + alpha_yx;
          double[] mi_asym_zero_terms = new double[] {
              (alpha_yx/Z_yx) * Math.log(alpha_yx),    // 1
//              (alpha_yx/Z_yx) * sum_py_lpy_zero,       // 2
//              (alpha_yx/Z_yx) * sum_px_lpx_zero,       // 3
              -(alpha_yx/Z_yx) * Math.log(Z_yx),       // 4
//              -(alpha_yx / Z_yx) * sum_py_lpy_zero,    // y terms
//              -(alpha_yx / Z_yx) * sum_px_lpx_zero,    // x terms
          };
          double mi_asym_zero = sum(mi_asym_zero_terms);

          double h_yx_smooth_zero = (alpha_yx/Z_yx) *
              (Math.log(Z_yx) - (Math.log(alpha_yx) + sum_py_lpy_zero + sum_px_lpx_zero));

          // part where c(y,x)>0
          double mi_old = 0;
          double mi_empirical = 0;
          double mi_nonzero = 0;
          double mi_asym_nonzero = 0;
          double mi_asym_zero_correction = 0;
          double mi_zero_correction = 0;
          double h_yx_emp = 0;
          double h_yx_smooth_nonzero = 0;
          double h_yx_smooth_correction = 0;
          for (Entry<IntPair, Integer> c : cyx.entrySet()) {
            double countYX = c.getValue();
            double countY = cy.get(c.getKey().first);
            double countX = cx.get(c.getKey().second);
            double pyx = (countYX + alpha_yx) / (C_yx + D_yx * alpha_yx);
            assert pyx > 0 && pyx < 1;

            // Old code (works great)
            mi_old += pyx * (Math.log(countYX * updates) - Math.log(countY * countX));

            // Just an alias
            double c_yx = countYX, c_y = countY, c_x = countX;

            double numerator =    (c_yx + alpha_yx)        * (C_y + alpha_y * D_y) * (C_x + alpha_x * D_x);
            double denonminator = (C_yx + alpha_yx * D_yx) * (c_y + alpha_y)       * (c_x + alpha_x);
            double pmi = Math.log(numerator) - Math.log(denonminator);
            mi_nonzero += pyx * pmi;
            assert Double.isFinite(mi_nonzero) && !Double.isNaN(mi_nonzero);

            // empirical
            double pyx_emp = countYX / C_yx;
            double num_emp = (c_yx + 0) * (C_y + 0) * (C_x + 0);
            double den_emp = (C_yx + 0) * (c_y + 0) * (c_x + 0);
            mi_empirical += pyx_emp * (Math.log(num_emp) - Math.log(den_emp));
            h_yx_emp += pyx_emp * -Math.log(pyx_emp);

            // subtract off double add under assumption of c(y,x)=0
            // TODO Use more than one aggregator for numerical stability.
            double numerator_zero =    (0 + alpha_yx)           * (C_y + alpha_y * D_y) * (C_x + alpha_x * D_x);
            double denonminator_zero = (C_yx + alpha_yx * D_yx) * (c_y + alpha_y)       * (c_x + alpha_x);
            double pmi_zero = Math.log(numerator_zero) - Math.log(denonminator_zero);
            mi_zero_correction += pyx_zero * pmi_zero;
            assert Double.isFinite(mi_zero_correction) && !Double.isNaN(mi_zero_correction);

            // Assymetric prior on p(y,x) -- backoff to p(y)p(x)
            double py_hat = (countY + alpha_y) / (C_y + alpha_y * D_y);
            double px_hat = (countX + alpha_x) / (C_x + alpha_x * D_x);
            double pyx_asym_backoff = py_hat * px_hat;
            double pyx_asym =
                (countYX + alpha_yx * pyx_asym_backoff) / (C_yx + alpha_yx);
            // Since pyx_asym_backoff is a proper probability distribution
            // (this is because px_hat and py_hat and are proper. they are
            // proper because they are normalized properly), the sum is one,
            // so the denominator doesn't need a D_yx term in it (its 1).
            double num_asym =   (c_yx + alpha_yx * pyx_asym_backoff) * (C_y + alpha_y * D_y) * (C_x + alpha_x * D_x);
            double denom_asym = (C_yx + alpha_yx)                    * (c_y + alpha_y)       * (c_x + alpha_x);
            double pmi_asym = Math.log(num_asym) - Math.log(denom_asym);
            mi_asym_nonzero += pyx_asym * pmi_asym;
            // Correction
            double num_asym_cor =   (0    + alpha_yx * pyx_asym_backoff) * (C_y + alpha_y * D_y) * (C_x + alpha_x * D_x);
            double denom_asym_cor = (C_yx + alpha_yx)                    * (c_y + alpha_y)       * (c_x + alpha_x);
            double pmi_asym_cor = Math.log(num_asym_cor) - Math.log(denom_asym_cor);
            double pyx_asym_zero = 
                (0 + alpha_yx * pyx_asym_backoff) / (C_yx + alpha_yx);
            mi_asym_zero_correction += pyx_asym_zero * pmi_asym_cor;

            h_yx_smooth_nonzero += pyx_asym * -Math.log(pyx_asym);
            h_yx_smooth_correction += pyx_asym_zero * -Math.log(pyx_asym_zero);
          }

          ig = (mi_zero - mi_zero_correction) + mi_nonzero;
          double mi_asym = (mi_asym_zero - mi_asym_zero_correction) + mi_asym_nonzero;
          double h_yx_smooth = (h_yx_smooth_zero - h_yx_smooth_correction) + h_yx_smooth_nonzero;
          if (BACKOFF_TO_MARGINS)
            ig = mi_asym;

//          Log.info(this.name + ": ig_zero=" + ig_zero + " ig_zero_correction=" + ig_zero_correction + " ig_nonzero=" + ig_nonzero + " ig=" + ig);

          if (verbose) {
            System.out.println("method=old mi=" + ig
                + " mi_nonzero=" + mi_nonzero + " mi_zero=" + mi_zero + " mi_zero_correction=" + mi_zero_correction
                + " mi_old=" + mi_old
                + " mi_empirical=" + mi_empirical
                + " h_x_smooth=" + h_x_smooth + " h_y_smooth=" + h_y_smooth + " h_yx_smooth=" + h_yx_smooth
                + " h_x_emp=" + h_x_emp + " h_y_emp=" + h_y_emp + " h_yx_emp=" + h_yx_emp
                + " mi_asym=" + mi_asym
                + " mi_asym_nonzero=" + mi_asym_nonzero
                + " mi_asym_zero=" + mi_asym_zero
                + " mi_asym_zero_correction=" + mi_asym_zero_correction
                + " mi_entropy_reg_sub=" + (mi_empirical - h_x_smooth/10)
                + " mi_entropy_reg_div=" + (mi_empirical / (1 + h_x_smooth))
                + " pyx_zero=" + pyx_zero + " sum_lpy_zero=" + sum_lpy_zero + " sum_lpx_zero=" +  sum_lpx_zero
                + " D_y=" + D_y + " D_x=" + D_x + " D_yx=" + D_yx
                + " C_y=" + C_y + " C_x=" + C_x + " C_yx=" + C_yx
                + " alpha_y=" + alpha_y + " alpha_x=" + alpha_x + " alpha_yx=" + alpha_yx
                + " arity=" + name.split("\\*").length
                + " name=" + name
                );
          }

          if (NMI) {
            ig /= h_x_smooth;
          }
        }
        igCache = ig;
      }
      return igCache;
    }
    public static final double LOG2 = Math.log(2);
    public static double log(double x) {
      return Math.log(x) / LOG2;
    }
    public static Comparator<TemplateIG> BY_IG_DECREASING = new Comparator<TemplateIG>() {
      @Override
      public int compare(TemplateIG o1, TemplateIG o2) {
        double d = o2.ig() - o1.ig();
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

    TemplateIG.ADD_LAMBDA_SMOOTHING =
        config.getDouble("ig.addLambda", TemplateIG.ADD_LAMBDA_SMOOTHING);

    TemplateIG.NEW_SMOOTHING =
        config.getBoolean("NEW_SMOOTHING", true);

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

    Alphabet tNames = null;
    String tNamesFile = config.getString("templateAlph", "none");
    if (!tNamesFile.equals("none")) {
      Log.info("loading template names from " + tNamesFile);
      tNames = new Alphabet(new File(tNamesFile));
    }

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
      if (tNames == null)
        line = t.ig() + "\t" + t.getIndex();
      else
        line = t.ig() + "\t" + t.getIndex() + "\t" + tNames.get(t.getIndex()).name;
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
