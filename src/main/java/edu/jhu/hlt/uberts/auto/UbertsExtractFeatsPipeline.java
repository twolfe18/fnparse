package edu.jhu.hlt.uberts.auto;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.List;

import edu.jhu.hlt.fnparse.features.BasicFeatureTemplates;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Target;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.TemplateAlphabet;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.Step;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.features.FeatureExtractionFactor;
import edu.jhu.hlt.uberts.features.OldFeaturesWrapper;
import edu.jhu.hlt.uberts.features.Weight;
import edu.jhu.hlt.uberts.features.WeightList;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.srl.Srl3EdgeWrapper;
import edu.jhu.prim.tuple.Pair;

public class UbertsExtractFeatsPipeline extends UbertsPipeline {
  private double pNegSkip = 0.75;
  private BasicFeatureTemplates bft;
  private OldFeaturesWrapper.Strings feSlow;

  public UbertsExtractFeatsPipeline(Uberts u, File grammarFile, Iterable<File> schemaFiles, File relationDefs) throws IOException {
    super(u, grammarFile, schemaFiles, relationDefs);
    ExperimentProperties config = ExperimentProperties.getInstance();
    pNegSkip = config.getDouble("pNegSkip", 0.75);
    bft = new BasicFeatureTemplates();
    feSlow = new OldFeaturesWrapper.Strings(new OldFeaturesWrapper(bft), pNegSkip);
    feSlow.cacheAdjointsForwards = false;
  }

  private BufferedWriter w;
  private FeaturePrecomputation.AlphWrapper fpre;
  private File fPreTemplateFeatureAlph;
  private File fPreRoleNameAlph;
  private File fPreFeatureOutputFile;
  /**
   * Call this method to make the feature output look like {@link FeaturePrecomputation}.
   * 
   * @param fPreTemplateFeatureAlph is where to write the 4-column tsv for
   * templates and features and their indices. Typically called template-feat-indices.txt.gz.
   * 
   * @param fPreRoleNameAlph is where to write the frame/role/frame role names
   * (the column referred to as "k"). Typically called role-names.txt.gz.
   * 
   * @param fPreFeatureOutputFile is where to write features in svm-light-like format.
   */
  public void setFeaturePrecomputationMode(
      File fPreTemplateFeatureAlph,
      File fPreRoleNameAlph,
      File fPreFeatureOutputFile) {
    assert fPreRoleNameAlph != null;
    assert fPreTemplateFeatureAlph != null;
    this.fPreRoleNameAlph = fPreRoleNameAlph;
    this.fPreTemplateFeatureAlph = fPreTemplateFeatureAlph;
    this.fPreFeatureOutputFile = fPreFeatureOutputFile;
    Log.info("writing features to " + fPreFeatureOutputFile);
  }

  @Override
  public FeatureExtractionFactor<?> getScoreFor(Rule r) {
    return feSlow;
  }

  @Override
  public void start(String dataName) {
    super.start(dataName);
    // This will initialize the Alphabet over frame/role names and provide
    // the code to write out features.txt.gz and template-feat-indices.txt.gz
    boolean roleMode = true;
    fpre = new FeaturePrecomputation.AlphWrapper(roleMode, feSlow.getInner().getFeatures());
    try {
      w = FileUtil.getWriter(fPreFeatureOutputFile);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  public void finish(String dataName) {
    super.finish(dataName);
    try {
      w.close();
      fpre.saveAlphabets(fPreTemplateFeatureAlph, fPreRoleNameAlph);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }


  @Override
  public void consume(RelDoc doc) {
    try {
      consume2(doc);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private TimeMarker tm = new TimeMarker();
  private Counts<String> posRel = new Counts<>();
  private Counts<String> negRel = new Counts<>();
  private long features = 0, actions = 0, docs = 0;
  public void consume2(RelDoc doc) throws IOException {
    // Run inference and record extracted features
    docs++;
    String docid = doc.getId();
    boolean dedupEdges = true;
    int skipped = 0, kept = 0;
    List<Step> traj = u.recordOracleTrajectory(dedupEdges);
    FPR fpr = new FPR();
    timer.start("trajProcessing");
    for (Step t : traj) {
      boolean y = u.getLabel(t.edge);

      @SuppressWarnings("unchecked")
      WeightList<Pair<TemplateAlphabet, String>> fx = (WeightList<Pair<TemplateAlphabet, String>>) t.score;
      //          WeightAdjoints<Pair<TemplateAlphabet, String>> fx =
      //          (WeightAdjoints<Pair<TemplateAlphabet, String>>) t.score;
      //          if (fx.getFeatures() == feSlow.SKIP) {
      //            skipped++;
      //            continue;
      //          }
      kept++;
      // OUTPUT
      // TODO a lot of overlap between features(srl2) and features(srl3)?
      // TODO do I need to group-by (t,s) for this output format?
      // I think the solution which satisfies both of those problems is to
      // => only write out srl3 edges
      // this way you know how to format the (t,s,k) fields
      if (t.edge.getRelation().getName().equals("srl3")) {
        Srl3EdgeWrapper s3 = new Srl3EdgeWrapper(t.edge);
        String k;
        if (y) {
          k = fpre.lookupRole(s3.k)
              + "," + fpre.lookupFrameRole(s3.f, s3.k)
              + "," + fpre.lookupFrame(s3.f);
        } else {
          k = "-1";
        }
        w.write(Target.toLine(new FeaturePrecomputation.Target(docid, s3.t)));
        w.write("\t" + s3.s.shortString());
        w.write("\t" + k);
        //              for (Pair<TemplateAlphabet, String> tf : fx.getFeatures()) {
        for (Weight<Pair<TemplateAlphabet, String>> wtf : fx) {
          Pair<TemplateAlphabet, String> tf = null;   // TODO I don't know how we got here...
          TemplateAlphabet template = tf.get1();
          String feat = tf.get2();
          int featIdx = template.alph.lookupIndex(feat, true);
          w.write('\t');
          w.write(template.index + ":" + featIdx);
          features++;
        }
        w.newLine();
      }

      if (y) posRel.increment(t.edge.getRelation().getName());
      else negRel.increment(t.edge.getRelation().getName());
      //          if (debug)
      //            System.out.println("[exFeats.orTraj] " + lab + " " + t.edge);// + " " + new HashableHypEdge(t.edge).hc);
      actions++;
    }
    timer.stop("trajProcessing");
    Log.info("n=" + traj.size() + " model wrt weakOracle: " + fpr.toString());

    if (tm.enoughTimePassed(15)) {
      double sec = tm.secondsSinceFirstMark();
      double fPerD = features / ((double) docs);
      double fPerA = features / ((double) actions);
      String msg = String.format(
          "extracted %d features for %d docs (%.1f feat/doc) and %d actions (%.1f feat/act) in %.1f seconds",
          features, docs, fPerD, actions, fPerA, sec);
      Log.info(msg);
      double aPerD = actions / ((double) docs);
      msg = String.format("%.1f doc/sec, %.1f act/sec, %.1f act/doc",
          docs/sec, actions/sec, aPerD);
      Log.info(msg);
      Log.info("numPosFeatsExtracted: " + posRel + " sum=" + posRel.getTotalCount());
      Log.info("numNegFeatsExtracted: " + negRel + " sum=" + negRel.getTotalCount());
      Log.info("docsKept=" + docs);
      System.out.println();
    }
  }
}