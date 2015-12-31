package edu.jhu.hlt.fnparse.rl.full2;

import java.io.PrintStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import edu.jhu.hlt.fnparse.rl.full.HowToSearch;
import edu.jhu.hlt.fnparse.rl.full.MaxLoss;
import edu.jhu.hlt.fnparse.rl.full.StepScores;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.tuple.Pair;

/**
 * Holds logic and cache of eggs for F nodes (k-valued eggs) and K nodes
 * (s-valued eggs). All eggs for a given (t,f) subtree are created and ranked in
 * one go in the constructor. Getters allow you to pull out the LL of eggs you
 * need later.
 */
public class SortedEggCache {
  public static boolean DEBUG = false;

  private int t;
  private int f;    // keep?
  private LLTVN fEggs;                      // children of F node, K values
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
    initFEggs(fEggs, howToScore);
    initKEggs(kEggs, howToScore);
    if (AbstractTransitionScheme.DEBUG && DEBUG)
      show(System.out);
  }

  /**
   * Just sorts k-valued (F) eggs by their model score (as deemed by howToScore).
   * I thought I wanted to do some type of max_s score(k,s), but I'm not sure
   * about that anymore, seems like the model already has an opinon about k-valued
   * eggs which needs to be respected.
   */
  private void initFEggs(List<Pair<TFKS, EggWithStaticScore>> fEggs, HowToSearch howToScore) {
    if (AbstractTransitionScheme.DEBUG && DEBUG)
      Log.info("sorting " + fEggs.size() + " k-valued eggs");
    // Just need kEggs sorted in order (low -> high)
    Collections.sort(fEggs, byEggScore(howToScore, false));
    this.fEggs = null;
    for (int i = 0; i < fEggs.size(); i++) {
      EggWithStaticScore egg = fEggs.get(i).get2();
      if (AbstractTransitionScheme.DEBUG && DEBUG)
        System.out.println("k-valued egg after sorting: " + egg + "\thts.forwards=" + howToScore.forwards(e2ss(egg)));
      this.fEggs = new LLTVN(egg, this.fEggs);
    }
  }

  public static Comparator<Pair<TFKS, EggWithStaticScore>> byEggScore(HowToSearch howToScore, boolean groupByKSFirst) {
    return new Comparator<Pair<TFKS, EggWithStaticScore>>() {
      @Override
      public int compare(Pair<TFKS, EggWithStaticScore> p1, Pair<TFKS, EggWithStaticScore> p2) {
        if (groupByKSFirst) {
          // First sort on (k,s)
          TFKS i1 = p1.get1();
          TFKS i2 = p2.get1();
          if (i1.k != i2.k)
            return i1.k < i2.k ? -1 : 1;
          if (i1.s != i2.s)
            return i1.s < i2.s ? -1 : 1;
        }
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
    };
  }

  private void initKEggs(List<Pair<TFKS, EggWithStaticScore>> kEggs, HowToSearch howToScore) {
    // Sort by (k,s) then score (low -> high)
    Collections.sort(kEggs, byEggScore(howToScore, true));

    // Group-by k and check that (t,f) is the same for all
    this.k2Eggs = new IntObjectHashMap<>();
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
      EggWithStaticScore egg = p.get2();  // contains hatch static score
      assert t == i.t && f == i.f;
      assert i.k >= 0 && i.s >= 0;
      assert egg.getModel() != null : "s-valued eggs must have a model score (Adjoints)";
      if (AbstractTransitionScheme.DEBUG && DEBUG)
        System.out.println("s-valued egg after sorting: " + i.str() + "\t" + egg + "\thts.forwards=" + howToScore.forwards(e2ss(egg)));

      // New k value
      if (curK != i.k) {
        // Check that this isn't the first run through the loop (null aggregates)
        if (curK >= 0)
          k2Eggs.put(curK, curKEggs);

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
    k2Eggs.put(curK, curKEggs);
  }

  public void show(PrintStream ps) {
    int i = 0;
    for (LLTVN cur = fEggs; cur != null; cur = cur.cdr(), i++) {
      ps.println("fEggs[" + i + "]=" + cur.car());
    }
    for (int k : k2Eggs.keys()) {
      ps.println("k=" + k);
      for (LLTVN cur = k2Eggs.get(k); cur != null; cur = cur.cdr()) {
        ps.println("\t" + cur.car());
      }
    }
  }

  public LLTVN getSortedEggs() {
    return fEggs;
  }

  public LLTVN getSortedEggs(int k) {
    return k2Eggs.get(k);
  }

}
