package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.KbpSearching;
import edu.jhu.hlt.ikbp.tac.FindCommonPredicate.PFeat;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.PkbpSearching.New.MultiEntityMention;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.LabeledDirectedGraph;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;
import edu.jhu.util.DiskBackedFetchWrapper;
import edu.jhu.util.TokenizationIter;

public class DependencyTreeRandomWalk {
  
  public static boolean INCLUDE_TOP_DOWN_FACTOR = false;
  
  private LabeledDirectedGraph deps;
  private double[] beliefs;
  private int source;
  
  public DependencyTreeRandomWalk(int source, int sentenceLength, DependencyParse deps) {
    if (deps != null)
      this.deps = LabeledDirectedGraph.fromConcrete(deps, sentenceLength, null);
    this.beliefs = new double[sentenceLength];
    this.source = source;
  }
  
  public static DependencyTreeRandomWalk walkFromRoot(int sentenceLength, DependencyParse deps) {
    for (Dependency d : deps.getDependencyList()) {
      if (!d.isSetGov() || d.getGov() < 0) {
        int root = d.getDep();
        return new DependencyTreeRandomWalk(root, sentenceLength, deps);
      }
    }
    return null;
  }

  public void iterate(double pSource, int n) {
    for (int i = 0; i < n; i++)
      iterate(pSource);
  }
  
  public void iterate(double pSource) {
    double[] next = new double[beliefs.length];
    next[source] += pSource;
    for (int i = 0; i < next.length; i++) {
      LabeledDirectedGraph.Node node = deps.getNode(i);
      if (node == null)
        continue;
      int[] out = node.getNeighbors();
      double p = beliefs[i] * (1-pSource) / out.length;
      for (int j = 0; j < out.length; j++) {
        if (out[j] >= beliefs.length)   // redirect mass going from the root towards the source
          next[source] += p;
        else
          next[out[j]] += p;
      }
    }
    beliefs = next;
  }
  
  public void showBeliefs(Tokenization t) {
    if (t.getTokenList().getTokenListSize() != beliefs.length)
      throw new IllegalArgumentException();
    for (int i = 0; i < beliefs.length; i++) {
      System.out.printf("% 3d  %-12s %.3f", i, t.getTokenList().getTokenList().get(i).getText(), Math.log(beliefs[i]));
      if (i == source)
        System.out.println(" <= source");
      else
        System.out.println();
    }
  }
  
  public static double[] logOfProbProd(double[] a, double[] b) {
    if (a.length != b.length)
      throw new IllegalArgumentException();
    double[] c = new double[a.length];
    for (int i = 0; i < c.length; i++)
      c[i] = Math.log(a[i]) + Math.log(b[i]);
    return c;
  }
  
  public static void anneal(double rate, double[] a) {
    if (rate <= 0)
      throw new IllegalArgumentException();
    for (int i = 0; i < a.length; i++)
      a[i] /= rate;
  }
  
  public static void setMaxTo(double newMax, double[] a) {
    double oldMax = a[0];
    for (int i = 1; i < a.length; i++)
      oldMax = Math.max(oldMax, a[i]);
    double delta = newMax - oldMax;
    for (int i = 0; i < a.length; i++)
      a[i] += delta;
  }
  
  public static void convertLogProbsToProbs(double[] lp) {
    double z = 0;
    for (int i = 0; i < lp.length; i++) {
      lp[i] = Math.exp(lp[i]);
      z += lp[i];
    }
    assert !Double.isNaN(z);
    assert Double.isFinite(z);
    assert z > 0;
    for (int i = 0; i < lp.length; i++)
      lp[i] /= z;
  }
  
  static class WalkFeat implements Serializable {
    private static final long serialVersionUID = 1119162602335405132L;

    public final String arg0Type, arg1Type;
    public final String dest;
    private int count;    // for merging samples
    
    public WalkFeat(String a0Type, String a1Type, String dest) {
      if (a0Type.compareTo(a1Type) > 0) {
        arg0Type = a1Type;
        arg1Type = a0Type;
      } else {
        arg0Type = a0Type;
        arg1Type = a1Type;
      }
      this.dest = dest;
      this.count = 1;
    }
    
