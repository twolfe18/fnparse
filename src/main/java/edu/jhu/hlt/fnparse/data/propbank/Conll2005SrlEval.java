package edu.jhu.hlt.fnparse.data.propbank;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation.EvalFunc;
import edu.jhu.hlt.fnparse.evaluation.BasicEvaluation;
import edu.jhu.hlt.fnparse.evaluation.SentenceEval;
import edu.jhu.hlt.fnparse.features.precompute.CachedFeatures;
import edu.jhu.hlt.fnparse.rl.full.FModel;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.fnparse.util.FNDiff;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.InputStreamGobbler;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Span;

/**
 * Calls the CoNLL 2005 span-based SRL evaluation script. Basic functionality
 * is available through static methods, but you may also create an instance
 * which holds an output location and implements {@link EvalFunc}.
 * @see BasicEvaluation
 *
 * @author travis
 */
public class Conll2005SrlEval implements EvalFunc {

  private static File srlEvalPl =
      new File("data/conll05st-release-generated-by-mgormley/scripts/srl-eval.pl");

  public static MultiTimer TIMER = new MultiTimer();

  private File outputDir;
  private File evalScript;
  private int count;

  public Conll2005SrlEval(File outputDir, File evalScript) {
    if (!outputDir.isDirectory())
      throw new IllegalArgumentException("outputDir must be a dir: " + outputDir.getPath());
    this.outputDir = outputDir;
    this.evalScript = evalScript;
    this.count = 0;
  }

  @Override
  public String getName() {
    String n = getClass().getName();
    String[] na = n.split("\\.");
    return na[na.length - 1];
  }

