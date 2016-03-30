package edu.jhu.hlt.fnparse.features.precompute.featureselection;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.featureselection.InformationGain.MI;
import edu.jhu.hlt.fnparse.features.precompute.featureselection.InformationGain.MIFixed;
import edu.jhu.hlt.fnparse.features.precompute.featureselection.InformationGain.MISummary;
import edu.jhu.hlt.tutils.ProductIndex;
import edu.jhu.prim.map.LongIntEntry;
import edu.jhu.prim.map.LongIntHashMap;
import edu.jhu.prim.vector.IntIntDenseVector;

/**
 * Holds a template and statistics related to mutual information for it (x)
 * against all roles/labels (y), possibly with frame/frameRole restrictions.
 *
 * @author travis
 */
public class TemplateIG implements Serializable {
  private static final long serialVersionUID = 1287953772086645433L;
  public static int HASHING_DIM = 4 * 512 * 1024;
  public static boolean ADD_UNOBSERVED = false;

  // Don't use hashing! I have run into problems where the BUB code cannot
  // handle m > Integer.MAX_INT, and thus you have to be careful with filtering
  // features, but I get very different entropy/MI estimates with and without
  // feature hashing.
  public static final boolean USE_HASHING = false;

  private EntropyMethod entropyMethod = EntropyMethod.BUB;

  // What template we're measuring
  private int index;
  private String name;

  public FeatureName featureName; // optionally set by users of this class

  // If >=0, then only consider (t,s) s.t. (f,k) match these restrictions
  // What to count. This could, e.g. extract the frame for an instance if you
  // are doing feature selection for frame id, or roles if you are doing SRL.
  // See FeaturePrecomputation for what fields are available.
  // You can also use this to FILTER the instances used for computing IG:
  // if this method returns null, that line is skipped.
  protected Function<FeatureFile.Line, int[]> getY;

  private IntIntDenseVector cy;
  // (y:int) * (x:ProductIndex) can be represented as a ProductIndex :)
  // ProductIndex can be represented exactly as a long (64 bits) without overflowing in reasonable situations
  // I can store counts for long keys in LongIntHashMap
  private LongIntHashMap cx, cyx;

  // Used in computing indices for cyx
  private int numY; // ensure that all values of y are less than this

  private int updates;
  private int observations, observationsWithSomeX;
  private MISummary igCache = null;

  // alpha_yx_p works differently from alpha_[yx](_p)?
  // The former is add alpha/D and the latter is add alpha
  double alpha_yx_p;   // should be ~= (alpha_y_p * alpha_x_p)
  double alpha_y_p;
  double alpha_x_p;
  double alpha_y;
  double alpha_x;

  private BubEntropyEstimatorAdapter bubEst;


  /**
   * If this class keeps track of c(y,x) where y is a D dimensional multinomial,
   * this performs the transform in D binomials.
   *
   * This is useful if you want to use this code to compute PMIs and another
   * external tool compute MI=E[PMI]; exploding produces TemplateIGs which store
   * PMIs.
   */
  public List<TemplateIG> explode() {

    if (getY instanceof NullLabelGetY) {
      // NullLabelGetY already does the exploding for you
      return Arrays.asList(this);
    }

    if (!getY.getClass().getName().contains("jhu")) {
      // Refinement.NONE
      return Arrays.asList(this);
    }

    List<TemplateIG> out = new ArrayList<>();
    int cySum = cy.getSum();
    int nY = cy.getNumExplicitEntries();
    for (int y = 0; y < nY; y++) {
      int c = cy.get(y);
      if (c == 0)
        continue;

      // I'm going to assume that getY is currently an @frame FrameRoleFilter
      Function<FeatureFile.Line, int[]> refinedGetY;
      if (getY instanceof FrameRoleFilter) {
        FrameRoleFilter gy = (FrameRoleFilter) getY;
        assert !gy.hasRoleRestriction() : "there should be nothing to refine if you have restricted to a single role already";
        int role = y;
        refinedGetY = new FrameRoleFilter(gy.getWrapped(), gy.getAddOne(), gy.getFrame(), role);
      } else {
        throw new RuntimeException("implement me");
      }
      int index = this.index; //new ProductIndex(y, numY).destructiveProd(this.index).getProdFeatureSafe();
      String name = this.name;// + "@" + y;
      TemplateIG yig = new TemplateIG(index, name, 2, entropyMethod, refinedGetY);
      yig.updates = updates;
      yig.observations = observations;
      yig.observationsWithSomeX = observationsWithSomeX;
      yig.igCache = null;
      yig.cy.add(0, cySum - c);
      yig.cy.add(1, c);
      Iterator<LongIntEntry> itr = cx.iterator();
      while (itr.hasNext()) {
        LongIntEntry e = itr.next();
        long x = e.index();
        ProductIndex xpi = new ProductIndex(x, 1);
        int ci_x = e.get();
        int ci_yx = cyx.get(index(y, numY, xpi));
        yig.cyx.add(index(0, 2, xpi), ci_x-ci_yx);
        yig.cyx.add(index(1, 2, xpi), ci_yx);
        yig.cx.add(index(xpi), ci_x);
      }
      out.add(yig);
    }
    return out;
  }