    @Override
    public int hashCode() {
      return Hash.mix(arg0Type.hashCode(), arg1Type.hashCode(), dest.hashCode());
    }
    
    @Override
    public boolean equals(Object other) {
      if (other instanceof WalkFeat) {
        WalkFeat wf = (WalkFeat) other;
        return arg0Type.equals(wf.arg0Type)
            && arg1Type.equals(wf.arg1Type)
            && dest.equals(wf.dest);
      }
      return false;
    }
    
    @Override
    public String toString() {
      return "(WF " + arg0Type + " " + arg1Type + " " + dest + ")";
    }
    
    public static WalkFeat argTypesKey(WalkFeat wf) {
      return argTypesKey(wf.arg0Type, wf.arg1Type);
    }
    public static WalkFeat argTypesKey(String a0, String a1) {
      return new WalkFeat(a0, a1, "");
    }
  }
  
  public static ReservoirSample<WalkFeat> sample(Random rand, Tokenization t, int nSamples) {
    return sample(rand, t, 0.1, 2, nSamples, false);
  }

  public static ReservoirSample<WalkFeat> sample(Random rand, Tokenization t, double pStay, double anneal, int nSamples, boolean verbose) {
    if (verbose)
      System.out.println("#######################################################################");
    ReservoirSample<WalkFeat> res = new ReservoirSample<>(nSamples, rand);
    DependencyParse deps = IndexCommunications.getPreferredDependencyParse(t);
    if (deps != null) {
      int n = t.getTokenList().getTokenListSize();
      List<Integer> args = DependencySyntaxEvents.extractEntityHeads(t);
      List<DependencyTreeRandomWalk> walks = new ArrayList<>();
      for (int arg : args) {
        DependencyTreeRandomWalk walk = new DependencyTreeRandomWalk(arg, n, deps);
        for (int i = 0; i < 3*n; i++)
          walk.iterate(pStay);
        walks.add(walk);
      }

      // Compute the product
      for (int i = 0; i < args.size()-1; i++) {
        for (int j = i+1; j < args.size(); j++) {
          DependencyTreeRandomWalk w1 = walks.get(i);
          DependencyTreeRandomWalk w2 = walks.get(j);
          List<WalkFeat> wfwf = sample(t, w1, w2, anneal, nSamples, rand, verbose);
          for (WalkFeat wf : wfwf)
            res.add(wf);
        }
      }
    }
    return res;
  }
  
  public static int sample(Random rand, double[] probs) {
    double r = rand.nextDouble();
    double s = 0;
    for (int i = 0; i < probs.length; i++) {
      assert probs[i] >= 0;
      assert probs[i] <= 1;
      s += probs[i];
      if (s >= r)
        return i;
    }
    return -1;
  }
  
  public static boolean allLogZero(double[] b) {
    for (int i = 0; i < b.length; i++)
      if (!(Double.isInfinite(b[i]) && b[i] < 0))
        return false;
    return true;
  }

  /**
   * NOTE: Does not have root-flow log probability term
   */
  public static List<WalkFeat> sample(
      Tokenization t,
      DependencyTreeRandomWalk w1,
      DependencyTreeRandomWalk w2,
      double anneal,
      int nSamples,
      Random rand,
      boolean verbose) {
    if (verbose)
      Log.info("s1=" + w1.source + " s2=" + w2.source + " nSamples=" + nSamples + " anneal=" + anneal);
    double[] b = logOfProbProd(w1.beliefs, w2.beliefs);
    if (allLogZero(b))
      return Collections.emptyList();
    anneal(anneal, b);
    setMaxTo(2, b);
    convertLogProbsToProbs(b);
    if (verbose) {
      DependencyTreeRandomWalk tmp = new DependencyTreeRandomWalk(-1, b.length, null);
      tmp.beliefs = b;
      tmp.showBeliefs(t);
      System.out.println();
    }
    TokenTagging ner = IndexCommunications.getPreferredNerTags(t);
    TokenTagging pos = IndexCommunications.getPreferredPosTags(t);
    String a0 = ner.getTaggedTokenList().get(w1.source).getTag().intern();
    String a1 = ner.getTaggedTokenList().get(w2.source).getTag().intern();
    List<WalkFeat> wf = new ArrayList<>();
    for (int i = 0; i < nSamples; i++) {
      int s = sample(rand, b);
      if (s < 0) {
        System.out.println("inexact math? " + Arrays.toString(b));
        continue;
      }
      String word = t.getTokenList().getTokenList().get(s).getText()
          + "." + pos.getTaggedTokenList().get(s).getTag();
      wf.add(new WalkFeat(a0, a1, word));
    }
    return wf;
  }
  
