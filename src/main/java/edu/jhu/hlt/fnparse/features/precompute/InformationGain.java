package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;

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
        double N = updates;
        for (Entry<IntPair, Integer> c : cyx.entrySet()) {
          double countYX = c.getValue();
          double countY = cy.get(c.getKey().first);
          double countX = cx.get(c.getKey().second);
          double py = countYX / N;
          double pmi = Math.log(countYX * N) - Math.log(countY * countX);
          ig += py * pmi;
        }
        igCache = ig;
      }
      return igCache;
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

  public InformationGain() {
    templates = new TemplateIG[3000];  // TODO resize code
    for (int i = 0; i < templates.length; i++)
      templates[i] = new TemplateIG(i);
  }

  @Override
  public void observeLine(String line) {
    String[] toks = line.split("\t");
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
    System.out.println("\toutputFeats: text file for saving the templates/information gain");

    // stats + features -> stats
    // stats -> topK
    String inputStats = config.getString("inputIG", "none");
    InformationGain input = null;
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

    String outf = config.getString("outputFeats", "none");
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
