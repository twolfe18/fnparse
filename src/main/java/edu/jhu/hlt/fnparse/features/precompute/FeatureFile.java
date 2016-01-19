package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.features.precompute.InformationGainProducts.BaseTemplates;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;

public class FeatureFile {

  public static class TemplateExtraction {
    public int template;
    public int[] features;
    public TemplateExtraction(int t, List<Feature> features) {
      template = t;
      this.features = new int[features.size()];
      for (int i = 0; i < this.features.length; i++) {
        Feature tf = features.get(i);
        assert tf.template == t;
        this.features[i] = tf.feature;
      }
    }

    /**
     * Converts features from ints to ProductIndex
     */
    public ProductIndex[] featureToProductIndex() {
      ProductIndex[] pi = new ProductIndex[features.length];
      for (int i = 0; i < pi.length; i++)
        pi[i] = new ProductIndex(features[i]);
      return pi;
    }
  }

  /**
   * This does group by template, which {@link BaseTemplates} doesn't do.
   */
  public static class Line {
    private boolean sorted;
    private final String line;
    private String[] tokenized;
    private List<Feature> features;

    public Line(String line, boolean sorted) {
      features = new ArrayList<>();
      this.line = line;
      init(line, sorted);
    }

    public void init(String line, boolean sorted) {
      this.sorted = sorted;
      tokenized = null;
      features.clear();
    }

    public String getLine() {
      return line;
    }

    private void tokenize() {
      tokenized = line.split("\t");
      for (int i = 5; i < tokenized.length; i++) {
        String tf = tokenized[i];
        if (tf.trim().isEmpty()) continue;
        String[] tfs = tf.split(":");
        assert tfs.length == 2 : "tokenized[" + i + "]=\"" + tokenized[i] + "\"";
        int t = Integer.parseInt(tfs[0]);
        int f = Integer.parseInt(tfs[1]);
        features.add(new Feature(null, t, null, f, 1));
      }
    }

    public Span getTarget() {
      if (tokenized == null)
        tokenize();
      String ts = tokenized[2];
      return Span.inverseShortString(ts);
    }

    public Span getArgSpan() {
      if (tokenized == null)
        tokenize();
      String ts = tokenized[3];
      return Span.inverseShortString(ts);
    }

    public String getSentenceId() {
      if (tokenized == null)
        tokenize();
      return tokenized[1];
    }

    public int[] getRoles(boolean addOne) {
      if (tokenized == null)
        tokenize();
      String[] t = tokenized[4].split(",");
      int[] roles = new int[t.length];
      for (int i = 0; i < t.length; i++)
        roles[i] = Integer.parseInt(t[i]) + (addOne ? 1 : 0);
      return roles;
    }

    public List<TemplateExtraction> groupByTemplate() {
      if (tokenized == null)
        tokenize();
      if (!sorted)
        Collections.sort(features, Feature.BY_TEMPLATE_IDX);
      List<TemplateExtraction> out = new ArrayList<>();
      int curT = -1;
      List<Feature> cur = new ArrayList<>();
      for (int i = 0; i < features.size(); i++) {
        Feature f = features.get(i);
        if (f.template != curT) {
          if (cur.size() > 0)
            out.add(new TemplateExtraction(curT, cur));
          cur.clear();
          curT = f.template;
        }
        cur.add(f);
      }
      return out;
    }

    public List<Feature> getFeatures() {
      if (tokenized == null)
        tokenize();
      return features;
    }

    /** ascending */
    public boolean checkFeaturesAreSortedByTemplate() {
      List<Feature> f = getFeatures();
      for (int i = 1; i < features.size(); i++) {
        int t = f.get(i - 1).template;
        int tt = f.get(i).template;
        if (t > tt)
          return false;
      }
      return true;
    }
  }

  public static class Iterbl implements Iterable<Line> {
    public static String readLineOrBlowup(BufferedReader r) {
      try {
        return r.readLine();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    public final File file;
    public final boolean sorted;
    public Iterbl(File f, boolean featureAreSorted) {
      this.file = f;
      this.sorted = featureAreSorted;
    }
    @Override
    public Iterator<Line> iterator() {
      Log.info("reading from " + file.getPath());
      return new Iterator<Line>() {
        private BufferedReader r = FileUtil.getReaderOrBlowup(file);
        private String line = readLineOrBlowup(r);
        @Override
        public boolean hasNext() {
          return line != null;
        }
        @Override
        public Line next() {
          String l = line;
          line = readLineOrBlowup(r);
          if (line == null) {
            try {
              r.close();
              Log.info("successfully closed " + file.getPath());
            } catch (Exception e) {
              e.printStackTrace();
            }
            r = null;
          }
          return new Line(l, sorted);
        }
      };
    }
  }

  public static Iterable<Line> getLines(File f, boolean featuresSorted) {
    Log.info("reading features from=" + f.getPath() + " sorted=" + featuresSorted);
    return new Iterbl(f, featuresSorted);
  }

  /**
   * Checks the integrity of a feature file given an alphabet and prints some
   * stats.
   */
  public static void checkFeatureFileIntegrity(ExperimentProperties config)
      throws IOException {

    boolean featuresSorted = config.getBoolean("featuresSorted");
    File featuresParent = config.getExistingDir("featuresParent");
    String featuresGlob = config.getString("featuresGlob", "glob:**/*");

    BiAlph bialph = new BiAlph(
        config.getExistingFile("bialph"),
        LineMode.valueOf(config.getString("bialph.lineMode", "ALPH_AS_TRIVIAL_BIALPH")));

    // Scan each of the input files
    boolean stopImmediately = config.getBoolean("stopImmediately", false);
    PathMatcher pm = FileSystems.getDefault().getPathMatcher(featuresGlob);
    Files.walkFileTree(featuresParent.toPath(), new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
        if (pm.matches(path)) {
          Log.info("reading features: " + path.toFile().getPath() + "\t" + Describe.memoryUsage());

          // Things to check
          // 1) are the features sorted?
          // 2) are the cardinalities valid?

          for (Line l : getLines(path.toFile(), featuresSorted)) {
            if (featuresSorted && !l.checkFeaturesAreSortedByTemplate()) {
              String msg = "you said the features were sorted by template and they weren't: " + l;
              if (stopImmediately)
                throw new RuntimeException(msg);
              else
                Log.warn(msg);
            }

            for (Feature f : l.getFeatures()) {
              int card = bialph.getUpperBoundOnNumFeatures(f.template);
              if (f.feature >= card) {
                String msg = "bialph says cardinality of "
                    + f.template + " (" + bialph.mapTemplate(f.template) + ") is "
                    + card + " but we just observed a feature of " + f.feature
                    + ": " + l;
                if (stopImmediately)
                  throw new RuntimeException(msg);
                else
                  Log.warn(msg);
              }
            }
          }
        }
        return FileVisitResult.CONTINUE;
      }
      @Override
      public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
        return FileVisitResult.CONTINUE;
      }
    });
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    checkFeatureFileIntegrity(config);
  }

}