  static class WalkFeatCounts implements Serializable {
    private static final long serialVersionUID = 904047685299756787L;

    private Map<WalkFeat, StringCountMinSketch> counts;
    private StringCountMinSketch countsBackoff;
    private int nhash, logb;
    private int maxSentLen;
    private Set<String> importantNerTypes;
    
    public WalkFeatCounts() {
      nhash = 10;
      logb = 19;
      maxSentLen = 60;
      counts = new HashMap<>();
      countsBackoff = new StringCountMinSketch(nhash, logb+1, true);
      importantNerTypes = new HashSet<>();
      importantNerTypes.add("PERSON");
      importantNerTypes.add("ORGANIZATION");
    }
    
    public boolean keep(WalkFeat wf) {
      if (importantNerTypes.contains(wf.arg0Type))
        return true;
      if (importantNerTypes.contains(wf.arg1Type))
        return true;
      return false;
    }
    
    public int get(String a0Type, String a1Type, String dest) {
      WalkFeat key = WalkFeat.argTypesKey(a0Type, a1Type);
      StringCountMinSketch cms = counts.get(key);
      if (cms == null)
        return 0;
      int c = cms.apply(dest, false);
      c += countsBackoff.apply(dest, false) / (1+counts.size());
      return c;
    }
    
    public void increment(WalkFeat wf) {
      if (!keep(wf))
        return;
      assert wf.count == 1;
      WalkFeat key = WalkFeat.argTypesKey(wf);
      StringCountMinSketch cms = counts.get(key);
      if (cms == null) {
        Log.info("new NER type pair: " + key);
        cms = new StringCountMinSketch(nhash, logb, true);
        counts.put(key, cms);
      }
      cms.apply(wf.dest, true);
      countsBackoff.apply(wf.dest, true);
    }
    
    public void increment(Random rand, Communication c) {
      boolean verbose = false;
      double pStay = 0.1;
      int nSamples = 30;
      double anneal = 2;
      List<WalkFeat> wfs = new ArrayList<>();
      for (Tokenization t : new TokenizationIter(c)) {
        if (t.getTokenList().getTokenListSize() > maxSentLen)
          continue;
        ReservoirSample<WalkFeat> sample = sample(rand, t, pStay, anneal, nSamples, verbose);
        for (WalkFeat wf : sample)
          wfs.add(wf);
      }
      Collections.shuffle(wfs, rand);
      for (WalkFeat wf : wfs)
        increment(wf);
    }
  }

  static class Analysis {
    private Tokenization toks;
    private String[] words;   // word.pos
//    private WalkFeatCounts wfc;
    private int arg0, arg1;
    private double[] b;
    private double[] argWalkLogProbs;
    private double[] rootWalkLogProbs;
    private double[] logWordFreq;
    private int[] wordFreq;

