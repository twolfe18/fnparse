package edu.jhu.hlt.fnparse.features.precompute.featureselection;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Serializable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.ShardUtils.Shard;
import edu.jhu.prim.map.LongIntEntry;
import edu.jhu.prim.map.LongIntHashMap;
import edu.jhu.prim.util.Lambda.FnLongIntToVoid;
import edu.jhu.prim.vector.IntIntDenseVector;

/**
 * Holds a template and statistics related to mutual information for it (x)
 * against all roles/labels (y), possibly with frame/frameRole restrictions.
 *
 * @author travis
 */
public class TemplateIG implements Serializable {
  private static final long serialVersionUID = 1287953772086645433L;
  public static final boolean DEBUG = false;

  /**
   * @param <T> should be hashable (hashcode/equals)
   */
  static class Refined<T> {
    int numY;
    EntropyMethod em;
    Map<T, TemplateIG> refinement2counts;
    Shard shard;    // ignore all refinements that don't match this (including registered ones)
    boolean open;   // if false, ignore non-registered refinements, event if they match shard

    public Refined(int numY, EntropyMethod em, Shard shard, boolean open) {
      this.numY = numY;
      this.em = em;
      this.shard = shard;
      this.open = open;
      this.refinement2counts = new HashMap<>();
    }

    public Iterable<T> getRefinements() {
      return refinement2counts.keySet();
    }

    public int getNumRefinements() {
      return refinement2counts.size();
    }

    /**
     * Ensures that this refinement has a {@link TemplateIG} built (eager, as
     * opposed to the lazy way of just calling update). Only does anything if
     * this refinement matches this instance's shard. If so, this refinement
     * will appear in getRefinments().
     * @return true if the refinement was kept.
     */
    public boolean register(T refinement) {
      if (shard.matches(refinement)) {
        Object old = refinement2counts.put(refinement, new TemplateIG(-1, null, numY, em));
        assert old == null;
        return true;
      }
      return false;
    }

    public void update(T refinement, int y, long[] x) {
      if (!shard.matches(refinement))
        return;
      TemplateIG tig = refinement2counts.get(refinement);
      if (tig == null) {
        if (!open)
          return;
        tig = new TemplateIG(1, null, numY, em);
        refinement2counts.put(refinement, tig);
      }
      tig.update(y, x);
    }
    public void update(T refinement, int y, int[] x) {
      if (!shard.matches(refinement))
        return;
      TemplateIG tig = refinement2counts.get(refinement);
      if (tig == null) {
        if (!open)
          return;
        tig = new TemplateIG(1, null, numY, em);
        refinement2counts.put(refinement, tig);
      }
      tig.update(y, x);
    }

    public void writeout(BufferedWriter w, Function<T, String> howToWriteRefinement) throws IOException {
      for (Entry<T, TemplateIG> e : refinement2counts.entrySet()) {
        T refinement = e.getKey();
        TemplateIG tig = e.getValue();

        // FOM
        w.write(String.valueOf(tig.heuristicScore()));

        // mi
        w.write("\t" + tig.ig().mi());

        // hx
        w.write("\t" + tig.hx());

        // selectivity
        // TODO fixme
        w.write("\t" + tig.ig().selectivity);

        // How many updates we've seen. If no filtering is done, this will just
        // be the number of lines in the feature files. If however, we create
        // TemplateIGs with filters, then it will reflect the relative frequency
        // of the filter passing.
        w.write("\t" + tig.numObservationsWithSomeX());

        // frame,framerole restrictions
        String refStr = howToWriteRefinement.apply(refinement);
        w.write("\t" + refStr);

        w.newLine();
      }
    }
  }


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

  private IntIntDenseVector cy;
  // (y:int) * (x:ProductIndex) can be represented as a ProductIndex :)
  // ProductIndex can be represented exactly as a long (64 bits) without overflowing in reasonable situations
  // I can store counts for long keys in LongIntHashMap
  private LongIntHashMap cyx;   // marginalize to compute cx later

  // Used in computing indices for cyx
  private int numY; // ensure that all values of y are less than this

