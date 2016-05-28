package edu.jhu.hlt.uberts.io;

import java.io.File;
import java.io.IOException;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;

public class NoSrlFilter extends ManyDocSplitter {
  private File output;
  public NoSrlFilter(File output) {
    this.output = output;
  }
  @Override
  public String partition(RelDoc d) {
    for (HypEdge e : d.facts)
      if (e.getRelation().getName().startsWith("srl"))
        return "keep";
    for (RelLine line : d.items)
      if (line.tokens.length >= 3 && line.tokens[1].startsWith("srl"))
        return "keep";
    return null;
  }

  @Override
  public File getOutputForPartition(String p) {
    return output;
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    try (NoSrlFilter ts = new NoSrlFilter(config.getFile("relOutput"))) {
      ts.split(config.getExistingFile("relInput"));
    }
  }
}