  /**
   * Writes out the predictions and output of evaluation script in a uniquely
   * named directory by a counter which increments every time you call this method.
   *
   * @return 0 (doesn't parse out results from output file)
   */
  @Override
  public double evaluate(List<SentenceEval> instances) {
    File d = new File(outputDir, "" + (count++));
    d.mkdirs();
    try {
      srlEval(instances, d, evalScript);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return 0;
  }

  /**
   * Creates a directry called modelName in this instances outputDir and writes
   * out predictions and script output.
   */
  public void evaluate(List<SentenceEval> instances, String modelName) {
    File d = new File(outputDir, modelName);
    if (d.isDirectory()) {
      Log.warn("[main] already exists, not over-writing: " + d.getPath());
      return;
    }
    d.mkdirs();
    try {
      srlEval(instances, d, evalScript);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }


  public static void srlEval(List<SentenceEval> se, File outputDir) throws IOException, InterruptedException {
    srlEval(se, outputDir, srlEvalPl);
  }
  public static void srlEval(List<SentenceEval> se, File outputDir, File evalScript) throws IOException, InterruptedException {
    Log.info("[main] evaluating " + se.size() + " parses and writing results to " + outputDir.getPath());
    if (!outputDir.isDirectory())
      throw new IllegalArgumentException();

    // Write out data in .prop file format
    TIMER.start("setup-io");
    File gf = new File(outputDir, "gold.props.gz");
    File hf = new File(outputDir, "hyp.props.gz");
    File gf2 = new File(outputDir, "gold.text.gz");
    File hf2 = new File(outputDir, "hyp.text.gz");
    File df = new File(outputDir, "diff.text.gz");
    try (BufferedWriter g = FileUtil.getWriter(gf);
        BufferedWriter h = FileUtil.getWriter(hf);
        BufferedWriter g2 = FileUtil.getWriter(gf2);
        BufferedWriter h2 = FileUtil.getWriter(hf2);
        BufferedWriter d = FileUtil.getWriter(df);) {
      for (SentenceEval s : se) {
        writeProps(g, s.getGoldParse());
        writeProps(h, s.getHypothesisParse());
        g2.write(Describe.fnParse(s.getGoldParse()) + "\n");
        h2.write(Describe.fnParse(s.getHypothesisParse()) + "\n");
        d.write(FNDiff.diffArgs(s.getGoldParse(), s.getHypothesisParse(), true) + "\n");
      }
    }
    TIMER.stop("setup-io");

    // Call the evaluation script
    TIMER.start("eval-script");
    String[] command = new String[] {
        evalScript.getPath(),
        gf.getPath(),
        hf.getPath(),
    };
    ProcessBuilder pb = new ProcessBuilder(command);
    Process p = pb.start();
    InputStreamGobbler stdout = new InputStreamGobbler(p.getInputStream());
    InputStreamGobbler stderr = new InputStreamGobbler(p.getErrorStream());
    stdout.start();
    stderr.start();
    int r = p.waitFor();
    if (r != 0)
      throw new RuntimeException("exit value: " + r);
    try (BufferedWriter w = FileUtil.getWriter(new File(outputDir, "performance.txt"))) {
      for (String line : stdout.getLines()) {
        w.write(line);
        w.newLine();
      }
    }
    TIMER.stop("eval-script");
  }

  /**
   * Writes out a {@link FNParse} in the props file format, e.g.
   * train/props/train.02.props.gz
   * @return the number of lines written
   */
  public static int writeProps(Writer w, FNParse y) throws IOException {
    Sentence s = y.getSentence();
    int n = s.size();
    int t = y.numFrameInstances();
    String[][] g = new String[n][1 + t];

    // Grid starts as all "*"s
    for (int i = 0; i < n; i++) {
      Arrays.fill(g[i], "*");
      g[i][0] = "-";
    }

    // Fill in the pred/args
    for (int i = 0; i < t; i++) {
      FrameInstance fi = y.getFrameInstance(i);

      Span tg = fi.getTarget();
      g[tg.start][i + 1] = "(V" + g[tg.start][i + 1];
      g[tg.end - 1][i + 1] += ")";
      for (int j = tg.start; j < tg.end; j++)
        g[j][0] = s.getWord(j);

      Frame f = fi.getFrame();
      int K = f.numRoles();
      for (int k = 0; k < K; k++) {
        Span a = fi.getArgument(k);
        if (a == Span.nullSpan)
          continue;
        g[a.start][i + 1] = "(" + f.getRole(k) + g[a.start][i + 1];
        g[a.end-1][i + 1] += ")";
      }
      for (int k = 0; k < K; k++) {
        for (Span a : fi.getContinuationRoleSpans(k)) {
          g[a.start][i + 1] = "(C-" + f.getRole(k) + g[a.start][i + 1];
          g[a.end-1][i + 1] += ")";
        }
        for (Span a : fi.getReferenceRoleSpans(k)) {
          g[a.start][i + 1] = "(R-" + f.getRole(k) + g[a.start][i + 1];
          g[a.end-1][i + 1] += ")";
        }
      }
    }

    // Do IO
    for (int i = 0; i < n; i++) {
      for (int j = 0; j <= t; j++) {
        if (j > 0)
          w.write('\t');
        w.write(g[i][j]);
      }
      w.write('\n');
    }
    w.write('\n');
    return n + 1;
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    ExperimentProperties.init(args);
    File output = new File("/tmp/conll-2005-prop-style-output");
    if (!output.isDirectory())
      output.mkdirs();
    List<SentenceEval> toEval = new ArrayList<>();
    for (CachedFeatures.Item i : FModel.fooMemo()) {
      FNParse y = i.getParse();
      String n = y.getId() + ".props";
      File f = new File(output, n.replace('/', '-'));
      Log.info("working on " + f.getPath());
      Writer w = FileUtil.getWriter(f);
      writeProps(w, y);
      w.close();
      toEval.add(new SentenceEval(y, y));
    }
    // Run evaluation
    File evaldir = new File(output, "evaluation");
    if (!evaldir.isDirectory())
      evaldir.mkdirs();
    srlEval(toEval, evaldir);
  }
}