    public Analysis(Tokenization t, int arg0, int arg1, WalkFeatCounts wfc) {
      toks = t;
      this.arg0 = arg0;
      this.arg1 = arg1;
//      this.wfc = wfc;
      int n = t.getTokenList().getTokenListSize();
      DependencyParse deps = IndexCommunications.getPreferredDependencyParse(t);
      DependencyTreeRandomWalk w1 = new DependencyTreeRandomWalk(arg0, n, deps);
      DependencyTreeRandomWalk w2 = new DependencyTreeRandomWalk(arg1, n, deps);
      DependencyTreeRandomWalk wr = DependencyTreeRandomWalk.walkFromRoot(n, deps);

      double pSource = 0.1;
      w1.iterate(pSource, 3*n);
      w2.iterate(pSource, 3*n);
      wr.iterate(pSource, 3*n);

      rootWalkLogProbs = Arrays.copyOf(wr.beliefs, wr.beliefs.length);
      if (INCLUDE_TOP_DOWN_FACTOR) {
        for (int i = 0; i < rootWalkLogProbs.length; i++)
          rootWalkLogProbs[i] = Math.log(rootWalkLogProbs[i]);
      } else {
        Arrays.fill(rootWalkLogProbs, 0);
      }
      
      logWordFreq = new double[rootWalkLogProbs.length];
      wordFreq = new int[rootWalkLogProbs.length];

      b = logOfProbProd(w1.beliefs, w2.beliefs);
      argWalkLogProbs = Arrays.copyOf(b, b.length);
      if (!allLogZero(b)) {

        words = new String[logWordFreq.length];
        TokenTagging ner = IndexCommunications.getPreferredNerTags(t);
        TokenTagging pos = IndexCommunications.getPreferredPosTags(t);
        String a0Type = ner.getTaggedTokenList().get(arg0).getTag();
        String a1Type = ner.getTaggedTokenList().get(arg1).getTag();
        for (int i = 0; i < b.length; i++) {
          words[i] = t.getTokenList().getTokenList().get(i).getText()
            + "." + pos.getTaggedTokenList().get(i).getTag();
          wordFreq[i] = wfc.get(a0Type, a1Type, words[i]);
          logWordFreq[i] = Math.log(wordFreq[i] + 10);
          b[i] -= logWordFreq[i];
          b[i] += rootWalkLogProbs[i];
        }

        setMaxTo(8, b);
        anneal(INCLUDE_TOP_DOWN_FACTOR ? 4 : 3, b);
        convertLogProbsToProbs(b);
      }
    }
    
    public void show() {
      TokenTagging ner = IndexCommunications.getPreferredNerTags(toks);
      TokenTagging pos = IndexCommunications.getPreferredPosTags(toks);
      String a0Type = ner.getTaggedTokenList().get(arg0).getTag();
      String a1Type = ner.getTaggedTokenList().get(arg1).getTag();
      System.out.printf("% 03d  %-16s\t%s\t%s\t%s\n", 0, "WORD", "logWordFreq", "rootWalkLogProb", "argWalkLogProb", "logBelief");
      for (int i = 0; i < b.length; i++) {
        String word = toks.getTokenList().getTokenList().get(i).getText()
            + "." + pos.getTaggedTokenList().get(i).getTag();
        System.out.printf("% 03d  %-16s\t%.3f\t%.3f\t%.3f\t%.3f", i, word, logWordFreq[i], rootWalkLogProbs[i], argWalkLogProbs[i], Math.log(b[i]));
        if (i == arg0)
          System.out.println("\t<= arg0 " + a0Type);
        else if (i == arg1)
          System.out.println("\t<= arg1 " + a1Type);
        else
          System.out.println();
      }
      System.out.println();
    }
    
    public List<Integer> getBestTriggerWordIndices() {
      List<Integer> a = new ArrayList<>();
      int n = toks.getTokenList().getTokenListSize();
      for (int i = 0; i < n; i++)
        a.add(i);
      Collections.sort(a, new Comparator<Integer>() {
        @Override
        public int compare(Integer o1, Integer o2) {
          if (b[o1] > b[o2])
            return -1;
          if (b[o1] < b[o2])
            return +1;
//          if (b[o1] < b[o2])  // log probs, not relevant
//            return -1;
//          if (b[o1] > b[o2])
//            return +1;
          return 0;
        }
      });
      return a;
    }
  }

