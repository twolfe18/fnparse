package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.thrift.TException;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.PkbpSearching.New.MultiEntityMention;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.LabeledDirectedGraph;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.TimedCallback;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;
import edu.jhu.util.DiskBackedFetchWrapper;

/**
 * Given a bunch of situation mentions with:
 * 1) a predicate token
 * 2) a seed entity mention head token
 * 3) a related entity mention head token
 * 4) optionally more argument head tokens (TODO not implemented yet)
 * (generally these will be produced by {@link DependencySyntaxEvents})
 * 
 * Produce a distribution over predicate words which "explain" or "best characterize"
 * the relation between the seed and related entity.
 * 
 * This is done by extracting features on a token position and
 * summing the weight of these features across the situation mentions.
 * The weight of a feature is proportional to 1/frequency(feature).
 * 
 * @author travis
 */
public class FindCommonPredicate implements Serializable {
  private static final long serialVersionUID = -187379702254164273L;
  
  static class PFeat implements Serializable {
    private static final long serialVersionUID = 8873296911722790998L;
    public static final Feat intercept = new Feat("intercept", 1d);

    String featType;        // what sort of MEM feature, e.g. "pred"
    String featValue;       // value extracted by template, e.g. "loves"
    String predWord;        // what predicate this feature is voting for, e.g. "loves"
    int predWordIdx;        // the location of predWord
    String predWordTokUuid; // the location of predWord
    List<Feat> weight;

    public int[] origPredArgs;  // [pred, seed, related, etc...]

    /** @deprecated */
    public List<Dependency> path; // path from orig pred to predWordIdx

    public PFeat(String featType, String featValue, String predWord, int predWordIdx, String predWordTokUuid) {
      this.featType = featType;
      this.featValue = featValue;
      this.predWord = predWord;
      this.predWordIdx = predWordIdx;
      this.predWordTokUuid = predWordTokUuid;
      this.weight = new ArrayList<>();
    }
    
    @Override
    public String toString() {
      double s = Feat.sum(weight);
      return String.format("(PFeat f(%s)=%s pred=%s source=%s weights=%.2f=%s)",
          featType, featValue, predWord, predWordTokUuid, s, weight);
    }
  }

  // Counts of paths from (pred|seed|related) token to arbitrary tokens up to maxEdges away
  private StringCountMinSketch[] featFreqs;
  private String[] featTypes;
  private int maxEdges;
  
