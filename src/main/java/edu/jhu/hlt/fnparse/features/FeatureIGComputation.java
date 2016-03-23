package edu.jhu.hlt.fnparse.features;

import java.io.BufferedWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.stream.Collectors;

import com.google.common.collect.Iterables;

import edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData;
import edu.jhu.hlt.fnparse.data.propbank.PropbankReader;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.features.TemplatedFeatures.TemplateDescriptionParsingException;
import edu.jhu.hlt.fnparse.inference.heads.HeadFinder;
import edu.jhu.hlt.fnparse.inference.heads.SemaforicHeadFinder;
import edu.jhu.hlt.fnparse.pruning.DeterministicRolePruning.Mode;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.ActionType;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.hlt.fnparse.rl.rerank.Reranker;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.Timer;
import edu.jhu.prim.tuple.Pair;

/**
 * Computes information gain for the n-gram feature templates and COMMMIT actions.
 *
 * @author travis
 */
public class FeatureIGComputation {

  // n-gram order of the templates being considered
  private int order;

  // What slice of the feature templates to work with (all data is considered at once)
  private int shard, numShards;

  // What data to compute PMI over
  private Iterable<FNParse> data;

  // Where to write the results to (TODO refactor into something a little more flexible)
  private File output;
  private BufferedWriter outputW;
  private boolean outputCountsToo = false;

