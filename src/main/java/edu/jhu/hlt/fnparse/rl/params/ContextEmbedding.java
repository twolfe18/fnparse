package edu.jhu.hlt.fnparse.rl.params;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.params.EmbeddingParams.ContextEmbeddingParams;
import edu.jhu.hlt.fnparse.rl.params.EmbeddingParams.EmbeddingAdjoints;
import edu.jhu.hlt.fnparse.util.RandomInitialization;
import edu.jhu.prim.vector.IntDoubleDenseVector;
import edu.jhu.prim.vector.IntDoubleVector;
import edu.jhu.util.Alphabet;

/**
 * 1) in span
 * 2) span left context         (k-words)
 * 3) span right context        (k-words)
 * 4) entire sentence
 * 
 * TODO this can be cached on span (will be computed many times for each (t,k))
 * (this is even more extreme caching that Params.Stateless.Caching)
 * 
 * @author travis
 */
public class ContextEmbedding implements ContextEmbeddingParams {

  static class WordEmbeddings {
    private double[][] embeddings;
    private int dimension;
    private Alphabet<String> words;

    public WordEmbeddings() {
      dimension = -1;
      words = new Alphabet<>();
    }

    public int getDimension() {
      return dimension;
    }

    private String normalize(String w) {
      return w.toLowerCase();
    }

    public int lookupIndex(String w, boolean addIfNotPresent) {
      return words.lookupIndex(normalize(w), addIfNotPresent);
    }

    public void initializeEmbeddings(int dimension, Random r, double variance) {
      this.dimension = dimension;
      embeddings = new double[words.size()][dimension];
      new RandomInitialization(r).unif(embeddings, variance);
    }

    public void getEmbedding(int word, double[] addTo) {
      if (word >= 0)
        System.arraycopy(embeddings[word], 0, addTo, 0, dimension);
      else
        Arrays.fill(addTo, 0d);
    }

    public void getEmbedding(String w, double[] addTo) {
      if (addTo.length != this.dimension)
        throw new IllegalArgumentException();
      int i = words.lookupIndex(normalize(w), false);
      if (i >= 0)
        System.arraycopy(embeddings[i], 0, addTo, 0, dimension);
      else
        Arrays.fill(addTo, 0d);
    }

    public void update(
        String w,
        double[] deriv,
        double learningRate,
        double l2Penalty) {
      int i = words.lookupIndex(normalize(w), false);
      if (i >= 0)
        update(i, deriv, learningRate, l2Penalty);
    }

    public void update(
        int i,
        double[] dScore_dForwards,
        double learningRate,
        double l2Penalty) {
      if (i < 0)
        return; // OOV
      double[] emb = embeddings[i];
      assert dimension == emb.length && dimension == dScore_dForwards.length;
      for (int j = 0; j < dimension; j++)
        emb[j] += learningRate * (dScore_dForwards[j] - l2Penalty * 2 * emb[j]);
    }
  }

  public static final String SENT_START = "<S>";
  public static final String SENT_END = "</S>";

