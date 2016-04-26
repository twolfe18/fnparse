package edu.jhu.hlt.fnparse.features.precompute.featureselection;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import edu.jhu.hlt.fnparse.features.precompute.Alphabet;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph;
import edu.jhu.hlt.fnparse.features.precompute.BiAlphMerger;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile.Line;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile.TemplateExtraction;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.LineByLine;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;

/**
 * Uses output of {@link FeaturePrecomputation} to compute IG for feature selection.
 *
 * Deprecated Use {@link InformationGainProducts} instead.
 * Previously {@link TemplateIG} was located here, which is not deprecated.
 *
 *
 * Every line in a {@link FeatureFile} corresponds to a (target:Span,arg:Span)
 * and associated features. The "label"/Y is the role (meaning "ARG2" for PB
 * and "Buyer" in FN.
 *
 * One option I didn't consider, is having y=[r,fr]
 * Would like to compute I(r,f(t,s)) + I(fr,f(t,s))
 * uhh....
 * I think I really want something like:
 *   1: I(r, f(t,s) | f)
 * This corresponds to a pipeline where you predict/know f before predicting r.
 * OR, you could have:
 *   2: I(fr, f(t,s))
 * Which corresponds to a pipeline where you could just pick t, not label f,
 * pick s, THEN pick (f,k) in one fell swoop. (You would need some sort of
 * decoding constraint that same(f for (f,k) in frameInst.frameRoles))
 *
 * 1 is more realistic and simple, so I'll use that.
 * The "|f" part means that counts from "f=foo" should not interfere with
 * counts from "f=bar".
 * In fact, we should have a IG number for every frame!
 * Lets try to list the axes of our tensor:
 * - features along a template (we want a score(template))
 * - frames in the index
 * - roles for a given frame
 * score(template) =
 *  \acc_{g \in template}       -- this loop can float up and down
 *    \acc_{f \in frames}
 *      \acc_{k \in f.roles}    -- this loop is dependent on f, must be lower
 *        Y = bernoilli:   yes/no for (f,k) at a particular (t,s)
 *        X = multinomial: g(t,s,sentence)
 *        I(Y,X)
 * The way to justify this is that you have to choose what a draw from your random
 * variables are, which is maybe an more intuitive way to choose the types of the RVs.
 * In this case, we had to choose draw=(t,s) since this is what a feature/template
 * works on. If we made it any "bigger" then there is no obvious way to merge
 * feature vectors (multinomials). If we made it "smaller" then we would not have
 * enough information to compute the features.
 *
 * The standard thing to do is to use \acc=E_{empirical dist} always.
 * This is harsh on templates which instantiate a few good features and many
 * bad ones: the bad ones bring down the average, even if a discriminative
 * learner could set their weight to 0 later (L1 seems particularly relevant).
 *
 * Lets use h for template.
 * We have a table (h,g,f,k) with rows ordered by I(Y,X).
 * If you said "we are now working on data where only f=foo appears, give me" a
 * good feature set, we would sub-set only the rows of out I(Y,X) table that are
 * relevant and then re-run our select(h) mechanism.
 * Un-relatedly, if we knew h=foo worked really well for f=bar but not f=baz,
 * we might still include it and hope that L1 helps weights(h,f=baz,*) go to 0.
 * We are going to need some proceedure to get this down to a table of just h.
 * (h,g,f,k,count,I) -> (h,g,f,count,I) by some mix of expectation and max
 * (h,g,f,count,I) -> (h,g,count,I) by some mix of expectation and max
 * etc.
 *
 * Joint reduction.
 * Lets say we've gotten down to (h,f) and we want to get down to (h,)
 * We want to solve this as a sort of set-cover problem.
 * E.g. if h1,h2,h3 are all very good, we only have room for two templates,
 * and h1,h2 are primarily "good" on the same frames f, then maybe we could take
 * h1,h3 or h2,h3.
 * This is like a portfolio optimization problem where the analogy is between
 * "rate of return" and "mutual information". In portfolio optimization, the
 * simplest thing to do is minimize the pairwise variances of the stocks (templates)
 * in your portfolio.
 * Q = covariance matrix between stocks/templates
 * c = expected return or negative mutual information
 * a = stock cost or entropy of the template
 * b = budget in dollars or entropy
 * x = vector of how much/whether to buy/include stock/template
 * QP problem:
 *   \arg\min_x x^t Q x + x^t c
 *       s.t.   diag(a) x <= b
 * The trouble, as in all joint inference, is getting the problem to be small
 * enough such that you can compute Q, which is O(n^2) in the size of the solution.
 * After doing naive ranking and filtering by name similarity, we might get
 * length(x) down to 20K, which would mean that there are 400M entries in Q.
 * Even if you sharded this across 400 jobs, this means that every job needs to
 * estimate a correlation between 1M template pairs. If we assume that we need
 * to look at 1K instances (t,s) to get a reasonable covariance estimate, then
 * each job needs to do 1B operations.
 *
 * => Template correlation is not even defined a priori!
 *    You could measure correlation between two binary features, but to get an
 *    association score between templates you would need some lifting operation.
 *
 * Note that this approach is distinct from classic dimensionality reduction
 * like "just run PCA on all the features". The drawback of PCA is that it doesn't
 * give you any run-time speedup: you have to extract ALL of the features. It
 * does convey a learning benefit, that you only have to learn weights on the
 * top K eigen dimensions.
 *
 *
 * How to interpolate between E_p[x] and \arg\max f(x)
 * assume f(x) is between 0 and 1, but not necessarily a probability
 * Take expectation w.r.t.: (c0 + c1 * f^d1 + c2 * p^d2)^2
 * 1 means uniform distribution
 * ^0 takes any distribution and makes it uniform
 * ^inf takes any distribution and puts all the mass on the max
 * alpha and beta are params you can choose.
 * E_p[x] using         {c0=0, c1=0, d1=*, c2=1, d2=0.5} => (0 + 0 + p^0.5)^2 = p
 * \arg\max f(x) using  {c0=0, c1=1, d1=0.5, c2=0, d2=*} => (0 + f^0.5 + 0)^2 = f
 * Ok, this quadratic form is not essential, and perhaps a bit more complex than needed.
 * I could just directly target the terms I want:
 * p + f + (p^0.5)*(f^0.5)
 * or p + f + harmonicMean(p,f) = p + f + 2*p*f/(p+f)
 *
 * We can extend this to higher dimensions, e.g. assume
 * p = p(y,x)
 * Normalizing (by range for f and by sum for p) depends on whether you think of
 * products as one big dist or not.
 * Lets use our loops to tell us what is flat and what is not.
 * => if \acc_{g \in templates} is on the outside, then there is no p(g)
 *    we can however get p(g;f,r) = proportion of times g shows up in all (t,s) s.t. Y=(f,r))
 *
 *
 * NOTE: This version was created before bialph merging. This may be good enough
 * for getting a rough top K list of templates, but if not, see
 * {@link InformationGainProducts} for how to read many feature files that don't
 * shard a common indexing scheme (you map with a bialph created by {@link BiAlphMerger}).
 *
 * @author travis
 */