  public FeatureIGComputation(ExperimentProperties config) {
    this.order = config.getInt("featPMI.order");
    this.shard = config.getInt("shard");
    this.numShards = config.getInt("numShards");
    this.output = config.getFile("featPMI.output");
    try {
      this.outputW = FileUtil.getWriter(output);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // TODO specify other data
    ParsePropbankData.Redis propbankAutoParses = new ParsePropbankData.Redis(config);
    PropbankReader pbr = new PropbankReader(config, propbankAutoParses);

    // Take less data to speed things up
    final int thin = config.getInt("featPMI.thinData", 0);
    if (thin > 1)
      pbr.setKeep(s -> s.hashCode() % thin == 0);

    Iterable<FNParse> x1 = pbr.getTrainData();
    Iterable<FNParse> x2 = pbr.getDevData();
    this.data = Iterables.concat(x1, x2);
  }

  public static void setFrameIdContext(FNParse y, Span t, TemplateContext ctx, HeadFinder hf) {
    Sentence sent = y.getSentence();
    ctx.clear();
    ctx.setSentence(sent);
    ctx.setSpan1(t);
    ctx.setTarget(t);
    ctx.setHead1(hf.head(t, sent));
    ctx.setTargetHead(ctx.getHead1());
  }

  public static void setRoleIdContext(FNParse y, Action commit, TemplateContext ctx, HeadFinder hf) {
    FrameInstance fi = y.getFrameInstance(commit.t);
    Sentence sent = y.getSentence();
    ctx.clear();
    ctx.setSentence(sent);
    ctx.setSpan1(commit.getSpan());
    ctx.setSpan2(fi.getTarget());
    ctx.setHead1(hf.head(ctx.getSpan1(), sent));
    ctx.setHead2(hf.head(ctx.getSpan2(), sent));
//    ctx.setFrame(fi.getFrame());
//    ctx.setRole(commit.k);
    ctx.setArg(ctx.getSpan1());
    ctx.setArgHead(ctx.getHead1());
    ctx.setTarget(ctx.getSpan2());
    ctx.setTargetHead(ctx.getHead2());
  }

  public void run() throws TemplateDescriptionParsingException {

    Reranker r = new Reranker(null, null, null, Mode.XUE_PALMER_HERMANN, null, 1, 1, new Random(9001));
    Counts<String> cyx = new Counts<>();
    Counts<String> cy = new Counts<>();
    Counts<String> cx = new Counts<>();
    TemplateContext ctx = new TemplateContext();

    HeadFinder hf = new SemaforicHeadFinder();
    BasicFeatureTemplates templateIndex = new BasicFeatureTemplates();
    List<String> templates = templateIndex.getBasicTemplateNames();
    List<String> features = products(templates, this.order);
    List<String> featuresRel = features.stream().filter(s -> Math.floorMod(s.hashCode(), numShards) == shard).collect(Collectors.toList());
    Timer t = new Timer("computeIG", 50, true);
    Log.info("starting, features.size=" + features.size() + " featuresRel.size=" + featuresRel.size() + " shard=" + this.shard + " numShards=" + this.numShards);
    features = null;

    // Loop over all features
    for (String feat : featuresRel) {
      Log.info("working on feat=" + feat);

      // START processing this feature
      t.start();
      assert TemplatedFeatures.parseTemplates(feat).size() == 1;
      TemplatedFeatures.Template feature = TemplatedFeatures.parseTemplates(feat).get(0);
      cyx.clear();
      cy.clear();
      cx.clear();

      // Loop over all data
      for (FNParse y : this.data) {
        State st = r.getInitialStateWithPruning(y, y);

        // Look at the features extracted upon COMMITS (which correspond to y indexed by {frame,role,span})
        for (Action commit : ActionType.COMMIT.next(st)) {
          FrameInstance fi = y.getFrameInstance(commit.t);
          String role = fi.getFrame().getRole(commit.k);

          // Setup the context for feature extraction
          setRoleIdContext(y, commit, ctx, hf);

          // c(y), c(x), c(y,x)
          // y = role name
          // x = feat
          // at least y should be an int (fetching from a FV)
          Iterable<String> xValues = feature.extract(ctx);
          if (xValues != null) {
            for (String xv : xValues) {
              cyx.increment(index(role, xv));
              cy.increment(role);
              cx.increment(xv);
            }
          }
        }
      }

      // STOP processing this feature (output PMI, counts)
      StringBuilder sb = new StringBuilder();
      double infoGain = computeIG(cyx, cy, cx);
      sb.append(String.valueOf(infoGain));
      sb.append('\t');
      sb.append(feat);
      if (outputCountsToo) {
        for (String yv : cy.getKeysSortedByCount(true)) {
          for (String xv : cx.getKeysSortedByCount(true)) {
            sb.append("\t"
                + cyx.getCount(index(yv, xv)) + "\t"
                + cy.getCount(yv) + "\t"
                + cx.getCount(xv) + "\t"
                + yv + "\t" + xv);
          }
        }
      }
      try {
        outputW.write(sb.toString());
        outputW.write('\n');
        outputW.flush();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      t.stop();

      if (t.getCount() % 10 == 0) {
        int n = featuresRel.size();
        Log.info("processed " + t.getCount() + " of " + n + ", estimated "
            + t.minutesUntil(n) + " minutes remaining");
        Log.info("memory usage: " + Describe.memoryUsage());
      }
    }
  }

  public static double computeIG(Counts<String> cyx, Counts<String> cy, Counts<String> cx) {
    double ig = 0;
    double N = cyx.getTotalCount();
    assert N == cy.getTotalCount();
    assert N == cx.getTotalCount();
    if (N == 0)
      return 0;
    for (Entry<String, Integer> x : cyx.entrySet()) {
      String syx = x.getKey();
      Pair<String, String> sy_sx = unindex(syx);
      double countYX = x.getValue();
      double countY = cy.getCount(sy_sx.get1());
      double countX = cx.getCount(sy_sx.get2());
      assert countYX <= countY;
      assert countYX <= countX;
      assert countX > 0;
      assert countY > 0;
      double py = countYX / N;
      double pmi = Math.log(countYX * N) - Math.log(countY * countX);
      ig += py * pmi;
      assert !Double.isNaN(ig) && !Double.isInfinite(ig);
    }
    return ig;
  }

  public static String index(String y, String x) {
    return y + "\t" + x;
  }
  public static Pair<String, String> unindex(String both) {
    String[] ar = both.split("\t");
    if (ar.length != 2)
      throw new RuntimeException("extra tab!: " + both);
    return new Pair<>(ar[0], ar[1]);
  }

  public static List<String> products(List<String> templates, int order) {
    if (order < 1)
      throw new IllegalArgumentException();
    if (order == 1)
      return templates;
    List<String> prods = new ArrayList<>();
    int n = templates.size();
    for (int i = 0; i < n - 1; i++) {
      for (int j = i + 1; j < n; j++) {
        if (order == 2) {
          prods.add(templates.get(i) + " * " + templates.get(j));
        } else if (order == 3) {
          for (int k = j + 1; k < n; k++) {
            prods.add(templates.get(i)
                + " * " + templates.get(j)
                + " * " + templates.get(k));
          }
        } else {
          throw new RuntimeException("not implemented! order=" + order);
        }
      }
    }
    return prods;
  }

  public static void main(String[] args) throws TemplateDescriptionParsingException {
    ExperimentProperties config = ExperimentProperties.init(args);

    List<String> templates = Arrays.asList("a", "b", "c", "d", "e");
    System.out.println(products(templates, 1));
    System.out.println(products(templates, 2));
    System.out.println(products(templates, 3));

    if (config.getBoolean("featPMI.doTesting", false)) {
      config.setProperty("shard", "0");
      config.setProperty("numShards", "100");
      config.setProperty("featPMI.order", "2");
      config.setProperty("featPMI.output", "/tmp/pmi1.txt");

      config.setProperty("featPMI.thinData", "100");

      config.setProperty("redis.host.propbankParses", "localhost");
      config.setProperty("redis.port.propbankParses", "6379");
      config.setProperty("redis.db.propbankParses", "0");
      config.setProperty("data.wordnet", "toydata/wordnet/dict");
      config.setProperty("data.embeddings", "data/embeddings");
      config.setProperty("data.ontonotes5", "data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations");
      config.setProperty("data.propbank.conll", "../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data");
      config.setProperty("data.propbank.frames", "data/ontonotes-release-5.0-fixed-frames/frames");
      config.setProperty("disallowConcreteStanford", "false");

      config.setProperty("DeterministicRolePruning.showPruningRecall", "false");
      config.setProperty("Reranker.logArgPruningStats", "false");
    }

    FeatureIGComputation pmi = new FeatureIGComputation(config);
    pmi.run();
  }
}
