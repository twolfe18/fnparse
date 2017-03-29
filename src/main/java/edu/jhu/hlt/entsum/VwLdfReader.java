package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
  
  /**
   * Reads lines of the form <hashOfLabel>:<cost> where instances are separated
   * by empty lines. For these empty lines, NaN is returned by the iterator.
   */
  static class ScoreIter implements Iterator<Double>, AutoCloseable {
    private BufferedReader r;
    private String cur;
    public ScoreIter(File f) throws IOException {
      Log.info("opening " + f.getPath());
      r = FileUtil.getReader(f);
      cur = r.readLine();
    }
    @Override
    public void close() throws IOException {
      r.close();
    }
    @Override
    public boolean hasNext() {
      return cur != null;
    }
    @Override
    public Double next() {
      double ret;
      if (cur.isEmpty()) {
        ret = Double.NaN;
      } else {
        String[] ar = cur.split(":");
        assert ar.length == 2;
        ret = Double.parseDouble(ar[1]);
      }
      try {
        cur = r.readLine();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ret;
    }
  }
  
  /**
   * Averages a bunch of {@link ScoreIter}
   */
  static class AvgIter implements Iterator<Double>, AutoCloseable {
    private List<ScoreIter> cols;
    
    public AvgIter(List<File> files) throws IOException {
      cols = new ArrayList<>();
      for (File f : files)
        cols.add(new ScoreIter(f));
    }

    @Override
    public void close() throws IOException {
      for (ScoreIter i : cols)
        i.close();
    }
    
    @Override
    public boolean hasNext() {
      return cols.get(0).hasNext();
    }

    @Override
    public Double next() {
      double s = 0;
      for (ScoreIter i : cols)
        s += i.next();
      return s / cols.size();
    }
  }
  
//  private BufferedReader rScores;   // tells you the scores/costs assigned by the model
  private AvgIter rScores;   // tells you the scores/costs assigned by the model
  private BufferedReader rFeats;    // tells you what the labels are
  private BufferedReader rLocs;     // tells you how to link these instances back to parses/mentions
  private VwLdfInstance cur;
  
//  public VwLdfReader(File scores, File features, File locations) throws IOException {
  public VwLdfReader(File locations, File features, List<File> scores) throws IOException {
    List<String> fs = new ArrayList<>();
    for (File f : scores) fs.add(f.getPath());
    Log.info("locations=" + locations.getPath()
        + " features=" + features.getPath());
//    rScores = FileUtil.getReader(scores);
    rScores = new AvgIter(scores);
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
//    for (String sLine = rScores.readLine(); sLine != null && !sLine.isEmpty(); sLine = rScores.readLine()) {
//      String fLine = rFeats.readLine();
//      if (sLine.isEmpty()) {
//        assert fLine.isEmpty();
//        break;
//      }
    for (String fLine = rFeats.readLine(); fLine != null; fLine = rFeats.readLine()) {
      double cost = rScores.next();
      if (fLine.isEmpty()) {
        assert Double.isNaN(cost);
        break;
      }
      assert !Double.isNaN(cost);
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
    File p = new File("data/facc1-entsum/code-testing-data/tokenized-sentences/dev/m.01ggbx/");
    File locations = new File(p, "infobox-pred.locations.txt");
    File x = new File(p, "infobox-pred.csoaa_ldf.x");
//    File yhat = new File(p, "distsup-typePlausible.csoaa_ldf.yhat");
    List<File> yhat = FileUtil.find(p, "glob:**/infobox-pred.csoaa_*.yhat");
    int read = 0;
    try (VwLdfReader r = new VwLdfReader(locations, x, yhat)) {
      while (r.hasNext()) {
        VwLdfInstance inst = r.next();
        System.out.println(inst);
        read++;
        if (read > 10)
          break;
      }
    }
    Log.info("done, read=" + read);
  }
}