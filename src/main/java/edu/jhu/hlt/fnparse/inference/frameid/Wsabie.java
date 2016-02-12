package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.tutils.Beam;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

/**
 * Based on:
 * http://www.dipanjandas.com/files/acl2014frames.pdf
 * http://www.thespermwhale.com/jaseweston/papers/wsabie-ijcai.pdf
 *
 * score(frame, target, x) =
 *   v(frame)^t (M g(target, x))
 *
 * where:
 *   v(frame) \in R^d
 *   g(target, x) \in {0,1}^n
 *   M \in R^{d \times n}
 *
 * Reads training data produced by {@link FeaturePrecomputation}.
 *
 * @author travis
 */
public class Wsabie implements Serializable {
  private static final long serialVersionUID = 5688564611134317326L;

  public static class Example implements Serializable {
    private static final long serialVersionUID = -3740519714667722215L;
    public final int frame;
    public final int[] targetFeatures;
    public Example(int frame, int[] tf) {
      this.frame = frame;
      this.targetFeatures = tf;
    }
  }

  private double margin = 0.001;
  private int dimFeat = 1<<19;
  private int dimEmb = 512;
  private int numTemplates;
  private double[][] V;       // frame embeddings
  private double[][] M;       // feature -> frame embedding projection
  private double[] lossAtRank;
  private List<Example> g;    // features of (target, sentence)
  private int gPtr = 0;       // pointer to first element of next mini-batch
  private int epoch = 0;
  private int batchSize = 128;
  private double learningRate = 0.05;
  private Random rand;

  // Note: If you set this to true, the effective learning rate goes way down
  private boolean averageFeatures = true;

  /**
   * @param numFrames is an upper bound on the frame ids we'll see in the feature files.
   * @param numTemplates is an upper bound on the number of templates in the feature files.
   */
  public Wsabie(int numFrames, int numTemplates) {
    Log.info("dimFeat=" + dimFeat);
    Log.info("dimEmb=" + dimEmb);
    Log.info("numFrames=" + numFrames);
    Log.info("numTemplates=" + numTemplates);
    Log.info("learningRate=" + learningRate);
    Log.info("margin=" + margin);
    Log.info("batchSize=" + batchSize);
    Log.info("V requires " + Math.ceil((numFrames*dimEmb*8d)/(1<<20)) + " MB");
    Log.info("M requires " + Math.ceil((dimFeat*dimEmb*8d)/(1<<20)) + " MB");
    g = new ArrayList<>();
    rand = new Random(9001);
    this.numTemplates = numTemplates;
    V = new double[numFrames][dimEmb];
    M = new double[dimFeat][dimEmb];
    randInit();

    lossAtRank = new double[numFrames];
    double lossSum = 0;
    for (int i = 1; i < lossAtRank.length; i++) {
      lossSum += 1d / i;
      lossAtRank[i] = lossSum;
    }
  }

  public void randInit() {
    double rV = 1d / Math.sqrt(V[0].length);
    for (int i = 0; i < V.length; i++)
      randInit(V[i], rV, rand);
    double rM = 1d / Math.sqrt(M[0].length);
    for (int i = 0; i < M.length; i++)
      randInit(M[i], rM, rand);
  }

  public static void randInit(double[] vec, double range, Random r) {
    for (int i = 0; i < vec.length; i++)
      vec[i] = r.nextDouble() * range * 2 - range;
  }

  public void train(int numEpoch) {
    int n = (int) (g.size() / batchSize + 0.5);
    double r = 0.95;
    double loss = batch();
    int batches = 0;
    for (int e = 0; e < numEpoch; e++) {
      for (int i = 0; i < n; i++, batches++) {
        if (i % 10 == 0)
          Log.info("ema(loss)=" + loss + " batches=" + batches);
        double l = batch();
        loss = r * loss + (1-r) * l;
      }
    }
  }