  // TODO Take this as an arg
  public static final ComputeIdf df;
  static {
    try {
      df = new ComputeIdf(new File("data/idf/cms/df-cms-simpleaccumulo-twolfe-cag1-nhash12-logb20.jser"));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  public FindCommonPredicate() {
    this(4, 10, 20);
  }

  public FindCommonPredicate(int maxEdges, int nhash, int logb) {
    Log.info("maxEdges=" + maxEdges + " nhash=" + nhash + " logb=" + logb);
    this.maxEdges = maxEdges;
    this.featTypes = new String[] {
        "pred",
        "seed",
        "related",
        "aux",
    };
    this.featFreqs = new StringCountMinSketch[this.featTypes.length];
    boolean conservativeUpdates = true;
    for (int i = 0; i < featFreqs.length; i++)
      featFreqs[i] = new StringCountMinSketch(nhash, logb, conservativeUpdates);
  }
  
  static class Explanation {
    // Input
//    private List<MultiEntityMention> input;
    // Keys for these are PFeat.predWord
    private Map<String, List<PFeat>> reasons;
    private Map<String, Double> scores;
    
    public Explanation() {
      reasons = new HashMap<>();
      scores = new HashMap<>();
    }
    
    public void add(PFeat f) {
      String key = f.predWord;
      List<PFeat> r = reasons.get(key);
      if (r == null) {
        r = new ArrayList<>();
        reasons.put(key, r);
      }
      r.add(f);
      double s = scores.getOrDefault(key, 0d);
      double d = Feat.sum(f.weight);
      scores.put(key, s + d);
    }
    
    public List<PFeat> getReasonsFor(String predWord) {
      return reasons.get(predWord);
    }
    
    public List<Feat> getBestExplanations(int k) {
      List<Feat> all = Feat.deindex(scores.entrySet());
      Collections.sort(all, Feat.BY_SCORE_DESC);
      if (all.size() > k)
        all = all.subList(0, k);
      return all;
    }
  }
  
  /**
   * Multi-step process for finding the "most informative predicate"
   * Step 0) Have a rule-based prior over what tokens can be predicates -- this rules out junk
   * Step 1) Compute posterior by intersecting prior with data, gives you a marginal distribution over predicates
   * Step 2) Upon seeing a spoke with multiple mentions, apply prior to get dist over predicates, then 
   * 
   * 
   * text -> pred <- theta <- alpha
   * p(pred|theta) is the "prior" described above
   * do posterior inference (counting) to estimate theta
   * prediction time: p(pred|text,theta) gives a prob, and thus information value, for every token in every mention
   *    take the predicate which convey the most information
   * 
   * How does this handle very rare predicates (things which basically appeared once)?
   * if they have high probability under the prior p(pred|text): then they are very informative
   * 
   * What is the form of p(pred|text,theta)?
   * p(pred|text,theta) = p(pred|theta) * p(pred|text)
   * 
   * What is the form of p(pred|theta)?
   * p(path|theta) * p(word|path,theta)
   */
  static class ExplanationInstance {
    int pred;
    int[] args;
    // p(pred|text)
    double[] logPriorDist;
    double[] logPriorPos;
    double[] logPriorWithinArg;
    // p(pred|text,theta)
    double[] logFeatFreq;   // TODO can be unigram prob of predicate word OR 
    
    public ExplanationInstance(int pred, int[] args, int nTokens) {
      this.pred = pred;
      this.args = args;
      this.logPriorDist = new double[nTokens];
      this.logPriorPos = new double[nTokens];
      this.logPriorWithinArg = new double[nTokens];
      this.logFeatFreq = new double[nTokens];
      
      
      
    }
  }
  
  public Explanation findBestExplanation(List<MultiEntityMention> mems) {
    boolean debug = true;
    Explanation e = new Explanation();
    for (MultiEntityMention mem : mems) {
      if (!mem.allMentionsUniq())
        continue;
      if (debug) {
        Log.info("counting explanation features in");
        PkbpSearching.New.showMultiEntityMention(mem);
        System.out.println("pred: " + mem.pred.getContextAroundHead());
      }
      TokenTagging pos = IndexCommunications.getPreferredPosTags(mem.pred.getTokenization());
      DependencyParse deps = IndexCommunications.getPreferredDependencyParse(mem.pred.getTokenization());
      int n = mem.pred.getTokenization().getTokenList().getTokenListSize();
      LabeledDirectedGraph deps2 = LabeledDirectedGraph.fromConcrete(deps, n, null);
      for (PFeat f : extract(mem)) {
//        int c = freq(f.featType, f.featValue);
//        double p = score(f.featType, f.featValue);
        double lp = logProb(f.featType, f.featValue);
        double p = df.idf(f.predWord);
        
        if (f.predWord.equalsIgnoreCase("Sunday")) {
          Log.info("checkme");
        }
        
        
        // DEBUGGING
        // Hypothesis: interesting features are distributed with a log-normal freuency
        // Gaussian density: f(x) = 1/(2*pi*var) exp(-(x-mu)^2 / 2*var)
        double mu = 7.5;
        double sigma = 4;
        /*
>>> for i in range(14):
...   print "%d\t%.1f" % (i, math.exp(i))
... 
0 1.0
1 2.7
2 7.4
3 20.1
4 54.6
5 148.4
6 403.4
7 1096.6
8 2981.0
9 8103.1
10  22026.5
11  59874.1
12  162754.8
13  442413.4
         */
        // log(p) = log(n/z) = log(n) - log(z)
        // so log(p) ~ normal => log(n) ~ normal with a shifted mean and same variance
        double z = Math.log(freq(f.featType, f.featValue)) - mu;
        double gaussDensity = 1d / Math.sqrt(2 * Math.PI * sigma * sigma)
            * Math.exp(-(z*z) / (2*sigma*sigma));
        System.out.println("type=" + f.featType + " value=" + f.featValue + " logProb=" + logProb(f.featType, f.featValue) + " density=" + gaussDensity);
        

//        f.setWeight(p);
//        f.weight.add(new Feat("freq", p));
//        f.weight.add(new Feat("dist", 3d / (1+f.path.size())));
//        
        Feat freq = new Feat("freq", 5 + p);
//        Feat freq = new Feat("freq", 1);
        Feat lpf = new Feat("logProbFeat", 1-lp);
        Feat lpw = new Feat("logProbWord", 1 + (Math.log(df.numDocs()) - Math.log(df.freq(f.predWord))));
        

//        Feat dist = new Feat("dist", 3d / (1+f.path.size()));
//        Feat fd = Feat.prod(freq, dist).rescale("good", 10);
//        f.weight.add(freq);
//        f.weight.add(dist);
//        f.weight.add(fd);
        
//        double dist = f.path.size();
//        double k = 1000d;
//        double p = (k + 1) / (k + c);
//        f.weight.add(new Feat("freq/dist", p / (1 + dist)));
        
//        f.weight.add(new Feat("logFreq", -Math.log(Math.E + c)));
        int[] pas = f.origPredArgs;
        int totalDistSum = 0 ;
        int totalDistProd = 1;
        for (int i = 0; i < pas.length; i++) {
          int a = pas[i];
          // This path is measured in nodes, not edges
          // I want the distance in edges, hence the 1+pathlength
          int[] spath = deps2.shortestPath(a, f.predWordIdx, true, false);
          if (spath != null) {
            totalDistSum += 1 + spath.length;
            totalDistProd *= 1 + spath.length;
          } else {
            totalDistSum += 1 + n;
            totalDistProd *= 1 + n;
          }
        }
        assert totalDistProd >= 1;
        assert totalDistSum >= 0;
        Feat tdSum = new Feat("totalDistSum", 10d / (1 + totalDistSum));
        Feat tdProd = new Feat("totalDistProd", 100d / (1 + totalDistProd));
//        f.weight.add(freq);
        f.weight.add(lpf);
        f.weight.add(lpw);
        f.weight.add(tdSum);
        f.weight.add(tdProd);
//        f.weight.add(Feat.prod(freq, tdSum));
//        f.weight.add(Feat.prod(freq, tdProd));
        f.weight.add(Feat.prod(lpf, tdSum));
        f.weight.add(Feat.prod(lpf, tdProd));
        f.weight.add(Feat.prod(lpw, tdSum).rescale("good", 10));
        f.weight.add(Feat.prod(lpw, tdProd).rescale("good", 10));
        
        String predPos = pos.getTaggedTokenList().get(f.predWordIdx).getTag().toUpperCase();
        if (predPos.startsWith("V")) {
          for (Feat ff : f.weight)
            ff.rescale("preferVerbs", 1.5);
        } else if (predPos.equals("CD")) {
          for (Feat ff : f.weight)
            ff.rescale("preferVerbs", 0.25);
        }
        
        // Rescale by the weight indicating all linking decisions are right
        double allRight = Math.sqrt(mem.getLinkingScore());
        for (Feat ff : f.weight)
          ff.rescale("memLinkQuality", 1 + allRight);

        e.add(f);
        if (debug)
          System.out.println(f);
      }
      if (debug)
        System.out.println();
    }
    return e;
  }
  
  private static String buildFeat(String endpoint, LL<Dependency> path) {
    StringBuilder sb = new StringBuilder();
    sb.append(endpoint);
    for (LL<Dependency> cur = path; cur != null; cur = cur.next) {
      sb.append('-');
      sb.append(cur.item.getEdgeType());
    }
    return sb.toString();
  }
  
  private static boolean pathContains(LL<Dependency> path, BitSet bs) {
    for (LL<Dependency> cur = path; cur != null; cur = cur.next) {
      Dependency d = cur.item;
      if (bs.get(d.getDep()))
        return true;
      if (d.isSetGov() && bs.get(d.getGov()))
        return true;
    }
    return false;
  }

  public List<PFeat> extract(int pred, int[] args, Tokenization tks) {
    DependencyParse deps = IndexCommunications.getPreferredDependencyParse(tks);
    List<PFeat> fs = new ArrayList<>();
    
    List<Token> toks = tks.getTokenList().getTokenList();
//    TokenTagging lemmas = IndexCommunications.getPreferredLemmas(tks);
    String tokUuid = tks.getUuid().getUuidString();
    
    // Set of tokens in any argument
    BitSet argPositions = new BitSet();
    for (int a : args) {
      Span s = IndexCommunications.nounPhraseExpand(a, deps);
      for (int i = s.start; i < s.end; i++)
        argPositions.set(i);
    }

    List<Integer> heads = new ArrayList<>();
    heads.add(pred);
    for (int a : args)
      heads.add(a);
    
    Set<Integer> seen = new HashSet<>();
    for (int head : heads) {
      List<Pair<Integer, LL<Dependency>>> ppaths = NNPSense.kHop(head, maxEdges, deps);
      for (Pair<Integer, LL<Dependency>> pp : ppaths) {
        int endpoint = pp.get1();
        
        // Endpoints should be uniq
        if (!seen.add(endpoint))
          continue;
        
        //      String predWord = toks.get(endpoint).getText();
        //      String predWord = lemmas.getTaggedTokenList().get(endpoint).getTag().toLowerCase();
        String predWord = toks.get(endpoint).getText().toLowerCase();
        LL<Dependency> path = pp.get2();
        if (argPositions.get(endpoint) || pathContains(path, argPositions))
          continue;
        String featType = "pred"; // TODO
        String featValue = buildFeat(predWord, path);
        PFeat pf = new PFeat(featType, featValue, predWord, endpoint, tokUuid);

        pf.origPredArgs = new int[1 + args.length];
        pf.origPredArgs[0] = pred;
        System.arraycopy(args, 0, pf.origPredArgs, 1, args.length);

        fs.add(pf);
      }
    }

//    // seed
//    for (int i = 0; i < args.length; i++) {
//      String featType;
//      if (i == 0) {
//        featType = "seed";
//      } else if (i == 1) {
//        featType = "related";
//      } else {
//        featType = "aux";
//      }
//
//      int head = args[i];
//      List<Pair<Integer, LL<Dependency>>> spaths = NNPSense.kHop(head, maxEdges, deps);
//      for (Pair<Integer, LL<Dependency>> sp : spaths) {
//        int endpoint = sp.get1();
////        String predWord = toks.get(endpoint).getText();
//        String predWord = lemmas.getTaggedTokenList().get(endpoint).getTag().toLowerCase();
//        LL<Dependency> path = sp.get2();
//        if (argPositions.get(endpoint) || pathContains(path, argPositions))
//          continue;
//        String feat = buildFeat(predWord, path);
//        fs.add(new PFeat(featType, feat, predWord, endpoint, tokUuid, LL.toList(path, true)));
//      }
//    }

    return fs;
  }

  public List<PFeat> extract(MultiEntityMention mem) {
    assert mem.allMentionsUniq();
//    assert mem.alignedMentions.length == 2;
    
    int[] args = new int[mem.alignedMentions.length];
    for (int i = 0; i < args.length; i++)
      args[i] = mem.getMention(i).head;
    
    return extract(mem.pred.head, args, mem.pred.getTokenization());
  }
  
  public double score(String featType, String featValue) {
    int freq = freq(featType, featValue);
    double k = 1000;
    double p = (k+1) / (k+freq);
    return p;
  }
  
  public int freq(String featType, String featValue) {
    int i = indexOf(featType, this.featTypes);
    return this.featFreqs[i].apply(featValue, false);
  }
  
  public double logProb(String featType, String featValue) {
    int i = indexOf(featType, this.featTypes);
    double n = 1 + this.featFreqs[i].apply(featValue, false);
    double z = 1 + this.featFreqs[i].numIncrements();
    if (n >= z) {
      Log.info("WARNING: featType=" + featType + " featValue=" + featValue + " n=" + n + " z=" + z + ", ROUNDING TO 1");
      return 0;
    }
    return Math.log(n) - Math.log(z);
  }
  
  public void increment(String featType, String featValue) {
    int i = indexOf(featType, this.featTypes);
    this.featFreqs[i].apply(featValue, true);
  }
  
  public void count(MultiEntityMention mem) {
    for (PFeat f : extract(mem))
      increment(f.featType, f.featValue);
  }
  
  public void count(Communication comm, ComputeIdf df, int maxSentenceLength) {
    for (MultiEntityMention mem : MultiEntityMention.makeDummyMems(comm, df, maxSentenceLength))
      count(mem);
  }
  
  private static int indexOf(String needle, String[] haystack) {
    for (int i = 0; i < haystack.length; i++)
      if (needle.equals(haystack[i]))
        return i;
    return -1;
  }

  public void trainOnSmallCommCollection(File commDir, ComputeIdf df, TimedCallback cb, int maxSentenceLength) throws IOException, TException {
    Log.info("commDir=" + commDir);
    int done = 0, skipped = 0;
    File[] allFiles = commDir.listFiles();
    for (File f : allFiles) {
      String s = f.getName().toLowerCase();
      if (s.endsWith(".comm") || s.endsWith(".comm.gz")) {
        Log.info("counting dummy MEMs in " + f.getPath() + " done=" + done + " skipped=" + skipped + " all=" + allFiles.length);
        byte[] bytes = FileUtil.readBytes(FileUtil.getInputStream(f));
        Communication comm = new Communication();
        DiskBackedFetchWrapper.DESER.deserialize(comm, bytes);
        count(comm, df, maxSentenceLength);
        done++;
        cb.tick();
      } else {
        skipped++;
      }
    }
  }
  
  public static void mainTrainOnSmallCommCollection(ExperimentProperties config) throws Exception {
    Log.info("starting...");
    ComputeIdf df = new ComputeIdf(config.getFile("wordDocFreq"));
    File commDir = config.getExistingDir("commDir", new File("data/sit-search/fetch-comms-cache"));
    File output = config.getFile("output");
    int maxEdges = config.getInt("maxEdges", 4);
    int nhash = config.getInt("nhash", 10);
    int logb = config.getInt("logb", 20);
    FindCommonPredicate fp = new FindCommonPredicate(maxEdges, nhash, logb);
    TimedCallback cb = TimedCallback.serialize(fp, output, 5 * 60);
    int maxSentenceLength = config.getInt("maxSentenceLength", 100);
    fp.trainOnSmallCommCollection(commDir, df, cb, maxSentenceLength);
    FileUtil.VERBOSE = true;
    FileUtil.serialize(fp, output);
    Log.info("done");
  }
  
  /**
   * Show the predicate selection in action.
   * Runs findBestExplanation on a list of one MEM rather than a set of MEMs on a spoke.
   */
  public void testSimple(ExperimentProperties config) throws Exception {
    FindCommonPredicate fp = (FindCommonPredicate) FileUtil.deserialize(config.getFile("input"));
    ComputeIdf df = new ComputeIdf(config.getFile("wordDocFreq"));
    Communication comm = null;    // TODO
    int maxSentenceLenght = 100;
    for (MultiEntityMention mem : MultiEntityMention.makeDummyMems(comm, df, maxSentenceLenght)) {
      if (mem.query.length != 2)
        continue;
      if (!mem.allMentionsUniq())
        continue;
//      String pred = fp.findBestExplanation(Arrays.asList(mem));
      List<Feat> pred = fp.findBestExplanation(Arrays.asList(mem)).getBestExplanations(3);

      System.out.println(pred);
      PkbpSearching.New.showMultiEntityMention(mem);
      System.out.println();
    }
  }
  
  /**
   * Goes over CAG/CA-wiki and counts features,
   * creates an object of this class and serializes it for later use.
   */
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    String mode = config.getString("mode");
    switch (mode.toLowerCase()) {
    case "train":
    case "trainsmall":
      mainTrainOnSmallCommCollection(config);
      break;
    default:
      throw new RuntimeException("don't know about mode=" + mode);
    }
    Log.info("done");
  }
}
