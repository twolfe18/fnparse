package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.LineByLine;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.vector.IntIntDenseVector;

/**
 * Uses output of {@link FeaturePrecomputation} to compute IG for feature selection.
 *
 * NOTE: This version was created before bialph merging. This may be good enough
 * for getting a rough top K list of templates, but if not, see
 * {@link InformationGainProducts} for how to read many feature files that don't
 * shard a common indexing scheme (you map with a bialph created by {@link BiAlphMerger}).
 *
 * @author travis
 */
public class InformationGain implements Serializable, LineByLine {
  private static final long serialVersionUID = -5727222496637587197L;

  public static class TemplateIG implements Serializable {
    private static final long serialVersionUID = 1287953772086645433L;
    public static double ADD_LAMBDA_SMOOTHING = 0.5;
    public static boolean FULL_BAYESIAN_H = false;
    private int index;
    private IntIntDenseVector cy, cx;
    private Counts<IntPair> cyx;    // |Y| ~= 20  |X| ~= 20 * 10,000
    private int updates;
    private Double igCache = null;
    public TemplateIG(int index) {
      this.index = index;
      this.cy = new IntIntDenseVector();
      this.cx = new IntIntDenseVector();
      this.cyx = new Counts<>();
      this.updates = 0;
    }
    public int getIndex() {
      return index;
    }
    public void update(int y, int x) {
      cyx.increment(new IntPair(y, x));
      cy.add(y, 1);
      cx.add(x, 1);
      updates++;
      igCache = null;
    }
    public int numUpdates() {
      return updates;
    }
    public double ig() {
      if (igCache == null) {
        double ig = 0;
        if (FULL_BAYESIAN_H) {
          /*
           * Unfortuneatly, I think this is too slow to be practical.
           * Proving once again: you never go full Bayesian.
           */
          int Tx = cx.getNumImplicitEntries();
          int Ty = cy.getNumImplicitEntries();
          int Tyx = Ty * Tx;
          for (int x = 0; x < Tx; x++) {
            double cx = this.cx.get(x) + ADD_LAMBDA_SMOOTHING;
            double nx = updates + Tx * ADD_LAMBDA_SMOOTHING;
            for (int y = 0; y < Ty; y++) {
              double cyx = this.cyx.getCount(new IntPair(y, x)) + ADD_LAMBDA_SMOOTHING;
              double cy = this.cy.get(y) + ADD_LAMBDA_SMOOTHING;
              double nyx = updates + Tyx * ADD_LAMBDA_SMOOTHING;
              double ny = updates + Ty * ADD_LAMBDA_SMOOTHING;
              // pmi = log(p(xy)) - [log(p(x)) + log(p(y))]
//              double pmi =
//                  (log(cyx) - log(nyx))
//                  - (log(cy) - log(ny))
//                  - (log(cx) - log(nx));
//              double pmi = log( (cyx/nyx) / ((cy*cx)/(ny*nx)) );
//              double pmi = log( (cyx/nyx) ) - log( ((cy*cx)/(ny*nx)) );
//              double pmi = log( (cyx * nx * ny) / (nyx * cy * cx) );
              double pmi = log( (cyx * nx * ny) ) - log( (nyx * cy * cx) );
              double pyx = cyx / nyx;
              ig += pyx * pmi;
            }
          }
        } else {
          double N = updates;
          /*
           * NOTE: IG = KL(p(XY), p(X)*p(Y)) = E_{p(XY)} PMI(X,Y)
           * NOTE: I have not gone full Bayesian!
           * If I had, I would have to use IG = KL(posterior(XY), margin(posterior(X))*margin(posterior(Y)))
           * If I did this, I would have to loop over all XY.
           * As it is, I'm using a frequentist estimate of PMI, meaning that c(YX)=0 => pmi=0
           * If I went full Bayesian, c(yx)=0 + prior => posterior(c(yx)) > 0
           */
          //        int Nt = cyx.numNonZero();    // this is less than the number of types!
          int Nt = cy.getNumImplicitEntries() * cx.getNumImplicitEntries();
          for (Entry<IntPair, Integer> c : cyx.entrySet()) {
            double countYX = c.getValue();
            double countY = cy.get(c.getKey().first);
            double countX = cx.get(c.getKey().second);
            double pyx = (countYX + ADD_LAMBDA_SMOOTHING) / (N + Nt * ADD_LAMBDA_SMOOTHING);
            double pmi = Math.log(countYX * N) - Math.log(countY * countX);
            ig += pyx * pmi;
          }
        }
        igCache = ig;
      }
      return igCache;
    }
    public static final double LOG2 = Math.log(2);
    public static double log(double x) {
      return Math.log(x) / LOG2;
    }
    public static Comparator<TemplateIG> BY_IG_DECREASING = new Comparator<TemplateIG>() {
      @Override
      public int compare(TemplateIG o1, TemplateIG o2) {
        double d = o2.ig() - o1.ig();
        if (d > 0) return 1;
        if (d < 0) return -1;
        return 0;
      }
    };
  }

  // |XY| = 4M
  // |T| ~= 1000
  // |XYT| = 4B ints = 16B bytes
  protected TemplateIG[] templates;
  protected Set<String> ignoreSentenceIds;

