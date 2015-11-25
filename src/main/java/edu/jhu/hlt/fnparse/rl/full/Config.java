package edu.jhu.hlt.fnparse.rl.full;

public class Config {

  /** Label constraints *******************************************************/
  public boolean useContRoles = false;
  public boolean useRefRoles = false;

  /** Structural constraints **************************************************/

  // TODO Work on getting oneXperY constraints back.
  //  For now I'm assuming that you've backed them into the transition system informally

  // TODO If these are switched to false then we need to test better
  public boolean oneKperS = true;
  public boolean oneSperK = true;

  /** Transition constraints **************************************************/
  // How are new (t,f) nodes created?
  enum FrameActionTransitionSystem {
    TARGET_FIRST,
    ONE_STEP,
    ASSUME_FRAMES_ARE_GIVEN,
  }

  public FrameActionTransitionSystem frameMode = FrameActionTransitionSystem.ASSUME_FRAMES_ARE_GIVEN;

  // You may only add to a single args:RILL at a time.
  // You can add to the next RILL after you prepend NO_MORE_ARGS.
  public boolean frameByFrame = true;

  // Note that these are mutually exclusive, including ROLE_FIRST and SPAN_FIRST,
  // which seem like they could be compatible (they're not without enough book-keeping
  // to make it on the order of ONE_STEP in speed).
  enum ArgActionTransitionSystem {
    ROLE_FIRST,   // choose a role to find, then find it (a.k.a. (k,?) actions)
    ROLE_BY_ROLE, // like ROLE_FIRST, but you don't get to choose which role to complete, you take them in linear order (k=0, k=1, ...)
    SPAN_FIRST,   // choose a span to label, then label it (a.k.a. (?,s) actions)
    SPAN_BY_SPAN, // analog of ROLE_BY_ROLE where you loop over spans
    ONE_STEP,     // loop over all (k,s) possibilities at every step
  }

  // I don't have an intuition as to which *_FIRST method will work better, but I
  // think one of them will do about as well as ONE_STEP, which is how the old code
  // worked, and it was way too slow.
  public ArgActionTransitionSystem argMode = ArgActionTransitionSystem.ROLE_FIRST;

  // NOTE: I removed immediatelyResolve* flags, they must be true for a reasons related
  // to soundness of the transition system (and book-keeping).

  // NOTE: framesBeforeArgs was removed because we're not currently predicting (t,f),
  // but this is a good thing to consider when adding that functionality.

  /*
   * SPRL settings:
   */
  public static final Config SPRL_SETTINGS;
  public static final Config PB_SETTINGS;
  public static final Config FN_SETTINGS;
  public static final Config FAST_SETTINGS;
  static {    // TODO Specify some settings as you do experiments to see what works well.
    SPRL_SETTINGS = new Config();

    PB_SETTINGS = new Config();
    PB_SETTINGS.useContRoles = true;
    PB_SETTINGS.useRefRoles = true;

    FN_SETTINGS = new Config();

    FAST_SETTINGS = new Config();
    FAST_SETTINGS.frameByFrame = true;
    FAST_SETTINGS.argMode = ArgActionTransitionSystem.ROLE_BY_ROLE;
  }
}