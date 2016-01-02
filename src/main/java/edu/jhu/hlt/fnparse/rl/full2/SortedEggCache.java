package edu.jhu.hlt.fnparse.rl.full2;

import java.io.PrintStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.fnparse.rl.full.HowToSearch;
import edu.jhu.hlt.fnparse.rl.full.MaxLoss;
import edu.jhu.hlt.fnparse.rl.full.StepScores;
import edu.jhu.hlt.tutils.Log;
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
  private LLTVN fEggs;                      // children of F node, K values
  private IntObjectHashMap<LLTVN> k2Eggs;   // children of K node, S values

  public static StepScores<?> e2ss(EggWithStaticScore egg) {
    // TODO We are sorting this list with the intention of doing as many
    // hatches right away followed by prunes, so we are either sorting for
    // scoreHatch or scoreHatch-scoreSquash.
    // Is there a way to replace loss with a "loss assuming we hatch it" (scoreHatch)
    // or "delta loss if we hatch it vs prune it" (scoreHatch-scoreSquash).
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

    /*
     * TODO Current sort is based on *model score*, not *search objective*
     * (which might include loss,rand for example). The reason this is not a
     * trivial change is that all we have is an EggWithStaticScore/TVNS, which
     * does not yet know about loss (i.e. Node2).
     * Should we sort by search objective instead? Probably...
     */
    final Map<Integer, EggWithStaticScore> kScores = initKEggs(kEggs, howToScore);

    if (!Node2.INTERNAL_NODES_COUNT) {
      // fEggs should all be TVNS with score of Constant(0)
      // Sort by kEggs max score over all s for a given k
      // (low -> high)
      Collections.sort(fEggs, new Comparator<Pair<TFKS, EggWithStaticScore>>() {
        @Override
        public int compare(Pair<TFKS, EggWithStaticScore> o1, Pair<TFKS, EggWithStaticScore> o2) {
          int k1 = o1.get1().k;
          int k2 = o2.get1().k;
          assert k1 >= 0 && k2 >= 0;
          double s1 = howToScore.forwards(e2ss(kScores.get(k1)));
          double s2 = howToScore.forwards(e2ss(kScores.get(k2)));
          if (s1 < s2)
            return -1;
          if (s1 > s2)
            return +1;
          return 0;
        }
      });
    } else {
      // Just need kEggs sorted in order (low -> high)
      Collections.sort(fEggs, byEggScore(howToScore, false));
    }

    this.fEggs = null;
    if (AbstractTransitionScheme.DEBUG && DEBUG)
      Log.info("sorted " + fEggs.size() + " k-valued eggs");
    for (int i = 0; i < fEggs.size(); i++) {
      EggWithStaticScore fe = fEggs.get(i).get2();
      EggWithStaticScore feMod;
      int k = fe.value;
      if (kScores != null) {
        feMod = kScores.get(k); // s-valued egg, not k-valued!
        assert feMod.type == TFKS.S : "feMode.type=" + feMod.type + " fe.type=" + fe.type;
        assert fe.type == TFKS.K : "feMode.type=" + feMod.type + " fe.type=" + fe.type;
        feMod = fe.withScore(feMod.getModel(), feMod.getRand());
        if (AbstractTransitionScheme.DEBUG && DEBUG)
          System.out.println("k=" + k + " -> max_s model(k,s): " + howToScore.forwards(e2ss(feMod)));
      } else {
        feMod = fe;
        if (AbstractTransitionScheme.DEBUG && DEBUG)
          System.out.println("k=" + k + " -> model(k): " + howToScore.forwards(e2ss(feMod)));
      }
      assert feMod.type == TFKS.K && feMod.type == fe.type;
      this.fEggs = new LLTVN(feMod, this.fEggs);
    }

//    this.fEggs = null;
//    for (int i = 0; i < fEggs.size(); i++) {
//      EggWithStaticScore egg = fEggs.get(i).get2();
//      if (AbstractTransitionScheme.DEBUG && DEBUG)
//        System.out.println("k-valued egg after sorting: " + egg + "\thts.forwards=" + howToScore.forwards(e2ss(egg)));
//      this.fEggs = new LLTVN(egg, this.fEggs);
//    }

    if (AbstractTransitionScheme.DEBUG && DEBUG)
      show(System.out);
  }

  public static Comparator<Pair<TFKS, EggWithStaticScore>> byEggScore(HowToSearch howToScore, boolean groupByKFirst) {
    return new Comparator<Pair<TFKS, EggWithStaticScore>>() {
      @Override
      public int compare(Pair<TFKS, EggWithStaticScore> p1, Pair<TFKS, EggWithStaticScore> p2) {
        if (groupByKFirst) {
          // First sort on k
          TFKS i1 = p1.get1();
          TFKS i2 = p2.get1();
          if (i1.k != i2.k)
            return i1.k < i2.k ? -1 : 1;
        }
        // Within a k block, sort by score, low->high
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

  /**
   * Returns a k -> Adjoints representing the best (k,s) from an s-valued egg.
   * Use this to sort k-vauled eggs if !Node.INTERNAL_NODES_COUNT
   */
  private Map<Integer, EggWithStaticScore> initKEggs(List<Pair<TFKS, EggWithStaticScore>> kEggs, HowToSearch howToScore) {
    // Sort by (k,s) then score (low -> high)
    Collections.sort(kEggs, byEggScore(howToScore, true));

    Map<Integer, EggWithStaticScore> kScores = null;
    if (!Node2.INTERNAL_NODES_COUNT)
      kScores = new HashMap<>();

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
        if (curK >= 0) {
          k2Eggs.put(curK, curKEggs);
          if (kScores != null) {
            EggWithStaticScore bestScore = (EggWithStaticScore) curKEggs.car();
            EggWithStaticScore old = kScores.put(curK, bestScore);
            assert old == null;
          }
        }

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
    if (kScores != null) {
      EggWithStaticScore bestScore = (EggWithStaticScore) curKEggs.car();
      EggWithStaticScore old = kScores.put(curK, bestScore);
      assert old == null;
    }
    return kScores;
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
