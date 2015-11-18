package edu.jhu.hlt.fnparse.rl.full;

public class Config {

  /** Label constraints *******************************************************/
  public boolean useContRoles = false;
  public boolean useRefRoles = false;

  /** Structural constraints **************************************************/

  // False is not implemented!
  // It requires a change to the COMPLETE_F action:
  // It can't just be an argmax like it is when oneFramePerSpan=true
  // It should be some more complex circuit which has fertility penalties.
  // However, this should get too complex in light of the alternative:
  //   Another way to do this is to set chooseFramesOneStep=true, which means
  // that (t,?) states are never created -- this is what causes problems with
  // evaluation: do we allow a second (t,?) action if !oneFramePerSpan? If so,
  // does the second one "get credit" (is there an index in y/z corresponding to
  // (t,?) for each frame instance? this is not possible...).
  // This second option is much more expressive since subsequent actions can
  // use arbitrary dynamic features.
  public boolean oneFramePerSpan = true;  // maybe false when doing joint PB+FN prediction?

  // Similarly, these control how step 2 of (k,?) and (?,s) work:
  // If they are true you get an argmax.
  public boolean oneRolePerSpan = true;   // false for SPRL where "role" means "property"
  public boolean oneSpanPerRole = true;

  /** Transition constraints **************************************************/
  // All actions involving frames/roles must be punctuated by NO_MORE_FRAMES/NO_MORE_ARGS
  public boolean frameByFrame = true;
  public boolean roleByRole = false;

  // Two-step actions, e.g. (t,f) -> (k,?) -> (k,s), must be resolved immediately.
  // This means that if there is a step 1 action to take then step 2 actions may not be generated.
  public boolean immediatelyResolveArgs = true;       // p[(k,?) -> !(k,s)]=0 and p[(?,s) -> !(k,s)]=0 and p[(?,?) -> !(k,s)]=0
  public boolean immediatelyResolveFrames = true;
  /* The case where you should not use immediatelyResolve=false is when you want
   * to do something like lay down all of the spans and then label them using
   * joint features on all of the spane. The same applies to doing target id
   * before frame id.
   * The case where you need to set immediatelyResolve=true is when you can
   * create two partial commits where one of them could lead to a completion
   * that conflicts with the other partial commit.
   */

  // Dictates the types of step 1 actions generated
  public boolean chooseArgRoleFirst = true;   // allow (t,f,k,?) actions
  public boolean chooseArgSpanFirst = true;   // allow (t,f,?,s) actions
  public boolean chooseArgOneStep = false;    // allow (t,f,?,?) actions
  public boolean chooseFramesOnStep = false;  // allow loop over all (t,f)

  public boolean framesBeforeArgs = false;   // require NO_MORE_FRAMES < all RIs

  // Maybe transtition constraints
  private boolean chooseAllArgSpansFirst = false;   // for each (t,f): all (t,f,?,s) actions must proceed all (t,f,k,s) actions
  private boolean chooseAllTargetsFirst = false;    // all (t,) actions must proceed all (t,f) actions

  /*
   * SPRL settings:
   */
  public static final Config SPRL_SETTINGS;
  public static final Config PB_SETTINGS;
  public static final Config FN_SETTINGS;
  static {
    SPRL_SETTINGS = new Config();
    SPRL_SETTINGS.chooseAllArgSpansFirst = true;  // (generally its a problem of choosing two spans)
    SPRL_SETTINGS.oneRolePerSpan = false; // (remember, "role" == "property")
    SPRL_SETTINGS.oneSpanPerRole = false;
    SPRL_SETTINGS.useContRoles = false;
    SPRL_SETTINGS.useRefRoles = false;

    PB_SETTINGS = new Config();
    PB_SETTINGS.useContRoles = true;
    PB_SETTINGS.useRefRoles = true;

    FN_SETTINGS = new Config();
    FN_SETTINGS.frameByFrame = false;
  }


  /*
   * On step two of (k,?) actions, if oneSpanPerRole then blank out all (k,s) which were not selected
   * On step two of (?,s) actions, if oneRolePerSpan then blank out all (k,s) which were not selected
   * "Blank out all ..." is slow, I had wanted to do this lazily with a flag saying "the one is set here"
   * Naively, the one is set by looking through the LL of actions.
   * Optimized:
   *  use RILL.realizedRoles to filter the generation of (k,?) actions
   *    step 2 of (k,?) + oneSpanPerRole => use LL to inspect which (k,s) was chosen
   *  use RILL.??? to filter the generation of (?,s) actions
   *    step 2 of (?,s) + oneRolePerSpan => use LL to inspect which (k,s) was chosen
   */

  /*
   * chooseArgRoleFirst means add (k,?) actions -- choose a k, then choose a s for that k
   * chooseArgSpanFirst means add (s,?) actions -- choose a s, then choose a k label
   * Both of these are two-step to get to a particular (t,f,k,s)=1
   * deltaLoss is intuitive: if you choose (k,?), then deltaLoss=0 if \exists s s.t. y[t,f,k,s]=1
   *              similarly, if you choose (s,?), then deltaLoss=0 if \exists k s.t. y[t,f,k,s]=1
   *
   * Both of these actions will lead to a single (s,?) or (k,?) RI node being added,
   * which must immediately be followed by a set of actions to choose the ? value,
   * meaning that these two action templates cannot interact
   *
   * If you set chooseArgOneStep to true, then a loop over KxS is done in one step to choose a (t,f,k,s)
   * This is very slow! O(... K^2 S^2) vs O(... K + S)
   */
}