  public TemplateIG(int index, int numY, EntropyMethod em, Function<FeatureFile.Line, int[]> getY) {
    this(index, "template-" + index, numY, em, getY);
  }

  public TemplateIG(int index, String name, int numY, EntropyMethod em, Function<FeatureFile.Line, int[]> getY) {
    this.index = index;
    this.name = name;
    this.entropyMethod = em;
    this.getY = getY;
    this.cy = new IntIntDenseVector();
    this.cx = new LongIntHashMap();
    this.cyx = new LongIntHashMap();
    this.numY = numY;
    this.updates = 0;
    this.observations = 0;
    this.observationsWithSomeX = 0;

    if (em == EntropyMethod.MLE) {
      alpha_yx_p = 0;
      alpha_y_p = 0;
      alpha_x_p = 0;
      alpha_y = 0;
      alpha_x = 0;
    } else if (em == EntropyMethod.MAP) {
      alpha_yx_p = 50;
      alpha_y = alpha_y_p = 1;
      alpha_x = alpha_x_p = 1;
    }
  }

  public void useBubEntropyEstimation(BubEntropyEstimatorAdapter bubEst) {
    assert this.bubEst == null;
    assert bubEst != null;
    this.bubEst = bubEst;
  }

  public int getIndex() {
    return index;
  }

  public String getName() {
    return name;
  }

  public void update(FeatureFile.Line hasY, ProductIndex[] x) {
    int[] y = getY.apply(hasY);
    if (y != null) {
      for (int yy : y)
        update(yy, x);
    }
  }

  void update(int yy, ProductIndex[] x) {
    this.observations++;
    if (x.length > 0)
      this.observationsWithSomeX++;

    if (yy >= numY)
      throw new IllegalStateException("you set numY=" + numY + " and we just saw yy=" + yy);
    for (ProductIndex xpi : x) {
      cy.add(yy, 1);
//      if (USE_HASHING) {
//        cyx.add(xpi.prod(yy, numY).getProdFeatureModulo(HASHING_DIM), 1);
//        cx.add(xpi.getProdFeatureModulo(HASHING_DIM), 1);
//      } else {
//        cyx.add(xpi.prod(yy, numY).getProdFeature(), 1);
//        cx.add(xpi.getProdFeature(), 1);
//      }
      cyx.add(index(yy, numY, xpi), 1);
      cx.add(index(xpi), 1);
      updates++;
    }
    igCache = null;
  }

  private static long index(ProductIndex xpi) {
    if (USE_HASHING)
      return xpi.getProdFeatureModulo(HASHING_DIM);
    else
      return xpi.getProdFeature();
  }
  private static long index(int y, int numY, ProductIndex xpi) {
    ProductIndex xypi = xpi.prod(y, numY);
    if (USE_HASHING)
      return xypi.getProdFeatureModulo(HASHING_DIM);
    else
      return xypi.getProdFeature();
  }

  /**
   * How many times you called update.
   */
  public int numObservations() {
    return observations;
  }

