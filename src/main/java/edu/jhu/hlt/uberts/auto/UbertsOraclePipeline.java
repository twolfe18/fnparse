package edu.jhu.hlt.uberts.auto;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.Labels.Perf;
import edu.jhu.hlt.uberts.Step;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.prim.tuple.Pair;

/**
 * Runs oracle inference to check performance upper bounds.
 *
 * @author travis
 */
public class UbertsOraclePipeline extends UbertsPipeline {

  public boolean train = true;
  public boolean predict = false;
  public boolean showSteps = false;
  private Map<Rule, FeatureExtractionFactor.Oracle> phi;

  public UbertsOraclePipeline(Uberts u, File grammarFile, Iterable<File> schemaFiles, File relationDefs)
      throws IOException {
    super(u, grammarFile, schemaFiles, relationDefs);
  }

  @Override
  public FeatureExtractionFactor<?> getScoreFor(Rule r) {
    if (phi == null)
      phi = new HashMap<>();
    FeatureExtractionFactor.Oracle p = phi.get(r);
    if (p == null) {
      p = new FeatureExtractionFactor.Oracle(r.rhs.relName);
      phi.put(r, p);
    }
    return p;
  }

  @Override
  public void consume(RelDoc doc) {
    assert !(train && predict);
    Log.info("non-schema (hidden and observed) relations: " + u.getLabels().getLabeledRelationNames());
    List<Step> traj;
    if (predict) {
//      Uberts.DEBUG = true;
      u.showTrajDiagnostics = true;
      // oracleSaysYes and oracleSaysNo are separate features, we never learn
      // to move oracleSaysNo weight away from 0, so some negative instances
      // will have weigh 0 at test time.
      double thresh = 0.001;
      Pair<Perf, List<Step>> p = u.dbgRunInference(false, thresh, 0);
      traj = p.get2();
    } else {
      boolean dedupEdges = false;
      traj = u.recordOracleTrajectory(dedupEdges);
      Log.info("oracle traj size: " + traj.size());
      if (train) {
        for (Step s : traj)
          s.score.backwards(s.gold ? -1 : +1);
        for (Rule r : phi.keySet()) {
          FeatureExtractionFactor.Oracle p = phi.get(r);
          System.out.println("weights for " + r);
          System.out.println(p.getWeights());
        }
      }
    }
    if (showSteps) {
      Log.info("train=" + train + " predict=" + predict);
      for (Step s : traj)
        System.out.println(s);
    }
  }

}
