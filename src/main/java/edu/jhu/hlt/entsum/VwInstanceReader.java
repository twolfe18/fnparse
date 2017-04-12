package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
public class VwInstanceReader implements AutoCloseable, Iterator<VwInstance> {
  
  /**
   * Reads lines of the form <hashOfLabel>:<cost> where instances are separated
   * by empty lines. For these empty lines, NaN is returned by the iterator.
   */
  static class ScoreIter implements Iterator<Double>, AutoCloseable {
    private BufferedReader r;
    private String cur;
    private boolean ldf;
    public ScoreIter(File f, boolean ldf) throws IOException {
      Log.info("opening " + f.getPath() + " ldf=" + ldf);
      this.ldf = ldf;
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
        if (ldf) {
          String[] ar = cur.split(":");
          assert ar.length == 2;
          ret = Double.parseDouble(ar[1]);
        } else {
          ret = Double.parseDouble(cur);
        }
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
    
    public AvgIter(List<File> files, boolean ldf) throws IOException {
      cols = new ArrayList<>();
      for (File f : files)
        cols.add(new ScoreIter(f, ldf));
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
  
  private AvgIter rScores;   // tells you the scores/costs assigned by the model
  private BufferedReader rFeats;    // tells you what the labels are
  private BufferedReader rLocs;     // tells you how to link these instances back to parses/mentions
  private boolean ldf;
  private VwInstance cur;
  
  public VwInstanceReader(File locations, File features, File scores, boolean ldf) throws IOException {
    this(locations, features, Arrays.asList(scores), ldf);
  }

  public VwInstanceReader(File locations, File features, List<File> scores, boolean ldf) throws IOException {
    List<String> fs = new ArrayList<>();
    for (File f : scores) fs.add(f.getPath());
    Log.info("ldf=" + ldf
        + " locations=" + locations.getPath()
        + " features=" + features.getPath());
    this.ldf = ldf;
    rScores = new AvgIter(scores, ldf);
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

    // ldf: shared | feature+
    // binary: feature+
    String fs = rFeats.readLine();
    VwLine fx = new VwLine(fs);
    cur = new VwInstance(loc, fx);

    if (!ldf) {
      // If binary labels, then just read one line of (scores, features, locations) and return
      double cost = rScores.next();
      cur.add("1", cost);
    } else {
      // Else read the scores/labels from the ldf format
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
  }

  @Override
  public boolean hasNext() {
    return cur != null;
  }

  @Override
  public VwInstance next() {
    VwInstance c = cur;
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
  
  public static void testLdf() throws IOException {
    File p = new File("data/facc1-entsum/code-testing-data/tokenized-sentences/dev/m.01ggbx/");
    File locations = new File(p, "infobox-pred.locations.txt");
    File x = new File(p, "infobox-pred.csoaa_ldf.x");
    List<File> yhat = FileUtil.find(p, "glob:**/infobox-pred.csoaa_*.yhat");
    int read = 0;
    try (VwInstanceReader r = new VwInstanceReader(locations, x, yhat, true)) {
      while (r.hasNext()) {
        VwInstance inst = r.next();
        System.out.println(inst);
        read++;
        if (read > 10)
          break;
      }
    }
    Log.info("done, read=" + read);
  }
  
  public static void testBinaryLabels() throws IOException {
    File p = new File("data/facc1-entsum/code-testing-data/tokenized-sentences/train/m.025k5p");
    File locations = new File(p, "infobox-binary/unlab.location");
    File x = new File(p, "infobox-binary/unlab.x");
    List<File> yhat = Arrays.asList(new File(p, "infobox-binary/unlab.yhat"));
    int read = 0;
    try (VwInstanceReader r = new VwInstanceReader(locations, x, yhat, false)) {
      while (r.hasNext()) {
        VwInstance inst = r.next();
        System.out.println(inst);
        read++;
        if (read > 10)
          break;
      }
    }
    Log.info("done, read=" + read);
  }
  
  public static void main(String[] args) throws Exception {
//    ExperimentProperties config = ExperimentProperties.init(args);
    testLdf();
    testBinaryLabels();
  }
}