  /**
   * Sum of the counts in all the tables.
   */
  public int totalCount() {
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

      igCache.selectivity = ((double) observationsWithSomeX) / observations;

      final double C_yx = updates, C_y = updates, C_x = updates;

      if (entropyMethod == EntropyMethod.BUB) {
//        Log.info("calling BUB estimator for H[y,x]\t" + Describe.memoryUsage());
        double hyx = bubEst.entropyUsingDimensionNNZ(cyx);
//        Log.info("calling BUB estimator for H[x]\t" + Describe.memoryUsage());
        double hx = bubEst.entropyUsingDimensionNNZ(cx);
//        Log.info("calling BUB estimator for H[y]\t" + Describe.memoryUsage());
        double hy = bubEst.entropy(cy);
        igCache.h_x = hx;
        igCache.h_y = hy;
        igCache.h_yx = hyx;
        igCache.miSmoothed = new MIFixed(hx + hy - hyx);
        igCache.miEmpirical = null;
        return igCache;
      } else if (entropyMethod == EntropyMethod.MLE) {
        double mi = 0;
        double hyx = 0;
        Iterator<LongIntEntry> itr = cyx.iterator();
        while (itr.hasNext()) {
          LongIntEntry e = itr.next();
          double c_yx = e.get();
          double c_y = cy.get((int) (e.index() % ((long) numY)));
          double c_x = cx.get(e.index() / ((long) numY));

          // p_yx * [ log(p_yx) - (log(py) + log(px)) ]
          double p_yx = c_yx / C_yx;
          double p_y = c_y / C_y;
          double p_x = c_x / C_x;
          mi += p_yx * Math.log(p_yx);
          mi -= p_yx * Math.log(p_y);
          mi -= p_yx * Math.log(p_x);
          hyx -= p_yx * Math.log(p_yx);
        }
        igCache.miEmpirical = new MIFixed(mi);
        igCache.miSmoothed = null;
        igCache.h_yx = hyx;

        double hx = 0;
        itr = cx.iterator();
        while (itr.hasNext()) {
          LongIntEntry e = itr.next();
          double c_x = e.get();
          double p_x = c_x / C_x;
          hx -= p_x * Math.log(p_x);
        }
        igCache.h_x_emp = igCache.h_x = hx;

      } else if (entropyMethod == EntropyMethod.MAP) {
        igCache.miEmpirical = null;
        MI smo = igCache.miSmoothed = new MI();
        int D_y = 0;
        for (int i = 0; i < cy.getNumImplicitEntries(); i++)
          if (cy.get(i) > 0)
            D_y++;
        int D_x = cx.size();   // TODO should be max index in cx2, not size?
        final double Z_yx_p = C_yx + alpha_yx_p;
        final double Z_y_p = C_y + alpha_y_p * D_y;
        final double Z_x_p = C_x + alpha_x_p * D_x;
        final double Z_y = C_y + alpha_y * D_y;
        final double Z_x = C_x + alpha_x * D_x;

        // c(y,x)=0
        for (int i = 0; i < D_y; i++) {
          double c_y = cy.get(i);
          if (c_y > 0) {
            double p_y_emp = c_y / C_y;
            igCache.h_y_emp += p_y_emp * -Math.log(p_y_emp);
          }
          double p_y = (c_y + alpha_y) / Z_y;
          igCache.h_y += p_y * -Math.log(p_y);
          double p_y_p = (c_y + alpha_y_p) / Z_y_p;
          igCache.h_y_p += p_y_p * -Math.log(p_y_p);
        }
        Iterator<LongIntEntry> itr = cx.iterator();
        while (itr.hasNext()) {
          LongIntEntry e = itr.next();
          double c_x = e.get();
          if (c_x > 0) {
            double p_x_emp = c_x / C_x;
            igCache.h_x_emp += p_x_emp * -Math.log(p_x_emp);
          }
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
        itr = cyx.iterator();
        while (itr.hasNext()) {
          LongIntEntry e = itr.next();
          double c_yx = e.get();
          double c_y = cy.get((int) (e.index() % ((long) numY)));
          double c_x = cx.get(e.index() / ((long) numY));
          double n_y = c_y + alpha_y;
          double n_x = c_x + alpha_x;

          // Empirical
          //            double pyx_emp = c_yx / C_yx;
          //            double num_emp = (c_yx + 0) * (C_y + 0) * (C_x + 0);
          //            double den_emp = (C_yx + 0) * (c_y + 0) * (c_x + 0);
          //            emp.updateMiNonzero(pyx_emp, num_emp, den_emp);
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
    }
    return igCache;
  }

  /** Can return a negative value, based on approximate entropies */
  public double heuristicScore() {
    MISummary mis = ig();
    double mi = mis.mi();
    //      assert mi > -0.1 : "mi=" + mi + " hx=" + hx;    // numerical issues
    //      if (mi < 0) {
    //        // For very entropic features with little signal, hx will be very
    //        // close to hxy, and since we are approximating each of them, the sum
    //        // could come out negative, even though it shouldn't from an information
    //        // theory point of view.
    //        // We don't want these features anyway.
    //        mi = 0;
    //      }
    double hx = hx();
    double selPenalty = -Math.max(0, Math.log(mis.selectivity));
    assert selPenalty >= 0 : "selectivity=" + mis.selectivity;
    return mi / (4 + hx * hx) - 0.1 * selPenalty;
  }

  public double hx() {
    double hx = ig().h_x;
    assert hx > -0.1 : "hx=" + hx;    // numerical issues
    return hx;
  }

  public static Comparator<TemplateIG> BY_IG_DECREASING = new Comparator<TemplateIG>() {
    @Override
    public int compare(TemplateIG o1, TemplateIG o2) {
      double mi1 = o1.ig().miSmoothed.mi();
      double mi2 = o2.ig().miSmoothed.mi();
      double d = mi2 - mi1;
      if (d > 0) return +1;
      if (d < 0) return -1;
      return 0;
    }
  };
  public static Comparator<TemplateIG> BY_NMI_DECREASING = new Comparator<TemplateIG>() {
    @Override
    public int compare(TemplateIG o1, TemplateIG o2) {
      double fom1 = o1.heuristicScore();
      double fom2 = o2.heuristicScore();
      double d = fom2 - fom1;
      if (d > 0) return +1;
      if (d < 0) return -1;
      return 0;
    }
  };
}