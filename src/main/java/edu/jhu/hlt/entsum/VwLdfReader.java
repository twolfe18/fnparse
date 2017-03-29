package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import edu.jhu.hlt.entsum.SlotsAsConcepts.StreamingDistSupFeatEx;
import edu.jhu.hlt.entsum.SlotsAsConcepts.StreamingDistSupFeatEx.Fact;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

/**
 * Joins relations/verbs, their model scores, and their locations together.
 * Each item in this iterator has a sentence index which needs to be further
 * joined with a sentence/parse iterator.
 *
 * @author travis
 */
public class VwLdfReader implements AutoCloseable, Iterator<VwLdfInstance> {

  private BufferedReader rScores;   // tells you the scores/costs assigned by the model
  private BufferedReader rFeats;    // tells you what the labels are
  private BufferedReader rLocs;     // tells you how to link these instances back to parses/mentions
  private VwLdfInstance cur;
  
  public VwLdfReader(File scores, File features, File locations) throws IOException {
    Log.info("scores=" + scores.getPath()
        + " features=" + features.getPath()
        + " locations=" + locations.getPath());
    rScores = FileUtil.getReader(scores);
    rFeats = FileUtil.getReader(features);
    rLocs = FileUtil.getReader(locations);
    advance();
  }

  private void advance() throws IOException {
    String locStr = rLocs.readLine();
    if (locStr == null) {
      cur = null;
      return;
    }
    StreamingDistSupFeatEx.Fact loc = Fact.fromTsv(locStr);
    cur = new VwLdfInstance(loc);
    rFeats.readLine();    // shared | features+
    for (String sLine = rScores.readLine(); sLine != null && !sLine.isEmpty(); sLine = rScores.readLine()) {
      String fLine = rFeats.readLine();
      if (sLine.isEmpty()) {
        assert fLine.isEmpty();
        break;
      }
      // sLine := <hashOfY>:<cost>
      String[] sToks = sLine.split(":");
      assert sToks.length == 2;
      double cost = Double.parseDouble(sToks[1]);
      // fLine := <yString> | <featuresOfY>
      String[] fToks = fLine.split(" | ", 2);
      String label = fToks[0];
      cur.add(label, cost);
    }
  }

  @Override
  public boolean hasNext() {
    return cur != null;
  }

  @Override
  public VwLdfInstance next() {
    VwLdfInstance c = cur;
    try {
      advance();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return c;
  }

  @Override
  public void close() throws IOException {
    rScores.close();
    rFeats.close();
    rLocs.close();
  }
  
  public static void main(String[] args) throws Exception {
//    ExperimentProperties config = ExperimentProperties.init(args);
    File p = new File("data/facc1-entsum/code-testing-data");
    File x = new File(p, "distsup-typePlausible.csoaa_ldf.x");
    File yhat = new File(p, "distsup-typePlausible.csoaa_ldf.yhat");
    File mentions = new File(p, "distsup-typePlausible.locations.txt");
    int read = 0;
    try (VwLdfReader r = new VwLdfReader(yhat, x, mentions)) {
      while (r.hasNext()) {
        VwLdfInstance inst = r.next();
        System.out.println(inst);
        read++;
      }
    }
    Log.info("done, read=" + read);
  }
}