public class InformationGain implements Serializable, LineByLine {
  private static final long serialVersionUID = -5727222496637587197L;

  // On disk frames/roles use -1 for "no frame/role", and 0 and up for real values.
  // Various methods that retrieve data from disk will take an addOne option,
  // and for now I think they must all agree.
  // Be careful if you want to refer to a frame/role by index, should check this
  // and adjust accordingly.
  public static final boolean ADD_ONE = true;

  public static Function<FeatureFile.Line, int[]> getRoles(boolean addOne) {
//    return ffl -> {
//      return ffl.getRoles(addOne);  // addOne=true takes -1 (no role) to 0
//    };
    return new Function<FeatureFile.Line, int[]>() {
      @Override
      public int[] apply(Line ffl) {
        return ffl.getRoles(addOne);  // addOne=true takes -1 (no role) to 0
      }
      @Override
      public String toString() {
        return "getRoles";
      }
    };
  }
  public static Function<FeatureFile.Line, int[]> getFrames(boolean addOne) {
//    return ffl -> {
//      return ffl.getFrames(addOne); // addOne=true takes -1 (no frame) to 0
//    };
    return new Function<FeatureFile.Line, int[]>() {
      @Override
      public int[] apply(Line ffl) {
        return ffl.getFrames(addOne); // addOne=true takes -1 (no frame) to 0
      }
      @Override
      public String toString() {
        return "getFrames";
      }
    };
  }
  public static Function<FeatureFile.Line, int[]> getPosLabel() {
    return new Function<FeatureFile.Line, int[]>() {
      @Override
      public int[] apply(Line ffl) {
        return ffl.getPosLabel();
      }
      @Override
      public String toString() {
        return "getPosLabel";
      }
    };
  }

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

