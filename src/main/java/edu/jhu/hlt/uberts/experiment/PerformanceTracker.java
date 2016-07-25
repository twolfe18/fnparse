package edu.jhu.hlt.uberts.experiment;

import java.util.HashMap;
import java.util.Map;

import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.auto.UbertsLearnPipeline;
import edu.jhu.hlt.uberts.auto.UbertsPipeline.Mode;

/**
 * Knows about a metric of interest, e.g. F(argument4), and tracks its value
 * over the course of an experiment, allowing things like only writing out the
 * model file on iterations where the dev metric goes up.
 *
 * @author travis
 */
public interface PerformanceTracker {

  public static boolean DEBUG = true;

  /**
   * Call this before calling shouldSaveParameters or any other method. Its like
   * hasNext() before next().
   */
  public void observe(UbertsLearnPipeline.Mode mode, Map<String, FPR> perfByRelation);

  /**
   * Currently saved at the end of DEV, but this could change.
   */
  public boolean shouldSaveParameters(String relation);


//  /**
//   * Accepts strings like....
//   */
//  public static PerformanceTracker parse(String description) {
//    throw new RuntimeException("implement me");
//  }

  /**
   * Creates a {@link PerformanceTracker} for every relation it sees on the fly.
   */
  public static class Default implements PerformanceTracker {
    private Map<String, PerformanceTracker> byRelation = new HashMap<>();

    @Override
    public void observe(Mode mode, Map<String, FPR> perfByRelation) {
      for (String rel : perfByRelation.keySet()) {
        PerformanceTracker pt = byRelation.get(rel);
        if (pt == null) {
          pt = new Simple(rel);
          byRelation.put(rel, pt);
        }
        pt.observe(mode, perfByRelation);
      }
    }

    @Override
    public boolean shouldSaveParameters(String relation) {
      PerformanceTracker pt = byRelation.get(relation);
      if (pt == null) {
        Log.info("WARNING: this relation was never observed! " + relation);
        return false;
      }
      return pt.shouldSaveParameters(relation);
    }
  }

  /**
   * Tracks one relation and looks at F-measure.
   */
  static class Simple implements PerformanceTracker {
    private String relationOfInterest;
    private boolean lastWasIncrease = false;
    private double curMax = Double.NaN;

    public Simple(String relationOfInterest) {
      this.relationOfInterest = relationOfInterest;
    }

    @Override
    public void observe(UbertsLearnPipeline.Mode mode, Map<String, FPR> perfByRelation) {
      if (DEBUG)
        Log.info("relOfInt=" + relationOfInterest + " mode=" + mode + " perfByRel=" + perfByRelation);
      if (mode != UbertsLearnPipeline.Mode.DEV)
        return;

      FPR f = perfByRelation.get(relationOfInterest);
      lastWasIncrease = Double.isNaN(curMax) || f.f1() > curMax;
      if (lastWasIncrease)
        curMax = f.f1();
      if (DEBUG) {
        Log.info("relOfInt=" + relationOfInterest
            + " lastWasIncrease=" + lastWasIncrease
            + " curMax=" + curMax
            + " f=" + f.f1()
            + "\t" + f);
      }
    }

    @Override
    public boolean shouldSaveParameters(String relation) {
      return lastWasIncrease;
    }
  }

}
