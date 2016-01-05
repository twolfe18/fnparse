package edu.jhu.hlt.fnparse.rl.full2;

import edu.jhu.hlt.tutils.scoring.Adjoints;

/**
 * {@link AbstractTransitionScheme#genEggs(TFKS, HasCounts)} only needs to
 * produce eggs which are {@link TVN}s. Then {@link AbstractTransitionScheme#scoreHatch(Node2, HasCounts)}
 * scores an egg to make an child which is an {@link TVNS}. The problem with
 * this flow is that it doesn't allow you to score eggs by a static score which
 * allows you to basically only look at the top K eggs before bailing out (via
 * dynamic features which say "this is the i^th egg" and having weights learn
 * that "if i>10 then just prune everything else").
 *
 * Note, this class (so far) doesn't really have any special functionality other
 * than to make debugging easier (the types in {@link AbstractTransitionScheme}
 * are not strong enough to figure out exactly what is going on, you will have
 * to do some runtime type-checking).
 *
 * @author travis
 */
public class EggWithStaticScore extends TVNS /* and thus TVN as well */ {

  public EggWithStaticScore(int type, int value,
      int numPossible, int goldMatching,
      long prime, Adjoints model, double rand) {
    super(type, value, numPossible, goldMatching, prime, model, rand);
  }

  public EggWithStaticScore setScore(Adjoints modelScore, double rand) {
    return new EggWithStaticScore(type, value, numPossible, goldMatching, prime, modelScore, rand);
  }

  @Override
  public EggWithStaticScore withScore(Adjoints model, double rand) {
    return new EggWithStaticScore(type, value, numPossible, goldMatching, prime, model, rand);
  }
}
