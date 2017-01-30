package edu.jhu.hlt.ikbp.tac;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.thrift.TException;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.ikbp.tac.PkbpSearching.New.MultiEntityMention;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.LL;
import edu.jhu.hlt.tutils.Log;
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

    String featType;        // what sort of MEM feature, e.g. "pred"
    String featValue;       // value extracted by template, e.g. "loves"
    String predWord;        // what predicate this feature is voting for, e.g. "loves"
    int predWordIdx;        // the location of predWord
    String predWordTokUuid; // the location of predWord
    private double weight;

    public PFeat(String featType, String featValue, String predWord, int predWordIdx, String predWordTokUuid) {
      this.featType = featType;
      this.featValue = featValue;
      this.predWord = predWord;
      this.predWordIdx = predWordIdx;
      this.predWordTokUuid = predWordTokUuid;
      this.weight = 1;
    }
    
    public void setWeight(double w) {
      this.weight = w;
    }
    public double getWeight() {
      return this.weight;
    }
    
    @Override
    public String toString() {
      return String.format("(PFeat %s=%s %.2f pred=%s source=%s)", featType, featValue, weight, predWord, predWordTokUuid);
    }
  }

  // Counts of paths from (pred|seed|related) token to arbitrary tokens up to maxEdges away
  private StringCountMinSketch[] featFreqs;
  private String[] featTypes;
  private int maxEdges;
  
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
      scores.put(key, s + f.getWeight());
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
  
//  public String findBestExplanation(List<MultiEntityMention> mems) {
  public Explanation findBestExplanation(List<MultiEntityMention> mems) {
//    Counts.Pseudo<String> c = new Counts.Pseudo<>();
    Explanation e = new Explanation();
    for (MultiEntityMention mem : mems) {
      for (PFeat f : extract(mem)) {
        double p = score(f.featType, f.featValue);
        f.setWeight(p);
//        c.update(f.predWord, p);
        e.add(f);
      }
    }
//    List<String> best = c.getKeysSortedByCount(true);
//    return best.get(0);
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

  public List<PFeat> extract(int pred, int[] args, Tokenization tks) {
    DependencyParse deps = IndexCommunications.getPreferredDependencyParse(tks);
    List<PFeat> fs = new ArrayList<>();
    
    List<Token> toks = tks.getTokenList().getTokenList();
    String tokUuid = tks.getUuid().getUuidString();
    
    // pred
    List<Pair<Integer, LL<Dependency>>> ppaths = NNPSense.kHop(pred, maxEdges, deps);
    for (Pair<Integer, LL<Dependency>> pp : ppaths) {
      int endpoint = pp.get1();
      String predWord = toks.get(endpoint).getText();
      LL<Dependency> path = pp.get2();
      String feat = buildFeat(predWord, path);
      fs.add(new PFeat("pred", feat, predWord, endpoint, tokUuid));
    }
    
    // seed
    for (int i = 0; i < args.length; i++) {
      String featType;
      if (i == 0) {
        featType = "seed";
      } else if (i == 1) {
        featType = "related";
      } else {
        featType = "aux";
      }

      int head = args[i];
      List<Pair<Integer, LL<Dependency>>> spaths = NNPSense.kHop(head, maxEdges, deps);
      for (Pair<Integer, LL<Dependency>> sp : spaths) {
        int endpoint = sp.get1();
        String predWord = toks.get(endpoint).getText();
        LL<Dependency> path = sp.get2();
        String feat = buildFeat(predWord, path);
        fs.add(new PFeat(featType, feat, predWord, endpoint, tokUuid));
      }
    }

    return fs;
  }

  public List<PFeat> extract(MultiEntityMention mem) {
    assert mem.allMentionsUniq();
//    assert mem.alignedMentions.length == 2;
    
    int[] args = new int[mem.alignedMentions.length];
    for (int i = 0; i < args.length; i++)
      args[i] = mem.getMention(i).head;
    
    return extract(mem.pred.head, args, mem.pred.getTokenization());

//    DependencyParse deps = IndexCommunications.getPreferredDependencyParse(mem.pred.getTokenization());
//    List<PFeat> fs = new ArrayList<>();
//    
//    List<Token> toks = mem.pred.getTokenization().getTokenList().getTokenList();
//    String tokUuid = mem.pred.tokUuid;
//    
//    // pred
//    int phead = mem.pred.head;
//    List<Pair<Integer, LL<Dependency>>> ppaths = NNPSense.kHop(phead, maxEdges, deps);
//    for (Pair<Integer, LL<Dependency>> pp : ppaths) {
//      int endpoint = pp.get1();
//      String predWord = toks.get(endpoint).getText();
//      LL<Dependency> path = pp.get2();
//      String feat = buildFeat(predWord, path);
//      fs.add(new PFeat("pred", feat, predWord, endpoint, tokUuid));
//    }
//    
//    // seed
//    int shead = mem.getMention(0).head;
//    List<Pair<Integer, LL<Dependency>>> spaths = NNPSense.kHop(shead, maxEdges, deps);
//    for (Pair<Integer, LL<Dependency>> sp : spaths) {
//      int endpoint = sp.get1();
//      String predWord = toks.get(endpoint).getText();
//      LL<Dependency> path = sp.get2();
//      String feat = buildFeat(predWord, path);
//      fs.add(new PFeat("seed", feat, predWord, endpoint, tokUuid));
//    }
//
//    // related
//    int rhead = mem.getMention(1).head;
//    List<Pair<Integer, LL<Dependency>>> rpaths = NNPSense.kHop(rhead, maxEdges, deps);
//    for (Pair<Integer, LL<Dependency>> rp : rpaths) {
//      int endpoint = rp.get1();
//      String predWord = toks.get(endpoint).getText();
//      LL<Dependency> path = rp.get2();
//      String feat = buildFeat(predWord, path);
//      fs.add(new PFeat("related", feat, predWord, endpoint, tokUuid));
//    }
//    
//    return fs;
  }
  
  public double score(String featType, String featValue) {
    int freq = freq(featType, featValue);
    double k = 10;
    double p = (k+1) / (k+freq);
    return p;
  }
  
  public int freq(String featType, String featValue) {
    int i = indexOf(featType, this.featTypes);
    return this.featFreqs[i].apply(featValue, false);
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
