package edu.jhu.hlt.uberts.auto;

import java.io.File;
import java.io.IOException;
import java.util.List;

import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.Step;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;

/**
 * Runs oracle inference to check performance upper bounds.
 *
 * @author travis
 */
public class UbertsOraclePipeline extends UbertsPipeline {

  public UbertsOraclePipeline(Uberts u, File grammarFile, Iterable<File> schemaFiles, File relationDefs)
      throws IOException {
    super(u, grammarFile, schemaFiles, relationDefs);
  }

  @Override
  public FeatureExtractionFactor<?> getScoreFor(Rule r) {
    return new FeatureExtractionFactor.Oracle(r.rhs.relName);
  }

  @Override
  public void consume(RelDoc doc) {
    boolean dedupEdges = false;
    List<Step> traj = u.recordOracleTrajectory(dedupEdges);
    Log.info("non-schema (hidden and observed) relations: " + u.getLabels().getLabeledRelationNames());
    Log.info("oracle traj size: " + traj.size());
    for (Step s : traj) {
      System.out.println(s);
    }
  }

}
