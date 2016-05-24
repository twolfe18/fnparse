package edu.jhu.hlt.uberts.srl;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.uberts.Labels;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.auto.UbertsOraclePipeline;
import edu.jhu.hlt.uberts.auto.UbertsPipeline;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator;
import edu.jhu.hlt.uberts.io.RelationFileIterator;

/**
 * For one sentence: if you have parameters which get to look at the gold label,
 * perform inference. This is an integration test of things like rules, triggers,
 * etc.
 * 
 * This is sort of what {@link UbertsPipeline} was supposed to be.
 *
 * @author travis
 */
public class OracleSrlTest {

  Uberts u = new Uberts(new Random(9001));

  public void foo() throws IOException {
    File p = new File("data/srl-reldata/trump-pizza");
    File grammarFile = new File(p, "grammar.trans");
    List<File> schemaFiles = Arrays.asList(
        new File(p, "span-relations.def"),
        new File(p, "schema-framenet.def"));
    File relationDefs = new File(p, "relations.def");

    // This does setup like reading in grammar and doing type inference.
    File multiRels = new File(p, "trump-pizza-fork.backwards.xy.rel.multi");

    UbertsOraclePipeline pipe = new UbertsOraclePipeline(u, grammarFile, schemaFiles, relationDefs);

//    TNode.DEBUG = true;
//    Uberts.COARSE_EVENT_LOGGING = true;
    u.showOracleTrajDiagnostics = true;

    try (RelationFileIterator rels = new RelationFileIterator(multiRels, false);
        ManyDocRelationFileIterator many = new ManyDocRelationFileIterator(rels, true)) {
      pipe.runInference(many);
    }

    Labels y = u.getLabels();
    System.out.println(y.getRelCounts());
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties.init(args);
    OracleSrlTest t = new OracleSrlTest();
    t.foo();
  }
}
