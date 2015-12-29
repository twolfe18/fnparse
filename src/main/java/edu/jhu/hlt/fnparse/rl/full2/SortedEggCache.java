package edu.jhu.hlt.fnparse.rl.full2;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.fnparse.rl.full.HowToSearch;
import edu.jhu.hlt.fnparse.rl.full.MaxLoss;
import edu.jhu.hlt.fnparse.rl.full.StepScores;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.tuple.Pair;

/**
 * Holds logic and cache of eggs for F nodes (k-valued eggs) and K nodes
 * (s-valued eggs). All eggs for a given (t,f) subtree are created and ranked in
 * one go in the constructor. Getters allow you to pull out the LL of eggs you
 * need later.
 */
public class SortedEggCache {
  public static boolean DEBUG = true;

  private int t;
  private int f;    // keep?
  private LLTVN fEggs;                      // children of F node, K values; scores are max of kEgg scores
  private IntObjectHashMap<LLTVN> k2Eggs;   // children of K node, S values

  public static StepScores<?> e2ss(EggWithStaticScore egg) {
    return new StepScores<>(null, egg.getModel(), MaxLoss.ZERO, egg.getRand());
  }

  /**
   * @param fEggs is a list of pairs of (t,f) and K values which are the
   * eggs of an F node. Does NOT need to be sorted (will be sorted here).
   * There should only be K of these (depends on the F value).
   * These DO NOT NEED scores in the eggs (model and rand should acutally be set
   * to Adjoints.Constants.ZERO and 0 respectively), they will be derived from
   * the max of the (k,s) scores found in kEggs.
   *
   * @param kEggs is a list of pairs of (t,f,k) and S values which are the
   * eggs of a K node. Does NOT need to be sorted (will be sorted here).
   * There should be K*S of these.
   *
   * @param howToScore specifies how to convert (model,loss,rand) -> double o
   * which to sort. We assume that loss=0 since (currently) this is only used
   * for sorting according to model score (which includes rand).
   */
  public SortedEggCache(
      List<Pair<TFKS, EggWithStaticScore>> fEggs,
      List<Pair<TFKS, EggWithStaticScore>> kEggs,
      HowToSearch howToScore) {

    // Build a k -> EggWithStaticScore for later
    IntObjectHashMap<EggWithStaticScore> k2kEgg = new IntObjectHashMap<>();
    for (int i = 0; i < fEggs.size(); i++) {
      EggWithStaticScore egg = fEggs.get(i).get2();
      EggWithStaticScore old = k2kEgg.put(egg.value, egg);
      assert old == null;
    }

    // Sort by (k,s) then score (low -> high)
    Collections.sort(kEggs, new Comparator<Pair<TFKS, EggWithStaticScore>>() {
      @Override
      public int compare(Pair<TFKS, EggWithStaticScore> p1, Pair<TFKS, EggWithStaticScore> p2) {
        // First sort on (k,s)
        TFKS i1 = p1.get1();
        TFKS i2 = p2.get1();
        if (i1.k != i2.k)
          return i1.k < i2.k ? -1 : 1;
        if (i1.s != i2.s)
          return i1.s < i2.s ? -1 : 1;
        // Within a (k,s) block, sort by score, low->high
        StepScores<?> ss1 = e2ss(p1.get2());
        StepScores<?> ss2 = e2ss(p2.get2());
        double s1 = howToScore.forwards(ss1);
        double s2 = howToScore.forwards(ss2);
        if (s1 < s2)
          return -1;
        if (s2 < s1)
          return 1;
        return 0;
      }
    });

    // Group-by k and check that (t,f) is the same for all
    this.k2Eggs = new IntObjectHashMap<>();
    this.fEggs = null;
    TFKS curTFKS = kEggs.get(0).get1();
    this.t = curTFKS.t;
    this.f = curTFKS.f;
    assert t >= 0 && f >= 0;

    StepScores<?> curKmaxOverS = null;  // score derived from s-valued eggs given to k-valued eggs
    int curK = -1;
    LLTVN curKEggs = null;
    for (Pair<TFKS, EggWithStaticScore> p : kEggs) {
      // Sanity check
      TFKS i = p.get1();
      EggWithStaticScore egg = p.get2();
      assert t == i.t && f == i.f;
      assert i.k >= 0 && i.s >= 0;
      assert egg.getModel() != null : "s-valued eggs must have a model score (Adjoints)";
      if (DEBUG)
        System.out.println(i.str() + "\t" + egg + "\t" + howToScore.forwards(e2ss(egg)));

      // New k value
      if (curK != i.k) {
        // Check that this isn't the first run through the loop (null aggregates)
        if (curK >= 0)
          addValuesForK(curK, curKmaxOverS, k2kEgg, curKEggs);

        // Clear k-aggregators
        curKEggs = null;
        curK = i.k;
        curKmaxOverS = null;
      }
      curKEggs = new LLTVN(egg, curKEggs);
      StepScores<?> ss = new StepScores<>(null, egg.getModel(), MaxLoss.ZERO, egg.getRand());
      if (curKmaxOverS == null || howToScore.forwards(ss) > howToScore.forwards(curKmaxOverS)) {
        curKmaxOverS = ss;
      }
    }
    addValuesForK(curK, curKmaxOverS, k2kEgg, curKEggs);
  }

  /**
   * @param curK is the value of k which has been aggregated over.
   * @param curKmaxOverS is a max of s-valued egg scores which will be assigned to the k-valued egg being added.
   * @param k2kEgg is a map from k values to their repective egg (containing e.g. a needed prime but no valid score)
   * @param curKEggs are s-valued eggs matching curK
   */
  private void addValuesForK(int curK, StepScores<?> curKmaxOverS, IntObjectHashMap<EggWithStaticScore> k2kEgg, LLTVN curKEggs) {
    assert curKmaxOverS != null;
    assert curKEggs != null;
    EggWithStaticScore e = k2kEgg.get(curK);  // score of this will be bogus
    assert e != null : "s egg (with implicit k) which doesn't have k egg?";
    assert e.getModel() == Adjoints.Constant.ZERO && e.getRand() == 0
        : "we're supposed to compute this here as a max! use special value plz";
    EggWithStaticScore e2 = e.setScore(curKmaxOverS.getModel(), curKmaxOverS.getRand());
    fEggs = new LLTVN(e2, this.fEggs);
    k2Eggs.put(curK, curKEggs);
  }

  public LLTVN getSortedEggs() {
    return fEggs;
  }

  public LLTVN getSortedEggs(int k) {
    return k2Eggs.get(k);
  }

}