  /** returns average loss before update */
  public double batch() {
    if (gPtr + batchSize > g.size()) {
      Log.info("end of epoch " + epoch);
      epoch++;
      gPtr = 0;
      Collections.shuffle(g, rand);
    }

    // Measure losses
    double[] loss = new double[batchSize];
    int[] violator = new int[batchSize];
    double[][] gProj = new double[batchSize][dimEmb];
    for (int b = 0; b < batchSize; b++) {
      Example e = g.get(gPtr + b);

      // Build target embedding
      Arrays.fill(gProj[b], 0);
      for (int i = 0; i < e.targetFeatures.length; i++) {
        int f = e.targetFeatures[i];
        for (int j = 0; j < dimEmb; j++)
          gProj[b][j] += M[f][j];
      }
      if (averageFeatures) {
        for (int j = 0; j < dimEmb; j++)
          gProj[b][j] /= e.targetFeatures.length;
      }

      // Measure inner product with correct frame embedding
      double scGold = 0;
      for (int j = 0; j < dimEmb; j++)
        scGold += V[e.frame][j] * gProj[b][j];

      // Search for a violator
      int numFrames = V.length;
      int N = 0;
      violator[b] = -1;
      while (N < numFrames - 1 && violator[b] < 0) {
        int otherY = rand.nextInt(numFrames);
        if (otherY == e.frame)
          continue;
        double scOther = 0;
        for (int j = 0; j < dimEmb; j++)
          scOther += V[otherY][j] * gProj[b][j];
        N++;
        if (scOther + margin > scGold)
          violator[b] = otherY;
      }
      if (violator[b] >= 0) {
        int estRank = (numFrames - 1) / N;
        loss[b] = lossAtRank[estRank];
      }
    }

    // Take stochastic sub-gradient steps
    // Read from V and update M first
    double stepSize = learningRate / batchSize;
    for (int b = 0; b < batchSize; b++) {
      Example e = g.get(gPtr + b);
      int otherFr = violator[b];
      if (otherFr < 0)
        continue;
      double s = loss[b] * stepSize;
      if (averageFeatures)
        s /= e.targetFeatures.length;
      for (int i = 0; i < e.targetFeatures.length; i++) {
        int f = e.targetFeatures[i];
        for (int j = 0; j < dimEmb; j++) {
          M[f][j] += s * V[e.frame][j];
          M[f][j] -= s * V[otherFr][j];
        }
      }
    }
    // Now write to V, only reading old values of M via gProj
    for (int b = 0; b < batchSize; b++) {
      Example e = g.get(gPtr + b);
      int otherFr = violator[b];
      if (otherFr < 0)
        continue;
      double s = loss[b] * stepSize;
      for (int j = 0; j < dimEmb; j++)    // Sum over i is implicit in gProj
        V[e.frame][j] += s * gProj[b][j];
      for (int j = 0; j < dimEmb; j++)
        V[otherFr][j] -= s * gProj[b][j];
    }

    // Run projection step to make sure params stay within unit hyper-shpere
    for (int b = 0; b < batchSize; b++) {
      Example e = g.get(gPtr + b);
      // M
      for (int i = 0; i < e.targetFeatures.length; i++) {
        int f = e.targetFeatures[i];
        projectIntoUnitSphere(M[f]);
      }
      // V
      projectIntoUnitSphere(V[e.frame]);
      int otherFr = violator[b];
      if (otherFr >= 0)
        projectIntoUnitSphere(V[otherFr]);
    }

    // Return average loss and advance batch
    gPtr += batchSize;
    double lossSum = 0;
    for (int i = 0; i < loss.length; i++)
      lossSum += loss[i];
    return lossSum / loss.length;
  }

  public static void projectIntoUnitSphere(double[] w) {
    double ss = 0;
    for (int i = 0; i < w.length; i++)
      ss += w[i] * w[i];
    if (ss > 1) {
      ss = Math.sqrt(ss);
      for (int i = 0; i < w.length; i++)
        w[i] /= ss;
    }
  }

  public Beam<Integer> predict(Example e, int topK) {

    double[] gProj = new double[dimEmb];
    for (int i = 0; i < e.targetFeatures.length; i++) {
      int f = e.targetFeatures[i];
      for (int j = 0; j < dimEmb; j++)
        gProj[j] += M[f][j];
    }

    Beam<Integer> b = Beam.getMostEfficientImpl(topK);
    for (int frame = 0; frame < V.length; frame++) {
      double s = 0;
      for (int j = 0; j < dimEmb; j++)
        s += V[frame][j] * gProj[j];
      b.push(frame, s);
    }
    return b;
  }

  public void readFileForTraining(File f) throws IOException {
    Log.info("reading from: " + f.getPath());
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        Example e = readLine(line);
        g.add(e);
      }
    }
    Collections.shuffle(g, rand);
  }

  public List<Example> readFile(File f) throws IOException {
    Log.info("reading from: " + f.getPath());
    List<Example> l = new ArrayList<>();
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        Example e = readLine(line);
        l.add(e);
      }
    }
    return l;
  }

  public Example readLine(String line) {
    // NOTE: By this point we're assuming that input feature files have been
    // filtered so that all features in the file are relevant to classification.
    FeatureFile.Line l = new FeatureFile.Line(line, true);
    int[] y = l.getRoles(false);
    assert y.length == 1;
    int frame = y[0];
    int[] features = new int[l.getFeatures().size()];
    int i = 0;
    for (Feature f : l.getFeatures()) {
      features[i++] = new ProductIndex(f.template, numTemplates)
          .destructiveProd(f.feature)
          .getProdFeatureModulo(dimFeat);
    }
    return new Example(frame, features);
  }

  public static void main(String[] args) throws IOException {
    int numFrames = 9500;
    int numTemplates = 2004;
    Wsabie w = new Wsabie(numFrames, numTemplates);
    w.readFileForTraining(new File("/tmp/frame-id-features/features.txt.gz"));
    int k = 10;
    for (int i = 0; i < 10; i++) {
      w.train(k);
      FileUtil.serialize(w, new File("/tmp/wsabie-" + (i+1)*k + ".jser"));
    }
  }
}
