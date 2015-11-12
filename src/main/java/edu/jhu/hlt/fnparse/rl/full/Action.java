package edu.jhu.hlt.fnparse.rl.full;

import edu.jhu.hlt.fnparse.rl.full.State.FI;
import edu.jhu.hlt.fnparse.rl.full.State.FILL;
import edu.jhu.hlt.fnparse.rl.full.State.RI;

public class Action {
  // What are the types of actions I need?
  // 1) add target (no frame specified)
  // 2) assign frame to an existing target
  // 3a) choose a span/role given a particular (t,f) => choose a role
  // 3a) choose a role/span given a particular (t,f) => choose a span

  // 1 and 2 require the fields in an FI
  // 3a and 3b require the fields in an RI (and a FILL for context)

  public final FI newFrame;
  public final RI newArg;
  public final FILL newArgLoc;   // for 3a and 3b
  // Note that newArgLoc is the frame getting the new arg, but the FILL second
  // constructor also needs a FILL which is the head of the State's FILL,
  // which is implicit (carried in State), so it doesn't need to be in Action.

  public Action(FI newFrame) {
    this.newFrame = newFrame;
    this.newArg = null;
    this.newArgLoc = null;
  }

  public Action(RI newArg, FILL newArgLoc) {
    this.newFrame = null;
    this.newArg = newArg;
    this.newArgLoc = newArgLoc;
  }
}