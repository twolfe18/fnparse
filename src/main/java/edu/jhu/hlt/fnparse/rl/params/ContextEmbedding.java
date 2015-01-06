package edu.jhu.hlt.fnparse.rl.params;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.rl.Action;
import edu.jhu.hlt.fnparse.rl.State;
import edu.jhu.util.Alphabet;

/**
 * 1) in span
 * 2) span left context         (k-words)
 * 3) span right context        (k-words)
 * 4) entire sentence
 * 
 * TODO this can be cached on span (will be computed many times for each (t,k))
 * 
 * @author travis
 */
public class ContextEmbedding {

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
      int n = words.size();
      embeddings = new double[n][dimension];
      for (int i = 0; i < n; i++)
        for (int j = 0; j < dimension; j++)
          embeddings[i][j] = r.nextGaussian() * variance;
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

    public void update(String w, double[] deriv, double learningRate) {
      int i = words.lookupIndex(normalize(w), false);
      update(i, deriv, learningRate);
    }

    public void update(int i, double[] deriv, double learningRate) {
      if (i >= 0) {
        double[] emb = embeddings[i];
        for (int j = 0; j < dimension; j++)
          emb[j] += learningRate * deriv[j];
      }
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
    }
    public void constructEmbedding(WordEmbeddings from) {
      int D = from.getDimension();
      emb = new double[D];
      double[] temp = new double[D];
      for (int i = 0; i < words.length; i++) {
        from.getEmbedding(words[i], temp);
        for (int j = 0; j < D; j++) {
          emb[j] += weights[i] * temp[j];
          temp[j] = 0d;
        }
      }
      double z = 0d;
      for (double w : weights) z += w;
      for (int j = 0; j < D; j++) emb[j] /= z;
    }
    public void update(double[] dErr, WordEmbeddings from) {
      for (int i = 0; i < words.length; i++)
        from.update(words[i], dErr, weights[i]);
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
        weights[i] = 1d;
      }
      EmbAvg ea = new EmbAvg(words, weights);
      ea.constructEmbedding(we);
      return ea;
    }
  }
  class CtxEmb {
    EmbAvg eSpan;
    EmbAvg eLeft;
    EmbAvg eRight;
    EmbAvg eSent;
    double[] stacked;
    public CtxEmb(Sentence sent, Span target, Action action, State state) {
      int D = wordEmb.getDimension();
      stacked = new double[4 * D];
      if (action.hasSpan()) {
        Span arg = action.getSpan();
        eSpan = EmbAvg.unif(arg.start, arg.end, sent, wordEmb);
        eLeft = EmbAvg.unif(arg.start - contextWordsLeft, arg.start, sent, wordEmb);
        eRight = EmbAvg.unif(arg.end, arg.end + contextWordsRight, sent, wordEmb);
        eSent = EmbAvg.unif(0, sent.size(), sent, wordEmb);
        System.arraycopy(eSpan.getEmbedding(), 0, stacked, 0, D);
        System.arraycopy(eLeft.getEmbedding(), 0, stacked, D, D);
        System.arraycopy(eRight.getEmbedding(), 0, stacked, 2*D, D);
        System.arraycopy(eSent.getEmbedding(), 0, stacked, 3*D, D);
      }
    }
    public double[] getEmbedding() {
      return stacked;
    }
    public void update(double[] deriv, double learningRate) {
      int D = wordEmb.getDimension();
      double[] temp = new double[D];
      assert deriv.length == 4*D;
      for (EmbAvg ea : Arrays.asList(eSpan, eLeft, eRight, eSent)) {
        if (ea == null) continue;
        System.arraycopy(ea.getEmbedding(), 0, temp, 0, D);
        ea.update(temp, wordEmb);
      }
    }
  }

  private WordEmbeddings wordEmb;
  private int contextWordsLeft = 3;
  private int contextWordsRight = 3;

  public ContextEmbedding(int dimension) {
    wordEmb = new WordEmbeddings();
    wordEmb.lookupIndex(SENT_START, true);
    wordEmb.lookupIndex(SENT_END, true);
    Iterator<FNTagging> iter = FileFrameInstanceProvider.fn15lexFIP.getParsedOrTaggedSentences();
    while (iter.hasNext()) {
      Sentence s = iter.next().getSentence();
      for (int i = 0; i < s.size(); i++)
        wordEmb.lookupIndex(s.getWord(i), true);
    }
    wordEmb.initializeEmbeddings(dimension, new Random(9001), 0.05d);
  }

  public int getDimension() {
    return 4 * wordEmb.getDimension();
  }

  public CtxEmb embed(Sentence sent, Span target, Action action, State state) {
    return new CtxEmb(sent, target, action, state);
  }

  public void update(CtxEmb adjoints, double[] dErr_dEmbedding, double learningRate) {
    adjoints.update(dErr_dEmbedding, learningRate);
  }
}