  public static class MIFixed extends MI {
    private static final long serialVersionUID = 6352049409141433353L;
    public double mi_set;
    public MIFixed(double mi_set) {
      super();
      this.mi_set = mi_set;
    }
    @Override
    public double mi() {
      return mi_set;
    }
  }

  /** Values derived from counts in {@link TemplateIG} */
  public static class MISummary implements Serializable {
    private static final long serialVersionUID = 3888066629569424209L;

    // What is being described
    public int templateInt;
    public String templateName;
    // Values of interest

    public MI miEmpirical = null;
    public MI miSmoothed = null;
    public double h_yx;
    public double h_y_p, h_y, h_y_emp;
    public double h_x_p, h_x, h_x_emp;

    // Fraction of the time this feature fired
    public double selectivity;

    // Providence
    public double alpha_yx_p;
    public double alpha_y_p;
    public double alpha_x_p;
    public double alpha_y;
    public double alpha_x;
    public double C;    // sum of counts
    public int numInstances;
    public int hashinigDim;

    public double mi() {
      if ((miEmpirical == null) == (miSmoothed == null))
        throw new RuntimeException("empNull=" + (miEmpirical==null) + " smoNull=" + (miSmoothed==null));
      if (miEmpirical != null)
        return miEmpirical.mi();
      return miSmoothed.mi();
    }

