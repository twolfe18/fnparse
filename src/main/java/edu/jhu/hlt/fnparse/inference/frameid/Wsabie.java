package edu.jhu.hlt.fnparse.inference.frameid;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph;
import edu.jhu.hlt.fnparse.features.precompute.FeatureFile;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.features.precompute.ProductIndex;
import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.fnparse.inference.frameid.FrameSchemaHelper.Schema;
import edu.jhu.hlt.tutils.Beam;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FPR;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.OrderStatistics;

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

  public static final boolean USE_FLOATS = true;

  private double margin = 0.001;
  private int dimFeat = 1<<16;
  private int dimEmb = 1024;
  private int numTemplates;
  private double[][] V;       // frame embeddings
  private double[][] M;       // feature -> frame embedding projection
  private float[][] Mf;       // feature -> frame embedding projection
  private double[] lossAtRank;
  private int batchSize = 1;
  private double learningRate = 0.5;
  private Random rand;
  private FrameSchemaHelper schemas;

  // Note: If you set this to true, the effective learning rate goes way down
  private boolean averageFeatures = true;

  // Seems to hurt performance a good bit, haven't tried on larger dim embeddings
  private boolean dropout = false;

  // Wrapping means an actual feature value was greater than dimFeat and had
  // to wrap around using modulo.
  private int wrappedFeatures = 0;
  private int totalFeatures = 0;

  // Used to determine template cardinalities
  private BiAlph bialph;
  private int[] templateOffsets;  // number of features for all templates less than the index

  /**
   * @param numFrames is an upper bound on the frame ids we'll see in the feature files.
   * @param numTemplates is an upper bound on the number of templates in the feature files.
   */
  public Wsabie(FrameSchemaHelper schemas, int numTemplates) {
    int numFrames = schemas.numFrames();
    Log.info("dimFeat=" + dimFeat);
    Log.info("dimEmb=" + dimEmb);
    Log.info("numFrames=" + numFrames);
    Log.info("numTemplates=" + numTemplates);
    Log.info("learningRate=" + learningRate);
    Log.info("margin=" + margin);
    Log.info("batchSize=" + batchSize);
    Log.info("dropout=" + dropout);
    Log.info("V requires " + Math.ceil((numFrames*dimEmb*8d)/(1<<20)) + " MB");
    if (USE_FLOATS)
      Log.info("M requires " + Math.ceil((dimFeat*dimEmb*4d)/(1<<20)) + " MB");
    else
      Log.info("M requires " + Math.ceil((dimFeat*dimEmb*8d)/(1<<20)) + " MB");
    this.schemas = schemas;
    rand = new Random(9001);
    this.numTemplates = numTemplates;
    V = new double[numFrames][dimEmb];
    if (USE_FLOATS)
      Mf = new float[dimFeat][dimEmb];
    else
      M = new double[dimFeat][dimEmb];
    randInit();

    lossAtRank = new double[numFrames];
    double lossSum = 0;
    for (int i = 1; i < lossAtRank.length; i++) {
      lossSum += 1d / i;
      lossAtRank[i] = lossSum;
    }
  }

  public void setBialph(BiAlph ba) {
    this.bialph = ba;
    this.templateOffsets = new int[numTemplates];
    int offset = 0;
    for (int i = 0; i < numTemplates; i++) {
      this.templateOffsets[i] = offset;
      offset += ba.getUpperBoundOnNumFeatures(i);
    }
  }

  public void randInit() {
    double rV = 1d / Math.sqrt(V[0].length);
    for (int i = 0; i < V.length; i++)
      randInit(V[i], rV, rand);
    if (USE_FLOATS) {
      float rM = (float) (1d / Math.sqrt(Mf[0].length));
      for (int i = 0; i < Mf.length; i++)
        randInit(Mf[i], rM, rand);
    } else {
      double rM = 1d / Math.sqrt(M[0].length);
      for (int i = 0; i < M.length; i++)
        randInit(M[i], rM, rand);
    }
  }

  public static void randInit(float[] vec, float range, Random r) {
    for (int i = 0; i < vec.length; i++)
      vec[i] = r.nextFloat() * range * 2 - range;
  }
  public static void randInit(double[] vec, double range, Random r) {
    for (int i = 0; i < vec.length; i++)
      vec[i] = r.nextDouble() * range * 2 - range;
  }

  /** Will shuffle given list, so don't expect same order */
  public void train(List<FrameIdExample> data, int numEpoch) {
    int n = (int) (data.size() / batchSize + 0.5);
    int gPtr = 0;
    double r = 0.95;
    double loss = -1;
    int batches = 0;
    for (int e = 0; e < numEpoch; e++) {
      Collections.shuffle(data, rand);
      for (int i = 0; i < n; i++, batches++) {
        double l = batch(data, gPtr);
        if (loss < 0)
          loss = l;
        else
          loss = r * loss + (1-r) * l;
        gPtr += batchSize;
        if (gPtr + batchSize >= data.size()) {
          Collections.shuffle(data, rand);
          gPtr = 0;
        }
        if (i % 10 == 0)
          Log.info("ema(loss)=" + loss + " batches=" + batches);
      }
    }
  }

  /** returns average loss before update */
  public double batch(List<FrameIdExample> g, int gPtr) {
    if (gPtr + batchSize >= g.size())
      throw new IllegalArgumentException("gPtr=" + gPtr + " batchSize=" + batchSize + " g.size=" + g.size());

    if (dropout) {
      for (int b = 0; b < batchSize; b++) {
        FrameIdExample e = g.get(gPtr + b);
        e.newDropout(rand);
      }
    }

    // Measure losses
    double[] loss = new double[batchSize];
    int[] violator = new int[batchSize];
    double[][] gProj = new double[batchSize][dimEmb];
    for (int b = 0; b < batchSize; b++) {
      FrameIdExample e = g.get(gPtr + b);

      // Build target embedding
      Arrays.fill(gProj[b], 0);
      int n = 0;
      for (int i = 0; i < e.targetFeatures.length; i++) {
        if (e.dropout(i))
          continue;
        n++;
        int f = e.targetFeatures[i];
        if (USE_FLOATS) {
          for (int j = 0; j < dimEmb; j++)
            gProj[b][j] += Mf[f][j];
        } else {
          for (int j = 0; j < dimEmb; j++)
            gProj[b][j] += M[f][j];
        }
      }
      if (n == 0)
        continue;
      if (averageFeatures) {
        for (int j = 0; j < dimEmb; j++)
          gProj[b][j] /= n;
      } else if (dropout) {
        for (int j = 0; j < dimEmb; j++)
          gProj[b][j] *= 2;
      }

      // Measure inner product with correct frame embedding
      double scGold = 0;
      for (int j = 0; j < dimEmb; j++)
        scGold += V[e.frame][j] * gProj[b][j];

      // Search for a violator
      Schema s = schemas.getSchema(e.frame);
      int numFrames = schemas.numFrames(s);
      int N = 0;
      violator[b] = -1;
      while (N < numFrames - 1 && violator[b] < 0) {
        int otherY = rand.nextInt(numFrames);
        if (schemas.getSchema(otherY) != s)
          continue;
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
      FrameIdExample e = g.get(gPtr + b);
      int otherFr = violator[b];
      if (otherFr < 0)
        continue;
      double s = loss[b] * stepSize;
      if (averageFeatures)
        s /= e.targetFeatures.length;
      for (int i = 0; i < e.targetFeatures.length; i++) {
        if (e.dropout(i))
          continue;
        int f = e.targetFeatures[i];
        if (USE_FLOATS) {
          for (int j = 0; j < dimEmb; j++) {
            Mf[f][j] += s * V[e.frame][j];
            Mf[f][j] -= s * V[otherFr][j];
          }
        } else {
          for (int j = 0; j < dimEmb; j++) {
            M[f][j] += s * V[e.frame][j];
            M[f][j] -= s * V[otherFr][j];
          }
        }
      }
    }
    // Now write to V, only reading old values of M via gProj
    for (int b = 0; b < batchSize; b++) {
      FrameIdExample e = g.get(gPtr + b);
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
      FrameIdExample e = g.get(gPtr + b);
      // M
      for (int i = 0; i < e.targetFeatures.length; i++) {
        if (e.dropout(i))
          continue;
        int f = e.targetFeatures[i];
        if (USE_FLOATS)
          projectIntoUnitSphere(Mf[f]);
        else
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
  public static void projectIntoUnitSphere(float[] w) {
    double ss = 0;
    for (int i = 0; i < w.length; i++)
      ss += w[i] * w[i];
    if (ss > 1) {
      ss = Math.sqrt(ss);
      for (int i = 0; i < w.length; i++)
        w[i] /= ss;
    }
  }

  /**
   * Only returns beam items from the schema matching the frame in the given
   * example (set e.frame<0 if you want to rank all frames).
   */
  public Beam<Integer> predict(FrameIdExample e, int topK) {

    // Create feature embedding
    double[] gProj = new double[dimEmb];
    for (int i = 0; i < e.targetFeatures.length; i++) {
      int f = e.targetFeatures[i];
      if (USE_FLOATS) {
        for (int j = 0; j < dimEmb; j++)
          gProj[j] += Mf[f][j];
      } else {
        for (int j = 0; j < dimEmb; j++)
          gProj[j] += M[f][j];
      }
    }
    // dropout: don't need to scale by 1/2 here because decision function
    // is invariance to this scale.

    // Score all possible frames
    Beam<Integer> b = Beam.getMostEfficientImpl(topK);
    Schema sc = null;
    if (e.frame >= 0)
      sc = schemas.getSchema(e.frame);
    for (int frame = 0; frame < V.length; frame++) {
      if (schemas.getSchema(frame) != sc)
        continue;
      double s = 0;
      for (int j = 0; j < dimEmb; j++)
        s += V[frame][j] * gProj[j];
      b.push(frame, s);
    }
    return b;
  }

  public List<FrameIdExample> readFile(File f) throws IOException {
    Log.info("reading from: " + f.getPath());
    List<FrameIdExample> l = new ArrayList<>();
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        FrameIdExample e = readLine(line);
        if (e.frame == schemas.nullFrameId())
          continue;
        l.add(e);
      }
    }
    return l;
  }

  public FrameIdExample readLine(String line) {
    // NOTE: By this point we're assuming that input feature files have been
    // filtered so that all features in the file are relevant to classification.
    FeatureFile.Line l = new FeatureFile.Line(line, true);
    int[] y = l.getRoles(false);
    assert y.length == 1;
    int frame = y[0];
    int[] features = new int[l.getFeatures().size()];
    int i = 0;
    for (Feature f : l.getFeatures()) {

      ProductIndex fi;
      if (bialph == null) {
        fi = new ProductIndex(f.template, numTemplates)
          .destructiveProd(f.feature);
      } else {
//        int T = bialph.getUpperBoundOnNumFeatures(f.template);
//        fi = new ProductIndex(f.feature, T)
//          .destructiveProd(f.template);
        int offset = templateOffsets[f.template];
        int Z = templateOffsets[templateOffsets.length-1];
        fi = new ProductIndex(offset + f.feature, Z);
      }
      features[i++] = fi.getProdFeatureModulo(dimFeat);

      totalFeatures++;
      if (fi.getProdFeature() >= dimFeat) {
        wrappedFeatures++;
        if (wrappedFeatures % 2500000 == 0) {
          Log.info(String.format("wrapped %d/%d (%.1f %%) of features",
              wrappedFeatures, totalFeatures, (100d*wrappedFeatures)/totalFeatures));
        }
      }
    }
    return new FrameIdExample(frame, features);
  }

  public FPR accuracy(List<FrameIdExample> instances, int noFrameId) {
    FPR fpr = new FPR(false);
    for (FrameIdExample e : instances) {
      int yhat = predict(e, 1).pop();
      if (yhat != noFrameId) {
        if (yhat == e.frame)
          fpr.accumTP();
        else
          fpr.accumFP();
      } else {
        if (e.frame != noFrameId)
          fpr.accumFN();
      }
    }
    return fpr;
  }

  public String mrr(List<FrameIdExample> instances, int maxRank, int nullFrameId) {
    double right = 0;
    double rightF = 0;
    int totalF = 0;
    for (FrameIdExample e : instances) {
      if (e.frame != nullFrameId)
        totalF++;
      Beam<Integer> yhat = predict(e, maxRank);
      int rank = 0;
      while (yhat.size() > 0) {
        rank++;
        int yh = yhat.pop();
        if (yh == e.frame) {
          right += 1d / rank;
          if (e.frame != nullFrameId)
            rightF += 1d / rank;
          break;
        }
      }
    }
    return String.format("mrr_{all}=%.4f mrr_{trueFrame}=%.4f",
        right/instances.size(), rightF/totalF);
  }

  /** Shuffles all/first arg */
  public static <T> void split(List<T> all, List<T> testAddTo, List<T> trainAddTo, int maxTest, double maxTestProp, Random rand) {
    Collections.shuffle(all, rand);
    int n = (int) Math.min(maxTest, maxTestProp * all.size());
    for (int i = 0; i < all.size(); i++)
      (i < n ? testAddTo : trainAddTo).add(all.get(i));
  }

  public void showPerf(List<FrameIdExample> examples, String prefix) {
    // Get from raw-shards/job-0-of-256/role-names.txt.gz
    // Look for "f=UKN" ("f=NOT_A_FRAME" is a red herring, not used)
    int nullFrameId = schemas.nullFrameId();
    int nNonNull = 0;
    for (FrameIdExample e : examples)
      if (e.frame != nullFrameId)
        nNonNull++;
    int mrrMaxRank = 1000;
    FPR acc = accuracy(examples, nullFrameId);
    String mrr = mrr(examples, mrrMaxRank, nullFrameId);
    Log.info(prefix + " " + acc + " " + mrr +
        " n=" + examples.size() + " n_{trueFrame}=" + nNonNull);
  }

  public static void showStats(List<FrameIdExample> examples, String prefix) {
    BitSet frames = new BitSet();
    OrderStatistics<Integer> numFeats = new OrderStatistics<>();
    Counts<Integer> yCounts = new Counts<>();
    for (FrameIdExample e : examples) {
      frames.set(e.frame);
      numFeats.add(e.targetFeatures.length);
      yCounts.increment(e.frame);
    }
    int k = Math.min(yCounts.numNonZero(), 10);
    Log.info(prefix
        + " numFrames=" + frames.cardinality()
        + " numInstances=" + examples.size()
        + " numFeats=" + numFeats.getOrdersStr()
        + " mostCommonFrames=" + yCounts.getKeysSortedByCount(true).subList(0, k));
  }

  public static void main(String[] args) throws IOException {
    FrameSchemaHelper fsh = new FrameSchemaHelper(
        new File("data/frameid/feb15a/raw-shards/job-0-of-256/role-names.txt.gz"));
    int numTemplates = 2004;
    Wsabie w = new Wsabie(fsh, numTemplates);

    // This triggers a different way of indexing features, see readLine
//    w.setBialph(new BiAlph(new File("data/frameid/feb15a/coherent-shards/alphabet.txt.gz"), LineMode.ALPH));

    List<FrameIdExample> data = new ArrayList<>();
    for (int i = 0; i < 256 /*&& data.size() < 5000*/; i++) {
//      data.addAll(w.readFile(new File("/tmp/frame-id-features/features.txt.gz")));
      File f = new File("data/frameid/feb15a/coherent-shards/features/shard" + i + ".txt.gz");
      if (!f.isFile()) {
        Log.info("skpping: " + f.getPath());
        continue;
      }
      data.addAll(w.readFile(f));
    }
    List<FrameIdExample> train = new ArrayList<>();
    List<FrameIdExample> test = new ArrayList<>();
    int maxTest = 500;
    double maxTestProp = 0.3;
    split(data, test, train, maxTest, maxTestProp, w.rand);
    showStats(train, "[train]");
    showStats(test, "[test]");

    int maxTrainEval = Math.min(train.size(), test.size());
    int passes = 0;
    int k = 1;
    for (int i = 0; i < 150; i++) {
      w.train(train, k);
      passes += k;
      w.showPerf(train.subList(0, maxTrainEval), "train passes=" + passes);
      w.showPerf(test, "test passes=" + passes);
//      FileUtil.serialize(w, new File("/tmp/wsabie-" + passes + ".jser"));
    }
  }
}
