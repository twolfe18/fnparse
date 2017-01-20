package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedWriter;
import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.ParmaVw.QResultCluster;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.SitSearchResult;
import edu.jhu.hlt.ikbp.tac.TacKbp.KbpQuery;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.prim.tuple.Pair;

/**
 * Given a query and list of results (which have been dedup-ed with
 * parma/etc), create redundancy HITs. A redundancy HIT presents two search
 * results and asks if the second one is redundant information in light of the
 * first. These pairs will be drawn from two pools of pairs. Pairs formed by
 * results which were not clustered together (with a bias towards pairs in the
 * top of the list), and pairs formed by results which were clustered together.
 * For each pool we get TP (system and turker said "redundant"), TN (both said
 * "not redundant"), FP (system said "redundant"), and FN (turker said
 * "redundant"). We can assign FP and FN costs. We know the relative size of
 * each pool (dictated by deduplication policy), so we have a cost estimate.
 * Given FP and FN annotations, we can evaluate the cost of different policies.
 * 
 * TODO Start with fake data and figure out how many annotations we will need
 * and what precision/recall we will need to beat lemma baseline.
 *
 * NOTE In the real world, cost(FP) >> cost(FN).
 * FP = "system hid a potentially important response from me" and FN = "I had to
 * go through an extra search result". The only factor which might lower
 * cost(FP), ironically, is if the system didn't give very good results on
 * average.
 * 
 * 
 * 
 * 
 * 
 * 
 * AH, I was wrong, the way to do this is as a knapsack problem.
 * We can even make the size of the knapsack stochastic (n = number of results looked through ~ Poisson(lambda)).
 * I think that once we get complicated, folks are going to start criticizing our methods,
 * so I should also include something which is simpler to understand: raw dedup accuracy.
 * I can have two number: "FP,FN for the n pairs that we chose"
 * and "if the user looks at n~Poisson(lambda) results and utility(response)=p(coref), E[utility; dedup] - E[utility; raw] = X"
 * We can perhaps do the expected utility difference ratio if we measure as an average over many queries
 * (remember expected(fraction) != fraction(expectation)).
 * 
 *
 * @author travis
 */
public class EvaluateRedundancy implements Serializable {
  private static final long serialVersionUID = 4839223599118354435L;
  
  
  
  /*
   * n ~ Poisson(lambda)      # number of results looked at
   * We will have tables with columns corresponding to lambda=30, lambda=100, lambda=300, etc
   * 
   * Given n, you can deterministically compute the set of results which regular and dedup methods will show the user.
   * In the case where we've determined that result_i is dup with some other result before it, and thus tacked result_k onto the end:
   * (i.e. the difference in result sets between regular|n and deduped|n)
   *    delta(utility|n) -= utility(result_i) = 1-p(result_i is dup | yhat=1) = 1-TP/(TP+FP) = 1-precision ~ 10%-20%
   *    delta(utility|n) += utility(result_k) = p(result_k is novel | yhat=0) =   TN/(TN+FN) ~= 1
   *    
   * E[delta(utility)] = sum_n p(n ~ Poisson(lambda)) * delta(utility|n)
   * and we can truncate this for large values of n by assuming delta(utility|n)=0 for large n
   * (this is only really justified by observing delta(utility|n)>0 for small n, and retroactively using this as a lower-bounding assumption)
   *
   * This analysis is almost correct, but it conflates p(yhat_{i,j}=1) and p(yhat_{i,*}=1)
   * => This is a modeling error (i.e. parma) not an evaluation error
   *    remember, parma just pops out a yhat=1 or yhat=0, we don't need to incorporate how it made that decision into these numbers
   * => NO, this really is a problem since we are only showing turkers a pair of results, not the full set
   *    e.g. we are not really determining FP since the mention we show could be dup with another (higher-ranked) mention not show to the turker
   *    
   *
   * Wait, do we have a true lower bound by making the pairwise assumption?
   * We are always underestimating p(dup|*) by making the pairwise assumption
   * In delta(utility) we are:
   *    -= 1-p(dup|yhat=1)
   *    -= upperBound
   *    lowerBound
   * and
   *    += 1-p(dup|yhat=0)
   *    += upperBound
   *    ???
   */
  
  