    @Override
    public String toString() {
      return "(MISummary"
//          + " templateInt=" + templateInt
//          + " templateName=" + templateName
          + " miEmpirical=" + miEmpirical
          + " miSmoothed=" + miSmoothed
          + " h_y_p=" + h_y_p
          + " h_y=" + h_y
          + " h_y_emp=" + h_y_emp
          + " h_x_p=" + h_x_p
          + " h_x=" + h_x
          + " h_x_emp=" + h_x_emp
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

  // |XY| = 4M
  // |T| ~= 1000
  // |XYT| = 4B ints = 16B bytes
  protected TemplateIG[] templates;
  protected Set<String> ignoreSentenceIds;
  protected int numRoles;
  protected BubEntropyEstimatorAdapter bubEst;
  protected EntropyMethod entropyMethod;

  // If >=0, then only consider (t,s) s.t. (f,k) match these restrictions
  // What to count. This could, e.g. extract the frame for an instance if you
  // are doing feature selection for frame id, or roles if you are doing SRL.
  // See FeaturePrecomputation for what fields are available.
  // You can also use this to FILTER the instances used for computing IG:
  // if this method returns null, that line is skipped.
  protected Function<FeatureFile.Line, int[]> getY;

  public InformationGain(BubEntropyEstimatorAdapter bubEst, EntropyMethod em, Function<FeatureFile.Line, int[]> getY) {
    this(-1, bubEst, em, getY);
  }

  /** You need to provide numRoles to get the proper OOV counting */
  public InformationGain(int numRoles, BubEntropyEstimatorAdapter bubEst, EntropyMethod em, Function<FeatureFile.Line, int[]> getY) {
    ExperimentProperties config = ExperimentProperties.getInstance();
    this.templates = new TemplateIG[0];
    this.numRoles = numRoles;
    this.bubEst = bubEst;
    this.entropyMethod = em;
    this.getY = getY;

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

  private TemplateIG getTemplate(int t) {
    if (t >= templates.length) {
      int n = Math.max(t + 1, (int) (templates.length * 1.6 + 1));
      Log.info("making templates larger: templates.length=" + templates.length + " curTemplate=" + t + " newSize=" + n);
      templates = Arrays.copyOf(templates, n);
    }
    TemplateIG ut = templates[t];
    if (ut == null) {
      ut = new TemplateIG(t, numRoles, entropyMethod, getY);
      ut.useBubEntropyEstimation(bubEst);
      templates[t] = ut;
    }
    return ut;
  }

  @Override
  public void observeLine(String line) {
    FeatureFile.Line l = new FeatureFile.Line(line, true);
    String sentenceId = l.getSentenceId();
    if (ignoreSentenceIds.contains(sentenceId)) {
      if (numRoles < 1)
        return;
//      for (TemplateExtraction te : l.groupByTemplate()) {
//        getTemplate(te.template).update(null, te.featureToProductIndex());
//      }
      throw new RuntimeException("re-implement me");
    } else {
      // y = vector of frames/roles, see getY (probably just one, but FN may have >1)
      // x_t = vector of extracted values for template t
      int[] ys = getY.apply(l);
      if (ys != null) {
        for (TemplateExtraction te : l.groupByTemplate())
          for (int y : ys)
            getTemplate(te.template).update(y, te.featureToProductIndex());
      }
    }
  }

  public List<TemplateIG> getTemplatesSorted(Comparator<TemplateIG> cmp) {
    TimeMarker tm = new TimeMarker();
    int nt = 0;
    for (int i = 0; i < templates.length; i++)
      if (templates[i] != null && templates[i].totalCount() > 0)
        nt++;
    List<TemplateIG> l = new ArrayList<>();
    for (TemplateIG t : templates) {
      if (t == null || t.totalCount() == 0)
        continue;
      t.ig();
      l.add(t);
      if (tm.enoughTimePassed(15)) {
        Log.info("took " + tm.secondsSinceFirstMark()
            + " seconds to compute MI for "
            + l.size() + " of " + nt + " templates");
      }
    }
    Collections.sort(l, cmp);
    Log.info("done");
    return l;
  }

  public static Function<FeatureFile.Line, int[]> getGetY(ExperimentProperties config) {
    String l = config.getString("labelType");
//    Log.info("computing information gain w.r.t. " + l);
    Log.info("labelType=" + l);
    switch (l) {
    case "frame":
    case "frames":
      return getFrames(ADD_ONE);
    case "role":
    case "roles":
      return getRoles(ADD_ONE);
    case "pos":
    case "binary":
      return getPosLabel();
    default:
      throw new RuntimeException("unknown label: " + l);
    }
  }

  /**
   * @deprecated see {@link InformationGainProducts#main(String[])}
   */
  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    System.out.println("usage:");
    System.out.println("\tfeatures: int feature file produced by FeaturePrecomputation [optional]");
    System.out.println("\ttemplateAlph: alphabet file produced by FeaturePrecomputation for looking up template names [optional]");
    System.out.println("\ttopK: how many of the top templates to print [optional]");
    System.out.println("\toutputIG: where to serialize updated InformationGain");
    System.out.println("\toutputFeatures: text file for saving the templates/information gain");
    System.out.println("\tignoreSentenceIds: text file containing one sentence id per line to ignore");

    EntropyMethod em = EntropyMethod.valueOf(
        config.getString("entropyMethod"));//, EntropyMethod.BUB.name()));
    Log.info("using " + em + " to estimate entropies");

    Function<FeatureFile.Line, int[]> getY = getGetY(config);

    final File bubFuncParentDir = config.getExistingDir("bubFuncParentDir");
    Log.info("using BUB code in " + bubFuncParentDir.getPath());
    try (BubEntropyEstimatorAdapter bubEst = new BubEntropyEstimatorAdapter(bubFuncParentDir)) {
      bubEst.debug = config.getBoolean("bubDebug", false);

      int numRoles = config.getInt("numRoles", 30);
      final InformationGain input = new InformationGain(numRoles, bubEst, em, getY);

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

      Log.info("computing mutual information...");
      List<TemplateIG> templates = input.getTemplatesSorted(TemplateIG.BY_NMI_DECREASING);

      // You dont want this: Alphabet loads all feature names, too big for memory
      Alphabet tNames = null;
      //    String tNamesFile = config.getString("templateAlph", "none");
      //    if (!tNamesFile.equals("none")) {
      //      Log.info("loading template names from " + tNamesFile);
      //      boolean header = false;
      //      tNames = new Alphabet(new File(tNamesFile), header);
      //    }

      // This should fit much easiser in memory (only stores template names)
      BiAlph tNames2 = null;
      String tNames2File = config.getString("bialph", "");
      if (!tNames2File.isEmpty())
        tNames2 = new BiAlph(config.getExistingFile("bialph"), LineMode.ALPH);

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
        MISummary mi = t.ig();
        String prefix = String.format("%d\t%f\t%f\t%f\t%f\t%f",
            t.getIndex(), mi.miSmoothed.mi(), mi.h_x, mi.h_yx, mi.h_y, mi.selectivity);
        String line;
        if (tNames != null) {
          line = prefix + "\t" + tNames.get(t.getIndex()).name;
        } else if (tNames2 != null) {
          line = prefix + "\t" + tNames2.lookupTemplate(t.getIndex());
        } else {
          line = prefix;
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

      Log.info("closing matlab/bub connection");
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
