package edu.jhu.hlt.fnparse.rl.full2.eggs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;

import edu.jhu.hlt.fnparse.rl.full.HowToSearch;
import edu.jhu.hlt.fnparse.rl.full2.LLTVN;
import edu.jhu.hlt.fnparse.rl.full2.Node2;
import edu.jhu.hlt.fnparse.rl.full2.TFKS;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.tuple.Pair;

/**
 * Sorts eggs by
 *   G(egg) * p(hatch, egg)
 * where
 *   G(egg) = Utility(hatch, egg) - Utility(prune, egg)
 *   Utility(hatch, egg) = tp - fp \in {-1, +1}
 *   Utility(prune, egg) = tn - fn \in {-k, +k}   => See myopic loss
 *   p(hatch, egg) + p(prune, egg) = 1
 *
 * NOTE: For now, I've only implemented myopic loss, i.e. Utility(_, egg) \in {-1, +1}
 *
 * @author travis
 */
public class ExpectedUtilityEggSorter {
  public static boolean DEBUG = false;

  /** "Scored egg" */
  private static class SE {
    public final double score;
    public final EggWithStaticScore egg;
    public SE(double score, EggWithStaticScore egg) {
      this.score = score;
      this.egg = egg;
    }
    @Override
    public String toString() {
      return String.format("(SE %.3f %s)", score, egg);
    }
    public static Comparator<SE> BY_SCORE_ASC = new Comparator<SE>() {
      @Override
      public int compare(SE o1, SE o2) {
        if (o1.score < o2.score)
          return -1;
        if (o1.score > o2.score)
          return +1;
        return 0;
      }
    };
  }

  /**
   * The vanilla version only handles eggs for one node. The way {@link SortedEggCache}
   * was designed was to handle eggs forall k and s eggs. This class is an adapter
   * to make it work like {@link SortedEggCache}.
   */
  public static class Adapter extends SortedEggCache {
    private ExpectedUtilityEggSorter kEggsEU;
    private IntObjectHashMap<ExpectedUtilityEggSorter> sEggsEU;
    public Adapter(
        List<Pair<TFKS, EggWithStaticScore>> fEggs,
        List<Pair<TFKS, EggWithStaticScore>> kEggs,
        boolean fEggsAreMaxOverKEggs,
        HowToSearch howToScore,
        IntFunction<Span> decodeSpan) {
      super(fEggs, kEggs, fEggsAreMaxOverKEggs, howToScore, decodeSpan);

      // In super implementation, eggs will have hts score; for K valued eggs
      // (computed as a max_S) and S valued eggs. We just need to use this as a
      // log-prob and use that to compute EU.
      sEggsEU = new IntObjectHashMap<>();
      kEggsEU = new ExpectedUtilityEggSorter(howToScore);
      for (LLTVN cur = super.fEggs; cur != null; cur = cur.cdr()) {
        // Compute EU for K valued egg
        EggWithStaticScore egg = (EggWithStaticScore) cur.car();
//        SortedEggCache.EWSSMax egg = (SortedEggCache.EWSSMax) cur.car();
        assert fEggsAreMaxOverKEggs == (egg instanceof SortedEggCache.EWSSMax);
        kEggsEU.addEgg(egg);

        // Compute EU for S valued eggs
        assert egg.type == TFKS.K;
        int k = egg.value;
        ExpectedUtilityEggSorter euS = new ExpectedUtilityEggSorter(howToScore);
        LLTVN cur2 = super.k2Eggs.get(k);
        assert cur2 != null;
        for (; cur2 != null; cur2 = cur2.cdr()) {
          EggWithStaticScore egg2 = (EggWithStaticScore) cur2.car();
          assert egg2.type == TFKS.S;
          euS.addEgg(egg2);
        }
        ExpectedUtilityEggSorter old = sEggsEU.put(k, euS);
        assert old == null;
      }
    }
    @Override
    public LLTVN getSortedEggs() {
//      return fEggs;
      return kEggsEU.getSortedEggs();
    }
    @Override
    public LLTVN getSortedEggs(int k) {
//      return k2Eggs.get(k);
      ExpectedUtilityEggSorter eu = sEggsEU.get(k);
      LLTVN ll = eu.getSortedEggs();
      return ll;
    }
  }

  private List<SE> eggs;
  private LLTVN sorted;
  private HowToSearch howToScore;

  public ExpectedUtilityEggSorter(HowToSearch howToScore) {
    eggs = new ArrayList<>();
    sorted = null;
    this.howToScore = howToScore;
  }

  public void addEgg(EggWithStaticScore egg) {
    assert egg.getModel() != Adjoints.Constant.ZERO;
    double U_hatch, U_prune;
    // Problem is that if !Node2.INTERNAL_NODES_COUNT, then goldMatching will
    // be 0 always for non-terminal eggs.
    int gm = egg.goldMatching;
    if (egg instanceof SortedEggCache.EWSSMax) {
      int gm2 = ((SortedEggCache.EWSSMax) egg).maximalSValuedEgg.goldMatching;
      assert gm >= gm2 || (gm == 0 && !Node2.INTERNAL_NODES_COUNT);
      gm = Math.max(gm, gm2);
    }
    if (gm > 0) {
      U_hatch = 1;
      U_prune = 0;
    } else {
      U_hatch = 0;
      U_prune = 1;
    }
    double gain = U_hatch - U_prune;
    double wx = howToScore.forwards(SortedEggCache.e2ss(egg));
    double pHatch = 1d / (1 + Math.exp(-wx));
    double score = gain * pHatch;
    eggs.add(new SE(score, egg));
    sorted = null;
  }

  public LLTVN getSortedEggs() {
    if (sorted == null) {
      Collections.sort(eggs, SE.BY_SCORE_ASC);
      int n = eggs.size();
      for (int i = n-1; i >= 0; i--) {
        SE se = eggs.get(i);
        EggWithStaticScore e = se.egg;
        if (DEBUG)
          Log.info("egg[" + (n-i) + "] " + se);
        sorted = new LLTVN(e, sorted);
      }
    }
    return sorted;
  }
}