  /** Generates one HTML file */
  class Instance implements Serializable {
    private static final long serialVersionUID = -8298704626490189056L;
    double weight;
    SitSearchResult left, right;
    boolean systemSaysRedundant;
    Boolean turkerSaysRedundant;
    
    public Instance(double weight, SitSearchResult left, SitSearchResult right, boolean systemSaysRedundant, Boolean turkerSaysRedundant) {
      this.weight = weight;
      this.left = left;
      this.right = right;
      this.systemSaysRedundant = systemSaysRedundant;
      this.turkerSaysRedundant = turkerSaysRedundant;
    }
    
    @Override
    public String toString() {
      assert weight == 1 : "implement me";
      return "(RedInst q.id=" + query.id
          + " q.doc=" + query.docid
          + " y=" + turkerSaysRedundant
          + " yhat=" + systemSaysRedundant
          + " l=" + left.getCommTokIdShort()
          + " r=" + right.getCommTokIdShort()
          + ")";
    }

    public List<String> showHtml() {
      List<String> l = new ArrayList<>();
      
//      ShowResult srLeft = new ShowResult(query, left);
//      List<String> lsLeft = srLeft.show2(Collections.emptyList());
      
      l.add("<center>");
      l.add("<table border=\"1\" cellpadding=\"10\" width=\"80%\">");
      l.add("<col width=\"50%\"><col width=\"50%\">");

      l.add("<tr>");

      l.add("<td valign=top>");
      l.add(left.getWordsInTokenizationWithHighlightedEntAndSit(false));
      l.add("</td>");

      l.add("<td valign=top>");
      l.add(right.getWordsInTokenizationWithHighlightedEntAndSit(false));
      l.add("</td>");

      l.add("</tr>");

      l.add("</table>");
      l.add("</center>");

      
      return l;
    }
  }
  
  // Input
  private KbpQuery query;
  private List<QResultCluster> res;
  
  // Output: HIT stuff
  private List<Instance> allInstances;    // build in constructor, sample from this upon request
  
  public EvaluateRedundancy(KbpQuery q, List<QResultCluster> res) {
    this.query = q;
    this.res = res;
    
    allInstances = new ArrayList<>();
    int n = res.size();
    // POS examples
    for (int i = 0; i < n-1; i++) {
      for (int j = i+1; j < n; j++) {
        allInstances.add(new Instance(1, res.get(i).canonical, res.get(j).canonical, false, null));
      }
    }
    // NEG examples
    for (QResultCluster c : res) {
      int nr = c.numRedundant();
      for (int i = 0; i < nr; i++) {
        allInstances.add(new Instance(1, c.canonical, c.getRedundant(i), true, null));
      }
    }
  }
  
  private Counts<String> stats() {
    Counts<String> c = new Counts<>();

    c.update("instance", 0);
    for (Instance i : allInstances) {
      c.increment("instance");
      c.increment("instance/sys/" + (i.systemSaysRedundant ? "redundant" : "novel"));
      c.increment("instance/turker/" + (i.turkerSaysRedundant == null ? "NA" : (i.turkerSaysRedundant ? "redundant" : "novel")));
      
      if (i.left.getCommunicationId().equals(i.right.getCommunicationId()))
        c.increment("instance/sameDoc");
    }

    // Ensure these keys are present even if there count is 0
    c.update("cluster", 0);
    c.update("cluster/singleton", 0);
    c.update("cluster/twoOrMore", 0);
    for (QResultCluster clust : res) {
      c.increment("cluster");
      if (clust.numRedundant() > 0)
        c.increment("cluster/twoOrMore");
      else
        c.increment("cluster/singleton");
      c.increment(String.format("cluster/numRedundant=% 2d", clust.numRedundant()));
    }
    return c;
  }
  
