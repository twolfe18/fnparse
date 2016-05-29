package edu.jhu.hlt.uberts.io;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;

/**
 * A filter which keeps only documents which contain at least one fact from a
 * set of provided relation (names).
 *
 * @author travis
 */
public class KeepDocsWithRelation extends ManyDocSplitter {
  private File output;
  private Set<String> keepRels;

  public KeepDocsWithRelation(File output, String[] keepRelationsName) {
    this.output = output;
    this.keepRels = new HashSet<>();
    for (String s : keepRelationsName)
      this.keepRels.add(s);
    Log.info("keeping all documents which contain at least one fact from the "
        + "following set of relations: " + Arrays.toString(keepRelationsName));
    Log.info("writing output to " + output.getPath());
  }

  @Override
  public String partition(RelDoc d) {
    for (HypEdge e : d.facts) {
      if (keepRels.contains(e.getRelation().getName()))
        return "keep";
    }
    for (RelLine line : d.items) {
      if (line.tokens.length >= 3 && keepRels.contains(line.tokens[1]))
        return "keep";
    }
    return null;
  }

  @Override
  public File getOutputForPartition(String p) {
    return output;
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    String[] keep = config.getString("keepRelations").split(",");
    try (KeepDocsWithRelation ts = new KeepDocsWithRelation(config.getFile("relOutput"), keep)) {
      ts.split(config.getExistingFile("relInput"));
    }
  }
}