  static class EmbAvg {
    double[] emb;
    int[] words;
    double[] weights;
    public EmbAvg(int[] words, double[] weights) {
      this.words = words;
      this.weights = weights;

      // given weights should sum to 1
      double z = 0;
      for (double w : weights) z += w;
      assert Math.abs(z - 1d) < 1e-5;
    }
    public void constructEmbedding(WordEmbeddings from) {
      int D = from.getDimension();
      emb = new double[D];
      double[] temp = new double[D];
      for (int i = 0; i < words.length; i++) {
        double w = weights[i];
        from.getEmbedding(words[i], temp);
        for (int j = 0; j < D; j++) {
          emb[j] += w * temp[j];
          temp[j] = 0d;
        }
      }
    }
    public void update(
        double[] dScore,
        double learningRate,
        double l2Penalty,
        WordEmbeddings from) {
      for (int i = 0; i < words.length; i++)
        from.update(words[i], dScore, learningRate * weights[i], l2Penalty);
    }
    public double[] getEmbedding() {
      assert emb != null;
      return emb;
    }
    public static EmbAvg unif(int start, int end, Sentence s, WordEmbeddings we) {
      int n = end - start;
      if (n < 0) n = 0;
      double[] weights = new double[n];
      int[] words = new int[n];
      for (int i = 0; i < n; i++) {
        String w;
        if (start + i < 0)
          w = SENT_START;
        else if (start + i >= s.size())
          w = SENT_END;
        else
          w = s.getWord(start + i);
        words[i] = we.lookupIndex(w, false);
        weights[i] = 1d / n;
      }
      EmbAvg ea = new EmbAvg(words, weights);
      ea.constructEmbedding(we);
      return ea;
    }
  }
  class CtxEmb implements EmbeddingAdjoints {
    EmbAvg eSpan;
    EmbAvg eLeft;
    EmbAvg eRight;
    EmbAvg eSent;
    double[] stacked;
    public CtxEmb(Sentence sent, Span target, Action action) {
      int D = wordEmb.getDimension();
      stacked = new double[4 * D];
      if (action.hasSpan()) {
        Span arg = action.getSpan();
        eSpan = EmbAvg.unif(arg.start, arg.end, sent, wordEmb);
        eLeft = EmbAvg.unif(arg.start - contextWordsLeft, arg.start, sent, wordEmb);
        eRight = EmbAvg.unif(arg.end, arg.end + contextWordsRight, sent, wordEmb);
        eSent = EmbAvg.unif(0, sent.size(), sent, wordEmb);
        System.arraycopy(eSpan.getEmbedding(),  0, stacked, 0*D, D);
        System.arraycopy(eLeft.getEmbedding(),  0, stacked, 1*D, D);
        System.arraycopy(eRight.getEmbedding(), 0, stacked, 2*D, D);
        System.arraycopy(eSent.getEmbedding(),  0, stacked, 3*D, D);
      }
    }
    @Override
    public boolean takesUpdates() {
      return true;
    }
    @Override
    public IntDoubleVector forwards() {
      return new IntDoubleDenseVector(stacked);
    }
    @Override
    public void backwards(IntDoubleVector dScore_dForwards_v) {
      double[] dScore_dForwards =
          ((IntDoubleDenseVector) dScore_dForwards_v).getInternalElements();
      int D = wordEmb.getDimension();
      assert dScore_dForwards.length == 4*D;
      for (EmbAvg ea : Arrays.asList(eSpan, eLeft, eRight, eSent)) {
        if (ea == null) continue;
        ea.update(dScore_dForwards, learningRate, l2Penalty, wordEmb);
      }
    }
  }

  private WordEmbeddings wordEmb;
  private int contextWordsLeft = 3;
  private int contextWordsRight = 3;
  private int dimensionPerWord;
  private double learningRate;
  private double l2Penalty;

  public ContextEmbedding(int dimension, double l2Penalty) {
    wordEmb = new WordEmbeddings();
    wordEmb.lookupIndex(SENT_START, true);
    wordEmb.lookupIndex(SENT_END, true);
    Iterator<FNTagging> iter = FileFrameInstanceProvider.fn15lexFIP.getParsedOrTaggedSentences();
    while (iter.hasNext()) {
      Sentence s = iter.next().getSentence();
      for (int i = 0; i < s.size(); i++)
        wordEmb.lookupIndex(s.getWord(i), true);
    }
    this.dimensionPerWord = dimension;
    this.learningRate = 1d;
    this.l2Penalty = l2Penalty;
  }

  @Override
  public void initialize(double variance, Random rand) {
    wordEmb.initializeEmbeddings(dimensionPerWord, rand, variance);
  }

  public CtxEmb embed(Sentence sent, Span target, Action action) {
    return new CtxEmb(sent, target, action);
  }

  @Override
  public int dimension() {
    return 4 * dimensionPerWord;
  }

  @Override
  public EmbeddingAdjoints embed(FNTagging frames, Span target, Action action) {
    return new CtxEmb(frames.getSentence(), target, action);
  }
}