  public List<Instance> sampleHits(int nSystemRedundant, int nSystemNotRedunant, Random rand) {
    ReservoirSample<Instance> red = new ReservoirSample<>(nSystemRedundant, rand);
    ReservoirSample<Instance> reg = new ReservoirSample<>(nSystemNotRedunant, rand);
    
    for (Instance i : allInstances) {
      if (i.weight != 1)
        throw new RuntimeException("implement weighted sampling");
      (i.systemSaysRedundant ? red : reg).add(i);
    }
    
    Log.info("nRedIn=" + red.numObservations() + " nRegIn=" + reg.numObservations()
        + " nRedOut=" + nSystemRedundant + " nRegOut=" + nSystemNotRedunant);

    List<Instance> out = new ArrayList<>(red.size() + reg.size());
    for (Instance i : red)
      out.add(i);
    for (Instance i : reg)
      out.add(i);
    return out;
  }
  
  public static List<QResultCluster> lemmaMatchDedup(KbpQuery q, List<SitSearchResult> res) {
    Log.info("working on " + q);
    List<QResultCluster> clusts = new ArrayList<>();
    Map<String, QResultCluster> lemma2clust = new HashMap<>();
    for (SitSearchResult r : res) {
      TokenTagging l = IndexCommunications.getPreferredLemmas(r.getTokenization());
      String lemma = l.getTaggedTokenList().get(r.yhatEntitySituation).getTag();
      
      QResultCluster c = lemma2clust.get(lemma);
      if (c == null) {
        c = new QResultCluster(r);
        lemma2clust.put(lemma, c);
        clusts.add(c);
      } else {
        c.addRedundant(r);
      }
    }
    Log.info("reduced " + res.size() + " results down to " + clusts.size() + " clusters");
    return clusts;
  }

    
  
  /**
   * load some raw/dupped responses (no attrFeat, parma, etc)
   * run lemma match deduplication
   * output HITs
   */
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    File outputDir = config.getOrMakeDir("output");
    Random rand = config.getRandom();
    File queryResRoot = config.getExistingDir("queryResponses");
    for (File f : queryResRoot.listFiles()) {
      if (!f.getName().endsWith(".qrs.jser"))
        continue;
      
      // Get raw results from disk
      @SuppressWarnings("unchecked")
      Pair<KbpQuery, List<SitSearchResult>> p = (Pair<KbpQuery, List<SitSearchResult>>) FileUtil.deserialize(f);
      KbpQuery q = p.get1();
      List<SitSearchResult> res = p.get2();
      AccumuloIndex.getCommsFromQuery(q, res);
      res = AccumuloIndex.removeResultsInSameDocAsQuery(q, res);

      // Lemma match dedup
      List<QResultCluster> clusts = lemmaMatchDedup(q, res);
      
      // Generate some HITs
      EvaluateRedundancy e = new EvaluateRedundancy(q, clusts);
      int redundant = 10;
      int nonRedundant = 10;
      List<Instance> hits = e.sampleHits(redundant, nonRedundant, rand);

      // Output HITs to HTML
      File qOutDir = new File(outputDir, q.id);
      qOutDir.mkdirs();
      File allHitsHtml = new File(qOutDir, "all.html");
      Log.info("writing to " + allHitsHtml);
      // TODO have one output file per HIT
      try (BufferedWriter w = FileUtil.getWriter(allHitsHtml)) {
        w.write("<pre>Stats for all HITs in " + e.query + "\n");

        Counts<String> cs = e.stats();
        for (String key : cs.getKeysSorted()) {
          w.write(String.format("  %-25s% 4d\n", key, cs.getCount(key)));
        }

        w.write("</pre>");
        w.newLine();
        for (Instance h : hits) {
          w.write("<pre>" + h + "</pre>");
          w.newLine();
          for (String line : h.showHtml()) {
            w.write(line);
            w.newLine();
          }
          w.write("<br/><br/>\n\n");
        }
      }
    }
  }
}