  private int features;
  private int observations, observationsWithSomeX;
  private MI.Summary igCache = null;

  // alpha_yx_p works differently from alpha_[yx](_p)?
  // The former is add alpha/D and the latter is add alpha
  double alpha_yx_p;   // should be ~= (alpha_y_p * alpha_x_p)
  double alpha_y_p;
  double alpha_x_p;
  double alpha_y;
  double alpha_x;

  private BubEntropyEstimatorAdapter bubEst;

  @Override
  public String toString() {
    return String.format("<TemplateIG entropyMethod=%s numY=%d updates=%d obs=%d obsWithX=%d>",
        entropyMethod, numY, features, observations, observationsWithSomeX);
  }

  public TemplateIG(int index, String name, int numY, EntropyMethod em) {
    this.index = index;
    this.name = name;
    this.entropyMethod = em;
    this.cy = new IntIntDenseVector();
    this.cyx = new LongIntHashMap();
    this.numY = numY;
    this.features = 0;
    this.observations = 0;
    this.observationsWithSomeX = 0;

    if (em == EntropyMethod.MLE) {
      alpha_yx_p = 0;
      alpha_y_p = 0;
      alpha_x_p = 0;
      alpha_y = 0;
      alpha_x = 0;
    } else if (em == EntropyMethod.MAP) {
      ExperimentProperties config = ExperimentProperties.getInstance();
      alpha_yx_p = config.getDouble("alpha_yx_p", 500);
      alpha_y_p = config.getDouble("alpha_y_p", 1);
      alpha_x_p = config.getDouble("alpha_x_p", 1);
      alpha_y = config.getDouble("alpha_y", 1);
      alpha_x = config.getDouble("alpha_x", 1);
    }
  }

  public String getAlphaDesc() {
    return String.format("alpha_xy_p=%f alpha_y_p=%f alpha_x_p=%f alpha_y=%f alpha_x=%f",
        alpha_yx_p, alpha_y_p, alpha_x_p, alpha_y, alpha_x);
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

  void update(int y, long[] xs) {
    this.observations++;
    if (xs.length > 0)
      this.observationsWithSomeX++;
    if (y >= numY)
      throw new IllegalStateException("you set numY=" + numY + " and we just saw yy=" + y);
    cy.add(y, 1);
    for (long x : xs)
      cyx.add(x * numY + y, 1);
    features += xs.length;
    igCache = null;
  }
  void update(int y, int[] xs) {
    this.observations++;
    if (xs.length > 0)
      this.observationsWithSomeX++;
    if (y >= numY)
      throw new IllegalStateException("you set numY=" + numY + " and we just saw yy=" + y);
    cy.add(y, 1);
    for (long x : xs)
      cyx.add(x * numY + y, 1);
    features += xs.length;
    igCache = null;
  }

  /**
   * How many times you called update.
   */
  public int numObservationsWithSomeX() {
    return observationsWithSomeX;
  }

  
  public MI.Summary ig() {
    if (igCache == null) {

      igCache = new MI.Summary();
      igCache.alpha_yx_p = alpha_yx_p;
      igCache.alpha_y_p = alpha_y_p;
      igCache.alpha_x_p = alpha_x_p;
      igCache.alpha_y = alpha_y;
      igCache.alpha_x = alpha_x;
      igCache.C = features;
      igCache.hashinigDim = HASHING_DIM;
      igCache.numInstances = features;
      igCache.templateInt = index;
      igCache.templateName = name;

      igCache.selectivity = ((double) observationsWithSomeX) / observations;

      LongIntHashMap cx = new LongIntHashMap();
      cyx.iterate(new FnLongIntToVoid() {
        @Override
        public void call(long yx, int count) {
          long x = yx / ((long) numY);
          cx.add(x, count);
        }
      });

      final double C_yx = cyx.getSum();
      final double C_x = cx.getSum();
      final double C_y = cy.getSum();

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
        igCache.miSmoothed = new MI.Fixed(hx + hy - hyx);
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
        igCache.miEmpirical = new MI.Fixed(mi);
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
    MI.Summary mis = ig();
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