  public InformationGain() {
    ExperimentProperties config = ExperimentProperties.getInstance();
    templates = new TemplateIG[config.getInt("numTemplates", 3000)];  // TODO resize code
    for (int i = 0; i < templates.length; i++)
      templates[i] = new TemplateIG(i);

    File ignoreSentenceIdsFile = config.getExistingFile("ignoreSentenceIds");
    Log.info("ignoring the sentence ids in " + ignoreSentenceIdsFile.getPath());
    ignoreSentenceIds = new HashSet<>();
    try (BufferedReader r = FileUtil.getReader(ignoreSentenceIdsFile)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        ignoreSentenceIds.add(line);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void observeLine(String line) {
    String[] toks = line.split("\t");
    String sentenceId = toks[1];
    if (ignoreSentenceIds.contains(sentenceId))
      return;
    int k = Integer.parseInt(toks[4]);
    for (int i = 5; i < toks.length; i++) {
      String f = toks[i];
      int c = f.indexOf(':');
      int template = Integer.parseInt(f.substring(0, c));
      int feature = Integer.parseInt(f.substring(c + 1));
      // +1 maps k=-1 (no role) to 0, and shifts everything else up
      templates[template].update(k + 1, feature);
    }
  }

  public List<TemplateIG> getTemplatesSortedByIGDecreasing() {
    List<TemplateIG> l = new ArrayList<>();
    for (TemplateIG t : templates)
      if (t.numUpdates() > 0)
        l.add(t);
    Collections.sort(l, TemplateIG.BY_IG_DECREASING);
    return l;
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    System.out.println("usage:");
    System.out.println("\tinputIG: serialized InformationGain file to read from [optional]");
    System.out.println("\tfeatures: int feature file produced by FeaturePrecomputation [optional]");
    System.out.println("\ttemplateAlph: alphabet file produced by FeaturePrecomputation for looking up template names [optional]");
    System.out.println("\ttopK: how many of the top templates to print [optional]");
    System.out.println("\toutputIG: where to serialize updated InformationGain");
    System.out.println("\toutputFeatures: text file for saving the templates/information gain");
    System.out.println("\tignoreSentenceIds: text file containing one sentence id per line to ignore");

    TemplateIG.ADD_LAMBDA_SMOOTHING =
        config.getDouble("ig.addLambda", TemplateIG.ADD_LAMBDA_SMOOTHING);

    // stats + features -> stats
    // stats -> topK
    String inputStats = config.getString("inputIG", "none");
    final InformationGain input;
    if (inputStats.equals("none")) {
      Log.info("starting with empty stats");
      input = new InformationGain();
    } else {
      Log.info("reading stats from " + inputStats);
      input = (InformationGain) FileUtil.deserialize(new File(inputStats));
    }

    String features = config.getString("features", "none");
    if (!features.equals("none")) {
      Log.info("updating stats from " + features);
      input.run(new File(features));
    }

    String featuresGlob = config.getString("featuresGlob", "");
    if (!featuresGlob.isEmpty()) {
      File featuresParent = config.getExistingDir("featuresParent");
      PathMatcher pm = FileSystems.getDefault().getPathMatcher(featuresGlob);
      Files.walkFileTree(featuresParent.toPath(), new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
          if (pm.matches(path)) {
            Log.info("reading features: " + path.toFile().getPath() + "\t" + Describe.memoryUsage());
            input.run(path.toFile());
          }
          return FileVisitResult.CONTINUE;
        }
        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
          return FileVisitResult.CONTINUE;
        }
      });
    }

    Alphabet tNames = null;
    String tNamesFile = config.getString("templateAlph", "none");
    if (!tNamesFile.equals("none")) {
      Log.info("loading template names from " + tNamesFile);
      tNames = new Alphabet(new File(tNamesFile));
    }

    List<TemplateIG> templates = input.getTemplatesSortedByIGDecreasing();

    int topK = config.getInt("topK", 0);
    if (topK > 0)
      Log.info("top " + topK + " templates:");

    String outf = config.getString("outputFeatures", "none");
    BufferedWriter w = null;
    if (!outf.equals("none"))
      w = FileUtil.getWriter(new File(outf));

    // Loop over results
    Exception last = null;
    for (int i = 0; i < templates.size(); i++) {
      TemplateIG t = templates.get(i);
      String line;
      if (tNames == null)
        line = t.ig() + "\t" + t.getIndex();
      else
        line = t.ig() + "\t" + t.getIndex() + "\t" + tNames.get(t.getIndex()).name;
      if (i < topK)
        System.out.println(line);
      if (w != null) {
        try {
          w.write(line);
          w.newLine();
        } catch (Exception e) {
          last = e;
        };
      }
    }
    if (last != null)
      last.printStackTrace();
    if (w != null)
      w.close();

    String output = config.getString("outputIG", "none");
    if (!output.equals("none")) {
      Log.info("serializing InformationGain object to " + output);
      FileUtil.serialize(input, new File(output));
    }

    Log.info("done");
  }

  public static long pack(int i1, int i2) {
    assert i1 >= 0 && i2 >= 0;
    return ((long) i1) << 32 | ((long) i2);
  }
  public static int unpack1(long i1i2) {
    return (int) (i1i2 >>> 32);
  }
  public static int unpack2(long i1i2) {
    long mask = 0xffffffff;
    return (int) (i1i2 & mask);
  }
}
