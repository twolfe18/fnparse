package edu.jhu.hlt.entsum;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.ComputeIdf;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.prim.list.FloatArrayList;

/**
 * Finds sentences which are outliers with respect to some structural/linguistic features.
 * 
 * A feature must have a (string-valued) index distinguishing it from other features,
 * and return a real number given a sentence.
 * For example "numberOfEntities|sentLen=23" might return 4.0.
 * 
 * The cost assigned by a feature is:
 *   cost(x|f)  = A(f) * B(x,f)
 *   A(f)       = N / (N + alpha)
 *   B(x,f)     = N / (2*min(values<x, values>x) + beta*N) - 1
 * where
 *   N = total number of sentences f has seen
 *   alpha: smoothing param, set higher for more uniform costs
 *   beta: smoothing param "how similar in badness is the second-max to the max? n+1-th from the n-th (for small n)?"
 *
 * I chose this formula to satisfy the following desiderata:
 * a) 0 == lim_{N -> 0} cost
 * b) cost == 0 for median values
 * c) cost is high for mins/maxes (ranks near 0 and 1)
 *   
 * @author travis
 */
public class OddSentenceScore implements Serializable {
  private static final long serialVersionUID = 8660022542787665079L;

  private double alpha;
  private double beta;
  private Map<String, FloatArrayList> f2r;
  private int numEntries;
  
  public OddSentenceScore() {
    alpha = 64;
//    beta = 32;
    beta = 0.01;
    f2r = new HashMap<>();
    numEntries = 0;
  }
  
  public int numFeatures() {
    return f2r.size();
  }
  
  public int numEntries() {
    return numEntries;
  }
  
  public void prune(int minOcc) {
    int preNE = numEntries;
    List<String> pr = new ArrayList<>();
    for (Entry<String, FloatArrayList> e : f2r.entrySet()) {
      int n = e.getValue().size();
      if (n < minOcc) {
        pr.add(e.getKey());
        numEntries -= n;
        assert numEntries >= 0;
      }
    }
    Log.info("minOcc=" + minOcc
        + " numFeatsBefore=" + f2r.size()
        + " numEntriesBefore=" + preNE
        + " numFeatsAfter=" + (f2r.size()-pr.size())
        + " numEntriesAfter=" + numEntries);
    for (String p : pr)
      f2r.remove(p);
  }
  
  public static List<Feat> features(EffSent x, MultiAlphabet a) {
    Counts<String> pos = new Counts<>();
    int n = x.numTokens();
    for (int i = 0; i < n; i++)
      pos.increment(a.pos(x.parse(i).pos));

    boolean countEndOfSentPunc = false;
    int nuis = x.numNuisanceWords(a, countEndOfSentPunc);
    int cont = x.numContentWords(a);
    int ent = x.numMentions();
    
    List<Feat> fs = new ArrayList<>(pos.numNonZero() + 3);
    for (Entry<String, Integer> e : pos.entrySet()) {
      fs.add(new Feat("c(" + e.getKey() + ")|n=" + n, e.getValue()));
    }
    fs.add(new Feat("c(nuis)|n=" + n, nuis));
    fs.add(new Feat("c(cont)|n=" + n, cont));
    fs.add(new Feat("c(ent)|n=" + n, ent));
    fs.add(new Feat("len(sent)", n));
    return fs;
  }
  
  public static IntPair ltgt(double x, FloatArrayList xs) {
    int lt = 0;
    int gt = 0;
    int n = xs.size();
    for (int i = 0; i < n; i++) {
      double v = xs.get(i);
      if (x <= v) lt++;
      if (x >= v) gt++;
    }
    return new IntPair(lt, gt);
  }
  
  public void observe(EffSent s, MultiAlphabet a) {
    List<Feat> fs = features(s, a);
    for (Feat f : fs) {
      FloatArrayList vs = f2r.get(f.getName());
      if (vs == null) {
        vs = new FloatArrayList();
        f2r.put(f.getName(), vs);
      }
      vs.add((float) f.getWeight());
      numEntries++;
    }
  }
  