  /** proposes possible trigger words in one sentence */
  public static List<FindCommonPredicate.PFeat> explain(MultiEntityMention mem, WalkFeatCounts wfc, int maxTriggerTheories) {
    if (mem.alignedMentions.length != 2)
      throw new IllegalArgumentException();
    List<FindCommonPredicate.PFeat> pf = new ArrayList<>();
    Tokenization t = mem.pred.getTokenization();
    String tokUuid = t.getUuid().getUuidString();
    int arg0 = mem.getMention(0).head;
    int arg1 = mem.getMention(1).head;
    Span s0 = mem.getMention(0).span;
    Span s1 = mem.getMention(1).span;
    Analysis a = new Analysis(t, arg0, arg1, wfc);
    List<Integer> best = a.getBestTriggerWordIndices();
    for (int i = 0; i < best.size() && pf.size() < maxTriggerTheories; i++) {
      int idx = best.get(i);
      if (idx == arg0 || idx == arg1)
        continue;
      if (s0.covers(idx) || s1.covers(idx))
        continue;
      if (a.wordFreq[idx] < 8) {
        Log.info("too rare: " + a.words[idx] + " count=" + a.wordFreq[idx]);
        continue;
      }
      String w = t.getTokenList().getTokenList().get(idx).getText().toLowerCase();
      FindCommonPredicate.PFeat p = new FindCommonPredicate.PFeat("wfc", a.words[idx], w, idx, tokUuid);
      assert a.b[idx] >= 0 : "idx=" + idx + " b=" + a.b[idx];
      assert Double.isFinite(a.b[idx]);
      p.weight.add(new Feat("belief", a.b[idx]));

      p.origPredArgs = new int[3];
      p.origPredArgs[0] = mem.pred.head;
      p.origPredArgs[1] = arg0;
      p.origPredArgs[2] = arg1;

      pf.add(p);
    }
    return pf;
  }

  /** aggregates possible trigger words across many sentences (one per MEM) */
  public static FindCommonPredicate.Explanation findBestExplanation(List<MultiEntityMention> mems, WalkFeatCounts wfc, boolean useLinkScore) {
    FindCommonPredicate.Explanation ex = new FindCommonPredicate.Explanation();
    int triggersPerSent = 5;
    for (MultiEntityMention mem : mems) {
      double ls = 2d + Math.sqrt(mem.getLinkingScore());
      for (PFeat pf : explain(mem, wfc, triggersPerSent)) {
        if (useLinkScore) {
          for (Feat f : pf.weight)
            f.rescale("MEMlinkScore", ls);
        }
        ex.add(pf);
      }
    }
    return ex;
  }
  
  public static final File FETCH_CACHE_DIR = new File("../fetch-comms-cache");
//  public static final File FETCH_CACHE_DIR = new File("data/sit-search/fetch-comms-cache");
  
  public static ReservoirSample<String> getRandomCommIds(int n, Random rand) {
    ReservoirSample<String> comms = new ReservoirSample<>(n, rand);
    for (String fn : FETCH_CACHE_DIR.list()) {
      String suf = ".comm.gz";
      if (fn.endsWith(suf)) {
        String id = fn.substring(0, fn.length()-suf.length());
        comms.add(id);
      }
    }
    return comms;
  }

  public static void main(String[] mainArgs) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(mainArgs);
    Random rand = config.getRandom();
    String fetchHost = "localhost";
    int fetchPort = 9999;
    File fetchCacheDir = config.getOrMakeDir("fetchCacheDir", FETCH_CACHE_DIR);
    try (DiskBackedFetchWrapper commRet = KbpSearching.buildFetchWrapper(fetchCacheDir, fetchHost, fetchPort)) {

      WalkFeatCounts wfc;
      File wfcFile = config.getFile("wfcFile");
      if (wfcFile.isFile()) {
        // Load it
        Log.info("loading wfc from " + wfcFile.getPath());
        wfc = (WalkFeatCounts) FileUtil.deserialize(wfcFile);
      } else {
        // Compute it
        wfc = new WalkFeatCounts();
        for (String commId : getRandomCommIds(50000, rand)) {
          Log.info("working on " + commId);
          Communication c = commRet.fetch(commId);
          wfc.increment(rand, c);
        }
        // Save it
        Log.info("saving to " + wfcFile.getPath());
        FileUtil.serialize(wfc, wfcFile);
      }
      
      // Do some analysis
      for (String commId : getRandomCommIds(200, rand)) {
        Log.info("analyzing " + commId);
        Communication c = commRet.fetch(commId);
//        wfc.increment(rand, c);
        for (Tokenization t : new TokenizationIter(c, 35)) {
          Log.info("working on " + c.getId() + "\t" + t.getUuid().getUuidString());
          List<Integer> args = DependencySyntaxEvents.extractEntityHeads(t);
          for (int i = 0; i < args.size()-1; i++) {
            for (int j = i+1; j < args.size(); j++) {
              int arg0 = args.get(i);
              int arg1 = args.get(j);
              Analysis a = new Analysis(t, arg0, arg1, wfc);
              a.show();
            }
          }
        }
      }
    }

    Log.info("done");
  }
}