  /**
   * Anything above 10 is pretty fishy.
   */
  public double cost(EffSent s, MultiAlphabet a, boolean verbose) {
    List<Feat> fs = features(s, a);
    double c = 0;
    int n = 0;
    for (Feat f : fs) {
      FloatArrayList vs = f2r.get(f.getName());
      if (vs == null) {
        if (verbose)
          System.out.println("unk feat: " + f.getName());
        continue;
      }
      double N = vs.size();
      IntPair ltgt = ltgt(f.getWeight(), vs);
      double m = Math.min(ltgt.first, ltgt.second);
      double A = N / (N + alpha);
      double B = N / (m + beta*N);
      assert A > 0;
      assert B > 0;
      if (verbose) {
        System.out.printf("feat=%-18s  val=%.2f  N=% 5d  lt=% 5d  gt=% 5d  A=%.2f  B=%.2f  C=%.2f\n",
            f.getName(), f.getWeight(), vs.size(), ltgt.first, ltgt.second, A, B, A*B);
      }
      c += A * B;
      n++;
    }
    assert n > 0;
    assert !Double.isNaN(c);
    assert Double.isFinite(c);
    if (verbose)
      System.out.println("average cost: " + (c/n) + "\n");
    return c / n;
  }

  public Comparator<EffSent> byOddnessDesc(final MultiAlphabet a) {
    return new Comparator<EffSent>() {
      @Override
      public int compare(EffSent o1, EffSent o2) {
        double c1 = cost(o1, a, false);
        double c2 = cost(o2, a, false);
        if (c1 > c2)
          return -1;
        if (c2 > c1)
          return +1;
        return 0;
      }
    };
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    File outputJser = config.getFile("outputJser");
    Log.info("outputJser=" + outputJser.getPath());

    OddSentenceScore odd = new OddSentenceScore();

//    File dfF = new File("data/idf/cms/df-cms-simpleaccumulo-twolfe-cag1-nhash12-logb20.jser");
    File dfF = config.getExistingFile("wordDocFreq");
    ComputeIdf df = (ComputeIdf) FileUtil.deserialize(dfF);

//    File entityDirParent = new File("data/facc1-entsum/code-testing-data/tokenized-sentences/dev");
    File entityDirParent = config.getExistingDir("entityDirParent");
    List<File> conll = FileUtil.find(entityDirParent, "glob:**/parse.conll");
    Log.info("found " + conll.size() + " entities to train/count on");
    boolean debug = config.getBoolean("debug", false);
    ReservoirSample<EffSent> show = new ReservoirSample<>(50, new Random(9001));
    TimeMarker tm = new TimeMarker();

    MultiAlphabet a = new MultiAlphabet();
    int nf = 0;
    for (File f : conll) {
      nf++;
      File m = new File(f.getParentFile(), "mentionLocs.txt");
      int numWordsInKey = 2;
      try (EffSent.Iter iter = new EffSent.Iter(f, m, a);
          EffSent.DedupMaW3Iter diter = new EffSent.DedupMaW3Iter(iter, df, numWordsInKey)) {
        while (diter.hasNext()) {
          EffSent sent = diter.next().get1();
          odd.observe(sent, a);
          if (debug)
            show.add(sent);
        }
        if (debug || tm.enoughTimePassed(5))
          Log.info("nf=" + nf + " feats=" + odd.numFeatures() + " vals=" + odd.numEntries() + "\t" + Describe.memoryUsage());
      } catch (Exception e) {
        e.printStackTrace();
        Log.info("skipping");
      }
    }
    
    int minCount = config.getInt("minCount", 30);
    odd.prune(minCount);

    if (debug) {
      List<EffSent> r = new ArrayList<>();
      for (EffSent s : show) r.add(s);
      Collections.sort(r, odd.byOddnessDesc(a));
      int i = 0;
      for (EffSent s : r) {
        i++;
        System.out.println(i + "th most costly sentence:");
        s.showConllStyle(a);
        odd.cost(s, a, true);
      }
    }

    FileUtil.VERBOSE = true;
//    FileUtil.serialize(odd, new File("/tmp/odd.jser"));
    FileUtil.serialize(odd, outputJser);

    Log.info("done");
  }
}
